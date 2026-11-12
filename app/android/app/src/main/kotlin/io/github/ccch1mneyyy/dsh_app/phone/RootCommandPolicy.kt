package io.github.ccch1mneyyy.dsh_app.phone

/**
 * The rules a root command meets before it reaches su.
 *
 * `shell` is not a screen action: it never becomes a tap, so the accessibility layer cannot
 * see it, and [PhoneCore] used to hand whatever the model wrote straight to su. That left the
 * person's own rules — the 不碰 list, every-write confirmation, 解除限制 — decorative for this
 * one tool, and it let the agent read the bridge token or rewrite the switches that gate it.
 * This is the second layer: text-level intent, judged against the same [PhonePolicies] the
 * rest of phone control uses.
 *
 * Three answers:
 *  - **refused** — a hard rule: an app from `denyPackages`, DSH Go's own storage, a system
 *    directory while 系统设置 is locked, a secret store while 密码与验证码 is locked.
 *  - **ask** — an app from `confirmEveryWritePackages` is about to change: the person said
 *    every write there gets a yes, and a command is a write.
 *  - **allowed** — everything else, including plain reads.
 *
 * `confirmMode` is deliberately not applied: it governs taps and typing ("first step in an
 * app", "this task is waived"), and asking on every command would make the tool useless
 * without making it safer — the hard rules above are what actually hold.
 *
 * Matching is over-inclusive on purpose. A false refusal costs one rephrasing; a false allow
 * costs the person their data. Every rule names itself in the refusal, so the model can
 * rephrase or hand off instead of guessing.
 */
