package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import android.os.Build
import java.io.File

/**
 * frida-server, installed and started as **root** (real uid 0) at /data/local/tmp.
 *
 * Source order:
 *  1. a local file the person names (or one [scan] found) — the usual case; no network at all;
 *  2. a bundled copy in `assets/frida/frida-server`, if the build put one there;
 *  3. the GitHub release for this ABI through a mirror chain.
 *
 * Every candidate is validated with `--version` before the running server is replaced, so a
 * truncated download or a wrong-architecture binary never takes out a working one.
 */
internal object Frida {

    private val ABI_NAMES = mapOf(
        "arm64-v8a" to "arm64",
        "armeabi-v7a" to "arm",
        "x86_64" to "x86_64",
        "x86" to "x86",
    )

    /** Used when the caller names no version. Newest first; any of them may be gone. */
    private val FALLBACK_VERSIONS = listOf("17.25.1", "17.6.2", "16.7.19", "15.2.2")

    /** Where a person usually keeps a downloaded frida-server. Scanned shallowly, as root. */
    private val SCAN_DIRS = listOf(
        "/sdcard/Download/DSH-Go", "/sdcard/Download", "/sdcard/Documents", "/data/local/tmp",
    )

    private const val DIR = "/data/local/tmp"
    private const val REMOTE = "$DIR/frida-server"

    data class State(
        val ok: Boolean,
        val text: String,
        val version: String? = null,
        val path: String? = null,
        val port: Int = RootControl.DEFAULT_PORT,
        val pid: String = "",
    )

    fun arch(): String? = ABI_NAMES[Build.SUPPORTED_ABIS.firstOrNull() ?: ""]

    /** One candidate found on the device. */
    data class Candidate(val path: String, val bytes: Long, val compressed: Boolean) {
        fun map(): Map<String, Any?> = linkedMapOf(
            "path" to path, "bytes" to bytes, "compressed" to compressed,
        )
    }

    /** Lists local frida-server binaries, newest-looking first. Read-only; needs root to read /sdcard. */
    fun scan(): List<Candidate> {
        val dirs = SCAN_DIRS.joinToString(" ") { RootEngine.quote(it) }
        // A raw string: the shell needs its own backslashes, and Kotlin rejects \( and \; in a literal.
        val out = RootExec.run(
            """find $dirs -maxdepth 2 -type f \( -iname '*frida-server*' -o -iname 'frida-server*' \) -exec stat -c '%s %n' {} \; 2>/dev/null | head -40""",
            60_000,
        )
        return out.stdout.lines().mapNotNull { line ->
            val at = line.indexOf(' ')
            if (at <= 0) return@mapNotNull null
            val bytes = line.substring(0, at).toLongOrNull() ?: return@mapNotNull null
            val path = line.substring(at + 1).trim()
            if (path.isEmpty() || bytes < 1_000_000L) return@mapNotNull null
            Candidate(path, bytes, path.endsWith(".xz"))
        }.sortedByDescending { it.bytes }
    }

    /**
     * Installs from a file already on the device. [port] is where the server ends up listening.
     * [path] may be a raw binary or an .xz of one.
     */
    fun installLocal(path: String, port: Int): State {
        val arch = arch() ?: return State(false, "这台设备的 ABI 不受支持：${Build.SUPPORTED_ABIS.firstOrNull()}")
        val probe = RootEngine.cachedSupport() ?: RootEngine.probe()
        if (!probe.usable) return State(false, "没有可用的 su/chroot：${probe.reason ?: "unknown"}")
        val src = RootEngine.quote(path)
        val exists = RootExec.run("test -f $src && stat -c %s $src || echo 0", 30_000).stdout.trim()
        val bytes = exists.toLongOrNull() ?: 0L
        if (bytes < 1_000_000L) return State(false, "这个文件不存在或太小：$path")
        val state = deploy(path, versionOf(path), port)
        return state ?: State(false, "安装失败：这不是能在这个 ABI（$arch）上运行的 frida-server")
    }

    private fun versionOf(path: String): String {
        val m = Regex("""(\d+\.\d+\.\d+)""").find(path.substringAfterLast('/'))
        return "本地 ${m?.groupValues?.get(1) ?: "文件"}"
    }

