package io.github.ouy09103.dsh_go.root

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs one command through the device's su (Magisk / KernelSU / APatch / SuperSU),
 * waiting for the authorization grant when the manager asks for it.
 *
 * Nothing here is privileged by itself: su authorizes the child, and only the
 * strings we hand it are executed.
 */
internal object RootExec {

    /** Where su may live. First existing wins; a manager on PATH is checked last. */
    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk/su",
    )

    fun suPath(): String? = SU_CANDIDATES.firstOrNull { File(it).exists() }

    fun manager(): String = when {
        File("/data/adb/ksu").exists() -> "KernelSU"
        File("/data/adb/ap").exists() -> "APatch"
        File("/data/adb/magisk").exists() -> "Magisk"
        suPath() != null -> "su"
        else -> ""
    }

    data class Result(
        val ok: Boolean,
        val uid: Int,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        fun map(): Map<String, Any?> = mapOf(
            "ok" to ok,
            "uid" to uid,
            "exitCode" to exitCode,
            "stdout" to stdout,
            "stderr" to stderr,
        )
    }

    /** `id -u` through su: 0 means a real root child, anything else no authorization. */
    fun probe(timeoutMs: Long = 30_000): Result = run("id -u", timeoutMs)

    fun run(command: String, timeoutMs: Long = 60_000, cwd: String? = null, env: Map<String, String> = emptyMap()): Result {
        val su = suPath() ?: return Result(false, -1, -1, "", "no su on this device")
        val builder = ProcessBuilder(su, "-c", command).redirectErrorStream(false)
        if (cwd != null) builder.directory(File(cwd))
        builder.environment().putAll(env)
        val process = try {
            builder.start()
        } catch (error: IOException) {
            return Result(false, -1, -1, "", error.message ?: "su failed to start")
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = drain(process.inputStream, out)
        val errThread = drain(process.errorStream, err)
        val finished = process.waitFor(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            outThread.join(500)
            errThread.join(500)
            return Result(false, -1, -1, out.toString(), err.toString().ifEmpty { "timeout" })
        }
        outThread.join(1000)
        errThread.join(1000)
        val code = process.exitValue()
        val uid = Regex("^\\s*(\\d+)\\s*$", RegexOption.MULTILINE)
            .find(out.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1
        return Result(code == 0, uid, code, out.toString(), err.toString())
    }

    private fun drain(stream: java.io.InputStream, into: StringBuilder): Thread =
        Thread {
            runCatching {
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    synchronized(into) { into.append(String(buffer, 0, read)) }
                }
            }
        }.apply { isDaemon = true; name = "dsh-root-drain"; start() }
}

/**
 * The bundled Ubuntu rootfs, at the same absolute path the engine uses, launched
 * under real root: the guest sees uid 0 on the device, not proot's fake one.
 */
internal object GuestExec {
    private const val ROOTFS = "runtime/rootfs"

    private fun rootfs(context: Context): File? =
        File(context.filesDir, ROOTFS).takeIf { it.isDirectory }

    private fun tmp(context: Context): File =
        File(context.cacheDir, "proot-tmp").apply { mkdirs() }

    /** `proot ... /bin/sh -lc <command>` as one shell word list. */
    fun argv(context: Context, command: String): List<String>? {
        val root = rootfs(context) ?: return null
        val libs = context.applicationInfo.nativeLibraryDir
        val home = File(root, "root").apply { mkdirs() }
        File(root, "var/lib/dsh-app/empty").mkdirs()
        File(root, "var/lib/dsh-app/shm").mkdirs()
        File(home, ".l2s").mkdirs()
        return listOf(
            "$libs/libproot.so",
            "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "-0",
            "--rootfs=${root.absolutePath}", "--cwd=/root",
            "-b", "/dev",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "/sys",
            "-b", "${root.absolutePath}/var/lib/dsh-app/empty:/sys/fs/selinux",
            "-b", "${root.absolutePath}/var/lib/dsh-app/shm:/dev/shm",
            "-b", "/system",
            "-b", "/apex",
            "-b", "/data",
            "-b", "/sdcard",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp", "DSH_HOME=/root/.dsh",
            "NODE_OPTIONS=--dns-result-order=ipv4first",
            "/bin/sh", "-lc", command,
        )
    }

    fun quote(word: String): String =
        if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in "/._-:=@%+," }) word
        else "'" + word.replace("'", "'\\''") + "'"

    /** The guest command as one su argument list, with proot's own environment. */
    fun suCommand(context: Context, command: String): String? {
        val argv = argv(context, command) ?: return null
        val libs = context.applicationInfo.nativeLibraryDir
        val tmp = tmp(context).absolutePath
        val root = File(context.filesDir, ROOTFS).absolutePath
        val prelude = "export PROOT_LOADER=${quote("$libs/libproot-loader.so")} " +
            "PROOT_TMP_DIR=${quote(tmp)} PROOT_L2S_DIR=${quote("$root/root/.l2s")} " +
            "LD_LIBRARY_PATH=${quote(libs)}; exec " + argv.joinToString(" ", transform = ::quote)
        return prelude
    }
}