class RootCommandPolicy(
    private val ownPackage: String = SafetyRules.OWN_PACKAGE,
    private val ownLabel: String = "DSH Go",
) {
    sealed interface Verdict {
        object Allow : Verdict

        /** A hard rule stopped it. [rule] is the short wire name, for the action log. */
        class Refuse(val message: String, val rule: String) : Verdict

        /** Goes through only with the person's yes. [packages] are the every-write apps named. */
        class Confirm(val packages: List<String>, val reason: String) : Verdict
    }

    /** What counts as doing something rather than looking at it. */
    private val WRITES = listOf(
        "rm ", "rmdir", "mv ", "cp ", "dd ", "truncate", "shred", "mkfs", "mount ", "umount",
        "chmod", "chown", "chgrp", "ln -", "tee ", "sed -i", "patch ", "install ",
        "pm ", "cmd ", "am ", "settings put", "settings delete", "input ", "screencap",
        "uiautomator", "svc ", "setprop", "stop ", "start ", "kill", "pkill", "killall",
        "reboot", "shutdown", "wipe", "fastboot", ">", ">>", "curl ", "wget ", "git clone",
        "pip install", "pip3 install", "npm i", "apt install", "apt-get install", "su ",
        "sh -c", "eval", "frida", "content ", "run-as",
    )

    /** Directories that belong to the system; read-only unless 系统设置 is lifted. */
    private val SYSTEM_PREFIXES = listOf(
        "/system", "/system_ext", "/vendor", "/product", "/odm", "/apex",
        "/data/adb", "/data/misc", "/data/system",
    )

    /**
     * Stores whose whole point is a credential. Read as well as write: reading is the leak.
     *
     * Split in two because they match differently. A path is matched at its own boundaries, so
     * `/data/system` doesn't fire on `/data/system_ext`. A word is matched anywhere, because
     * `accounts_ce.db` puts an underscore right after `accounts` and `autofill` appears inside
     * longer names — over-matching is the safe direction here.
     */
    private val SECRET_PATHS = listOf(
        "/data/misc/keystore",
        "/data/misc/vold",
        "/data/system/locksettings",
        // accounts_ce.db / accounts_de.db, the device's saved sign-ins.
        "/data/system/users/0",
    )

    private val SECRET_WORDS = listOf(
        "accounts_ce", "accounts_de", "autofill", "keystore", "locksettings",
    )

    fun judge(command: String, policy: PhonePolicies, appLabel: (String) -> String? = { null }): Verdict {
        val text = command.trim()
        if (text.isEmpty()) return Verdict.Refuse("shell 需要 command：要执行的命令。", "no-command")
        val writes = writes(text)

        // 1. DSH Go's own storage — read or write. The bridge token and the switches that gate
        //    this tool live there, so touching it is the agent reaching for its own leash.
        ownPaths().firstOrNull { text.contains(it) }?.let { path ->
            return Verdict.Refuse(
                "不碰 DSH Go 自己的存储（$path）：手机操控的开关、规则和桥接配置都在那里，" +
                    "读它或改它等于绕开你自己的限制。要改设置请在 App 里手动改。",
                "own-storage",
            )
        }
        // Same for the app by name, unless the person lifted it.
        if (!policy.unlocks.ownApp && names(text, ownPackage)) {
            return Verdict.Refuse(
                "root 命令不针对 DSH Go 自己：用户没有解除「DSH Go 自身」这条限制。",
                "own-app",
            )
        }

        // 2. The 不碰 list. Naming the app is enough: reading its data, starting it and granting
        //    it permissions are the same breach from the person's side.
        for (pkg in policy.denyPackages) {
            if (names(text, pkg)) {
                val label = appLabel(pkg) ?: pkg
                return Verdict.Refuse(
                    "「$label」在不碰名单里，root 命令也不能碰（读数据、起进程、改权限都不行）。" +
                        "确实需要时请 handoff 让用户自己动手，或在 App 里把它移出名单。",
                    "deny-list",
                )
            }
        }

        // 3. 解除限制. The credential stores come first: they are the narrower rule, and a path
        //    like /data/system/users/0/accounts_ce.db would otherwise be reported as "系统目录",
        //    which sends the model to the wrong fix.
        val lifted = policy.unlocks
        if (!lifted.secretFields) {
            val store = SECRET_PATHS.firstOrNull { hitsPath(text, it) }
                ?: SECRET_WORDS.firstOrNull { text.contains(it, ignoreCase = true) }
            if (store != null) {
                return Verdict.Refuse(
                    "密码、验证码、支付凭据所在的存储（$store）不读也不写：" +
                        "用户没有解除「密码与验证码」这条限制。",
                    "secret-store",
                )
            }
        }
        if (!lifted.systemPages) {
            val hit = SYSTEM_PREFIXES.firstOrNull { hitsPath(text, it) } ?: settingsHit(text)
            if (hit != null && writes) {
                return Verdict.Refuse(
                    "系统目录和系统设置（$hit）只读不动：用户没有解除「系统设置」这条限制。" +
                        "要改请 handoff 让用户自己点。",
                    "system-page",
                )
            }
        }

        // 4. Every-write apps: the person said every write gets asked, and a command is a write.
        val ask = policy.confirmEveryWritePackages.filter { names(text, it) }
        if (ask.isNotEmpty() && writes) return Verdict.Confirm(ask, "every-write")
        return Verdict.Allow
    }

    /** True when [text] does anything beyond reading. */
    fun writes(text: String): Boolean = WRITES.any { text.contains(it) }

    /**
     * Whether [text] names the path [prefix] **as a path**, not as a substring of a longer one.
     *
     * `/data/system/users/0/...` contains `/system`, and a plain substring test would report a
     * credential store as "系统目录" — the right refusal with the wrong reason, which sends the
     * model to the wrong fix. Matching is "start of text, or after a separator, and ending at a
     * separator or the end".
     */
    fun hitsPath(text: String, prefix: String): Boolean {
        var from = 0
        while (true) {
            val at = text.indexOf(prefix, from)
            if (at < 0) return false
            val before = at == 0 || text[at - 1] in PATH_SEPARATORS
            val end = at + prefix.length
            val after = end == text.length || text[end] in PATH_SEPARATORS || text[end] == '/'
            if (before && after) return true
            from = at + 1
        }
    }

    /** Characters that may sit between a shell word and a path without being part of it. */
    private val PATH_SEPARATORS = " \t\n'\"`;|&()=<>,".toSet()

    private fun settingsHit(text: String): String? = when {
        text.contains("settings put") || text.contains("settings delete") -> "settings"
        text.contains("pm grant") || text.contains("pm revoke") || text.contains("pm clear") -> "pm"
        text.contains("cmd ") && (text.contains("wifi") || text.contains("net") || text.contains("uiautomator")) -> "cmd"
        text.contains("am start") || text.contains("am force-stop") || text.contains("am broadcast") -> "am"
        else -> null
    }

    /** The app's own data directories, in the spellings Android uses. */
    fun ownPaths(): List<String> = listOf(
        "/data/data/$ownPackage",
        "/data/user/0/$ownPackage",
        "/data/user_de/0/$ownPackage",
        "/storage/emulated/0/Android/data/$ownPackage",
        "/sdcard/Android/data/$ownPackage",
    )

    /**
     * Whether [command] names [pkg] (or this app, by its label). Plain substring on purpose:
     * `com.tencent.mm` also matches `com.tencent.mm.foo`, which is the safe direction.
     */
    fun names(command: String, pkg: String): Boolean =
        command.contains(pkg) || (pkg == ownPackage && command.contains(ownLabel))
}
