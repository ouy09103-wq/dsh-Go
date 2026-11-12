package io.github.ouy09103.dsh_go_root.root

import android.content.Context
import java.io.File

/**
 * frida-server 由 **Frida Control 模块**（KernelSU 模块 `frida-control`）提供，App 不再自带。
 *
 * 为什么不内置：目标 App（com.chunqiunativecheck 那类）的检测判据是
 * 「存在可执行段但没有对应磁盘文件」。frida-server 注入时 agent 走 memfd，
 * `/proc/self/maps` 里天生就是 `/memfd:frida-agent-64.so (deleted)` —— 改名字、
 * 改线程名、mprotect、换 abort 全都无效，因为它读的是内核原始 maps。
 * 实测：只 spawn 不 attach 活 7.9s，attach 后 0.3s detach 活 9.6s，
 * agent 常驻则 0.7~1.9s 死。所以「把 server 藏得更好」这条路走不通。
 *
 * 模块把二进制放在 `/data/adb/frida-hide/`（0700，非 root 进程内核层就拒绝），
 * 改名为 `ksud_helper`，进程名和路径都不含 frida。App 这里只负责**调用模块的
 * ctl.sh**，不再自己写文件、不再自己 setsid、不再改二进制。
 *
 * 端口：模块默认 27042，但目标 App 会主动连 27042，连上就 abort（3 次入站即触发）。
 * 所以这里默认 27043，跟实测通过的配置一致；启动前会把模块的 config.prop 也改成一致。
 */
internal object Frida {

    /** 模块目录（KernelSU / APatch 通用）。 */
    private const val MODULE_DIR = "/data/adb/modules/frida-control"
    private const val MODULE_CTL = "$MODULE_DIR/ctl.sh"
    private const val MODULE_CONF = "$MODULE_DIR/config.prop"

    /** 模块隐藏后的二进制位置，仅用于状态展示与兜底探测。 */
    private const val HIDDEN_BIN = "/data/adb/frida-hide/ksud_helper"

    /** 旧位置：模块关闭 HIDE 时用。 */
    private const val LEGACY_BIN = "/data/local/tmp/frida-server"

    /**
     * 默认端口。
     *
     * **不是 27042。** 目标会主动连 27042，连上即 abort；实测 27043 是 10/10 存活、
     * 27042 只有 2/10。
     */
    const val DEFAULT_PORT = 27043

    /** 客户端 pin，服务端版本读不到时用。 */
    private const val DEFAULT_CLIENT_PIN = "17.25.2"

    /** `17.23.3` 这种形状。 */
    private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")

    /**
     * 永不注入的进程。往 system_server 或 zygote 注 agent，崩一次就是整机重启。
     */
    val FORBIDDEN_TARGETS = setOf(
        "system_server", "zygote", "zygote64", "zygote32", "init", "surfaceflinger",
    )

    // ── 模块对接 ────────────────────────────────────────────────────────────────

    /** 模块装没装：ctl.sh 存在且可读。 */
    private fun modulePresent(): Boolean =
        RootExec.run("test -f $MODULE_CTL && echo yes", 15_000).stdout.contains("yes")

    /** 跑一条 ctl.sh 命令，返回 (stdout, exitCode)。 */
    private fun ctl(vararg args: String): RootExec.Result {
        val quoted = args.joinToString(" ") { quote(it) }
        return RootExec.run("sh $MODULE_CTL $quoted 2>&1", 90_000)
    }

    /**
     * 把模块的 config.prop 对齐到 App 这边要的端口。
     *
     * 两处配置各说各话是最容易踩的坑：模块默认 27042，而 27042 会让目标 abort。
     * 每次启动前写一次，幂等；写不进去不算失败（只读挂载时模块仍能用它的值）。
     */
    private fun alignPort(port: Int): Boolean {
        val script = buildString {
            append("C=$MODULE_CONF; [ -f \"\$C\" ] || exit 4; ")
            append("grep -q '^PORT=$port\$' \"\$C\" 2>/dev/null && { echo PORT_OK; exit 0; }; ")
            append("sed -i 's/^PORT=.*/PORT=$port/' \"\$C\" 2>/dev/null; ")
            append("grep -q '^PORT=$port\$' \"\$C\" 2>/dev/null && echo PORT_OK || echo PORT_FAIL")
        }
        val r = RootExec.run(script, 20_000)
        return r.stdout.contains("PORT_OK")
    }

