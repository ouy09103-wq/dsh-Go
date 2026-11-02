package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Real-root access for the app: MethodChannel `dsh/root` (see lib/root/root_channel.dart).
 *
 * - `status`   → `{su: <path|null>, manager: "Magisk"|"KernelSU"|"APatch"|"su"|"", sdk, abi,
 *                 guestRootfs: bool, shells: n}` — no process is spawned.
 * - `probe`    → `su -c 'id -u'`: `{ok, uid, exitCode, stdout, stderr}`; the manager's
 *                 authorization prompt appears here.
 * - `exec`     → one command through `su -c`: `{command, timeoutMs}`.
 * - `guest`    → the same command inside the bundled Ubuntu rootfs, under real root:
 *                 `{command, timeoutMs}`.
 * - `installFrida` → downloads frida-server for this ABI into /data/local/tmp and starts it:
 *                 `{version?, port?}`.
 * - `fridaStatus` → whether frida-server runs and on which port.
 * - `shellOpen` / `shellWrite` / `shellPoll` / `shellClose` → one persistent root shell.
 *
 * Everything runs on one worker thread per call; the shell owns its own process.
 */
class RootPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    private var methods: MethodChannel? = null
    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "dsh-root-io").apply { isDaemon = true }
    }
    private val shells = ConcurrentHashMap<Int, RootShell>()
    private val shellIds = AtomicInteger(0)

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methods = MethodChannel(binding.binaryMessenger, CHANNEL).also { it.setMethodCallHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods?.setMethodCallHandler(null)
        methods = null
        shells.values.forEach { it.close() }
        shells.clear()
        pool.shutdownNow()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "status" -> background(result) { status() }
            "probe" -> background(result) { RootExec.probe().map() }
            "exec" -> {
                val command = call.argument<String>("command")
                if (command == null) { result.error("badArgs", "command is required", null); return }
                val timeout = (call.argument<Int>("timeoutMs") ?: 60_000).toLong()
                background(result) { RootExec.run(command, timeout).map() }
            }
            "guest" -> {
                val command = call.argument<String>("command")
                if (command == null) { result.error("badArgs", "command is required", null); return }
                val timeout = (call.argument<Int>("timeoutMs") ?: 120_000).toLong()
                background(result) {
                    val line = GuestExec.suCommand(context, command)
                        ?: return@background mapOf("ok" to false, "uid" to -1, "exitCode" to -1,
                            "stdout" to "", "stderr" to "rootfs is not unpacked yet")
                    RootExec.run(line, timeout).map()
                }
            }
            "installFrida" -> {
                val version = call.argument<String>("version")
                val port = call.argument<Int>("port") ?: DEFAULT_PORT
                background(result) { Frida.install(context, version, port) }
            }
            "fridaStatus" -> background(result) { Frida.status() }
            "shellOpen" -> {
                val su = RootExec.suPath() ?: run {
                    result.error("noRoot", "no su on this device", null); return
                }
                background(result) {
                    val shell = RootShell.open(su, call.argument<String>("cwd"))
                    val id = shellIds.incrementAndGet()
                    shells[id] = shell
                    mapOf("id" to id, "pid" to shellPid(shell))
                }
            }
            "shellWrite" -> {
                val id = call.argument<Int>("id") ?: -1
                val text = call.argument<String>("text") ?: ""
                val shell = shells[id] ?: run { result.error("closed", "no such shell", null); return }
                result.success(mapOf("written" to shell.write(text)))
            }
            "shellPoll" -> {
                val id = call.argument<Int>("id") ?: -1
                val shell = shells[id] ?: run {
                    result.success(mapOf("stdout" to "", "stderr" to "", "closed" to true)); return
                }
                val polled = shell.poll()
                if (polled["closed"] == true) {
                    shells.remove(id)
                    shell.close()
                }
                result.success(polled)
            }
            "shellClose" -> {
                val id = call.argument<Int>("id") ?: -1
                shells.remove(id)?.close()
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    private fun shellPid(shell: RootShell): Int = shell.pid

    private fun status(): Map<String, Any?> {
        val rootfs = File(context.filesDir, "runtime/rootfs")
        return mapOf(
            "su" to RootExec.suPath(),
            "manager" to RootExec.manager(),
            "sdk" to Build.VERSION.SDK_INT,
            "abi" to Build.SUPPORTED_ABIS.firstOrNull(),
            "guestRootfs" to rootfs.isDirectory,
            "shells" to shells.size,
        )
    }

    private fun background(result: MethodChannel.Result, work: () -> Any?) {
        pool.execute {
            val value = try {
                work()
            } catch (error: Throwable) {
                main.post { result.error("rootFailed", error.message ?: error.javaClass.simpleName, null) }
                return@execute
            }
            main.post { result.success(value) }
        }
    }

    companion object {
        const val CHANNEL = "dsh/root"
        const val DEFAULT_PORT = 27042
    }
}

/**
 * frida-server: download the release build for this ABI, push it to /data/local/tmp,
 * start it as root on [port] and leave it running.
 */
internal object Frida {

    private val ABI_NAMES = mapOf(
        "arm64-v8a" to "arm64",
        "armeabi-v7a" to "arm",
        "x86_64" to "x86_64",
        "x86" to "x86",
    )

    /** Recent releases first; a caller-supplied version wins. */
    private val FALLBACK_VERSIONS = listOf("17.2.17", "17.2.9", "16.7.19")

    fun install(context: Context, version: String?, port: Int): Map<String, Any?> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return fail("unknown ABI")
        val arch = ABI_NAMES[abi] ?: return fail("unsupported ABI: $abi")
        val su = RootExec.suPath() ?: return fail("no su on this device")
        val probe = RootExec.probe()
        if (!probe.ok || probe.uid != 0) return fail("root not granted: " + probe.stderr.ifEmpty { "uid=${probe.uid}" })

        val dir = "/data/local/tmp"
        val remote = "$dir/frida-server"
        val candidates = (version?.let { listOf(it) } ?: emptyList()) + FALLBACK_VERSIONS

        var lastError = "no release matched"
        for (v in candidates) {
            val url = "https://github.com/frida/frida/releases/download/$v/frida-server-$v-android-$arch.xz"
            val local = File(context.cacheDir, "frida-server-$v-$arch.xz")
            val download = RootExec.run(
                "curl -fL --retry 3 --connect-timeout 20 -o ${GuestExec.quote(local.absolutePath)} ${GuestExec.quote(url)}" +
                    " || wget -q -O ${GuestExec.quote(local.absolutePath)} ${GuestExec.quote(url)}",
                600_000,
            )
            if (!download.ok || !local.isFile || local.length() == 0L) {
                lastError = "download failed for $v"
                continue
            }
            val unpack = RootExec.run(
                "xz -dcf ${GuestExec.quote(local.absolutePath)} > $remote && chmod 755 $remote && " +
                    "$remote --version",
                120_000,
            )
            if (!unpack.ok) {
                lastError = "unpack failed for $v: ${unpack.stderr.take(200)}"
                continue
            }
            runCatching { local.delete() }
            stop()
            val started = RootExec.run(
                "cd $dir && (setsid ./frida-server -l 127.0.0.1:$port >/dev/null 2>&1 &) ; sleep 1; " +
                    "pidof frida-server || pgrep -f frida-server | head -1",
                60_000,
            )
            return mapOf(
                "ok" to started.ok,
                "version" to v,
                "arch" to arch,
                "path" to remote,
                "port" to port,
                "pid" to started.stdout.trim(),
                "stdout" to unpack.stdout.trim(),
                "stderr" to (started.stderr + download.stderr).trim(),
            )
        }
        return fail(lastError)
    }

    fun status(): Map<String, Any?> {
        val su = RootExec.suPath() ?: return fail("no su on this device")
        val running = RootExec.run("pgrep -f frida-server | head -3", 20_000)
        val listen = RootExec.run("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -i ':69A2' | head -3", 20_000)
        return mapOf(
            "ok" to true,
            "running" to running.stdout.trim().isNotEmpty(),
            "pids" to running.stdout.trim(),
            "listening" to listen.stdout.trim().isNotEmpty(),
            "su" to su,
            "port" to 0x69A2,
        )
    }

    private fun stop() {
        RootExec.run("pkill -f frida-server; sleep 0.3; true", 30_000)
    }

    private fun fail(reason: String): Map<String, Any?> = mapOf("ok" to false, "error" to reason)
}
