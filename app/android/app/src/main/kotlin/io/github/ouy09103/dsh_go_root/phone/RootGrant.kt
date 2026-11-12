package io.github.ouy09103.dsh_go_root.phone

import android.content.SharedPreferences

/**
 * The root 模式 switch, with an expiry and a failure counter.
 *
 * The switch used to be one boolean that stayed true until someone turned it off. That is a long
 * time for a tool that runs arbitrary commands as uid 0: a person who turned it on once to try it,
 * closed the app and forgot about it, still has it on a week later. Two additions, both small:
 *
 *  - **an expiry.** Turning it on also records a deadline, [WINDOW_MS] out. Past it the switch
 *    reads false and is written back false, so nobody has to remember. Turning it on again
 *    restarts the window; the person asked, after all.
 *  - **a failure counter.** Consecutive su failures (a revoked grant, a manager that stopped
 *    answering) mean the grant is gone; after [MAX_STRIKES] the switch turns itself off and stays
 *    off until a person turns it on again. A permanent retry loop against a root manager is how
 *    a phone ends up in a prompt storm.
 *
 * Everything reads through [enabled], which also *writes* when it notices the deadline passed —
 * a tool that reports false but leaves the stored value true would come back on next launch.
 */
class RootGrant(private val prefs: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) {

    /** The switch as the tool should see it now; false once the window has closed. */
    fun enabled(): Boolean {
        if (!prefs.getBoolean(K_ON, false)) return false
        if (strikes() >= MAX_STRIKES) return off("strikes")
        val until = prefs.getLong(K_UNTIL, 0L)
        if (until > 0 && clock() > until) return off("expired")
        return true
    }

    /** Turns it on for a fresh window, or off. Returns the value that took effect. */
    fun set(on: Boolean): Boolean {
        val edit = prefs.edit()
        if (on) {
            edit.putBoolean(K_ON, true)
                .putLong(K_UNTIL, clock() + WINDOW_MS)
                .putInt(K_STRIKES, 0)
        } else {
            edit.putBoolean(K_ON, false).putLong(K_UNTIL, 0L)
        }
        edit.apply()
        return on
    }

    /** When the current grant lapses, or null when it is off. */
    fun expiresAt(): Long? =
        if (!prefs.getBoolean(K_ON, false)) null else prefs.getLong(K_UNTIL, 0L).takeIf { it > 0 }

    /**
     * One `shell` call that couldn't get root. Returns true when this turned the switch off, so
     * the caller can tell the person why instead of just reporting a failure.
     */
    fun recordFailure(): Boolean {
        val next = strikes() + 1
        prefs.edit().putInt(K_STRIKES, next).apply()
        if (next < MAX_STRIKES) return false
        off("strikes")
        return true
    }

    /** A call that worked: the grant is alive, so the counter goes back to zero. */
    fun recordSuccess() {
        if (strikes() != 0) prefs.edit().putInt(K_STRIKES, 0).apply()
    }

    private fun strikes(): Int = prefs.getInt(K_STRIKES, 0)

    private fun off(reason: String): Boolean {
        prefs.edit().putBoolean(K_ON, false).putLong(K_UNTIL, 0L).apply()
        lastReason = reason
        return false
    }

    /**
     * 严格模式：`shell` 是否也走手机操控那套规则。默认 **关**。
     *
     * 打开 root 开关本身就是一句明确的允许；再在每条命令上拦一次，用户会觉得「自己的机器
     * 自己说了不算」。想多一层保险的人可以在这里打开。
     */
    fun strict(): Boolean = prefs.getBoolean(K_STRICT, false)

    fun setStrict(on: Boolean) {
        prefs.edit().putBoolean(K_STRICT, on).apply()
    }

    /** Why [enabled] last read false: `expired` or `strikes`. Reset by [set]. */
    @Volatile
    var lastReason: String? = null
        private set

    companion object {
        /** How long one yes lasts. Long enough to get work done, short enough to be forgotten. */
        const val WINDOW_MS = 12 * 60 * 60 * 1000L

        /** Consecutive failures that mean the grant is gone. */
        const val MAX_STRIKES = 3

        const val K_ON = "root_shell"
        private const val K_STRICT = "root_shell_strict"
        private const val K_UNTIL = "root_shell_until"
        private const val K_STRIKES = "root_shell_strikes"
    }
}
