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

    /**
     * Rewrites [input] with [STEALTH_ALIASES] into [output], verifying the result.
     *
     * Byte-level, same-length replacement on the decompressed server. Every pair is
     * length-checked before anything is written, and the output must still be an ELF64
     * arm64 executable of exactly the same size — a short alias would silently corrupt
     * .rodata and the binary would segfault on the first Gum call.
     *
     * Returns the number of replacements, or throws with the reason.
     */
    private fun rewriteStealth(input: File, output: File): Int {
        var data = input.readBytes()
        for ((from, to) in STEALTH_ALIASES) {
            require(from.length == to.length) { "别名长度不一致：$from -> $to" }
        }
        var total = 0
        for ((from, to) in STEALTH_ALIASES) {
            val needle = from.toByteArray()
            var at = 0
            while (true) {
                val i = indexOf(data, needle, at)
                if (i < 0) break
                System.arraycopy(to.toByteArray(), 0, data, i, needle.size)
                total++
                at = i + needle.size
            }
        }
        val expected = input.length()
        require(data.size.toLong() == expected) { "改写后大小变了（${data.size} != $expected）" }
        require(data.size > 64 && data[0] == 0x7F.toByte() && data[1] == 'E'.code.toByte() &&
            data[2] == 'L'.code.toByte() && data[3] == 'F'.code.toByte()) { "改写后不是 ELF" }
        require(data[18] == 0xB7.toByte() && data[19] == 0x00.toByte()) { "改写后不是 arm64" }
        output.writeBytes(data)
        return total
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        var i = from.coerceAtLeast(0)
        while (i <= hay.size - needle.size) {
            var k = 0
            while (k < needle.size && hay[i + k] == needle[k]) k++
            if (k == needle.size) return i
            i++
        }
        return -1
    }

    /**
     * Stealth install: a random binary name in a directory that doesn't say "frida".
     *
     * Targets scan for the literal strings `frida-server`, `/data/local/tmp/frida`, and the
     * `gum-js-loop` thread name. None of that survives if the binary is called something else
     * and lives somewhere unremarkable — the report's com.chunqiunativecheck reads
     * `/proc/self/maps` and cmdline looking for exactly those.
     *
     * This is obscurity, not evasion: a determined checker can still find the agent in memory
     * after injection. It defeats name-and-path scanning, which is what most of them do.
     */
    private const val STEALTH_BINARY = "netd_helper"
    private const val STEALTH_DIR = "/data/local/tmp/.cache"

    /**
     * Same-length string aliases applied to the binary before it is installed.
     *
     * The technique is from phantom-frida (TheQmaks/phantom-frida, ~90 patches over 16
     * detection classes). Its key insight is that most of Frida's detection surface is
     * *strings* — a checker greps `/proc/self/maps`, the task names under
     * `/proc/self/task/<tid>/status`, or the loaded module list. Replacing those strings in the
     * already-compiled binary is enough for the name-based checks, and needs no toolchain
     * (rebuilding frida-server on a phone is not possible anyway).
     *
     * Rules this list follows, learned the hard way:
     *  - **Every pair must be the same length.** These are fixed-size string literals in
     *    .rodata; a different length shifts everything after it.
     *  - Keep the shape recognisable to Frida itself where Frida compares them (the
     *    `gum-js-loop` name is looked up by the JS engine), so only the *identifiable*
     *    part changes: `gum-js-loop` → `gum-jx-loop`, not a random word.
     *  - Do not touch `frida` in paths Frida resolves at runtime (its own asset dir).
     */
    private val STEALTH_ALIASES = listOf(
        // Thread names, read straight out of /proc/self/task/<tid>/status.
        "gum-js-loop" to "gum-jx-loop",
        "gmain" to "amain",
        "gdbus" to "gdbug",
        "pool-spawner" to "pool-spoiler",
        // Module and asset names, visible in /proc/self/maps.
        "frida-gadget" to "libgc-gadget",
        "frida-agent" to "libgc-agent",
        "frida-helper" to "libgc-helper",
        // Type names the runtime puts in memory.
        "FridaScriptEngine" to "xf0b52fe480d6411e",
        "GumScript" to "x11cd6c23",
        "GDBusProxy" to "x2152cded3",
        "GLib-GIO" to "x037fd60",
        // The D-Bus service name and the Android helper package.
        "re.frida.server" to "x1535704be60818",
        "re.frida.helper" to "xa799196c65e405",
        "re.frida.Helper" to "xa574125e0a38dd",
    )

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
    fun install(context: Context, port: Int, stealth: Boolean = false): Map<String, Any?> {
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

        // Stealth does two things: a differently-named copy in an unremarkable directory,
        // and the same-length string aliases inside the binary so a checker that greps
        // /proc/self/maps or the task names does not find Frida's own strings.
        var payload = extracted
        var rewritten = 0
        if (stealth) {
            val patched = File(extracted.parentFile, "frida-server.stealth")
            rewritten = try {
                rewriteStealth(extracted, patched)
            } catch (e: Exception) {
                extracted.delete()
                return fail("隐身改写失败：" + (e.message ?: e.javaClass.simpleName))
            }
            payload = patched
        }
        val target = if (stealth) "$STEALTH_DIR/$STEALTH_BINARY" else REMOTE_SERVER
        // `cp` onto a binary that is currently running fails with ETXTBSY ("Text file busy"):
        // the kernel refuses to open a running executable for writing. Two things fix it —
        // stop the old server first, then write to a temporary name and `mv` it into place,
        // because a rename only swaps the directory entry and never touches the busy inode.
        val staged = "$target.new"
        val push = RootExec.run(
            // Kill anything still holding the binary, then wait for it to actually exit.
            // The `[f]` form keeps the pattern from matching the shell that carries it.
            "for P in \$(pgrep -f '[f]rida-server|${STEALTH_BINARY.substring(0, 1)}]${STEALTH_BINARY.substring(1)}' 2>/dev/null); do " +
                "kill \"\$P\" 2>/dev/null; done; sleep 1; " +
                "for P in \$(pgrep -f '[f]rida-server|${STEALTH_BINARY.substring(0, 1)}]${STEALTH_BINARY.substring(1)}' 2>/dev/null); do " +
                "kill -9 \"\$P\" 2>/dev/null; done; sleep 1; " +
                "mkdir -p ${if (stealth) STEALTH_DIR else REMOTE_DIR} && " +
                "cp ${quote(payload.absolutePath)} $staged && chmod 755 $staged && " +
                "mv -f $staged $target && " +
                "$target --version",
            180_000,
        )
        if (payload != extracted) payload.delete()
        extracted.delete()
        if (!push.ok) {
            // A stale `.new` from a failed run would otherwise linger and confuse the next try.
            RootExec.run("rm -f $staged", 15_000)
            return fail(
                "写 $target 失败：" + (push.stderr.ifBlank { push.stdout }.take(300) +
                    "\n如果还是 Text file busy，说明那个二进制正在被运行：" +
                    "先 frida action=stop，或者重启手机后再装。"),
            )
        }
        val version = push.stdout.trim().lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()

        freePort(port)?.let { return fail(it) }
        // `start` always uses REMOTE_SERVER; for stealth, point it at the copy instead.
        val started = if (stealth) startAt(target, port) else start(port)
        return started + mapOf(
            "version" to version.ifBlank { null },
            "path" to target,
            "stealth" to stealth,
            "rewritten" to rewritten,
        )
    }

    /**
     * Starts the binary at [path] on [port].
     *
     * Split out from [start] so stealth can run a copy under a different name without
     * duplicating the setsid / /dev/null dance, which is the part that took the longest to
     * get right on this phone.
     */
    /**
     * Starts the binary at [path] on [port], and reports whether it is now running.
     *
     * Split out from [start] so stealth can run a renamed copy. The launch ladder is the one
     * MilkCode's installer uses (reverse-engineered from its dex): setsid, else nohup, else
     * plain background. Two rungs are not enough — some toybox builds have nohup but no setsid.
     *
     * Every rung redirects all three streams: an inherited pipe that nobody drains will block
     * the server the moment it writes a log line.
     */
    private fun startAt(path: String, port: Int): Map<String, Any?> {
        val dir = path.substringBeforeLast('/')
        val bin = path.substringAfterLast('/')
        // Bracketed so pgrep's pattern cannot match the shell that is running it.
        val pat = "[" + bin.first() + "]" + bin.drop(1)
        val script = buildString {
            append("B=${quote(path)}; ")
            append("cd $dir || exit 3; ")
            append("if command -v setsid >/dev/null 2>&1; then ")
            append("setsid \"\$B\" -l 127.0.0.1:$port </dev/null >/dev/null 2>&1 & ")
            append("elif command -v nohup >/dev/null 2>&1; then ")
            append("nohup \"\$B\" -l 127.0.0.1:$port </dev/null >/dev/null 2>&1 & ")
            append("else \"\$B\" -l 127.0.0.1:$port </dev/null >/dev/null 2>&1 & fi; ")
            append("sleep 2; ")
            append("if [ -n \"\$(pgrep -f '$pat' 2>/dev/null)\" ]; then echo FRIDA_RUNNING; ")
            append("else echo FRIDA_NOT_RUNNING; fi")
        }
        val result = RootExec.run(script, 60_000)
        if (!result.stdout.contains("FRIDA_RUNNING")) {
            return mapOf(
                "ok" to false,
                "port" to port,
                "error" to "frida-server 没有起来（$path 存在，但找不到进程）。" +
                    "到「常驻 root shell」里手动跑一次 $path -l 127.0.0.1:$port，看它说什么。",
                "stdout" to result.stdout.trim(),
                "stderr" to result.stderr.trim(),
            )
        }
        return mapOf("ok" to true, "port" to port, "pid" to pidOf(), "path" to path)
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
    /**
     * Starts the bundled binary at [REMOTE_SERVER] on [port].
     *
     * Just [startAt] with the default path — one launch ladder, one place to fix it.
     */
    private fun start(port: Int): Map<String, Any?> {
        val result = startAt(REMOTE_SERVER, port)
        // The caller (install) reports whether the port ended up listening; startAt only says
        // whether a process exists, which is a different question on a slow phone.
        return if (result["ok"] == true) result + mapOf("listening" to listening(port)) else result
    }

    /** What the page shows: whether the server runs, on which port, and which build. */
    fun status(context: Context): Map<String, Any?> {
        RootExec.suPath() ?: return fail("这台手机上没有 su")
        // "Installed" has to mean "a working frida-server is there", not "a file is there":
        // the report found that swapping the binary for anything else still reported
        // "installed 17.25.2" because only the path was checked. `--version` printing a
        // version number is the cheapest proof that the thing runs and is what it claims.
        val present = RootExec.run("test -x $REMOTE_SERVER && echo yes", 15_000).stdout.contains("yes")
        val version = if (present) {
            RootExec.run("$REMOTE_SERVER --version 2>/dev/null | tail -1", 20_000).stdout.trim()
        } else {
            ""
        }
        // No version line means the file is there but is not a usable server (wrong binary,
        // wrong ABI, or it aborted before printing — see the BOOTCLASSPATH note below).
        val installed = present && VERSION.containsMatchIn(version)
        // **Listening is the truth, not `pidof`.** On this phone `pidof frida-server` comes back
        // empty even while the server is up and serving (the process name differs from the
        // binary name), so the old check reported "installed, not running" against a healthy
        // server. A port that accepts connections is what the client actually needs; the pid is
        // a convenience for the page, never the verdict.
        //
        // The port is read from the running server's argv when possible: some targets kill
        // themselves on a connect to 27042 specifically (the report's com.chunqiunativecheck
        // probes it three times), so a server started on another port has to be seen as running.
        val port = activePort() ?: DEFAULT_PORT
        val listening = listening(port)
        val pid = pidOf()
        // The client and the server must agree on major.minor, not just the major: a client one
        // minor ahead made every attach time out after 25 s, and the tool used to report that as
        // a plain "timeout" — the least useful thing it could say.
        val client = clientVersion(context)
        return mapOf(
            "ok" to true,
            "installed" to installed,
            "running" to (listening || pid.isNotEmpty()),
            // One word instead of two booleans, the way MilkCode's installer reports it
            // (UNKNOWN / ROOT_REQUIRED / NOT_INSTALLED / STOPPED / RUNNING). A caller that
            // only reads `state` cannot get the "installed but not running" combination wrong.
            "state" to when {
                !installed -> "NOT_INSTALLED"
                listening || pid.isNotEmpty() -> "RUNNING"
                else -> "STOPPED"
            },
            "listening" to listening,
            "pids" to pid,
            "version" to version.ifBlank { null },
            "client" to client,
            "mismatch" to versionMismatch(version, client),
            "path" to REMOTE_SERVER,
            "port" to port,
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
    /**
     * Kills any server, whatever it is called.
     *
     * Matches the plain name, the stealth name, and anything whose cmdline carries either —
     * a renamed copy has to be stoppable too, or "stop" leaves a listening port behind and
     * the next start reports it as occupied.
     */
    /**
     * Kills any server, whatever name it carries.
     *
     * `pidof` is tried first because it matches the process *name* and can never match the
     * shell running this command — MilkCode's installer does the same. `pgrep -f` covers the
     * stealth name, bracketed so it can't match its own argv either.
     */
    fun stop() {
        RootExec.run(
            "for P in \$(pidof frida-server 2>/dev/null) \$(pidof $STEALTH_BINARY 2>/dev/null) " +
                "\$(pgrep -f '[f]rida-server' 2>/dev/null) \$(pgrep -f '[n]etd_helper' 2>/dev/null); do " +
                "kill \"\$P\" 2>/dev/null || true; done; sleep 1; " +
                "for P in \$(pidof frida-server 2>/dev/null) \$(pidof $STEALTH_BINARY 2>/dev/null) " +
                "\$(pgrep -f '[f]rida-server' 2>/dev/null) \$(pgrep -f '[n]etd_helper' 2>/dev/null); do " +
                "kill -9 \"\$P\" 2>/dev/null || true; done; true",
            30_000,
        )
    }

    /**
     * The port a running server was started with, or null when it can't be read.
     *
     * Taken from the process's own argv (`-l 127.0.0.1:27043`), so a server on a non-default
     * port is still seen as running. Some targets kill themselves the moment anything
     * connects to 27042, so running elsewhere is a legitimate and common setup.
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
     * **`/proc/net/tcp`'s 10th field is the socket inode, not a pid.** Reading it as a pid is
     * what produced the report's "PID 2710988" — a number that doesn't exist under /proc and
     * changes on every call. The only trustworthy sources are `pidof`/`pgrep` and, failing
     * those, walking /proc looking for the binary in each process's cmdline.
     *
     * Empty is not a verdict — see [status], which goes by [listening].
     */
    private fun pidOf(): String {
        // `pidof` first: it compares the process *name*, so it can never match the shell that
        // runs this query. That is what MilkCode's installer uses throughout, and it avoids the
        // self-match class of bug entirely. `pgrep -f` is the fallback for a renamed copy whose
        // name pidof doesn't know — bracketed so it can't match its own shell either.
        val named = RootExec.run(
            "pidof frida-server 2>/dev/null; pidof $STEALTH_BINARY 2>/dev/null; " +
                "pgrep -f '[f]rida-server' 2>/dev/null; pgrep -f '[n]etd_helper' 2>/dev/null; true",
            15_000,
        ).stdout.trim()
        if (named.isNotEmpty()) return named.lineSequence().filter { it.isNotBlank() }
            .joinToString(" ").trim()
        // No process carries the name: find who has the binary mapped. `grep -l` over cmdline
        // is cheap enough at this size and does not depend on any name at all.
        return RootExec.run(
            "for d in /proc/[0-9]*; do " +
                "c=\"\$(tr '\\0' ' ' < \$d/cmdline 2>/dev/null)\"; " +
                "case \"\$c\" in *[f]rida*) echo \${d#/proc/};; esac; done | head -3",
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
        // Only LISTEN blocks a new server. A TIME_WAIT socket (state 06) is a closed
        // connection waiting out its timeout — it cannot accept anything, it clears itself in
        // seconds, and telling people to reboot over it was wrong.
        repeat(6) {
            if (!listening(port)) return null
            Thread.sleep(300)
        }
        val pids = pidOf()
        // l10n:keep agent tool result
        return "端口 $port 还被别的程序监听" +
            (if (pids.isBlank()) "" else "（可能是 frida-server pid $pids）") +
            "。先把它停掉再重试；如果那是另一个 App 的服务，换个端口（参数 port）。"
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
if WANT and GOT and WANT.split(".")[:2] != GOT.split(".")[:2]:
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
