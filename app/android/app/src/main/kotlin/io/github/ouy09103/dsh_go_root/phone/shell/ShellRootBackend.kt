package io.github.ouy09103.dsh_go_root.phone.shell

import android.content.Context
import io.github.ouy09103.dsh_go_root.root.RootExec
import io.github.ouy09103.dsh_go_root.root.RootSpawn
import java.io.IOException

/**
 * The third Android Shell channel: the same framed host, but started through su so it runs as uid 0.
 *
 * The other two channels keep the app identity on purpose. This one is only accepted when the kernel
 * agrees: a su that hands back the app uid, or a uid 0 process still sitting in an app SELinux domain,
 * is reported as a failure instead of being passed off as root.
 */
internal class ShellRootBackend(private val context: Context, private val onChanged: () -> Unit = {}) {
    @Volatile private var host: Process? = null

    fun status(): Map<String, Any?> = mapOf("su" to RootExec.suPath(), "manager" to RootExec.manager())

    fun disconnect() {
        val current = host ?: return
        host = null
        runCatching { current.destroyForcibly() }
        onChanged()
    }

    /** Every failure gets its own code, so the page can say which of the four things went wrong. */
    fun connect(wanted: () -> Boolean): ShellConnection {
        disconnect()
        if (!wanted()) return ShellConnection(errorCode = "cancelled")
        // Preflight already proved su exists and answers as uid 0 in a usable domain.
        preflight()?.let { return ShellConnection(errorCode = it) }
        if (!wanted()) return ShellConnection(errorCode = "cancelled")
        val su = RootExec.suPath() ?: return ShellConnection(errorCode = "suMissing")
        val command = hostCommand() ?: return ShellConnection(errorCode = "rootHostFailed")
        val process = try {
            RootSpawn.interactive(listOf(su, "-c", command))
        } catch (_: IOException) {
            // The candidate existed but the kernel refused it: a stale /system/bin/su entry.
            return ShellConnection(errorCode = "suNotExecutable")
        }
        drain(process)
        host = process
        if (!banner(process)) {
            disconnect()
            return ShellConnection(errorCode = "rootHostFailed")
        }
        val link = ShellLink.root(process, synced = true)
        if (!link.probe()) {
            link.close()
            disconnect()
            return ShellConnection(errorCode = "identityChanged")
        }
        if (!wanted()) {
            link.close()
            disconnect()
            return ShellConnection(errorCode = "cancelled")
        }
        return ShellConnection(link = link)
    }

    /**
     * Runs su once, before any command, and reads the identity from the kernel rather than from an exit
     * code: uid 0 with an untrusted_app domain has no power over the files it claims to own. The
     * trailing `true` keeps a failing `cat` from masking the uid we already read.
     */
    private fun preflight(): String? {
        val result = RootExec.run("id -u 2>&1; cat /proc/self/attr/current 2>/dev/null; true", PREFLIGHT_TIMEOUT_MS)
        if (result.exitCode == START_FAILED) {
            val text = result.stderr.trim()
            // Three different launch failures, kept apart: no su at all, a su that never answers,
            // and a su the kernel refuses to execute (the stale /system/bin/su case).
            return when {
                text.contains(NO_SU) -> "suMissing"
                text == TIMEOUT -> "rootSuFailed"
                else -> "suNotExecutable"
            }
        }
        val lines = result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val uid = lines.firstOrNull()?.toIntOrNull()
        if (uid == null) {
            val text = (result.stdout + " " + result.stderr).lowercase()
            return if (DENIED.any { text.contains(it) }) "rootNotGranted" else "rootSuFailed"
        }
        // A su that answers with the app uid never reached the root domain; never call that root.
        if (uid != 0) return "rootNotRoot"
        val domain = lines.getOrNull(1).orEmpty()
        if (domain.isBlank()) return "rootDomainUnknown"
        if (domain.contains(UNTRUSTED)) return "rootWrongDomain"
        return null
    }

    /** app_process needs a moment to boot, so the banner is read outside the 5s RPC budget. */
    private fun banner(process: Process): Boolean {
        var synced = false
        val reader = Thread({
            runCatching { ShellWire.sync(process.inputStream); synced = true }
        }, "dsh-shell-root-sync").apply { isDaemon = true }
        reader.start()
        reader.join(BANNER_MS)
        return synced && !reader.isAlive
    }

    /** The host never writes to stderr, but an unread pipe can still stall a dying child. */
    private fun drain(process: Process) {
        Thread({
            runCatching { process.errorStream.use { it.readBytes() } }
        }, "dsh-shell-root-stderr").apply { isDaemon = true }.start()
    }

    /** Same shape as the privileged display host: CLASSPATH, then app_process with the main class. */
    private fun hostCommand(): String? {
        val info = context.applicationInfo ?: return null
        val paths = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
        if (!paths.all { it.startsWith('/') && '\u0000' !in it && ':' !in it }) return null
        return "CLASSPATH=${quote(paths.joinToString(":"))} exec /system/bin/app_process /system/bin " +
            AndroidShellMain::class.java.name
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val PREFLIGHT_TIMEOUT_MS = 20_000L
        const val BANNER_MS = 20_000L
        const val START_FAILED = -1
        const val TIMEOUT = "timeout"
        const val NO_SU = "no su on this device"
        const val UNTRUSTED = "untrusted_app"
        val DENIED = listOf("permission denied", "not granted", "not allowed", "denied")
    }
}
