package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import android.os.Build
import io.github.ccch1mneyyy.dsh_app.phone.McpServer
import io.github.ccch1mneyyy.dsh_app.phone.ToolOutput
import java.io.File

/**
 * Process-wide owner of the root side: the probe cache, the bridge, the MCP tool implementation,
 * and frida-server.
 *
 * `prepareLaunch` runs before every engine start: with real root available and the switch on, it
 * writes the guest relay + overlay, opens the bridge and returns true (the launch then adds
 * `--patch`). Otherwise it makes sure nothing is listening and returns false: the model then sees
 * no `mcp__root__*` tools at all.
 */
class RootControl private constructor(private val app: Context) {

    private val server = McpServer(RootToolCatalog.listJson(), ::call, SERVER_VERSION)
    private val bridge = RootBridge(server) { line -> logger(line) }

    @Volatile private var logger: (String) -> Unit = {}
    @Volatile private var rootfs: File? = null
    @Volatile private var enabledCache: Boolean? = null

    /** The probe result without spawning su (uses the process-wide cache). */
    fun support(): RootEngine.Support = RootEngine.cachedSupport() ?: RootEngine.Support(
        RootEngine.suPath(), -1, false, false, "", Build.SUPPORTED_ABIS.firstOrNull() ?: "", "unprobed",
        RootEngine.manager(),
    )

