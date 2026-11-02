package io.github.ccch1mneyyy.dsh_app.root

import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 真 root 引擎：把打包的 Ubuntu rootfs 用 chroot(2) 直接跑在设备的 su 下。
 *
 * 和 proot 的区别是本质的：进程真的是 uid 0、真持 root 能力，glibc 也不再受 App 的
 * seccomp 过滤（su 由 magiskd / ksud 拉起，不继承 zygote 的过滤器）。原先在部分机器上起不来的
 * glibc Node 在这里能正常跑，Node 的权限也变成真的。
 *
 * 一次 `su -c` 里做完：建挂载点 → bind /proc /sys /dev → bind 额外目录 → chroot → 跑 guest
 * 命令 → 退出时 lazy umount。停止时先 SIGTERM guest 的 node，脚本自然收尾并卸载。
 *
 * 可用性不猜：真跑一遍 `id -u` 与 `command -v chroot` 才认。
 */
internal object RootEngine {

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

    /** Result of the one-time probe; [usable] is what the switch reads. */
    data class Support(
        val su: String?,
        val uid: Int,
        val chroot: Boolean,
        val mount: Boolean,
        val kernel: String,
        val abi: String,
        val reason: String?,
    ) {
        val usable: Boolean get() = su != null && uid == 0 && chroot

        fun map(): Map<String, Any?> = linkedMapOf(
            "su" to su,
            "manager" to manager(),
            "uid" to uid,
            "chroot" to chroot,
            "mount" to mount,
            "kernel" to kernel,
            "abi" to abi,
            "sdk" to Build.VERSION.SDK_INT,
            "usable" to usable,
            "reason" to reason,
        )
    }

    @Volatile private var cached: Support? = null

    fun cachedSupport(): Support? = cached

    /** Runs the probe once per app process; [force] re-probes after a fresh grant. Blocking. */
    fun probe(force: Boolean = false): Support {
        cached?.let { if (!force) return it }
        val su = suPath()
        if (su == null) {
            return Support(null, -1, false, false, "", Build.SUPPORTED_ABIS.firstOrNull() ?: "", "noSu")
                .also { cached = it }
        }
        val out = RootExec.run(
            "id -u; command -v chroot || echo NOCHROOT; command -v mount || echo NOMOUNT; uname -r",
            60_000,
        )
        val lines = out.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val uid = lines.firstOrNull()?.toIntOrNull() ?: -1
        val chroot = lines.any { it.endsWith("/chroot") }
        val mount = lines.any { it.endsWith("/mount") }
        val kernel = lines.lastOrNull { it.contains(".") && !it.contains("/") } ?: ""
        val reason = when {
            uid != 0 -> "notGranted"
            !chroot -> "noChroot"
            else -> null
        }
        return Support(su, uid, chroot, mount, kernel, Build.SUPPORTED_ABIS.firstOrNull() ?: "", reason)
            .also { cached = it }
    }

