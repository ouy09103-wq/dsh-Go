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
     * root 命令的三档拦截：严格 / 放宽 / 完全放宽。
     *
     * 打开 root 开关本身就是一句明确的允许，所以默认不是最严的那档；但也不能一开就什么都
     * 不拦，默认「放宽」——任意命令都跑，只有不可逆的操作（格式化、刷机、清数据）还拦着。
     * 想完全按手机操控那套规则逐条判定就选「严格」。
     */
    fun mode(): RootPolicyMode {
        prefs.getString(K_MODE, null)?.let { return RootPolicyMode.parse(it) }
        // 旧版本只有一个「严格模式」布尔：当时打开过的人继续留在严格档，其余用默认。
        return if (prefs.getBoolean(K_STRICT, false)) RootPolicyMode.STRICT else RootPolicyMode.DEFAULT
    }

    fun setMode(mode: RootPolicyMode) {
        prefs.edit().putString(K_MODE, mode.id).apply()
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
        private const val K_MODE = "root_policy_mode"
        private const val K_STRICT = "root_shell_strict"
        private const val K_UNTIL = "root_shell_until"
        private const val K_STRIKES = "root_shell_strikes"
    }
}
