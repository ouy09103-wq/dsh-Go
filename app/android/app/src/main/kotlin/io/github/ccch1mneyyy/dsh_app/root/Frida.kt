package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import android.os.Build
import java.io.File

/**
 * frida-server, installed and started as **root** (real uid 0) at /data/local/tmp.
 *
 * Source order: a bundled copy in `assets/frida/frida-server` (if the build put one there), then
 * the GitHub release for this ABI through a mirror chain. The binary is verified with
 * `--version` before it is started, so a truncated download never replaces a working one.
 */
internal object Frida {

    private val ABI_NAMES = mapOf(
        "arm64-v8a" to "arm64",
        "armeabi-v7a" to "arm",
        "x86_64" to "x86_64",
        "x86" to "x86",
    )

    /** Used when the caller names no version. */
    private val FALLBACK_VERSIONS = listOf("17.6.2", "17.2.17", "17.2.9", "16.7.19")

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

    fun install(context: Context, version: String?, port: Int): State {
        val arch = arch() ?: return State(false, "这台设备的 ABI 不受支持：${Build.SUPPORTED_ABIS.firstOrNull()}")
        val probe = RootEngine.cachedSupport() ?: RootEngine.probe()
        if (!probe.usable) return State(false, "没有可用的 su/chroot：${probe.reason ?: "unknown"}")

        // Bundled first: no network, known bytes.
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

    /** Unpacks an .xz (or a raw binary) to /data/local/tmp, checks it, restarts it. */
    private fun deploy(local: String, version: String, port: Int): State? {
        val src = RootEngine.quote(local)
        val unpack = RootExec.run(
            "case $src in *.xz) xz -dcf $src > $REMOTE ;; *) cp -f $src $REMOTE ;; esac; " +
                "chmod 755 $REMOTE; chown 0:0 $REMOTE; $REMOTE --version",
            180_000,
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