    fun quote(word: String): String =
        if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in "/._-:=@%+," }) word
        else "'" + word.replace("'", "'\\''") + "'"

    /**
     * The whole launch as one shell script for `su -c`.
     *
     * [guestArgv] starts at the guest's `/usr/bin/env` (manifest template, expanded, overlays in).
     * [binds] are `src` or `src:dst` mounts to add inside the rootfs (phone storage).
     */
    fun launchScript(rootfs: File, guestArgv: List<String>, binds: List<String>, logFile: String? = null): String {
        val r = quote(rootfs.absolutePath)
        val base = linkedMapOf(
            "-t proc proc" to "$r/proc",
            "--bind /dev" to "$r/dev",
            "--bind /sys" to "$r/sys",
        )
        val extra = ArrayList<Pair<String, String>>()
        for (bind in binds) {
            val parts = bind.split(':', limit = 2)
            if (parts.isEmpty()) continue
            val src = parts[0].trimEnd('/').ifEmpty { "/" }
            val dst = parts.getOrElse(1) { parts[0] }.trimEnd('/').ifEmpty { "/" }
            if (dst == "/" || !dst.startsWith("/")) continue
            extra += src to dst
        }
        val extraLines = extra.joinToString("\n") { (src, dst) ->
            val host = quote(src)
            val guest = quote("$r$dst")
            buildString {
                append("if [ -e $host ]; then\n")
                append("  mkdir -p \$(dirname $guest) 2>/dev/null || true\n")
                append("  if [ -d $host ]; then mkdir -p $guest 2>/dev/null || true; else touch $guest 2>/dev/null || true; fi\n")
                append("  if mount --bind $host $guest 2>/dev/null; then echo $guest >> \$R/.dsh-root-mounts; fi\n")
                append("fi")
            }
        }
        val unmount = extra.asReversed().joinToString("\n") { (_, dst) ->
            "umount -l ${quote("$r$dst")} 2>/dev/null || true"
        }
        val env = guestArgv.joinToString(" ", transform = ::quote)
        val redirect = if (logFile != null) {
            " 2>&1 | tee -a ${quote(logFile)}; code=\${PIPESTATUS:-0}"
        } else {
            "; code=\$?"
        }
        return buildString {
            append("set -u\n")
            append("R=$r\n")
            append("mkdir -p \$R/proc \$R/sys \$R/dev \$R/dev/shm \$R/tmp \$R/root 2>/dev/null || true\n")
            append(": > \$R/.dsh-root-mounts 2>/dev/null || true\n")
            append("cleanup() {\n")
            if (unmount.isNotEmpty()) {
                append(unmount.replace("\n", "\n  ").let { "  $it" })
                append("\n")
            }
            append("  umount -l \$R/sys 2>/dev/null || true\n")
            append("  umount -l \$R/dev/shm 2>/dev/null || true\n")
            append("  umount -l \$R/dev 2>/dev/null || true\n")
            append("  umount -l \$R/proc 2>/dev/null || true\n")
            append("}\n")
            append("trap cleanup EXIT INT TERM HUP\n")
            for ((flags, target) in base) {
                append("mount $flags ${quote(target)} 2>/dev/null || true\n")
            }
            append("mount -t tmpfs tmpfs \$R/dev/shm 2>/dev/null || true\n")
            if (extraLines.isNotEmpty()) {
                append(extraLines)
                append("\n")
            }
            append("chroot \$R $env$redirect\n")
            append("exit \$code\n")
        }
    }

    /** The guest pid (`echo $$ > /tmp/dsh-web.pid`), read from the host. */
    fun guestPid(rootfs: File): Int? = try {
        File(rootfs, "tmp/dsh-web.pid").readText().trim().toIntOrNull()?.takeIf { it > 0 }
    } catch (_: Exception) {
        null
    }

    /** SIGTERM node (dsh exits 0), then SIGKILL; the launch script's trap unmounts. */
    fun terminate(rootfs: File, waitMs: Long = 6_000): Boolean {
        val pid = guestPid(rootfs) ?: return false
        RootExec.run("kill -TERM $pid 2>/dev/null; true", 20_000)
        val deadline = System.nanoTime() + waitMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (!alive(pid)) return true
            Thread.sleep(120)
        }
        RootExec.run("kill -KILL $pid 2>/dev/null; true", 20_000)
        return true
    }

    fun alive(pid: Int): Boolean = try {
        RootExec.run("test -d /proc/$pid && echo yes || echo no", 15_000).stdout.contains("yes")
    } catch (_: Exception) {
        false
    }

    /** Lazy-umounts whatever an interrupted launch left inside this rootfs. */
    fun cleanupMounts(rootfs: File) {
        val r = quote(rootfs.absolutePath)
        RootExec.run(
            "for m in \$(cat $r/.dsh-root-mounts 2>/dev/null); do umount -l \"\$m\" 2>/dev/null; done; " +
                "umount -l $r/sys 2>/dev/null; umount -l $r/dev/shm 2>/dev/null; " +
                "umount -l $r/dev 2>/dev/null; umount -l $r/proc 2>/dev/null; true",
            60_000,
        )
    }

    /** Makes the guest's own dirs root-owned (cosmetic; root ignores the app's modes anyway). */
    fun adopt(rootfs: File) {
        val r = quote(rootfs.absolutePath)
        RootExec.run("chown -R 0:0 $r/root $r/tmp $r/var/lib/dsh-app 2>/dev/null; true", 120_000)
    }

    internal fun waitFor(process: Process, millis: Long): Boolean = try {
        process.waitFor(millis, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        false
    }
}
