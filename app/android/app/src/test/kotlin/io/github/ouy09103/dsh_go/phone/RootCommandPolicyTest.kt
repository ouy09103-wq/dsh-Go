package io.github.ouy09103.dsh_go.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The root tool's rules, judged on the command text.
 *
 * These tests describe **strict mode**, which is off by default: turning on 允许模型执行 root
 * 命令 is itself an explicit permission, and re-checking every command after it makes the tool
 * feel like it doesn't trust its owner. The policy below is what the person gets if they turn
 * 严格模式 on — see [strictModeIsOffByDefault].
 *
 * Each test names the rule it pins, because a regression here is silent: the command simply runs.
 */
class RootCommandPolicyTest {
    /** The interface's own default: strict off unless a host says otherwise. */
    private class PhoneEnvDefaults : PhoneEnv {
        override fun enabled() = true
        override fun policies() = PhonePolicies()
        override fun isLocked() = false
        override fun gate() = null
        override fun driver(screen: PhoneScreen) = null
        override fun a11yState() = A11yState.DISABLED
        override fun backgroundUnavailable() = ""
        override fun system() = object : SystemApi {
            override fun apps() = emptyList<AppEntry>()
            override fun isInstalled(pkg: String) = false
            override fun launch(app: AppEntry) = SysResult(false, "")
            override fun urlTarget(url: String) = UrlTarget("no")
            override fun openUrl(url: String, target: UrlTarget) = SysResult(false, "")
            override fun intent(spec: IntentSpec, shareTo: AppEntry?) = SysResult(false, "")
            override fun notify(title: String, text: String) = SysResult(false, "")
            override fun clipboardSet(text: String) = SysResult(false, "")
            override fun clipboardGet() = SysResult(false, "")
        }
        override fun zone() = java.time.ZoneId.of("UTC")
        override fun notifyFix(kind: String) = Unit
    }

    private val policy = RootCommandPolicy()
    private val defaults = PhonePolicies()

    private fun judge(command: String, p: PhonePolicies = defaults, label: (String) -> String? = { null }) =
        policy.judge(command, p, label)

    @Test fun strictModeIsOffByDefault() {
        // The switch in 设置 › Root 权限 decides this; nothing in the core should force it on.
        assertFalse(
            "shell must not be gated unless the person asked for it",
            PhoneEnvDefaults().rootShellStrict(),
        )
    }

    @Test fun withStrictOffTheCoreDoesNotConsultThePolicyAtAll() {
        // Judged at the source level: the call is behind `if (env.rootShellStrict())`. This
        // asserts the shape that keeps the default path free of refusals, so a future refactor
        // that moves the check above the flag fails here instead of in someone's terminal.
        val source = java.io.File(
            "src/main/kotlin/io/github/ouy09103/dsh_go/phone/PhoneCore.kt",
        ).readText()
        val gate = source.indexOf("if (env.rootShellStrict()) {")
        val judge = source.indexOf("rootPolicy.judge(")
        assertTrue("the strict-mode gate must exist", gate > 0)
        assertTrue("the policy must sit inside the gate", judge > gate)
        assertTrue(
            "shell must not be rejected before the root switch is consulted",
            source.indexOf("rootShellEnabled()") < gate,
        )
    }

    @Test fun plainReadsAndHarmlessCommandsAreAllowed() {
        for (command in listOf("id", "ls /data/local/tmp", "uptime", "df -h", "cat /proc/meminfo", "getprop ro.product.model")) {
            assertEquals(command, RootCommandPolicy.Verdict.Allow, judge(command))
        }
    }

    @Test fun theDenyListStopsReadsStartsAndPermissionChanges() {
        // The shipped list has WeChat in every-write and the banks in deny.
        val bank = defaults.denyPackages.first()
        for (command in listOf(
            "ls /data/data/$bank/shared_prefs",
            "cat /data/user/0/$bank/databases/x.db",
            "cmd package resolve-activity --brief $bank",
            "pm grant $bank android.permission.READ_SMS",
        )) {
            val verdict = judge(command)
            assertTrue(command, verdict is RootCommandPolicy.Verdict.Refuse)
            assertEquals("deny-list", (verdict as RootCommandPolicy.Verdict.Refuse).rule)
        }
    }

    @Test fun theDenyListNamesTheAppWhenTheHostCanResolveIt() {
        val bank = defaults.denyPackages.first()
        val verdict = judge("ls /data/data/$bank", label = { "某某银行" })
        assertTrue((verdict as RootCommandPolicy.Verdict.Refuse).message.contains("某某银行"))
    }

    @Test fun ownStorageIsRefusedEvenForReading() {
        // Reading is the leak: bridge.json holds the phone bridge token.
        for (command in listOf(
            "cat /data/data/${SafetyRules.OWN_PACKAGE}/shared_prefs/dsh_phone.xml",
            "ls /data/user/0/${SafetyRules.OWN_PACKAGE}/shared_prefs",
            "cat /storage/emulated/0/Android/data/${SafetyRules.OWN_PACKAGE}/files/x",
        )) {
            val verdict = judge(command)
            assertTrue(command, verdict is RootCommandPolicy.Verdict.Refuse)
            assertEquals("own-storage", (verdict as RootCommandPolicy.Verdict.Refuse).rule)
        }
    }

    @Test fun writingTheSwitchesIsTheSelfUnlockTheAuditFound() {
        // The audit's problem 3: shared_prefs holds root_shell and the policies JSON.
        val command = "echo '<boolean name=\"root_shell\" value=\"true\"/>' > " +
            "/data/data/${SafetyRules.OWN_PACKAGE}/shared_prefs/dsh_phone.xml"
        val verdict = judge(command)
        assertEquals("own-storage", (verdict as RootCommandPolicy.Verdict.Refuse).rule)
    }