    /**
     * 把模块 `ctl.sh json` 的输出解析成 Map。
     *
     * v2 起模块直接吐 JSON，App 不再解析中文。解析失败返回空 map —— 老模块（v1）
     * 只会输出中文，这时 status 退化成「模块在但读不到状态」，不会崩。
     */
    private fun parseJson(out: String): Map<String, Any?> {
        val text = out.trim()
        if (!text.startsWith("{")) return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(text)
            val map = linkedMapOf<String, Any?>()
            for (key in obj.keys()) {
                when (val v = obj.get(key)) {
                    is org.json.JSONObject.NULL -> map[key] = null
                    else -> map[key] = v
                }
            }
            map
        }.getOrDefault(emptyMap())
    }

    /** JSON 里的布尔值；缺省 false。 */
    private fun flag(m: Map<String, Any?>, key: String): Boolean = when (val v = m[key]) {
        is Boolean -> v
        is String -> v == "true"
        else -> false
    }

    /** JSON 里的字符串；缺省 null。 */
    private fun text(m: Map<String, Any?>, key: String): String? =
        (m[key] as? String)?.takeIf { it.isNotBlank() }

    /**
     * 启动 frida（模块负责搬文件、改名、起进程）。
     *
     * [port] 会先写进模块的 config.prop，再 `ctl.sh start`。
     * [stealth] 保留参数是为了接口兼容 —— 隐藏现在由模块的 HIDE 开关负责，
     * App 这边不再改二进制（改名对 memfd 检测无效，记录里已证伪）。
     */
    fun install(context: Context, port: Int, stealth: Boolean = false): Map<String, Any?> {
        RootExec.suPath() ?: return fail("这台手机上没有 su，先用 root 管理器给 DSH Go 授权")
        if (!modulePresent()) {
            return fail(
                "没装 Frida Control 模块。frida-server 现在由模块提供，不再内置在 App 里。\n" +
                    "先装模块：把 frida-control-v1.3.0.zip 放进 KernelSU / APatch 里刷入，" +
                    "再把 frida-server 放到 /data/adb/frida-hide/ksud_helper（chmod 700），" +
                    "然后回来点「启用 Frida」。",
            )
        }
        val probe = RootExec.probe()
        if (!probe.ok || probe.uid != 0) {
            return fail("root 没授权成功：" + (probe.stderr.ifBlank { "uid=${probe.uid}" }))
        }
        val target = port.coerceIn(1, 65535)
        val aligned = alignPort(target)
        val r = ctl("start")
        // v2 模块的 start 直接返回状态 JSON：成功和失败都有结构化原因。
        val m = parseJson(r.stdout)
        if (m.isEmpty()) {
            val out = r.stdout.trim()
            return mapOf(
                "ok" to (r.exitCode == 0),
                "port" to target,
                "error" to if (r.exitCode == 0) null else
                    "模块没有返回 JSON（可能还是 v1）。原文：" +
                        (out.lines().firstOrNull { it.isNotBlank() } ?: "空（exit=${r.exitCode}）"),
                "stdout" to out.take(1_500),
                "hint" to "建议升级到 frida-control v2：它输出 JSON，失败时给 errorCode。",
            )
        }
        val running = flag(m, "running")
        val listening = flag(m, "listening")
        if (!running || !listening) {
            return mapOf(
                "ok" to false,
                "port" to (m["port"] as? Int) ?: target,
                "state" to text(m, "state"),
                "error" to (text(m, "error") ?: "模块启动了，但进程或端口没就绪"),
                "errorCode" to text(m, "errorCode"),
                "hint" to "到「常驻 root shell」里跑 sh $MODULE_CTL log 20 看日志。",
            )
        }
        return mapOf(
            "ok" to true,
            "port" to (m["port"] as? Int) ?: target,
            "portAligned" to aligned,
            "path" to text(m, "path"),
            "version" to text(m, "version_server"),
            "pid" to text(m, "pid"),
            "module" to true,
            "stealth" to flag(m, "hidden"),
            "listening" to listening,
            "state" to text(m, "state"),
        )
    }

    /**
     * 停掉 frida（走模块的 ctl.sh stop）。
     *
     * 模块会把隐藏位置和旧位置的进程一起收掉，App 不再自己 pgrep / kill。
     */
    fun stop() {
        if (!modulePresent()) return
        ctl("stop")
    }

    /**
     * 状态：模块在不在、frida 跑没跑、哪个端口、什么版本。
     *
     * 判据和之前一致：**端口在 LISTEN 才算在跑**，`pidof` 只做展示。模块隐藏后
     * 进程名是 `ksud_helper`，`pidof frida-server` 永远空，只看它必然误报。
     */
    fun status(context: Context): Map<String, Any?> {
        RootExec.suPath() ?: return fail("这台手机上没有 su")
        val present = modulePresent()
        if (!present) {
            return mapOf(
                "ok" to true, "module" to false, "installed" to false, "running" to false,
                "state" to "NO_MODULE", "listening" to false, "pids" to "",
                "version" to null, "path" to null, "port" to DEFAULT_PORT, "hidden" to false,
            )
        }
        // v2 模块：一次 ctl.sh json 拿全部状态，不再逐个 su 探测。
        val m = parseJson(ctl("json").stdout)
        if (m.isEmpty()) {
            // v1 模块：JSON 解析不了，退回自己探测，至少让界面能显示。
            val path = when {
                RootExec.run("test -x $HIDDEN_BIN && echo yes", 15_000).stdout.contains("yes") -> HIDDEN_BIN
                RootExec.run("test -x $LEGACY_BIN && echo yes", 15_000).stdout.contains("yes") -> LEGACY_BIN
                else -> null
            }
            val port = activePort() ?: DEFAULT_PORT
            val up = listening(port)
            return mapOf(
                "ok" to true, "module" to true, "installed" to (path != null),
                "running" to up,
                "state" to if (path == null) "NOT_INSTALLED" else if (up) "RUNNING" else "STOPPED",
                "listening" to up, "pids" to pidOf(),
                "version" to null, "path" to path, "port" to port, "hidden" to false,
                "legacyModule" to true,
            )
        }
        val client = clientVersion(context)
        val serverVersion = text(m, "version_server")
        return mapOf(
            "ok" to true,
            "module" to true,
            "moduleVersion" to text(m, "version"),
            "modulePath" to MODULE_DIR,
            "installed" to flag(m, "binary"),
            "running" to flag(m, "running"),
            "state" to text(m, "state"),
            "listening" to flag(m, "listening"),
            "pids" to text(m, "pid").orEmpty(),
            "version" to serverVersion,
            "client" to client,
            "mismatch" to versionMismatch(serverVersion.orEmpty(), client),
            "path" to text(m, "path"),
            "port" to (m["port"] as? Int) ?: DEFAULT_PORT,
            "hidden" to flag(m, "hidden"),
            "mode" to text(m, "mode"),
            "autostart" to flag(m, "autostart"),
            "artEnv" to flag(m, "art_env"),
            "error" to text(m, "error"),
        )
    }

    fun ensureClient(context: Context, serverVersion: String?): Map<String, Any?> {
        val pin = serverVersion?.takeIf { VERSION.containsMatchIn(it) } ?: DEFAULT_CLIENT_PIN
        val script = buildString {
            // No `set -e` here on purpose: every install command is expected to fail on some
            // mirror, and a bare `&& break` under `set -e` would abort the whole script on the
            // first unreachable mirror instead of trying the next one.
            append("if python3 -c 'import frida' >/dev/null 2>&1; then ")
            append("  V=\$(python3 -c 'import frida; print(frida.__version__)' 2>/dev/null); ")
            // Compare the full triple: a patch difference is enough to break attach (the
            // report measured it), so 17.25.0 must not pass for a pinned 17.25.2.
            append("  case \"\$V\" in $pin) echo CLIENT_READY; exit 0;; esac; ")
            append("fi; ")
            append("PKGS='frida==$pin frida-tools'; ")
            append("for M in https://pypi.tuna.tsinghua.edu.cn/simple https://mirrors.aliyun.com/pypi/simple https://pypi.org/simple; do ")
            append("  if command -v uv >/dev/null 2>&1; then ")
            append("    uv pip install --python /usr/local/bin/python3 --break-system-packages -i \"\$M\" \$PKGS >/dev/null 2>&1 && break || true; ")
            append("  fi; ")
            append("  python3 -m pip install -q --break-system-packages --ignore-installed -i \"\$M\" \$PKGS >/dev/null 2>&1 && break || true; ")
            append("done; ")
            append("if python3 -c 'import frida' >/dev/null 2>&1; then ")
            append("  python3 -c 'import frida; print(\"CLIENT_READY \" + frida.__version__)' 2>/dev/null; ")
            append("else echo CLIENT_FAILED; fi")
        }
        val command = GuestExec.suCommand(context, script)
            ?: return mapOf("ok" to false, "error" to "rootfs 还没解压，装不了客户端")
        val result = RootExec.run(command, 300_000)
        val out = result.stdout.trim()
        return if (out.contains("CLIENT_READY")) {
            val got = Regex("""CLIENT_READY\s+([0-9.]+)""").find(out)?.groupValues?.get(1)
            mapOf("ok" to true, "version" to got, "pinned" to pin)
        } else {
            mapOf(
                "ok" to false,
                "error" to "装 frida 客户端失败（三个镜像都试过了）。" +
                    "多半是 Linux 里连不上网，或者 pip 坏了。到「常驻 root shell」里手动跑一次 " +
                    "`pip3 install frida==$pin -i https://pypi.tuna.tsinghua.edu.cn/simple` 看它报什么。",
                "stdout" to out.take(1_500),
                "stderr" to result.stderr.trim().take(1_500),
            )
        }
    }

    /**
     * The guest's frida-python version, or null when it can't be read (no rootfs, not installed).
     *
     * Asked through the packaged Ubuntu rootfs because that is where the inject client runs.
     */

    fun clientVersion(context: Context): String? {
        val command = GuestExec.suCommand(context, "python3 -c 'import frida; print(frida.__version__)'")
            ?: return null
        val result = RootExec.run(command, 60_000)
        if (!result.ok) return null
        return VERSION.find(result.stdout.trim())?.value
    }

    /**
     * A sentence when the client and the server don't share a major.minor, null when they do.
     *
     * 17.23.3 and 17.25.2 look compatible (same major) but are not: every attach times out after
     * 25 s. Matching major.minor is what frida actually needs — which is why the bundled server
     * tracks the version `pip3 install frida` gives you.
     */

    private fun versionMismatch(server: String, client: String?): String? {
        if (server.isBlank() || client.isNullOrBlank()) return null
        if (sameSeries(server, client)) return null
        // l10n:keep agent tool result
        return "frida 客户端和服务端版本不一致：服务端 $server，Linux 里的客户端 $client。" +
            "同主版本也不够——attach 会全部超时（实测 25s）。先在「常驻 root shell」里跑：" +
            "pip3 install frida==$server frida-tools -i https://pypi.tuna.tsinghua.edu.cn/simple"
    }

    /** `17.23.3` vs `17.23.9` count as one series; `17.23` vs `17.25` do not. */

    private fun sameSeries(a: String, b: String): Boolean {
        // Through the patch number: 17.25.0 client against a 17.25.2 server is not the same
        // thing, and the report measured the failure mode (attach hangs, reads as anti-debug).
        fun whole(v: String) = v.trim().split(".").take(3).joinToString(".")
        return whole(a) == whole(b)
    }


    /**
     * The port a running server was started with, or null when it can't be read.
     *
     * Taken from the process's own argv (`-l 127.0.0.1:27043`), so a server on a non-default
     * port is still seen as running. The module's own default is 27042, which targets probe;
     * running on 27043 is the configuration that survived.
     */
    private fun activePort(): Int? {
        val pid = pidOf().split(" ").firstOrNull { it.isNotBlank() } ?: return null
        val cmdline = RootExec.run("tr '\\0' ' ' < /proc/$pid/cmdline 2>/dev/null", 15_000)
            .stdout.trim()
        val match = Regex("""(?:-l\s+|:)(?:127\.0\.0\.1:|0\.0\.0\.0:)?(\d{2,5})""").find(cmdline)
        return match?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    /**
     * Pids of a running server, best effort.
     *
     * The module renames the binary to `ksud_helper`, so `pidof frida-server` alone is not
     * enough — both names are queried, and the last resort walks /proc for either string in a
     * cmdline. Empty is never a verdict; [status] goes by [listening].
     */
    private fun pidOf(): String {
        val named = RootExec.run(
            "pidof frida-server 2>/dev/null; pidof ksud_helper 2>/dev/null; " +
                "pgrep -f '[f]rida-server' 2>/dev/null; pgrep -f '[k]sud_helper' 2>/dev/null; true",
            15_000,
        ).stdout.trim()
        if (named.isNotEmpty()) {
            val seen = linkedSetOf<String>()
            for (tok in named.split(Regex("\\s+"))) {
                val pid = tok.trim().toIntOrNull()
                if (pid != null && pid > 1) seen.add(pid.toString())
            }
            return seen.joinToString(" ")
        }
        return RootExec.run(
            "for d in /proc/[0-9]*; do " +
                "c=\"\$(tr '\\0' ' ' < \$d/cmdline 2>/dev/null)\"; " +
                "case \"\$c\" in *[f]rida*|*[k]sud_helper*) echo \${d#/proc/};; esac; done | head -3",
            20_000,
        ).stdout.trim()
    }

    /**
     * Whether something is *listening* on [port].
     *
     * `/proc/net/tcp` lists every socket in every state. Matching the port alone counts
     * `TIME_WAIT` leftovers (state `06`) as an occupied port, which is what made `start` tell
     * people to reboot the phone over a socket that clears itself in seconds. Only `0A`
     * (LISTEN) means occupied; `0x69A2` is 27042 in this file's hex.
     */
    private fun listening(port: Int): Boolean {
        val hex = "%04X".format(port)
        return RootExec.run(
            "awk -v p='$hex' '\$2 ~ \":\"p \$4 == \"0A\" {print; exit}' " +
                "/proc/net/tcp /proc/net/tcp6 2>/dev/null",
            15_000,
        ).stdout.trim().isNotEmpty()
    }

    /**
     * 把 [script] 注入 [pkg] 并收集它 send 回来的消息。
     *
     * 客户端跑在打包的 Ubuntu rootfs 里（frida-python 由用户自己装过，见 gpucheck 的报告），
     * 连模块启动的 frida-server（默认 127.0.0.1:27043）。
     *
     * 报告里踩过的两个坑，这里都绕开了：
     *  - **spawn 之后必须 resume**，否则进程停在挂起态，后续 attach 全排队超时
     *    （报告里那些 `TimedOutError` 就是这么来的，不是反调试）
     *  - **Frida 17 没有 `Java` 全局对象**，Java hook 要先 bundle frida-java-bridge；
     *    这条只能靠 AGENTS.md 告诉模型，工具这边只负责把脚本原样送进去
     *
     * 脚本以文件传给 guest（-s），不拼进命令行：hook 脚本里全是引号和反斜杠，
     * 经过 sh 和 su 两层再转义必然出错。
     */
    fun inject(
        context: Context,
        pkg: String,
        script: String?,
        timeoutMs: Long,
        port: Int? = null,
    ): Map<String, Any?> {
        val targetPort = port ?: activePort() ?: DEFAULT_PORT
        if (pkg in FORBIDDEN_TARGETS) {
            // l10n:keep agent tool result
            return fail("不往 $pkg 注入：那个进程一崩整台手机会重启。只对普通 App 用。")
        }
        RootExec.suPath() ?: return fail("这台手机上没有 su")
        val status = status(context)
        // Check the port we are about to connect to, not just "something is running": a
        // server on 27043 is useless to a client that dials 27042.
        if (!listening(targetPort)) {
            val elsewhere = status["port"] as? Int
            return fail(
                "端口 $targetPort 上没有 frida-server" +
                    (if (elsewhere != null && elsewhere != targetPort) "（它现在在 $elsewhere）" else "") +
                    "。先 action=start 装上并启动（可以传 port），再注入。",
            )
        }

        val payload = script?.takeIf { it.isNotBlank() }
            ?: return fail("inject 需要 script：要注入的 Frida 脚本内容（JS）。")

        // The Python client is not shipped in the rootfs; install it here, pinned to the
        // server's own version, instead of failing and telling the user to run pip by hand.
        // That hand-off is what made "inject" unusable in practice: a fresh
        // `pip3 install frida` pulls a newer minor and every attach then times out.
        val client = ensureClient(context, status["version"] as? String)
        if (client["ok"] != true) {
            return fail(client["error"] as? String ?: "装 frida 客户端失败")
        }

        // Refuse the shapes that crash the target, before spawning it. Each of these is a
        // measured crash, not a style opinion (see the frida-report: the crash window is 3–10 s
        // after attach, and the stack has no frida frames — it reads exactly like the target
        // app crashing on its own, which is why this is worth blocking rather than warning).
        scriptRisk(payload)?.let { return fail(it) }

        // Both files go into the rootfs as the app (the rootfs lives under filesDir, so no su
        // needed to write) and are removed in the finally below: a hook script can name a
        // target app, and leaving it in DSH home would leak that to the next session.
        val dir = File(context.filesDir, "runtime/rootfs/root/.dsh/frida-inject").apply { mkdirs() }
        val scriptFile = File(dir, "hook.js")
        val runnerFile = File(dir, "run.py")
        val scriptGuest = "/root/.dsh/frida-inject/hook.js"
        val runnerGuest = "/root/.dsh/frida-inject/run.py"
        val seconds = (timeoutMs.coerceIn(5_000L, 300_000L) / 1000).toString()
        try {
            scriptFile.writeText(payload)
            runnerFile.writeText(
                FRIDA_RUNNER
                    .replace("@PKG@", pkg)
                    .replace("@SCRIPT@", scriptGuest)
                    .replace("@PORT@", targetPort.toString()),
            )
            val serverVersion = status["version"] as? String ?: ""
            val command = GuestExec.suCommand(
                context,
                "python3 $runnerGuest $seconds ${quote(serverVersion)}",
            )
                ?: return fail("rootfs 还没解压，注入脚本没地方跑")
            val result = RootExec.run(command, timeoutMs.coerceIn(5_000L, 300_000L))
            val out = result.stdout.trim()
            val err = result.stderr.trim()
            val loaded = out.contains("[DSH] attached")
            // `target-crashed` is the one outcome that must not be retried: the process is gone.
            val crashed = out.contains("[DSH] target-crashed")
            // The cgroup freezer has the target stopped; nothing can attach until it is back
            // in the foreground, and the 25 s timeout would otherwise read as anti-debugging.
            val frozenTarget = out.contains("[DSH] target-frozen")
            return mapOf(
                "ok" to (loaded && result.ok && !crashed),
                "package" to pkg,
                "attached" to loaded,
                "crashed" to crashed,
                "frozen" to frozenTarget,
                "messages" to out.take(8_000),
                "stderr" to err.take(2_000),
                "error" to when {
                    // Each exit code in the runner maps to the one thing the model should do next.
                    frozenTarget ->
                        "目标进程被系统冻结了（cgroup freezer 把它挂起），frida 注入不了。" +
                            "这不是 App 有反调试，25 秒超时就是这个原因。" +
                            "把那个 App 切到前台再试：open 一下它，或者让用户手动点开。"
                    crashed -> "注入把目标进程搞崩了（不是 Frida 掉线，是那个 App 死了）。" +
                        "最常见的两个原因：" +
                        "① hook 体内用 `this.method(...)` 回调原方法 —— 改成在 Java.perform 里缓存" +
                        " `const orig = M.m.overload(...)`，然后 `orig.call(this, ...)`；" +
                        "② 在 hook 体内用 Java.use() 或做字符串拼接，把 GC 压力放到最脆弱的时刻。" +
                        "崩因在 tombstone 里（logcat 搜 SIGSEGV / libart）。"
                    out.contains("no frida module") || err.contains("no frida module") ->
                        "Linux 里没有 frida 的 Python 客户端。先在「常驻 root shell」里跑一次 " +
                            "pip3 install frida frida-tools -i https://pypi.tuna.tsinghua.edu.cn/simple，" +
                            "然后重试注入。"
                    out.contains("version mismatch") || err.contains("version mismatch") ->
                        "frida 客户端和服务端的版本对不上，attach 会全部超时（不是目标 App 的问题）。" +
                            "照上面那行 fix 里的命令装成同一版，再重试。"
                    out.contains("spawn failed") || err.contains("spawn failed") ->
                        "spawn 失败：包名可能不对，或者那个 App 不允许被启动。" +
                            "先用 apps 工具确认包名。"
                    out.contains("attach failed after 3 tries") || err.contains("attach failed after 3 tries") ->
                        "attach 连试 3 次都没成功。这在进程线程很多时会发生（frida 冻结线程和 ART 的 " +
                            "suspend 打架），不是反调试。再试一次，或者换一个更轻的目标。"
                    out.contains("script load failed") || err.contains("script load failed") ->
                        "脚本本身跑挂了（load 阶段）。检查语法，以及是不是用了 Frida 17 已经没有的 " +
                            "`Java` 全局对象——那要先 bundle frida-java-bridge。"
                    // Nothing matched: show what the client actually printed, with the
                    // stage markers, instead of a bare "no output". The report's
                    // `[object Object]` came from exactly this gap.
                    !loaded -> buildString {
                        append("注入没成功。客户端说：")
                        val detail = (out + "\n" + err).trim()
                        append(if (detail.isEmpty()) "没有任何输出（脚本可能没跑起来）" else detail.take(500))
                    }
                    else -> null
                },
            )
        } finally {
            runCatching { dir.deleteRecursively() }
        }
    }

    /**
     * 在 guest 里跑的客户端。spawn → load → resume → 收消息 → detach。
     *
     * `resume` 放在 `load()` 之后、`time.sleep` 之前：脚本还没加载就 resume，
     * 前几毫秒的调用会漏掉；反过来忘了 resume，进程一直挂着，后面的 attach 全超时。
     */

    private val FRIDA_RUNNER = """
import sys, time, json
try:
    import frida
except Exception as e:
    print("[DSH] no frida module: %s" % e); sys.exit(3)

WANT = sys.argv[2] if len(sys.argv) > 2 else ""
GOT = getattr(frida, "__version__", "?")
# Full three-part compare. A patch difference (17.25.0 client vs 17.25.2 server) is
# enough to break attach, and the report measured it: same minor is NOT the same thing.
if WANT and GOT and WANT.split(".")[:3] != GOT.split(".")[:3]:
    print("[DSH] version mismatch: client=%s server=%s" % (GOT, WANT))
    print("[DSH] fix: pip3 install frida==%s frida-tools -i https://pypi.tuna.tsinghua.edu.cn/simple" % WANT)
    sys.exit(4)

dev = frida.get_device_manager().add_remote_device("127.0.0.1:@PORT@")

# spawn is the stable path: attach to an already-running process times out at random
# ("waiting for stop"), and that has nothing to do with the target app.
# spawn takes a package name (or an argv list); a one-element list also works but a bare
# string is what frida-python's own docs use, and it reads better in the error below.
try:
    pid = dev.spawn("@PKG@")
except Exception as e:
    print("[DSH] spawn failed: %s: %s" % (type(e).__name__, e)); sys.exit(5)

# A frozen process cannot be attached to: the cgroup freezer has it stopped, so frida waits
# out its 25 s timeout and reports "waiting for stop" — which reads exactly like a target app
# with anti-debugging. Check first and say what it is.
def frozen(pid):
    try:
        for line in open("/proc/%d/cgroup" % pid, encoding="utf-8"):
            path = line.strip().split(":")[-1]
            if not path.startswith("/apps/"):
                continue
            f = "/sys/fs/cgroup" + path + "/cgroup.freeze"
            return open(f, encoding="utf-8").read().strip() == "1"
    except Exception:
        return False
    return False

if frozen(pid):
    print("[DSH] target-frozen")
    sys.exit(8)

sess = None
last = ""
# Three attempts, doubling the wait: the report measured one success in three on a heavy app.
for attempt in range(3):
    try:
        sess = dev.attach(pid)
        break
    except Exception as e:
        last = "%s: %s" % (type(e).__name__, e)
        time.sleep(1.0 * (attempt + 1))
if sess is None:
    print("[DSH] attach failed after 3 tries: %s" % last); sys.exit(6)

script = sess.create_script(open("@SCRIPT@", encoding="utf-8").read())
def on_message(message, data):
    if message.get("type") == "send":
        print("[DSH] " + json.dumps(message.get("payload"), ensure_ascii=False))
    elif message.get("type") == "error":
        print("[DSH] error: " + str(message.get("description"))[:400])
script.on("message", on_message)

# A detached session means two very different things, and the report's P2 is exactly this:
# `process-terminated` is the TARGET dying (a bad hook crashed it — go read tombstones),
# anything else is the SESSION ending (server went away, script unloaded). Conflating them is
# how "the app crashed" gets reported as "frida disconnected", and the caller then retries
# forever against a dead process.
detach = {}
def on_detached(reason, crash):
    detach["reason"] = str(reason)
    detach["crash"] = str(crash) if crash else None
sess.on("detached", on_detached)

try:
    script.load()
except Exception as e:
    print("[DSH] script load failed: %s: %s" % (type(e).__name__, e)); sys.exit(7)

print("[DSH] attached pid=%d" % pid)
# Without resume the process stays suspended at spawn, and every later attach queues until it
# times out — the exact failure the report chased for a while.
dev.resume(pid)

window = float(sys.argv[1]) if len(sys.argv) > 1 else 15.0
deadline = time.time() + window
while time.time() < deadline:
    if detach:
        break
    time.sleep(0.25)

if detach.get("reason") == "process-terminated":
    print("[DSH] target-crashed: %s" % (detach.get("crash") or "process terminated"))
elif detach:
    print("[DSH] detached: %s" % detach.get("reason"))
else:
    try:
        script.unload()
    except Exception:
        pass
    print("[DSH] done")
"""

    /**
     * A sentence naming the first crash-prone construct in [script], or null when it looks safe.
     *
     * Deliberately textual: this cannot know what the script does, only that it uses a shape
     * which reliably kills the target on Android 14+. It refuses rather than warns because a
     * warning would be read after the process is already gone.
     */

    private fun scriptRisk(script: String): String? {
        val code = script.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        // `this.foo(` inside an implementation body: the replacement frame has no legal
        // OatQuickMethodHeader, and MarkCompact's DecodeGcMasksOnly dereferences it.
        if (RECEIVER_CALL.containsMatchIn(code) && !code.contains(".call(this") && !code.contains(".apply(this")) {
            return "脚本里用 `this.方法(...)` 回调原方法 —— 这会崩（Android 14 的 MarkCompact GC " +
                "扫到 replacement 帧时解引用空的 OatQuickMethodHeader，栈里没有 frida 帧，" +
                "看起来像目标 App 自己崩）。改成在 Java.perform 里缓存句柄：" +
                "`const orig = M.m.overload(...);` 然后 `return orig.call(this, ...)`。"
        }
        // Old Frida APIs: TypeError on load, which reads as "the script is broken".
        REMOVED_API.firstOrNull { it.containsMatchIn(code) }?.let {
            return "脚本用了 Frida 17 已经删掉的 API（${it.pattern}）。" +
                "`Module.findExportByName` 换成 `Module.findGlobalExportByName`；" +
                "`Module.enumerateExports` 换成 `Process.getModuleByName(m).enumerateExports()`。"
        }
        // The functions a lot of SDKs, packers and risk-control frameworks hook themselves;
        // stacking ours on top is the fastest way to crash.
        FORBIDDEN_HOOKS.firstOrNull { code.contains(it) }?.let {
            return "脚本要 hook `$it` —— 这个函数大量 SDK / 加固 / 风控框架自己就在挂，" +
                "叠加即崩。只挂低频只读参数的：openat / open / fopen / readlink / 具体业务符号。"
        }
        // A jobject stored in a JS variable and used later is a use-after-free local ref.
        // Narrow on purpose: only a top-level `let`/`var` holding a Java object (a `Java.use`
        // result, or a value taken from one) that is later read outside its own block. Matching
        // every `let x = null` would refuse most harmless scripts.
        if (storedJobject(code)) {
            return "脚本把 Java 对象存进顶层变量后跨调用用（`let ctx = ...getApplicationContext()` " +
                "再在 setTimeout / 别的回调里用之类）。jobject 是 JNI 局部引用，出了那次调用帧就失效，" +
                "ART 会直接 abort。每次在 Java.perform 里当场重新取对象。"
        }
        // Object allocation inside a hot hook puts GC pressure at the worst moment.
        if (code.contains("toString()") && (code.contains("console.log") || code.contains("send("))) {
            return "hook 体内做了字符串拼接 / toString() / send({...}) —— 每轮都造新对象，" +
                "把 GC 压力放在最脆弱的时刻。高频 hook 只计数，内容用 rpc.exports 批量取。"
        }
        return null
    }

    /**
     * True when a declared variable later receives a Java object and something runs after that.
     *
     * `let ctx; Java.perform(() => { ctx = ...getApplicationContext(); }); setTimeout(...)` is the
     * shape that aborts ART: the jobject is a JNI local reference that died with its frame.
     */
    private fun storedJobject(code: String): Boolean {
        if (!LATER_USE.containsMatchIn(code)) return false
        for (decl in DECLARATION.findAll(code)) {
            val name = decl.groupValues[1]
            val assign = Regex("""\b${Regex.escape(name)}\s*=\s*([^;\n]*)""")
            for (a in assign.findAll(code)) {
                if (a.range.first < decl.range.last) continue
                if (JAVA_VALUE.containsMatchIn(a.groupValues[1])) return true
            }
        }
        return false
    }

    /** `this.foo(` — a method call on the receiver inside a hook body. */

    private val RECEIVER_CALL = Regex("""this\.[A-Za-z_$][A-Za-z0-9_$]*\s*\(""")

    /** APIs Frida 17 removed; using any of them is a TypeError at load. */
    private val REMOVED_API = listOf(
        Regex("""Module\.findExportByName"""),
        Regex("""Module\.getExportByName"""),
        Regex("""Module\.enumerateExports"""),
    )

    /** Hook targets that collide with the target's own instrumentation. */
    private val FORBIDDEN_HOOKS = listOf(
        "dlopen", "dlsym", "android_dlopen_ext", "sigtimedwait", "sigaction", "pthread_create",
    )

    /** `let x` / `var x` declarations: the only way a jobject gets kept across calls. */
    private val DECLARATION = Regex("""\b(?:let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)""")

    /** The right-hand side of an assignment that produces or holds a Java object. */
    private val JAVA_VALUE = Regex(
        """(?:Java\.use|currentApplication|getApplicationContext|ActivityThread|_handle)""",
    )

    /** Something scheduled to run later, which is when a stale jobject gets touched. */
    private val LATER_USE = Regex("""setTimeout|setInterval|setImmediate""")

    private fun quote(word: String): String =
        if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in "/._-:=@%+," }) word
        else "'" + word.replace("'", "'\\''") + "'"

    private fun fail(reason: String): Map<String, Any?> = mapOf("ok" to false, "error" to reason)
}
