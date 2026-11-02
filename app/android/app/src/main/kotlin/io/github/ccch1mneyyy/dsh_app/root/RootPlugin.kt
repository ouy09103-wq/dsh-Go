package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Flutter bridge for the root side: MethodChannel `dsh/root` (see lib/root/root_channel.dart).
 *
 * - `status`       cached probe only; no su spawn.
 * - `probe`        `su -c 'id -u; command -v chroot; uname -r'` — the manager's grant prompt shows here.
 * - `supported`    force a re-probe and report whether the chroot engine may be used.
 * - `exec`         one command on the device as root.
 * - `guest`        one command inside the bundled rootfs as root (chroot first, proot as fallback).
 * - `installFrida` / `fridaStatus` / `fridaStop` / `scanFrida`
 * - `shellOpen` / `shellWrite` / `shellPoll` / `shellClose`   one persistent root shell.
 * - `toolsEnabled` / `setToolsEnabled`   the `mcp__root__*` switch for the model.
 * - `cleanup`      lazy-umount whatever an interrupted launch left in the rootfs.
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
        val control = RootControl.get(context)
        when (call.method) {
            "status" -> background(result) { control.status() }
            "probe" -> background(result) {
                RootExec.probe().map() + mapOf("support" to control.probe(true).map())
            }
            "supported" -> background(result) { control.probe(true).map() }
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
                background(result) { guest(command, timeout).map() }
            }
            "installFrida" -> {
                val version = call.argument<String>("version")
                val path = call.argument<String>("path")
                val port = call.argument<Int>("port") ?: RootControl.DEFAULT_PORT
                background(result) {
                    val state = if (!path.isNullOrBlank()) Frida.installLocal(path, port)
                    else Frida.install(context, version, port)
                    mapOf(
                        "ok" to state.ok, "text" to state.text, "version" to state.version,
                        "path" to state.path, "port" to state.port, "pid" to state.pid,
                    )
                }
            }
            "scanFrida" -> background(result) {
                mapOf("candidates" to Frida.scan().map { it.map() })
            }
            "fridaStatus" -> background(result) { Frida.statusMap() + mapOf("ok" to true, "text" to Frida.report()) }
            "fridaStop" -> background(result) { mapOf("ok" to true, "text" to Frida.stopText()) }
            "toolsEnabled" -> result.success(control.isEnabled())
            "setToolsEnabled" -> {
                control.setEnabled(call.argument<Boolean>("enabled") == true)
                result.success(control.isEnabled())
            }
            "cleanup" -> background(result) {
                val rootfs = File(context.filesDir, ROOTFS)
                if (rootfs.isDirectory) {
                    RootEngine.cleanupMounts(rootfs)
                    // A root run can leave the whole tree owned by uid 0; proot would then be unable
                    // to write it. This is the one button that recovers from both.
                    RootEngine.releaseTo(rootfs, android.os.Process.myUid(), force = true)
                }
                // 同一颗按钮也把外部存储收干净：别的 App 写不动自己的目录（游戏进不去、
                // QQ 存不了图），起因正是这里以本应用 / root 的身份往它们目录里写过东西。
                val storage = runCatching { ExternalStorage.repair(context) }.getOrNull()
                mapOf(
                    "ok" to true,
                    "text" to buildString {
                        append("已清理残留挂载，并把 rootfs 属主交还 App")
                        append("；外部存储属主：")
                        append(storage?.text() ?: "检查失败（没有 root 时无法改属主）")
                    },
                )
            }
            "shellOpen" -> {
                val su = RootEngine.suPath() ?: run {
                    result.error("noRoot", "no su on this device", null)
                    return
                }
                background(result) {
                    val shell = RootShell.open(su, call.argument<String>("cwd"), File(context.cacheDir, "root-shell"))
                    val id = shellIds.incrementAndGet()
                    shells[id] = shell
                    mapOf("id" to id, "pid" to shell.pid)
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
                    result.success(mapOf("stdout" to "", "stderr" to "", "closed" to true))
                    return
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

    /** Runs [command] inside the rootfs: chroot when the device allows it, proot otherwise. */
    private fun guest(command: String, timeoutMs: Long): RootExec.Result {
        val rootfs = File(context.filesDir, ROOTFS)
        if (!rootfs.isDirectory) return RootExec.Result(false, -1, -1, "", "rootfs is not unpacked yet")
        val support = RootEngine.cachedSupport() ?: RootEngine.probe()
        val script = if (support.usable) {
            RootEngine.launchScript(
                rootfs,
                listOf(
                    "/usr/bin/env", "HOME=/root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "LANG=C.UTF-8", "TERM=xterm-256color",
                    "/bin/sh", "-lc", command,
                ),
                emptyList(),
            )
        } else {
            GuestExec.suCommand(context, command)
                ?: return RootExec.Result(false, -1, -1, "", "proot is unavailable")
        }
        return RootExec.run(script, timeoutMs)
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
        private const val ROOTFS = "runtime/rootfs"
    }
}
