package io.github.ouy09103.dsh_go_root.root

/**
 * The helper's own exit rule — DSHA v0.2.2's `RootHelperWatchdog` and `ProcStat`.
 *
 * The app cannot signal a su child (EPERM on a uid 0 process), so the decision to stop has to be
 * made by the helper itself, while it still is root: past the deadline the request gave, or once
 * the app process that asked is gone (reinstalled, killed, or its pid reused), the helper kills
 * its own child and exits. Without it a helper outlives the request and keeps holding a slot and
 * the device's root process budget.
 *
 * Pure logic: /proc is read by the caller, so this is testable off a device.
 */
internal object RootHelperWatchdog {

    /** Room left for the helper to reclaim its own child after the caller stopped waiting. */
    const val EXIT_MARGIN_MS = 5_000L

    /** Longest request the phone tools can make (900 s) plus the caller's wait and the margin. */
    const val MAX_DEADLINE_MS = 900_000L + 25_000L + EXIT_MARGIN_MS

    enum class Verdict { CONTINUE, DEADLINE, OWNER_GONE }

    /** The asking process; an unknown owner disables the owner check and leaves the deadline. */
    class Owner(val pid: Int, val started: Long) {
        val known: Boolean get() = pid > 1 && started > 0
    }

    /** The request's own deadline when it is sane, the caller's default otherwise. */
    fun deadlineMs(requested: Long, fallback: Long): Long {
        if (requested in 1..MAX_DEADLINE_MS) return requested
        return Math.max(1_000L, Math.min(fallback, MAX_DEADLINE_MS))
    }

    /**
     * [ownerStat] is the contents of `/proc/<owner>/stat`; null means the read failed, which for
     * the app's own process means it is gone.
     */
    fun check(elapsedMs: Long, deadlineMs: Long, owner: Owner?, ownerStat: String?): Verdict {
        if (elapsedMs >= deadlineMs) return Verdict.DEADLINE
        if (owner == null || !owner.known) return Verdict.CONTINUE
        if (ownerStat == null) return Verdict.OWNER_GONE
        val stat = ProcStat.parse(ownerStat)
            // An unreadable /proc line is "cannot confirm", not "gone": the deadline still holds.
            ?: return Verdict.CONTINUE
        if (stat.started != owner.started || stat.exited) return Verdict.OWNER_GONE
        return Verdict.CONTINUE
    }
}

/** The two fields of `/proc/<pid>/stat` the watchdog needs, and nothing else. */
internal object ProcStat {

    class Parsed(val state: Char, val started: Long) {
        val exited: Boolean get() = state == 'Z' || state == 'X' || state == 'x'
    }

    /**
     * Field 3 (state) and field 22 (starttime). `comm` can contain spaces and parentheses, so the
     * split starts after the *last* `)`, not the first space.
     */
    fun parse(text: String): Parsed? {
        val close = text.lastIndexOf(')')
        if (close < 0 || close + 2 > text.length) return null
        val fields = text.substring(close + 2).trim().split(' ')
        val state = fields.getOrNull(0)?.firstOrNull() ?: return null
        val started = fields.getOrNull(STARTTIME_INDEX)?.toLongOrNull() ?: return null
        return Parsed(state, started)
    }

    /** starttime is field 22; the substring after `)` starts at field 3. */
    private const val STARTTIME_INDEX = 22 - 3
}