package io.github.ouy09103.dsh_go.root

import android.content.Context
import android.os.Build
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * frida-server for real root, shipped inside the APK.
 *
 * The previous attempts downloaded the release from GitHub, decompressed it with `xz` on the
 * device, and then fought ART to make the server start. All three were wrong for a phone:
 * Android's toolbox has no `curl`, no `wget` and no `xz`; the guest's Ubuntu rootfs doesn't ship
 * them either; and downloading made the feature depend on GitHub being reachable at the moment
 * someone taps the button.
 *
 * This is the approach a working on-device tool uses: put the binary in the APK, write it out,
 * `chmod`, `setsid` it, and read the state back with `pidof`. No network, no external tool, no
 * environment surgery.
 *
 * The asset is xz-compressed (59 MB → 17 MB) because it travels through git; the APK already
 * depends on org.tukaani:xz for the rootfs, so decompressing costs nothing extra.
 *
 * Version: the asset is 17.25.2, deliberately matched to what `pip3 install frida` gives you
 * today — a client and server that differ even in the minor version make every attach time out
 * after 25 s (measured), which reads exactly like a target app fighting back. Bumping the asset
 * means updating [SHA256] and the version string in [VERSION_HINT].
 */
internal object Frida {

    /** The bundled build: `assets/frida/<this>`. */
    private const val ASSET = "frida/frida-server-17.25.2-android-arm64.xz"

    /** sha256 of the **decompressed** binary; a bad asset fails here instead of at exec time. */
    private const val SHA256 = "7bb6219131819c11e5fe53b3e32fae0b8d1ff4c2a9c35c1a549d2703ea0f3b9b"

    /** Where the working tool puts it, and where frida's own tooling looks by default. */
    const val REMOTE_DIR = "/data/local/tmp"
    const val REMOTE_SERVER = "$REMOTE_DIR/frida-server"

    const val DEFAULT_PORT = 27042

    /** `17.23.3` at the start of a line: the shape `frida.__version__` prints. */
    private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")

    /**
     * 永不注入的进程。往 system_server 或 zygote 注 agent，崩一次就是整机重启
     * （这台机器上已经发生过一次，见 frida-notes/SUMMARY.md）。
     */
    val FORBIDDEN_TARGETS = setOf(
        "system_server", "zygote", "zygote64", "zygote32", "init", "surfaceflinger",
    )

    /**
     * Installs the bundled server and starts it on [port].
     *
     * The binary is written as the app first (the app owns its cache dir), then copied to
     * `/data/local/tmp` and `chmod 755` through su — so the push never has to stream 59 MB
     * through a pipe, which is what makes a slow root manager time out.
     */
    fun install(context: Context, port: Int): Map<String, Any?> {
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") {
            return fail("内置的 frida-server 只有 arm64 版，这台手机的 ABI 是 ${Build.SUPPORTED_ABIS.firstOrNull()}")
        }
        RootExec.suPath() ?: return fail("这台手机上没有 su，先用 root 管理器给 DSH Go 授权")
        val probe = RootExec.probe()
        if (!probe.ok || probe.uid != 0) {
            return fail("root 没授权成功：" + (probe.stderr.ifBlank { "uid=${probe.uid}" }))
        }

        val extracted = try {
            extract(context)
        } catch (e: Exception) {
            return fail("内置 frida-server 解出失败：" + (e.message ?: e.javaClass.simpleName))
        }

        val push = RootExec.run(
            "mkdir -p $REMOTE_DIR && cp ${quote(extracted.absolutePath)} $REMOTE_SERVER && " +
                "chmod 755 $REMOTE_SERVER && $REMOTE_SERVER --version",
            180_000,
        )
        extracted.delete()
        if (!push.ok) {
            return fail("写 $REMOTE_SERVER 失败：" + (push.stderr.ifBlank { push.stdout }.take(300)))
        }
        val version = push.stdout.trim().lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()

