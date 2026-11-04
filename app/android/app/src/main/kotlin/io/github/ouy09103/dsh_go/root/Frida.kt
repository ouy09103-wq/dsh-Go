package io.github.ouy09103.dsh_go.root

import android.content.Context
import android.os.Build
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * frida-server for real root: download the release build for this ABI, write it to
 * `/data/local/tmp/frida-server`, start it under su, and report whether it is listening.
 *
 * Three things this has to get right, each of which broke a previous attempt:
 *
 *  1. **No external tools.** Android's toolbox has no `curl`, no `wget` and no `xz`, and the
 *     bundled Ubuntu rootfs doesn't ship them either. The old code ran
 *     `curl … || wget …` and then `xz -dcf`, which on a real phone fails on the first line and
 *     fell through to a version list nobody had chosen. Here the download is
 *     [HttpURLConnection] and the decompression is [XZInputStream] (org.tukaani:xz, already a
 *     dependency for the rootfs bundle) — nothing outside this process.
 *  2. **The version must match the guest's client.** frida refuses to talk across major
 *     versions ("please ensure that major versions match"), and a mismatch looks exactly like a
 *     broken server. [clientVersion] asks the guest's `frida` package what it has, and that
 *     version is installed unless the caller names one.
 *  3. **The server needs zygote's ART environment.** frida-server creates a Java VM of its own;
 *     with no `BOOTCLASSPATH` it aborts with "BOOTCLASSPATH and DEX2OATBOOTCLASSPATH must not be
 *     empty", and with a partial one, "No mainline framework library found". Both come from the
 *     process environment, not from a missing boot image — this phone has
 *     `/system/framework/arm64/boot.art`. [artEnvironment] copies the variables from a running
 *     zygote.
 *
 * And one thing it must never do: attach system_server or zygote. A broken agent there takes
 * the whole phone down (that is the 14:09 reboot in the notes), so [FORBIDDEN_TARGETS] is
 * checked before any attach, and the tool never injects on its own.
 */
internal object Frida {

    /** Released builds are `arm64`, `arm`, `x86_64`, `x86`; Android reports the other spelling. */
    private val ABI_NAMES = mapOf(
        "arm64-v8a" to "arm64",
        "armeabi-v7a" to "arm",
        "x86_64" to "x86_64",
        "x86" to "x86",
    )

    const val REMOTE_DIR = "/data/local/tmp"
    const val REMOTE_SERVER = "$REMOTE_DIR/frida-server"

    /**
     * Only for when the guest can't say what it has. The versions are kept close to the
     * current release: a server two majors behind a client is the mismatch that looks like
     * a broken install, and an old major is what injected an agent into system_server.
     */
    private val FALLBACK_VERSIONS = listOf("17.25.2", "17.2.17")

    /** Never attach these: a crashed agent here restarts the phone. */
    val FORBIDDEN_TARGETS = setOf("system_server", "zygote", "zygote64", "zygote32", "init", "surfaceflinger")

    /**
     * The ART variables frida-server needs, in the order zygote has them. Copied verbatim
     * because a half-filled set produces a different abort than an empty one.
     */
    private val ART_VARS = listOf(
        "BOOTCLASSPATH",
        "DEX2OATBOOTCLASSPATH",
        "SYSTEMSERVERCLASSPATH",
        "ANDROID_ROOT",
        "ANDROID_DATA",
        "ANDROID_ART_ROOT",
        "ANDROID_I18N_ROOT",
        "ANDROID_TZDATA_ROOT",
    )

    /**
     * Installs and starts frida-server.
     *
     * [version] wins when given; otherwise the guest's `frida` package decides, and only if that
     * can't be read does [FALLBACK_VERSIONS] get tried. A version whose server refuses to start
     * is not retried with an older major: that path is what injected an agent into system_server
     * last time.
     */
    fun install(context: Context, version: String?, port: Int): Map<String, Any?> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return fail("读不到这台手机的 ABI")
        val arch = ABI_NAMES[abi] ?: return fail("不支持的 ABI：$abi")
        RootExec.suPath() ?: return fail("这台手机上没有 su，先用 root 管理器给 DSH Go 授权")
        val probe = RootExec.probe()
        if (!probe.ok || probe.uid != 0) {
            return fail("root 没授权成功：" + (probe.stderr.ifBlank { "uid=${probe.uid}" }))
        }

        // One frida-server per device, whoever started it. Two DSH Go builds can be installed
        // side by side (that is the point of the separate package name), and each has its own
        // root channel — but there is only one 27042. Replacing the other instance's server would
        // silently break its session, so say whose it is and stop.
        val busy = foreignServer(context, port)
        if (busy != null) return fail(busy)