    @Test fun liftingOwnAppAllowsNamingTheAppButNotRewritingItsRules() {
        val lifted = defaults.copy(unlocks = PhoneUnlocks(ownApp = true))
        // Naming it is now fine…
        assertTrue(judge("pidof ${SafetyRules.OWN_PACKAGE}", lifted) is RootCommandPolicy.Verdict.Allow)
        // …but the data directory is still the switches.
        assertEquals(
            "own-storage",
            (judge("rm /data/data/${SafetyRules.OWN_PACKAGE}/shared_prefs/dsh_phone.xml", lifted)
                as RootCommandPolicy.Verdict.Refuse).rule,
        )
    }

    @Test fun systemDirectoriesAreReadOnlyUntilThePersonLiftsThem() {
        for (command in listOf(
            "mount -o rw,remount /system",
            "settings put global adb_enabled 1",
            "pm grant com.example.app android.permission.WRITE_SECURE_SETTINGS",
            "rm -rf /system/framework/x.jar",
        )) {
            val verdict = judge(command)
            assertTrue(command, verdict is RootCommandPolicy.Verdict.Refuse)
            assertEquals("system-page", (verdict as RootCommandPolicy.Verdict.Refuse).rule)
        }
        // Reading a system file is fine even with the rule locked.
        assertEquals(RootCommandPolicy.Verdict.Allow, judge("cat /system/build.prop"))
        // Lifted: the same write goes through.
        val lifted = defaults.copy(unlocks = PhoneUnlocks(systemPages = true))
        assertEquals(RootCommandPolicy.Verdict.Allow, judge("settings put global adb_enabled 1", lifted))
    }

    @Test fun aPathMatchesAtItsOwnBoundariesNotInsideALongerOne() {
        // /data/system/users/0 is a credential path; the "系统目录" rule must not claim it first.
        assertTrue(policy.hitsPath("ls /data/system/users/0/accounts_ce.db", "/data/system"))
        assertFalse(policy.hitsPath("ls /data/system_ext/build.prop", "/data/system"))
        assertFalse(policy.hitsPath("ls /system_ext/build.prop", "/system"))
        assertTrue(policy.hitsPath("cat /system/build.prop", "/system"))
        assertTrue(policy.hitsPath("rm -rf '/system/framework'", "/system"))
    }

    @Test fun credentialStoresAreRefusedInBothDirections() {
        for (command in listOf(
            "ls /data/misc/keystore",
            "cp /data/system/users/0/accounts_ce.db /sdcard/x",
            "rm -rf /data/system/locksettings.db",
        )) {
            val verdict = judge(command)
            assertTrue(command, verdict is RootCommandPolicy.Verdict.Refuse)
            assertEquals("secret-store", (verdict as RootCommandPolicy.Verdict.Refuse).rule)
        }
        val lifted = defaults.copy(unlocks = PhoneUnlocks(secretFields = true))
        assertEquals(RootCommandPolicy.Verdict.Allow, judge("ls /data/misc/keystore", lifted))
    }

    @Test fun everyWriteAppsAskBeforeAWriteAndPassOnARead() {
        val chat = defaults.confirmEveryWritePackages.first()
        val write = judge("rm /data/data/$chat/files/x")
        assertTrue(write is RootCommandPolicy.Verdict.Confirm)
        assertEquals("every-write", (write as RootCommandPolicy.Verdict.Confirm).reason)
        assertEquals(listOf(chat), write.packages)
        // A plain read there is still allowed: the person said every *write* is asked.
        assertEquals(RootCommandPolicy.Verdict.Allow, judge("ls /data/data/$chat/files"))
    }

    @Test fun confirmModeDoesNotGateCommands() {
        // always / perTask govern taps; asking on every command would make the tool useless
        // without making it safer — the hard rules above are what hold.
        val always = defaults.copy(confirmMode = ConfirmMode.ALWAYS)
        assertEquals(RootCommandPolicy.Verdict.Allow, judge("ls /data/local/tmp", always))
        val task = defaults.copy(confirmMode = ConfirmMode.TASK)
        assertEquals(RootCommandPolicy.Verdict.Allow, judge("ls /data/local/tmp", task))
    }

    @Test fun emptyCommandIsItsOwnRefusal() {
        val verdict = policy.judge("   ", defaults)
        assertEquals("no-command", (verdict as RootCommandPolicy.Verdict.Refuse).rule)
    }

    @Test fun writesRecognisesMoreThanListings() {
        assertFalse(policy.writes("ls -la /sdcard"))
        assertFalse(policy.writes("cat /proc/cpuinfo"))
        assertTrue(policy.writes("rm -rf /sdcard/tmp"))
        assertTrue(policy.writes("echo x > /sdcard/a"))
        assertTrue(policy.writes("cmd wifi set-wifi-enabled disabled"))
    }

    @Test fun aPackageIdMatchesItsOwnPrefixesOnly() {
        assertTrue(policy.names("ls /data/data/com.tencent.mm/shared_prefs", "com.tencent.mm"))
        // The safe direction: a longer id that starts with the denied one is still denied.
        assertTrue(policy.names("ls /data/data/com.tencent.mmfoo", "com.tencent.mm"))
        assertFalse(policy.names("ls /data/data/com.tencent.mobileqq", "com.tencent.mm"))
    }
}
