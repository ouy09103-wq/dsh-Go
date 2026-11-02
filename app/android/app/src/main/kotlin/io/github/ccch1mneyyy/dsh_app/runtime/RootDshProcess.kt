package io.github.ccch1mneyyy.dsh_app.runtime

import io.github.ccch1mneyyy.dsh_app.phone.PhoneGuest
import io.github.ccch1mneyyy.dsh_app.root.RootEngine
import io.github.ccch1mneyyy.dsh_app.root.RootGuest
import android.content.Context
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * `dsh web` inside the bundled Ubuntu rootfs, launched through the device's su with chroot(2):
 * the guest is real uid 0 with real capabilities, and libc is free of the app's seccomp filter.
 *
 * Same contract as [DshProcess]: the token line on stdout is the ready signal, the guest writes
 * its pid to `/tmp/dsh-web.pid`, stop is SIGTERM → SIGKILL on that pid, and the launch script's
 * EXIT trap lazy-umounts what it mounted.
 */
class RootDshProcess(
    private val context: Context,
    private val manifest: RuntimeManifest,
    private val rootfs: File,
    private val listener: DshProcess.Listener,
) : EngineProcess {

    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    @Volatile private var expectedExit = false
    @Volatile private var readyReported = false
    @Volatile private var waiter: Thread? = null
    @Volatile private var stopper: Thread? = null

    override val launchDiagnostics = LaunchDiagnostics()
    override val executionMode: String? get() = launchDiagnostics.currentMode(process?.isAlive == true)
    override val direct: Boolean get() = false
    override val rootEngine: Boolean get() = true
    override var phoneMounted: Boolean = false
        private set
    override var startupError: String? = null
        private set

    var port: Int = DshProcess.DEFAULT_PORT
        private set

    override fun start(
        guestEnv: Map<String, String>,
        binds: List<String>,
        safe: Boolean,
        phone: Boolean,
        directEngine: Boolean,
        directFailed: Boolean,
        onLaunchMethod: ((Boolean) -> Unit)?,
    ) {
        check(process == null) { "already started" }
        val support = RootEngine.cachedSupport() ?: RootEngine.probe()
        if (!support.usable) {
            failStart("root engine unavailable: ${support.reason ?: "unknown"}")
            return
        }
        launchDiagnostics.selected(false, null)
        // A previous run that was killed outright leaves mounts behind; the script's trap
        // only covers a clean exit, so clear them before mounting again.
        RootEngine.cleanupMounts(rootfs)
        val pidFile = File(rootfs, "tmp/dsh-web.pid")
        pidFile.delete()
        port = DshProcess.pickPort()
        val tmp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        val vars = mapOf(
            "{NATIVE_LIB_DIR}" to context.applicationInfo.nativeLibraryDir,
            "{ROOTFS}" to rootfs.canonicalPath,
            "{TMP}" to tmp.absolutePath,
            "{FILES_DIR}" to context.filesDir.absolutePath,
            "{PORT}" to port.toString(),
        )
        fun expand(s: String) = vars.entries.fold(s) { acc, (k, v) -> acc.replace(k, v) }

        // From the guest's /usr/bin/env on: the same template the proot engine uses, minus proot's
        // own flags. Overlays (phone / root tools / search / auth / speech) are inserted the same way.
        val template = manifest.launchArgv
        val envAt = template.indexOfFirst { it == "/usr/bin/env" }
        check(envAt >= 0) { "runtime manifest has no /usr/bin/env anchor" }
        val guestArgv = ArrayList<String>()
        var guestEnvPlaced = false
        var profileSelected = false
        for (arg in template.drop(envAt)) {
            if (arg == DshProcess.GUEST_ENV) {
                guestEnv.forEach { (k, v) -> guestArgv += "$k=$v" }
                guestEnvPlaced = true
                continue
            }
            val selected = SafeMode.profileArg(arg, safe)
            if (selected != null) profileSelected = true
            val withPhone = if (phone && !safe && !phoneMounted) PhoneGuest.launchArg(selected ?: arg) else null
            if (withPhone != null) phoneMounted = true
            val withRoot = RootGuest.launchArg(withPhone ?: selected ?: arg)
            guestArgv += expand(withRoot ?: withPhone ?: selected ?: arg)
        }
        check(!safe || profileSelected) { "safe mode: this runtime's launch command has no `dsh web` to replace" }
        if (!guestEnvPlaced) guestArgv += guestEnv.map { (k, v) -> "$k=$v" }
        if (phone && !phoneMounted) listener.onLog(
            "[dsh-app] root engine: this runtime's launch command has no `dsh web` to add the phone overlay to", "system",
        )

        val script = RootEngine.launchScript(rootfs, guestArgv, binds)
        val su = RootEngine.suPath() ?: run { failStart("no su on this device"); return }
        listener.onLog("[dsh-app] launch method: root-chroot (su · chroot · uid 0)", "system")
        listener.onLog("[dsh-app] launching $su -c <chroot script> (${guestArgv.size} guest args)", "system")
        if (stopping) {
            listener.onExit(this, 0, true)
            return
        }
        val builder = ProcessBuilder(su, "-c", script).directory(context.filesDir)
        val env = builder.environment()
        env.clear()
        env["PATH"] = "/system/bin:/system/xbin"
        env["HOME"] = context.filesDir.absolutePath
        env["TMPDIR"] = tmp.absolutePath
        env["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
        env["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"
        val p = try {
            builder.start()
        } catch (e: Exception) {
            failStart("su failed to start: ${e.message}")
            return
        }
        process = p
        launchDiagnostics.spawned()
        onLaunchMethod?.invoke(false)
        val out = pump(p.inputStream.bufferedReader(), "stdout")
        val err = pump(p.errorStream.bufferedReader(), "stderr")
        waiter = thread(name = "dsh-root-wait", isDaemon = true) {
            val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
            try {
                out.join(2000)
                err.join(2000)
            } catch (_: InterruptedException) {
            }
            val stopThread = synchronized(this) {
                process = null
                stopper
            }
            stopThread?.join()
            listener.onExit(this, code, expectedExit)
        }
        if (stopping) stop()
    }

    override fun awaitExit(millis: Long): Boolean {
        val ending = waiter ?: stopper
        ending?.join(millis)
        return ending?.isAlive != true
    }

    override fun failStart(message: String) {
        if (stopping) return
        startupError = message
        listener.onLog("[dsh-app] start failed: $message", "system")
        stop(expected = false)
    }

    override fun stop(expected: Boolean) {
        stopping = true
        if (expected) expectedExit = true
        if (stopper != null) return
        val p = process ?: return
        stopper = thread(name = "dsh-root-stop", isDaemon = true) {
            try {
                if (RootEngine.guestPid(rootfs) != null) RootEngine.terminate(rootfs)
                if (p.isAlive) {
                    p.destroy()
                    if (!RootEngine.waitFor(p, 4000)) p.destroyForcibly()
                }
                RootEngine.cleanupMounts(rootfs)
            } finally {
                p.waitFor()
            }
        }
    }

    override fun killStrays(): Boolean {
        // su's child tree is uid 0, so a uid sweep would either miss it or be wildly out of scope.
        // The pid file plus the script's trap are the ownership proof here.
        return runCatching { RootEngine.terminate(rootfs, 2_000) }.getOrDefault(false)
    }

    private fun pump(reader: java.io.BufferedReader, stream: String) = thread(name = "dsh-root-$stream", isDaemon = true) {
        try {
            reader.useLines { lines ->
                lines.forEach { line ->
                    listener.onLog(line, stream)
                    if (!readyReported) detectToken(line)
                }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized private fun detectToken(line: String) {
        if (readyReported || stopping) return
        val match = TOKEN_URL.find(line) ?: return
        val token = TOKEN_PARAM.find(match.value)?.groupValues?.get(1) ?: return
        readyReported = true
        val base = "http://127.0.0.1:$port"
        thread(name = "dsh-root-ready", isDaemon = true) {
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline && !stopping && process?.isAlive == true) {
                if (canConnect(port)) {
                    listener.onReady(this, base, token)
                    return@thread
                }
                Thread.sleep(250)
            }
            if (!stopping && process?.isAlive == true) failStart("token seen but port $port never accepted connections")
        }
    }

    companion object {
        private val TOKEN_URL = Regex("""https?://[^\s'"]*[?&#]token=[A-Za-z0-9._~%-]+""")
        private val TOKEN_PARAM = Regex("""[?&#]token=([A-Za-z0-9._~%-]+)""")

        fun canConnect(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500); true }
        } catch (_: Exception) {
            false
        }

        fun pickPort(): Int {
            for (candidate in DshProcess.DEFAULT_PORT..DshProcess.DEFAULT_PORT + 20) {
                try {
                    ServerSocket(candidate, 1, InetAddress.getByName("127.0.0.1")).close()
                    return candidate
                } catch (_: Exception) {
                }
            }
            return ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }
    }
}
