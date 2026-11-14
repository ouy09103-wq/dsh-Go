package io.github.ouy09103.dsh_go_root.root

import android.os.Process as AndroidProcess
import java.io.File

/**
 * The engine's own root launch: the same bundled rootfs, run through su so Node really is uid 0
 * instead of proot's `-0` imitation.
 *
 * The app cannot inspect a uid 0 process: every read under `/proc/<pid>` of another uid fails, so
 * the launcher's normal ownership sweep is blind here. This object is the one place that asks su
 * about those pids, and every answer is checked against the runtime path before a signal is sent —
 * a recycled pid must never be killed, and a pid that cannot be confirmed is reported as still
 * running rather than assumed dead.
 *
 * One su call does each step: a bare `su -c` costs a process spawn plus a manager round trip, so
 * polling from Kotlin would ask the root manager dozens of times per second.
 */
internal object RootEngine {

    /** Written by the launch script with the engine's own pid, so stop needs no /proc of ours. */
    const val PID_FILE = "tmp/dsh-root-engine.pid"

    private const val SHORT_TIMEOUT_MS = 10_000L
    private const val TERMINATE_TIMEOUT_MS = 30_000L
    private const val RECLAIM_TIMEOUT_MS = 60_000L
    private const val TICKS = 40

    /** Outside the rootfs on purpose: the app writes it, and the guest may be root-owned. */
    private const val MARKER = ".root-owned"

    fun pidFile(rootfs: File): File = File(rootfs, PID_FILE)

    fun marker(rootfs: File): File = File(rootfs.absoluteFile.parentFile, MARKER)

    /** True when a previous root launch may have left the guest tree root-owned. */
    fun touched(rootfs: File): Boolean = marker(rootfs).exists() || pidFile(rootfs).exists()

    /** Records that the guest tree is about to be written by uid 0. Written before the spawn: a
     *  failed launch only means the next start reclaims an already app-owned tree. */
    fun mark(rootfs: File) {
        runCatching { marker(rootfs).writeText("") }
    }

    /** Forgets the marker once the tree is the app's again. */
    fun unmark(rootfs: File) {
        runCatching { marker(rootfs).delete() }
    }

    fun pid(rootfs: File): Int? = runCatching {
        pidFile(rootfs).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
    }.getOrNull()

    /** `cat /proc/<pid>/cmdline` through su; null when the process is gone or su refused. */
    fun command(pid: Int): String? = if (pid <= 0) {
        null
    } else {
        RootExec.run("cat /proc/$pid/cmdline 2>/dev/null", SHORT_TIMEOUT_MS)
            .takeIf { it.ok }?.stdout?.replace('\u0000', ' ')
    }

    fun alive(pid: Int): Boolean = pid > 0 && RootExec.run(
        "if [ -d /proc/$pid ]; then echo up; fi",
        SHORT_TIMEOUT_MS,
    ).stdout.contains("up")

    /**
     * A live pid that still looks like this runtime's engine. Nothing else is ever signalled:
     * the runtime path has to appear in its own command line, so a recycled pid is left alone.
     */
    fun owned(rootfs: File, pid: Int): Boolean {
        val text = command(pid) ?: return false
        val roots = listOf(rootfs.absolutePath, rootfs.canonicalPath)
        return roots.any { it.isNotEmpty() && text.contains(it) }
    }

    /**
     * The engine a previous root launch left behind, if any. Returns the pid that was stopped;
     * an engine that survives is left named in its pid file for the next attempt.
     */
    fun reap(rootfs: File): Int? {
        val pid = pid(rootfs) ?: return null
        if (!alive(pid)) {
            pidFile(rootfs).delete()
            return null
        }
        if (!terminate(rootfs, pid)) return null
        pidFile(rootfs).delete()
        return pid
    }

    /**
     * SIGTERM, wait, SIGKILL, all inside one su script: the app cannot signal a uid 0 child, so
     * the escalation has to happen on the other side of su. False means the pid is still there,
     * or could not be confirmed as this runtime's engine.
     */
    fun terminate(rootfs: File, pid: Int): Boolean {
        if (pid <= 0) return true
        if (!alive(pid)) return true
        // Alive but unconfirmed: never kill a stranger, and never claim it is gone.
        if (!owned(rootfs, pid)) return false
        // [dollar] keeps the shell's own `$n` out of Kotlin's string templates.
        val dollar = '$'
        val script = "kill -TERM $pid 2>/dev/null; " +
            "n=0; while [ ${dollar}n -lt $TICKS ]; do " +
            "if [ ! -d /proc/$pid ]; then exit 0; fi; " +
            "sleep 0.1; n=${dollar}((n+1)); done; " +
            "kill -KILL $pid 2>/dev/null; exit 0"
        RootExec.run(script, TERMINATE_TIMEOUT_MS)
        return !alive(pid)
    }

    /**
     * Files the engine created as root stay root-owned, and the next compatible launch runs as the
     * app uid: hand the guest home and tmp back before that can happen.
     *
     * Only the two directories the engine writes as root are touched; the rootfs itself stays the
     * installer's. Best effort — a failure is logged, never fatal.
     */
    fun reclaim(rootfs: File): Boolean {
        val uid = AndroidProcess.myUid()
        val paths = listOf(File(rootfs, "root"), File(rootfs, "tmp"))
            .filter { it.exists() }
            .joinToString(" ") { GuestExec.quote(it.absolutePath) }
        if (paths.isEmpty()) return true
        return RootExec.run("chown -R $uid:$uid $paths", RECLAIM_TIMEOUT_MS).ok
    }
}
