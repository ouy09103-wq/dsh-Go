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
 * Version: the asset is 17.23.3. frida only checks the **major** version, so it talks to the
 * guest's 17.25.2 client. A future bump means replacing the asset and [SHA256].
 */
internal object Frida {

    /** The bundled build: `assets/frida/<this>`. */
    private const val ASSET = "frida/frida-server-17.23.3-android-arm64.xz"

    /** sha256 of the **decompressed** binary; a bad asset fails here instead of at exec time. */
    private const val SHA256 = "8887fb3dc84222416e2cdde706a10037c920df01d7858025168739d926c2fe17"

    /** Where the working tool puts it, and where frida's own tooling looks by default. */
    const val REMOTE_DIR = "/data/local/tmp"
    const val REMOTE_SERVER = "$REMOTE_DIR/frida-server"

    const val DEFAULT_PORT = 27042

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

        // One frida-server per device. Two DSH Go builds can be installed side by side, and each
        // has its own root channel, but there is only one 27042 — replacing the other instance's
        // server would silently break its session.
        foreignServer(context, port)?.let { return fail(it) }

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

        stop()
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
    fun status(): Map<String, Any?> {
        RootExec.suPath() ?: return fail("这台手机上没有 su")
        val installed = RootExec.run("test -x $REMOTE_SERVER && echo yes", 15_000).stdout.contains("yes")
        val version = if (installed) {
            RootExec.run("$REMOTE_SERVER --version 2>/dev/null | tail -1", 20_000).stdout.trim()
        } else {
            ""
        }
        val pid = pidOf()
        return mapOf(
            "ok" to true,
            "installed" to installed,
            "running" to pid.isNotEmpty(),
            "pids" to pid,
            "listening" to listening(DEFAULT_PORT),
            "version" to version.ifBlank { null },
            "path" to REMOTE_SERVER,
            "port" to DEFAULT_PORT,
        )
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
     * A sentence when something already serves [port] and it isn't ours, null otherwise.
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

    private fun quote(word: String): String =
        if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in "/._-:=@%+," }) word
        else "'" + word.replace("'", "'\\''") + "'"

    private fun fail(reason: String): Map<String, Any?> = mapOf("ok" to false, "error" to reason)
}