        val chosen = version?.trim()?.takeIf { it.isNotEmpty() }
            ?: clientVersion(context)
            ?: FALLBACK_VERSIONS.first()

        val built = fetch(context, chosen, arch, port)
        if (built != null) return built

        // One more try with the newest known build, then say what happened.
        for (v in FALLBACK_VERSIONS) {
            if (v == chosen) continue
            fetch(context, v, arch, port)?.let { return it }
        }
        return fail("$chosen 的 frida-server 下载或解压失败（网络或 GitHub 被拦）。先确认能打开 github.com。")
    }

    /**
     * One version, start to finish. Returns the result map, or null when this version could not
     * be fetched (so the caller may try another); a *downloaded* server that won't start returns
     * a failure map instead, because trying an older major is what broke the phone before.
     */
    private fun fetch(context: Context, version: String, arch: String, port: Int): Map<String, Any?>? {
        val name = "frida-server-$version-android-$arch.xz"
        val url = "https://github.com/frida/frida/releases/download/$version/$name"
        val local = File(context.cacheDir, "frida-server-$version-$arch")
        if (!download(url, local)) return null

        val install = push(context, local)
        local.delete()
        if (!install.ok) return fail("解压 / 推送 frida-server 失败：" + install.stderr.take(300))

        stop()
        val start = start(context, version, arch, port)
        return start
    }

    /** Downloads and un-xz's [url] into [dest]; false for any network or format problem. */
    private fun download(url: String, dest: File): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "dsh-go-root")
            }
            if (connection.responseCode !in 200..299) return false
            connection.inputStream.use { input ->
                XZInputStream(input).use { xz ->
                    FileOutputStream(dest).use { out -> xz.copyTo(out, 128 * 1024) }
                }
            }
            dest.length() > 0
        } catch (_: Exception) {
            dest.delete()
            false
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Puts the binary at [REMOTE_SERVER] as root with mode 0755.
     *
     * The apk's cache dir is readable by the app only; su can read it because the root shell runs
     * as the same uid the file belongs to, so no /sdcard round trip is needed.
     */
    private fun push(context: Context, local: File): RootExec.Result {
        val q = ::quote
        return RootExec.run(
            "mkdir -p $REMOTE_DIR && cp ${q(local.absolutePath)} $REMOTE_SERVER && " +
                "chmod 755 $REMOTE_SERVER && $REMOTE_SERVER --version",
            120_000,
        )
    }

    /**
     * Starts the server on [port] with zygote's ART environment.
     *
     * `-P` (disable preload) keeps it from pulling the whole framework in at startup, which is
     * the part that needs a complete boot image. The pid is read back from the log file rather
     * than `pidof`, because `pidof` is a toolbox binary that isn't on every build.
     */
    private fun start(context: Context, version: String, arch: String, port: Int): Map<String, Any?> {
        val env = artEnvironment()
        val exports = env.entries.joinToString(" ") { (k, v) -> "export $k=${quote(v)};" }
        val log = "$REMOTE_DIR/frida-server.log"
        val script = buildString {
            append("cd $REMOTE_DIR; ")
            if (exports.isNotEmpty()) { append(exports); append(' ') }
            append("rm -f $log; ")
            // setsid detaches it from su's process group: su exits, the server keeps listening.
            append("setsid ./frida-server -l 127.0.0.1:$port -P >$log 2>&1 & ")
            append("sleep 2; ")
            // A server that died leaves its abort message here.
            append("if command -v pidof >/dev/null 2>&1; then pidof frida-server; fi; ")
            append("tail -n 20 $log 2>/dev/null")
        }
        val result = RootExec.run(script, 60_000)
        val lines = result.stdout.trim().lines().filter { it.isNotBlank() }
        val pid = lines.firstOrNull { it.trim().toIntOrNull() != null }?.trim().orEmpty()
        val output = lines.filter { it.trim().toIntOrNull() == null }.joinToString("\n").trim()
        val listening = listening(port)

        // A start that printed "Aborted" or "Check failed" is a failure even though the shell
        // exited 0 (the `&` succeeds). Say so, with the server's own words.
        val abort = ABORT_HINTS.firstOrNull { output.contains(it, ignoreCase = true) }
        if (pid.isEmpty() && !listening) {
            return mapOf(
                "ok" to false,
                "version" to version,
                "arch" to arch,
                "path" to REMOTE_SERVER,
                "port" to port,
                "error" to failureReason(abort, output),
                "stdout" to output,
            )
        }
        return mapOf(
            "ok" to true,
            "version" to version,
            "arch" to arch,
            "path" to REMOTE_SERVER,
            "port" to port,
            "pid" to pid,
            "listening" to listening,
            "env" to env.keys.joinToString(" "),
            "stdout" to output,
        )
    }

    /**
     * A sentence when something already serves [port] and it isn't ours, null otherwise.
     *
     * Two DSH Go builds can be installed side by side — that is the point of the separate package
     * name — and each has its own root channel, but there is only one 27042. Replacing the other
     * instance's server would silently break its session, so say whose it is and stop.
     *
     * In `/proc/net/tcp` the eighth column is the socket owner's uid, and a listening socket
     * belongs to the process that opened it. Any DSH Go build runs as its own `u0_a*` uid, so a
     * uid that isn't ours is another instance.
     */
    private fun foreignServer(context: Context, port: Int): String? {
        val hex = "%04X".format(port)
        val owner = RootExec.run(
            "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -i ':$hex ' | awk '{print \$8}' | sort -u | head -1",
            15_000,
        ).stdout.trim().toIntOrNull() ?: return null
        if (owner == context.applicationInfo.uid) return null
        return "端口 $port 已经被另一个 DSH Go 实例的 frida-server 占着（uid $owner）。" +
            "同一台手机只能有一个：先去那边关掉，再在这里装。"
    }

    /** Turns the server's own output into something a person can act on. */
    private fun failureReason(abort: String?, output: String): String = when {
        output.isBlank() -> "frida-server 起来就退了，没有任何输出。可能是 SELinux 拦了，或者二进制和这台手机不匹配。"
        abort != null && output.contains("BOOTCLASSPATH", ignoreCase = true) ->
            "frida-server 建 Java VM 时缺 BOOTCLASSPATH。这台手机的 zygote 环境读不到，需要手动补。原始输出：$output"
        abort != null && output.contains("mainline", ignoreCase = true) ->
            "frida-server 找不到 ART 的 mainline 库。这台 Android 版本的 ART 布局和它预期的不一样。原始输出：$output"
        abort != null -> "frida-server 启动即退出（$abort）。原始输出：$output"
        output.contains("Permission denied", ignoreCase = true) ->
            "没有权限执行 $REMOTE_SERVER，root 授权可能被撤了。"
        else -> "frida-server 没有监听端口。原始输出：$output"
    }

    private val ABORT_HINTS = listOf("Aborted", "Check failed", "SIGABRT", "SIGSEGV", "Fatal signal")

    /**
     * The guest's installed `frida` version, asked through the packaged Ubuntu rootfs.
     *
     * The Python binding and frida-server must agree on the major version; asking the guest what
     * it has is the only way to match without guessing.
     */
    fun clientVersion(context: Context): String? {
        val command = GuestExec.suCommand(context, "python3 -c 'import frida; print(frida.__version__)'")
            ?: return null
        val result = RootExec.run(command, 60_000)
        if (!result.ok) return null
        return Regex("""^\d+\.\d+\.\d+$""", RegexOption.MULTILINE).find(result.stdout.trim())?.value
    }

    /**
     * ART's environment as a running zygote has it.
     *
     * `/proc/<pid>/environ` needs root, which this call already has; when it can't be read (the
     * process is gone, or hidepid hides it) the known-good constants are used instead, so the
     * server still gets a complete set rather than none.
     */
    fun artEnvironment(): Map<String, String> {
        val env = linkedMapOf<String, String>()
        val zygote = RootExec.run("pidof zygote64 zygote 2>/dev/null | tr ' ' '\\n' | head -1", 15_000)
            .stdout.trim().lines().firstOrNull()?.trim().orEmpty()
        if (zygote.isNotEmpty()) {
            val raw = RootExec.run("tr '\\0' '\\n' < /proc/$zygote/environ 2>/dev/null", 20_000).stdout
            for (line in raw.lines()) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq)
                if (key in ART_VARS) env[key] = line.substring(eq + 1)
            }
        }
        // Whatever zygote didn't give us, from the layout Android 14+ uses. Only fills gaps: an
        // inherited value is always preferred.
        if (env["ANDROID_ROOT"].isNullOrBlank()) env["ANDROID_ROOT"] = "/system"
        if (env["ANDROID_DATA"].isNullOrBlank()) env["ANDROID_DATA"] = "/data"
        if (env["ANDROID_ART_ROOT"].isNullOrBlank()) env["ANDROID_ART_ROOT"] = "/apex/com.android.art"
        if (env["BOOTCLASSPATH"].isNullOrBlank()) env["BOOTCLASSPATH"] = DEFAULT_BOOTCLASSPATH
        return env
    }

    /** Android 14–17 framework jars, in load order. Used only when zygote can't be asked. */
    private val DEFAULT_BOOTCLASSPATH = listOf(
        "/system/framework/core-oj.jar",
        "/system/framework/core-libart.jar",
        "/system/framework/conscrypt.jar",
        "/system/framework/okhttp.jar",
        "/system/framework/bouncycastle.jar",
        "/system/framework/apache-xml.jar",
    ).joinToString(":")

    /** Whether anything listens on [port]; `0x69A2` is 27042 in /proc/net/tcp's hex. */
    private fun listening(port: Int): Boolean {
        val hex = "%04X".format(port)
        val tcp = RootExec.run(
            "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -i ':$hex ' | head -3",
            20_000,
        )
        return tcp.stdout.trim().isNotEmpty()
    }

    /** What the root page shows: whether a server runs, on which port, and on which build. */
    fun status(): Map<String, Any?> {
        RootExec.suPath() ?: return fail("这台手机上没有 su")
        val running = RootExec.run("pidof frida-server 2>/dev/null || pgrep -f frida-server | head -1", 20_000)
        val version = RootExec.run("$REMOTE_SERVER --version 2>/dev/null | head -1", 20_000).stdout.trim()
        val port = 27042
        return mapOf(
            "ok" to true,
            "running" to running.stdout.trim().isNotEmpty(),
            "pids" to running.stdout.trim(),
            "listening" to listening(port),
            "version" to version.ifBlank { null },
            "path" to REMOTE_SERVER,
            "port" to port,
        )
    }

    /** Stops a running server. Also used before a start, so two builds never share the port. */
    fun stop() {
        RootExec.run("pkill -f frida-server; sleep 0.3; true", 30_000)
    }

    /**
     * Attaches frida-inject to one app process.
     *
     * The alternative when frida-server can't initialise its Java VM: `frida-inject` loads the
     * agent into the target process and needs no server-side VM. It takes a pid, so the caller
     * names the app and this resolves it — and refuses the targets in [FORBIDDEN_TARGETS], since
     * an agent in system_server is what restarted this phone.
     */
    fun inject(context: Context, packageName: String, script: String?, version: String?): Map<String, Any?> {
        if (packageName in FORBIDDEN_TARGETS) {
            return fail("不往 $packageName 注入：那个进程一崩整台手机会重启。只对普通 App 用。")
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return fail("读不到 ABI")
        val arch = ABI_NAMES[abi] ?: return fail("不支持的 ABI：$abi")
        val chosen = version?.trim()?.takeIf { it.isNotEmpty() } ?: clientVersion(context) ?: FALLBACK_VERSIONS.first()

        val pid = RootExec.run("pidof $packageName 2>/dev/null | tr ' ' '\\n' | head -1", 20_000)
            .stdout.trim().lines().firstOrNull()?.trim().orEmpty()
        if (pid.toIntOrNull() == null) return fail("「$packageName」没在运行。先让 App 在前台，再注入。")

        val name = "frida-inject-$chosen-android-$arch.xz"
        val url = "https://github.com/frida/frida/releases/download/$chosen/$name"
        val local = File(context.cacheDir, "frida-inject-$chosen-$arch")
        if (!download(url, local)) return fail("下载 $name 失败")

        val remote = "$REMOTE_DIR/frida-inject"
        val pushed = RootExec.run(
            "cp ${quote(local.absolutePath)} $remote && chmod 755 $remote && $remote --version",
            120_000,
        )
        local.delete()
        if (!pushed.ok) return fail("推送 frida-inject 失败：" + pushed.stderr.take(200))

        val scriptFile = script?.let {
            val f = File(context.cacheDir, "frida-script.js").apply { writeText(it) }
            f.absolutePath
        }
        val command = buildString {
            append("$remote -p $pid")
            if (scriptFile != null) append(" -s ${quote(scriptFile)}")
        }
        val result = RootExec.run(command, 120_000)
        return mapOf(
            "ok" to result.ok,
            "version" to chosen,
            "pid" to pid,
            "package" to packageName,
            "stdout" to result.stdout.take(2_000),
            "stderr" to result.stderr.take(2_000),
            "error" to if (result.ok) null else "frida-inject 退出码 ${result.exitCode}",
        )
    }

    private fun quote(word: String): String =
        if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in "/._-:=@%+," }) word
        else "'" + word.replace("'", "'\\''") + "'"

    private fun fail(reason: String): Map<String, Any?> = mapOf("ok" to false, "error" to reason)
}