    fun isEnabled(): Boolean = enabledCache ?: prefs.getBoolean(K_ENABLED, true).also { enabledCache = it }

    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        prefs.edit().putBoolean(K_ENABLED, enabled).apply()
        enabledCache = enabled
        if (!enabled) bridge.stop()
        emitStatus()
        return true
    }

    fun probe(force: Boolean): RootEngine.Support {
        val result = RootEngine.probe(force)
        emitStatus()
        return result
    }

    val bridgeListening: Boolean get() = bridge.listening

    /**
     * Before the engine launches. Returns whether the root overlay should be added.
     * [rootEngine] says whether this launch will actually be uid 0 (chroot), which is what makes
     * `exec` / frida meaningful; the overlay is still served under proot for read-only status.
     */
    fun prepareLaunch(rootfs: File, safe: Boolean, rootEngine: Boolean, log: (String) -> Unit): Boolean {
        logger = log
        bridge.stop()
        this.rootfs = rootfs
        // Whatever happens next — safe mode, tools off, proot/direct — this launch runs as the app,
        // so anything the root engine wrote as uid 0 must go back to the app's uid first. Otherwise
        // DSH cannot write its own config, sessions and plugins on the next non-root start.
        if (!rootEngine) handBackToApp(rootfs, log)
        if (!isEnabled() || safe) {
            RootGuest.clearConfig(rootfs)
            if (isEnabled()) log("[dsh-app] root tools stay off in safe mode")
            emitStatus()
            return false
        }
        val support = RootEngine.cachedSupport() ?: RootEngine.probe()
        if (!support.usable) {
            RootGuest.clearConfig(rootfs)
            log("[dsh-app] root tools: no usable su/chroot (${support.reason ?: "unknown"}); model tools off")
            emitStatus()
            return false
        }
        return try {
            RootGuest.writeFiles(rootfs)
            val ep = bridge.start(withTcp = false)
            RootGuest.writeConfig(rootfs, ep)
            if (rootEngine) RootEngine.adopt(rootfs)
            emitStatus()
            true
        } catch (e: Exception) {
            log("[dsh-app] root tools: setup failed (${e.message}); starting without them")
            bridge.stop()
            false
        }
    }

    /**
     * Runs outside the root engine path too (safe mode, or the person switching the engine off), so
     * a root run never leaves the runtime unwritable. Cheap when the uid already matches.
     */
    private fun handBackToApp(rootfs: File, log: (String) -> Unit) {
        val uid = android.os.Process.myUid()
        try {
            if (RootEngine.releaseTo(rootfs, uid)) {
                log("[dsh-app] rootfs ownership returned to the app (uid $uid) for this launch")
            }
        } catch (e: Exception) {
            log("[dsh-app] rootfs ownership: could not return it to the app (${e.message})")
        }
    }

    fun launched(mounted: Boolean) {
        if (mounted) return
        logger("[dsh-app] root tools: this runtime's launch command has no `dsh web` to add the overlay to; tools are off")
        bridge.stop()
    }

    fun onEngineStopped() {
        bridge.stop()
        rootfs?.let { RootGuest.clearConfig(it) }
        emitStatus()
    }

    // ── Tools ───────────────────────────────────────────────────────────────────────

    private fun call(name: String, args: Map<String, Any?>): ToolOutput = try {
        when (name) {
            RootToolCatalog.STATUS -> ToolOutput(statusText())
            RootToolCatalog.EXEC -> execTool(args)
            RootToolCatalog.GUEST -> guestTool(args)
            RootToolCatalog.FRIDA_SCAN -> ToolOutput(fridaScanText())
            RootToolCatalog.FRIDA_START -> fridaStart(args)
            RootToolCatalog.FRIDA_STOP -> fridaStop()
            RootToolCatalog.FRIDA_STATUS -> ToolOutput(Frida.report())
            RootToolCatalog.FRIDA_PS -> fridaPs(args)
            RootToolCatalog.FRIDA_SCRIPT -> fridaScript(args)
            else -> ToolOutput("没有这个工具：$name", isError = true)
        }
    } catch (e: Throwable) {
        // l10n:keep agent tool error
        ToolOutput("root 工具出错：${e.javaClass.simpleName}${e.message?.let { "：$it" } ?: ""}", isError = true)
    }

    private fun statusText(): String {
        val s = RootEngine.cachedSupport() ?: RootEngine.probe()
        val rb = rootfs
        return buildString {
            appendLine("su：${s.su ?: "未找到"}")
            appendLine("管理器：${s.manager.ifEmpty { "未知" }}")
            appendLine("uid（probe）：${s.uid}")
            appendLine("chroot：${if (s.chroot) "可用" else "不可用"}")
            appendLine("mount：${if (s.mount) "可用" else "不可用"}")
            appendLine("内核：${s.kernel.ifEmpty { "?" }}")
            appendLine("ABI：${s.abi} · Android ${s.sdk}")
            appendLine("rootfs：${if (rb != null && rb.isDirectory) rb.absolutePath else "未解压"}")
            appendLine("root 工具开关：${if (isEnabled()) "开" else "关"} · 桥接：${if (bridge.listening) "监听中" else "未启动"}")
            append("frida-server：${if (Frida.running()) "运行中" else "未运行"}")
        }
    }

    private fun timeoutOf(args: Map<String, Any?>, fallback: Long): Long {
        val raw = args["timeoutMs"]
        val value = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: fallback
            else -> fallback
        }
        return value.coerceIn(1_000, 600_000)
    }

    private fun format(result: RootExec.Result): String {
        val out = StringBuilder()
        out.appendLine("exit=${result.exitCode} uid=${result.uid}")
        if (result.stdout.isNotBlank()) out.appendLine("--- stdout ---").appendLine(result.stdout.trimEnd())
        if (result.stderr.isNotBlank()) out.appendLine("--- stderr ---").appendLine(result.stderr.trimEnd())
        if (result.stdout.isBlank() && result.stderr.isBlank()) out.append("(无输出)")
        return out.toString()
    }

    private fun execTool(args: Map<String, Any?>): ToolOutput {
        val command = args["command"] as? String
        if (command.isNullOrBlank()) return ToolOutput("exec 需要 command", isError = true)
        val result = RootExec.run(command, timeoutOf(args, 60_000))
        return ToolOutput(format(result), isError = !result.ok)
    }

    private fun guestTool(args: Map<String, Any?>): ToolOutput {
        val command = args["command"] as? String
        if (command.isNullOrBlank()) return ToolOutput("guest 需要 command", isError = true)
        val root = rootfs ?: return ToolOutput("rootfs 还没解压，先启动一次引擎", isError = true)
        val script = RootEngine.launchScript(root, listOf("/usr/bin/env", "HOME=/root", "/bin/sh", "-lc", command), emptyList())
        val result = RootExec.run(script, timeoutOf(args, 120_000))
        return ToolOutput(format(result), isError = !result.ok)
    }

    private fun fridaScanText(): String {
        val found = Frida.scan()
        if (found.isEmpty()) return "设备上没找到本地 frida-server；可以用 frida_start 的 version 让它联网下载。"
        return buildString {
            appendLine("找到 ${found.size} 个本地 frida-server：")
            for (c in found) {
                appendLine("· ${c.path}（${c.bytes / 1024 / 1024} MB${if (c.compressed) " · xz" else ""}）")
            }
            append("用 frida_start 的 path 参数指定其中一个即可。")
        }
    }

    private fun fridaStart(args: Map<String, Any?>): ToolOutput {
        val version = args["version"] as? String
        val path = args["path"] as? String
        val port = (args["port"] as? Number)?.toInt() ?: DEFAULT_PORT
        val state = if (!path.isNullOrBlank()) Frida.installLocal(path, port) else Frida.install(app, version, port)
        return ToolOutput(state.text, isError = state.ok != true)
    }

    private fun fridaStop(): ToolOutput = ToolOutput(Frida.stopText(), isError = false)

    private fun fridaPs(args: Map<String, Any?>): ToolOutput {
        val filter = (args["filter"] as? String)?.trim().orEmpty()
        val result = RootExec.run(
            "ps -A -o PID,NAME 2>/dev/null || ps -A 2>/dev/null || ps",
            30_000,
        )
        val lines = result.stdout.lines()
        val kept = if (filter.isEmpty()) lines else lines.filter { it.contains(filter, ignoreCase = true) }
        return ToolOutput(if (kept.isEmpty()) "没有匹配的进程" else kept.joinToString("\n").take(60_000))
    }

    /**
     * Runs a Frida JS script against a target through the guest's frida Python package, so the
     * script text never needs shell-quoting: it goes in as a file under /tmp.
     */
    private fun fridaScript(args: Map<String, Any?>): ToolOutput {
        val target = args["target"] as? String
        val script = args["script"] as? String
        if (target.isNullOrBlank() || script.isNullOrBlank()) return ToolOutput("frida_script 需要 target 和 script", isError = true)
        val duration = ((args["durationMs"] as? Number)?.toInt() ?: 3000).coerceIn(200, 120_000)
        val spawn = args["spawn"] == true
        val root = rootfs ?: return ToolOutput("rootfs 还没解压", isError = true)
        if (!Frida.running()) return ToolOutput("frida-server 没在跑，先 frida_start", isError = true)
        val file = File(root, "tmp/dsh-frida-${System.nanoTime()}.js")
        file.parentFile?.mkdirs()
        file.writeText(script)
        val py = File(root, "tmp/dsh-frida-${System.nanoTime()}.py")
        py.writeText(
            buildString {
                appendLine("import sys, time, frida")
                appendLine("script_text = open(${pyQuote(file.absolutePath)}).read()")
                appendLine("target = ${pyQuote(target)}")
                appendLine("mode = 'spawn' if ${if (spawn) "True" else "False"} else 'attach'")
                appendLine("dev = frida.get_device_manager().add_remote_device('127.0.0.1:$DEFAULT_PORT')")
                append("pid = dev.spawn([target]) if mode == 'spawn' else dev.attach(int(target) if target.isdigit() else target)\n")
                appendLine("session = dev.attach(pid) if mode == 'spawn' else pid")
                appendLine("sc = session.create_script(script_text)")
                appendLine("sc.on('message', lambda m, d: print('[frida]', m))")
                appendLine("sc.load()")
                appendLine("if mode == 'spawn': dev.resume(pid)")
                appendLine("time.sleep($duration / 1000.0)")
                appendLine("session.detach()")
            },
        )
        val script_ = RootEngine.launchScript(
            root,
            listOf("/usr/bin/env", "HOME=/root", "/bin/sh", "-lc", "python3 ${RootEngine.quote(py.absolutePath)}"),
            emptyList(),
        )
        val result = RootExec.run(script_, (duration + 60_000).toLong())
        runCatching { file.delete() }
        runCatching { py.delete() }
        return ToolOutput(format(result), isError = !result.ok)
    }

    private fun pyQuote(text: String): String = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

    // ── Flutter bridge ──────────────────────────────────────────────────────────────

    fun status(): Map<String, Any?> {
        val s = support()
        val rb = rootfs
        return linkedMapOf(
            "enabled" to isEnabled(),
            "su" to s.su,
            "manager" to s.manager,
            "usable" to s.usable,
            "chroot" to s.chroot,
            "reason" to s.reason,
            "kernel" to s.kernel,
            "abi" to s.abi,
            "uid" to s.uid,
            "sdk" to Build.VERSION.SDK_INT,
            "rootfs" to (rb?.isDirectory == true),
            "listening" to bridge.listening,
            "frida" to Frida.statusMap(),
        )
    }

    private fun emitStatus() {
        // Throttled surface for the settings page; nothing here pushes to Dart (it polls).
    }

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        const val PREFS = "dsh-root"
        const val K_ENABLED = "rootToolsEnabled"
        const val DEFAULT_PORT = 27042
        const val SERVER_VERSION = "1"

        @Volatile private var instance: RootControl? = null

        fun get(context: Context): RootControl =
            instance ?: synchronized(this) { instance ?: RootControl(context.applicationContext).also { instance = it } }
    }
}
