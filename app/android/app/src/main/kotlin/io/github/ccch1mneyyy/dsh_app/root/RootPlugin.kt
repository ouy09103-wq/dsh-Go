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
 * - `installFrida` → writes the **bundled** frida-server to /data/local/tmp and starts it:
 *                 `{port?}`. No download: the binary ships in the APK (see [Frida]).
 * - `fridaStatus` → whether frida-server is installed, runs, and which build.
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
                val port = call.argument<Int>("port") ?: Frida.DEFAULT_PORT
                background(result) { Frida.install(context, port) }
            }
            "fridaStatus" -> background(result) { Frida.status(context) }
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
    }
}
