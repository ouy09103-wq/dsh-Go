package io.github.ouy09103.dsh_go_root.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootIdentityTest {
    private val status = "Name:\tsh\nState:\tS (sleeping)\nUid:\t0\t0\t0\t0\nGid:\t0\t0\t0\t0\n"
    private val domain = "u:r:magisk:s0\n"
    private val mark = "DSH_IDENTITY_DOMAIN\n"

    @Test fun kernelUidAndDomainAreBothRequired() {
        val granted = RootIdentity.parse(status + mark + domain, "", 0)
        assertNull(granted.failure)
        assertEquals(0, granted.uid)
        assertEquals("u:r:magisk:s0", granted.domain)
        assertTrue(granted.granted)
    }

    @Test fun aUidZeroChildInAnAppDomainIsNotRoot() {
        val wrong = RootIdentity.parse(status + mark + "u:r:untrusted_app_27:s0:c172,c257\n", "", 0)
        assertEquals(RootIdentity.WRONG_DOMAIN, wrong.failure)
        assertEquals(0, wrong.uid)
    }

    @Test fun aSuThatAnswersWithTheAppUidIsNotRoot() {
        val app = RootIdentity.parse("Uid:\t10428\t10428\t10428\t10428\n" + mark + domain, "", 0)
        assertEquals(RootIdentity.NOT_ROOT, app.failure)
        assertEquals(10428, app.uid)
    }

    @Test fun anEmptyDomainIsReportedRatherThanAssumedSafe() {
        assertEquals(RootIdentity.DOMAIN_UNKNOWN, RootIdentity.parse(status + mark, "", 0).failure)
    }

    @Test fun aRefusalIsNotTheSameAsAFailedLaunch() {
        val refused = RootIdentity.parse("", "su: permission denied\n", 1)
        assertEquals(RootIdentity.NOT_GRANTED, refused.failure)
        val failed = RootIdentity.parse("", "su: something else\n", 1)
        assertEquals(RootIdentity.SU_FAILED, failed.failure)
    }

    @Test fun theThreeLaunchFailuresStayApart() {
        assertEquals(RootIdentity.SU_MISSING, RootIdentity.parse("", "no su on this device", -1).failure)
        assertEquals(RootIdentity.SU_FAILED, RootIdentity.parse("", "timeout", -1).failure)
        assertEquals(RootIdentity.SU_NOT_EXECUTABLE, RootIdentity.parse("", "Cannot run program", -1).failure)
    }

    @Test fun theDescribeLineNeverClaimsRootWithoutTheKernel() {
        assertTrue(RootIdentity.describe(RootIdentity.parse(status + mark + domain, "", 0)).startsWith("uid=0"))
        assertEquals("uid=10428（非 root）", RootIdentity.describe(RootIdentity.parse("Uid:\t10428\n" + mark, "", 0)))
        assertEquals(RootIdentity.SU_MISSING, RootIdentity.describe(RootIdentity.Result(RootIdentity.SU_MISSING, -1, "")))
    }

    @Test fun suArgvCarriesOneShellScript() {
        assertEquals(listOf("/system/bin/su", "-c", "id -u"), RootIdentity.argv("/system/bin/su", "id -u"))
    }
}
