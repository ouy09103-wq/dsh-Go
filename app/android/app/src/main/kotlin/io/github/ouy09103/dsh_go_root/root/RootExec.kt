package io.github.ouy09103.dsh_go_root.root

import android.content.Context
import java.io.File

/**
 * Runs one command through the device's su (Magisk / KernelSU / APatch / SuperSU),
 * waiting for the authorization grant when the manager asks for it.
 *
 * Nothing here is privileged by itself: su authorizes the child, and only the
 * strings we hand it are executed.
 */
internal object RootExec {

    /** A PATH covering AOSP, Magisk, KernelSU and APatch, for commands run through su. */
    const val ROOT_PATH =
        "/system/bin:/system/xbin:/vendor/bin:/product/bin" +
            ":/data/adb/magisk:/data/adb/ksu/bin:/data/adb/ap/bin:/sbin:/usr/bin:/bin"

    /**
     * Root-owned su only — DSHA v0.2.2's `RootShellCandidates` check, applied here.
     *
     * `File.exists()` used to be the whole test: an entry earlier in the list wins, so a file the
     * app could write would be run as root. Ownership, file type and the group/other write bits
     * are checked instead, and a candidate that fails is skipped rather than used.
     */
    private fun verifiedSu(path: String): Boolean = runCatching {
        val stat = android.system.Os.stat(path)
        android.system.OsConstants.S_ISREG(stat.st_mode) &&
            stat.st_uid == 0 &&
            (stat.st_mode and
                (android.system.OsConstants.S_IWGRP or android.system.OsConstants.S_IWOTH)) == 0 &&
            File(path).canExecute()
    }.getOrElse {
        // Off device (plain JVM unit tests) Os.stat is unavailable: the file API is all there is.
        val file = File(path)
        file.isFile && file.canExecute()
    }

    fun suPath(): String? = SuCandidates.firstVerified(::verifiedSu)

    /**
     * The installed APK, needed as app_process's CLASSPATH. Bound once at engine attach; the
     * reflection fallback keeps a path available for work that starts earlier than that.
     */
    @Volatile
    private var apk: String? = null

    fun bind(context: Context) {
        apk = context.applicationInfo.sourceDir
    }

    private fun apkPath(): String? = apk ?: runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
        app?.applicationInfo?.sourceDir
    }.getOrNull()?.also { apk = it }

    private val UID = Regex("^\\s*(\\d+)\\s*$", RegexOption.MULTILINE)

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
        /** The helper never finished in time: the command may still be running. */
        val timedOut: Boolean = false,
        /** Every slot was taken, so su was never started: nothing was sent. */
        val busy: Boolean = false,
        /** [stdout] hit the size cap and is a prefix of the real answer. */
        val truncated: Boolean = false,
    ) {
        fun map(): Map<String, Any?> = mapOf(
            "ok" to ok,
            "uid" to uid,
            "exitCode" to exitCode,
            "stdout" to stdout,
            "stderr" to stderr,
            "timedOut" to timedOut,
            "busy" to busy,
            "truncated" to truncated,
        )
    }

    /** `id -u` through su: 0 means a real root child, anything else no authorization. */
    fun probe(timeoutMs: Long = 30_000): Result = run("id -u", timeoutMs)

    fun run(
        command: String,
        timeoutMs: Long = 60_000,
        cwd: String? = null,
        env: Map<String, String> = emptyMap(),
    ): Result {
        val su = suPath() ?: return Result(false, -1, -1, "", "no su on this device")
        val apk = apkPath() ?: return Result(
            false, -1, -1, "",
            "root helper 需要安装包路径：App 上下文尚未绑定（RootExec.bind）",
        )
        // The command goes to a uid 0 helper, not to `su -c`: only that helper can kill the
        // child after the app has given up, and only it can report what really happened.
        val outcome = RootHelperClient.run(apk, su, command, timeoutMs, cwd, env)
        val uid = UID.find(outcome.output)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val message = listOf(outcome.stderr, outcome.note)
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        return Result(
            ok = !outcome.busy && !outcome.timedOut && outcome.exitCode == 0 && !outcome.truncated,
            uid = uid,
            exitCode = if (outcome.busy) 124 else outcome.exitCode,
            stdout = outcome.output,
            stderr = message,
            timedOut = outcome.timedOut,
            busy = outcome.busy,
            truncated = outcome.truncated,
        )
    }

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