        freePort(port)?.let { return fail(it) }
        return start(port) + mapOf("version" to version.ifBlank { null }, "path" to REMOTE_SERVER)
    }

    /**
     * Writes the bundled binary into the app's cache dir, checking the sha256.
     *
     * The hash is over the decompressed bytes, so a truncated asset or a broken xz stream is
     * caught here — not by a mysterious "Aborted" from a half-written ELF.
     */
    private fun extract(context: Context): File {
        val out = File(context.cacheDir, "frida-server")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        context.assets.open(ASSET).use { raw ->
            XZInputStream(raw).use { xz ->
                FileOutputStream(out).use { file ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = xz.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        file.write(buffer, 0, read)
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != SHA256) {
            out.delete()
            throw IllegalStateException("校验失败（sha256 $actual）")
        }
        return out
    }

    /**
     * Starts the server on [port] the way the working tool does:
     *
     * ```
     * B=/data/local/tmp/frida-server
     * setsid "$B" </dev/null >/dev/null 2>&1 &
     * ```
     *
     * stdin from /dev/null so it never waits on a pipe, `setsid` so su's exit doesn't take it
     * down, output discarded so a full pipe can't block it. No `-P`, no injected environment:
     * that is the combination that actually starts on this phone.
     */
    private fun start(port: Int): Map<String, Any?> {
        val script = buildString {
            append("B=$REMOTE_SERVER; ")
            append("cd $REMOTE_DIR || exit 3; ")
            append("if command -v setsid >/dev/null 2>&1; then ")
            append("setsid \"\$B\" -l 127.0.0.1:$port </dev/null >/dev/null 2>&1 & ")
            append("else \"\$B\" -l 127.0.0.1:$port </dev/null >/dev/null 2>&1 & fi; ")
            append("sleep 2; ")
            append("if [ -n \"\$(pidof frida-server 2>/dev/null)\" ]; then echo FRIDA_RUNNING; ")
            append("else echo FRIDA_NOT_RUNNING; fi")
        }
        val result = RootExec.run(script, 60_000)
        if (!result.stdout.contains("FRIDA_RUNNING")) {
            return mapOf(
                "ok" to false,
                "port" to port,
                "error" to "frida-server 没有起来（二进制在 $REMOTE_SERVER，但 pidof 找不到进程）。" +
                    "到「常驻 root shell」里手动跑一次 $REMOTE_SERVER -l 127.0.0.1:$port，看它说什么。",
                "stdout" to result.stdout.trim(),
                "stderr" to result.stderr.trim(),
            )
        }
        return mapOf(
            "ok" to true,
            "port" to port,
            "pid" to pidOf(),
            "listening" to listening(port),
            "running" to true,
        )
    }

    /** What the page shows: whether the server runs, on which port, and which build. */
    fun status(context: Context): Map<String, Any?> {
        RootExec.suPath() ?: return fail("这台手机上没有 su")
        val installed = RootExec.run("test -x $REMOTE_SERVER && echo yes", 15_000).stdout.contains("yes")
        val version = if (installed) {
            RootExec.run("$REMOTE_SERVER --version 2>/dev/null | tail -1", 20_000).stdout.trim()
        } else {
            ""
        }
        val pid = pidOf()
        // The client and the server must agree on major.minor, not just the major: a client one
        // minor ahead made every attach time out after 25 s, and the tool used to report that as
        // a plain "timeout" — the least useful thing it could say.
        val client = clientVersion(context)
        return mapOf(
            "ok" to true,
            "installed" to installed,
            "running" to pid.isNotEmpty(),
            "pids" to pid,
            "listening" to listening(DEFAULT_PORT),
            "version" to version.ifBlank { null },
            "client" to client,
            "mismatch" to versionMismatch(version, client),
            "path" to REMOTE_SERVER,
            "port" to DEFAULT_PORT,
        )
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
        fun head(v: String) = v.trim().split(".").take(2).joinToString(".")
        return head(a) == head(b)
    }

    /** Stops a running server. Also used before a start, so two builds never share the port. */
    fun stop() {
        RootExec.run(
            "for P in \$(pidof frida-server 2>/dev/null); do kill \"\$P\" 2>/dev/null || true; done; " +
                "sleep 1; for P in \$(pidof frida-server 2>/dev/null); do kill -9 \"\$P\" 2>/dev/null || true; done; true",
            30_000,
        )
    }

    private fun pidOf(): String =
        RootExec.run("pidof frida-server 2>/dev/null | tr ' ' '\\n' | head -3", 15_000).stdout.trim()

    /** Whether something listens on [port]; `0x69A2` is 27042 in /proc/net/tcp's hex. */
    private fun listening(port: Int): Boolean {
        val hex = "%04X".format(port)
        return RootExec.run(
            "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -i ':$hex ' | head -1",
            15_000,
        ).stdout.trim().isNotEmpty()
    }

    /**
     * Stops whatever already holds [port], so the new server can bind it.
     *
     * The first version of this tried to tell "another DSH Go instance" from ours by the socket's
     * uid in /proc/net/tcp — which is nonsense: frida-server runs as root, so that uid is always
     * 0, and every install refused itself. There is no useful ownership test here; what a person
     * wants is "make it work", and two frida-servers cannot share 27042 anyway.
     *
     * So: kill any frida-server, wait for the port to clear, and if something *else* still
     * holds it, say so with the one fact that helps — which pid.
     */
    private fun freePort(port: Int): String? {
        stop()
        val hex = "%04X".format(port)
        repeat(6) {
            val held = RootExec.run(
                "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -i ':$hex ' | head -1",
                15_000,
            ).stdout.trim()
            if (held.isEmpty()) return null
            Thread.sleep(300)
        }
        val pids = RootExec.run("pidof frida-server 2>/dev/null", 15_000).stdout.trim()
        // l10n:keep agent tool result
        return "端口 $port 还是被占着" + (pids.ifBlank { "" }.let { if (it.isEmpty()) "" else "（frida-server pid $it）" }) +
            "。它不是 frida-server，或者是刚被杀还没退干净：重启手机后最干净。"
    }

    /**
     * 把 [script] 注入 [pkg] 并收集它 send 回来的消息。
     *
     * 客户端跑在打包的 Ubuntu rootfs 里（frida-python 由用户自己装过，见 gpucheck 的报告），
     * 连 127.0.0.1:27042 的 frida-server。
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
    fun inject(context: Context, pkg: String, script: String?, timeoutMs: Long): Map<String, Any?> {
        if (pkg in FORBIDDEN_TARGETS) {
            // l10n:keep agent tool result
            return fail("不往 $pkg 注入：那个进程一崩整台手机会重启。只对普通 App 用。")
        }
        RootExec.suPath() ?: return fail("这台手机上没有 su")
        val status = status(context)
        if (status["running"] != true) {
            return fail("frida-server 没在跑。先 action=start 装上并启动，再注入。")
        }

        val payload = script?.takeIf { it.isNotBlank() }
            ?: return fail("inject 需要 script：要注入的 Frida 脚本内容（JS）。")

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
                FRIDA_RUNNER.replace("@PKG@", pkg).replace("@SCRIPT@", scriptGuest),
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
            return mapOf(
                "ok" to (loaded && result.ok),
                "package" to pkg,
                "attached" to loaded,
                "messages" to out.take(8_000),
                "stderr" to err.take(2_000),
                "error" to when {
                    // Each exit code in the runner maps to the one thing the model should do next.
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
                    !loaded -> "注入没成功：" + (err.ifBlank { out }.take(400).ifBlank { "没有输出" })
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
if WANT and GOT and WANT.split(".")[:2] != GOT.split(".")[:2]:
    print("[DSH] version mismatch: client=%s server=%s" % (GOT, WANT))
    print("[DSH] fix: pip3 install frida==%s frida-tools -i https://pypi.tuna.tsinghua.edu.cn/simple" % WANT)
    sys.exit(4)

dev = frida.get_device_manager().add_remote_device("127.0.0.1:27042")

# spawn is the stable path: attach to an already-running process times out at random
# ("waiting for stop"), and that has nothing to do with the target app.
try:
    pid = dev.spawn(["@PKG@"])
except Exception as e:
    print("[DSH] spawn failed: %s: %s" % (type(e).__name__, e)); sys.exit(5)

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
try:
    script.load()
except Exception as e:
    print("[DSH] script load failed: %s: %s" % (type(e).__name__, e)); sys.exit(7)

print("[DSH] attached pid=%d" % pid)
# Without resume the process stays suspended at spawn, and every later attach queues until it
# times out — the exact failure the report chased for a while.
dev.resume(pid)
time.sleep(float(sys.argv[1]) if len(sys.argv) > 1 else 15.0)
try:
    script.unload()
except Exception:
    pass
print("[DSH] done")
"""

    private fun quote(word: String): String =
        if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in "/._-:=@%+," }) word
        else "'" + word.replace("'", "'\\''") + "'"

    private fun fail(reason: String): Map<String, Any?> = mapOf("ok" to false, "error" to reason)
}