    fun install(context: Context, version: String?, port: Int): State {
        val arch = arch() ?: return State(false, "这台设备的 ABI 不受支持：${Build.SUPPORTED_ABIS.firstOrNull()}")
        val probe = RootEngine.cachedSupport() ?: RootEngine.probe()
        if (!probe.usable) return State(false, "没有可用的 su/chroot：${probe.reason ?: "unknown"}")

        // A binary the person already downloaded beats every network path.
        val local = if (version == null) scan().firstOrNull() else null
        if (local != null) {
            deploy(local.path, versionOf(local.path), port)?.let { return it }
        }

        // Bundled next: no network, known bytes.
        val bundled = File(context.cacheDir, "frida-server-asset")
        runCatching {
            context.assets.open("frida/frida-server").use { input ->
                bundled.outputStream().use { out -> input.copyTo(out) }
            }
        }
        if (bundled.isFile && bundled.length() > 1_000_000L) {
            val v = deploy(bundled.absolutePath, "bundled", port)
            if (v != null) return v
        }

        val candidates = (version?.let { listOf(it) } ?: emptyList()) + FALLBACK_VERSIONS
        var lastError = "没有匹配的 frida 版本"
        for (v in candidates) {
            val file = "frida-server-$v-android-$arch.xz"
            val urls = listOf(
                "https://github.com/frida/frida/releases/download/$v/$file",
                "https://gh-proxy.com/https://github.com/frida/frida/releases/download/$v/$file",
                "https://ghfast.top/https://github.com/frida/frida/releases/download/$v/$file",
                "https://ghproxy.net/https://github.com/frida/frida/releases/download/$v/$file",
            )
            val local = File(context.cacheDir, "frida-$v-$arch.xz")
            var got = false
            for (url in urls) {
                val r = RootExec.run(
                    "curl -fL --retry 2 --connect-timeout 20 -o ${RootEngine.quote(local.absolutePath)} ${RootEngine.quote(url)}" +
                        " || wget -q -O ${RootEngine.quote(local.absolutePath)} ${RootEngine.quote(url)}",
                    600_000,
                )
                if (r.ok && local.isFile && local.length() > 1_000_000L) {
                    got = true
                    break
                }
            }
            if (!got) {
                lastError = "下载失败：$v"
                continue
            }
            val state = deploy(local.absolutePath, v, port)
            runCatching { local.delete() }
            if (state != null) return state
            lastError = "解压或启动失败：$v"
        }
        return State(false, lastError)
    }

    /**
     * Unpacks an .xz (or copies a raw binary) to /data/local/tmp, checks it with `--version`,
     * and only then stops the old server and starts this one. Null when the file is not a
     * runnable frida-server for this device.
     */
    private fun deploy(local: String, version: String, port: Int): State? {
        val src = RootEngine.quote(local)
        val unpack = RootExec.run(
            "case $src in *.xz) xz -dcf $src > $REMOTE ;; *) cp -f $src $REMOTE ;; esac; " +
                "chmod 755 $REMOTE; chown 0:0 $REMOTE; $REMOTE --version",
            300_000,
        )
        if (!unpack.ok) return null
        val printed = unpack.stdout.trim().lines().firstOrNull().orEmpty()
        stopQuiet()
        val start = RootExec.run(
            "cd $DIR && (setsid ./frida-server -l 127.0.0.1:$port >/dev/null 2>&1 &) ; sleep 1; " +
                "pgrep -f frida-server | head -1",
            60_000,
        )
        val pid = start.stdout.trim().lines().firstOrNull().orEmpty()
        val ok = pid.isNotEmpty()
        return State(
            ok = ok,
            text = if (ok) "frida-server 已启动（$version，$printed）· PID $pid · 127.0.0.1:$port\n$REMOTE"
            else "frida-server 解压好了但没起来：${start.stderr.take(300)}",
            version = version,
            path = REMOTE,
            port = port,
            pid = pid,
        )
    }

    fun running(): Boolean = RootExec.run("pgrep -f frida-server >/dev/null && echo yes || echo no", 20_000)
        .stdout.contains("yes")

    fun pids(): String = RootExec.run("pgrep -f frida-server | head -5", 20_000).stdout.trim()

    fun listening(port: Int = RootControl.DEFAULT_PORT): Boolean {
        val hex = Integer.toHexString(port).uppercase().padStart(4, '0')
        return RootExec.run("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -i ':$hex' | head -2", 20_000)
            .stdout.isNotBlank()
    }

    fun report(): String {
        val pid = pids()
        val up = pid.isNotEmpty()
        val port = RootControl.DEFAULT_PORT
        val version = RootExec.run("$REMOTE --version 2>/dev/null | head -1", 20_000).stdout.trim()
        return buildString {
            appendLine(if (up) "frida-server 运行中 · PID $pid" else "frida-server 未运行")
            appendLine("监听：${if (listening(port)) "127.0.0.1:$port 在监听" else "没有监听 127.0.0.1:$port"}")
            appendLine("路径：$REMOTE（${if (File(REMOTE).exists()) "存在" else "不存在"}）")
            append("版本：${version.ifEmpty { "未知" }}")
        }
    }

    fun statusMap(): Map<String, Any?> {
        val pid = pids()
        return linkedMapOf(
            "running" to pid.isNotEmpty(),
            "pids" to pid,
            "port" to RootControl.DEFAULT_PORT,
            "listening" to listening(),
            "path" to REMOTE,
        )
    }

    fun stopText(): String {
        stopQuiet()
        return if (running()) "frida-server 还在跑（可能被别的管理器拉起）" else "frida-server 已停止"
    }

    private fun stopQuiet() {
        RootExec.run("pkill -f frida-server; sleep 0.3; pkill -9 -f frida-server; true", 40_000)
    }
}
