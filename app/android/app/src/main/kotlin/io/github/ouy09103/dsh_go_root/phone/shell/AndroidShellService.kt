package io.github.ouy09103.dsh_go_root.phone.shell

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Process
import io.github.ouy09103.dsh_go_root.phone.background.Bundles
import io.github.ouy09103.dsh_go_root.root.RootHelperWatchdog
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.UUID
import kotlin.system.exitProcess

internal class ShellHost(classpath: String) {
    private val instance = UUID.randomUUID().toString()
    private val core = ShellJobCore { command, timeout -> AndroidShellProcess(command, timeout, classpath) }
    fun handle(request: Bundle): Bundle {
        if (request.getString("revision") != ShellProtocol.REVISION) return shellBundle(ShellProtocol.failure("protocolMismatch"))
        val op = request.getString("op")
        val result = if (op == "probe") mapOf(
            "ok" to true, "revision" to ShellProtocol.REVISION, "instance" to instance, "uid" to Process.myUid(),
        ) else if (request.getString("instance") != instance) ShellProtocol.failure("identityChanged")
        else when (op) {
            "start" -> if (request.getInt("uid", -1) != Process.myUid()) ShellProtocol.failure("identityChanged")
                else core.start(request.getString("jobId").orEmpty(), request.getString("command").orEmpty(), request.getLong("timeoutMs", 0))
            "poll" -> core.poll(request.getString("jobId").orEmpty())
            "cancel" -> core.cancel(request.getString("jobId").orEmpty())
            "destroy" -> { core.destroy(); mapOf("ok" to true) }
            else -> ShellProtocol.failure("invalidArguments")
        }
        return shellBundle(result)
    }
    fun destroy() = core.destroy()
}

interface IAndroidShell : IInterface {
    fun execute(request: Bundle): Bundle
    abstract class Stub : Binder(), IAndroidShell {
        init { attachInterface(this, DESCRIPTOR) }
        override fun asBinder(): IBinder = this
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            val result = execute(data.readBundle(javaClass.classLoader) ?: Bundle())
            reply?.writeNoException()
            reply?.writeBundle(result)
            return true
        }
    }
    companion object {
        const val DESCRIPTOR = "io.github.ouy09103.dsh_go_root.phone.shell.IAndroidShell"
        const val DESTROY = 16777115
        fun remote(binder: IBinder): IAndroidShell = object : IAndroidShell {
            override fun asBinder(): IBinder = binder
            override fun execute(request: Bundle): Bundle {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeBundle(request)
                    if (!binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) throw java.io.IOException("protocolMismatch")
                    reply.readException()
                    return reply.readBundle(javaClass.classLoader) ?: throw java.io.IOException("protocolMismatch")
                } finally { data.recycle(); reply.recycle() }
            }
        }
    }
}

/** Shizuku instantiates this Binder, not an Android Service. */
@androidx.annotation.Keep
class AndroidShellService(context: Context) : IAndroidShell.Stub() {
    private val appUid = context.applicationInfo.uid
    private val host = ShellHost((listOf(context.applicationInfo.sourceDir) + context.applicationInfo.splitSourceDirs.orEmpty()).joinToString(":"))
    private fun checkCaller() {
        val caller = Binder.getCallingUid()
        if (caller != appUid && caller != Process.myUid()) throw SecurityException("identityChanged")
    }
    override fun execute(request: Bundle): Bundle { checkCaller(); return host.handle(request) }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == IAndroidShell.DESTROY) {
            checkCaller()
            data.enforceInterface(IAndroidShell.DESCRIPTOR)
            host.destroy()
            exitProcess(0)
        }
        return super.onTransact(code, data, reply, flags)
    }
}

/** Self-ADB stdin/stdout control channel; EOF is an owned-process cancellation, not detachment. */
@androidx.annotation.Keep
object AndroidShellMain {
    private const val OWNER_PID_ARG = 0
    private const val OWNER_STARTED_ARG = 1
    private const val WATCHDOG_MS = 1_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val host = ShellHost(System.getenv("CLASSPATH") ?: System.getProperty("java.class.path"))
        startOwnerWatchdog(host, args)
        val output = FileOutputStream(FileDescriptor.out)
        try {
            output.write(ShellWire.magic); output.flush()
            while (true) {
                val bytes = ShellWire.read(System.`in`) ?: break
                val request = Bundles.unmarshal(bytes)
                ShellWire.write(output, Bundles.marshal(host.handle(request)))
                if (request.getString("op") == "destroy") break
            }
        } catch (_: Exception) {
            // Protocol loss retires this host; never print command data into the control stream.
        } finally { host.destroy() }
    }

    /**
     * T38, from DSHA v0.2.2: this host runs as uid 0, so the app cannot signal it — closing the
     * app, or an in-place reinstall, used to leave the host holding root and the control pipe
     * until something else killed it. The host therefore checks for itself whether the process
     * that started it is still that process (pid alone can be reused) and leaves when it is not.
     */
    private fun startOwnerWatchdog(host: ShellHost, args: Array<String>) {
        val owner = RootHelperWatchdog.Owner(
            args.getOrNull(OWNER_PID_ARG)?.toIntOrNull() ?: return,
            args.getOrNull(OWNER_STARTED_ARG)?.toLongOrNull() ?: return,
        )
        if (!owner.known) return
        Thread({
            while (true) {
                val stat = runCatching {
                    String(
                        java.nio.file.Files.readAllBytes(
                            java.nio.file.Paths.get("/proc/" + owner.pid + "/stat"),
                        ),
                    )
                }.getOrNull()
                // No deadline: a shell session may legitimately live as long as the app does.
                val verdict = RootHelperWatchdog.check(0L, Long.MAX_VALUE, owner, stat)
                if (verdict != RootHelperWatchdog.Verdict.CONTINUE) {
                    runCatching { host.destroy() }
                    exitProcess(0)
                }
                runCatching { Thread.sleep(WATCHDOG_MS) }
            }
        }, "dsh-shell-root-watchdog").apply {
            isDaemon = true
            start()
        }
    }
}
