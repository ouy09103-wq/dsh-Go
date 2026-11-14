package io.github.ouy09103.dsh_go_root.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootHelperWatchdogTest {

    /** Field 22 (starttime) is the last token here; the first token is field 3 (state). */
    private fun stat(state: String = "S", started: Long = 987654L): String =
        "1234 (sh) $state 1 1234 1234 0 -1 4194560 1234 0 0 0 0 0 0 20 0 1 0 $started 12345 678"

    private val owner = RootHelperWatchdog.Owner(4321, 987654L)

    @Test fun aHelperPastItsDeadlineExitsByItself() {
        assertEquals(
            RootHelperWatchdog.Verdict.DEADLINE,
            RootHelperWatchdog.check(30_000, 30_000, owner, stat()),
        )
    }

    @Test fun anAppThatIsGoneEndsTheHelper() {
        // /proc/<owner>/stat unreadable: the app process no longer exists.
        assertEquals(
            RootHelperWatchdog.Verdict.OWNER_GONE,
            RootHelperWatchdog.check(1_000, 30_000, owner, null),
        )
    }

    @Test fun aReusedPidDoesNotKeepTheHelperAlive() {
        // Same pid, different starttime: the app restarted and the pid now belongs to something
        // else, so the request it belonged to is long gone.
        assertEquals(
            RootHelperWatchdog.Verdict.OWNER_GONE,
            RootHelperWatchdog.check(1_000, 30_000, owner, stat(started = 111111L)),
        )
    }

    @Test fun aZombieOwnerIsTreatedAsGone() {
        assertEquals(
            RootHelperWatchdog.Verdict.OWNER_GONE,
            RootHelperWatchdog.check(1_000, 30_000, owner, stat(state = "Z")),
        )
    }

    @Test fun anUnknownOwnerLeavesTheDeadlineInCharge() {
        assertEquals(
            RootHelperWatchdog.Verdict.CONTINUE,
            RootHelperWatchdog.check(1_000, 30_000, RootHelperWatchdog.Owner(-1, -1), null),
        )
    }

    @Test fun anUnreadableStatLineIsNotTreatedAsOwnerGone() {
        assertEquals(
            RootHelperWatchdog.Verdict.CONTINUE,
            RootHelperWatchdog.check(1_000, 30_000, owner, "not a stat line"),
        )
    }

    @Test fun aRidiculousRequestedDeadlineFallsBackToTheDefault() {
        assertEquals(30_000L, RootHelperWatchdog.deadlineMs(0, 30_000))
        assertEquals(30_000L, RootHelperWatchdog.deadlineMs(-5, 30_000))
        assertEquals(
            RootHelperWatchdog.MAX_DEADLINE_MS,
            RootHelperWatchdog.deadlineMs(RootHelperWatchdog.MAX_DEADLINE_MS + 1, 30_000),
        )
        assertEquals(900_000L, RootHelperWatchdog.deadlineMs(900_000, 30_000))
    }

    @Test fun commWithSpacesAndParenthesesDoesNotShiftTheStarttime() {
        val line = "1234 (a)b c) S 1 1234 1234 0 -1 4194560 1234 0 0 0 0 0 0 20 0 1 0 987654 12345 678"
        val parsed = ProcStat.parse(line)
        assertEquals('S', parsed?.state)
        assertEquals(987654L, parsed?.started)
    }

    @Test fun aTruncatedStatLineHasNoAnswer() {
        assertNull(ProcStat.parse("1234 (sh"))
        assertNull(ProcStat.parse("1234 (sh) "))
    }
}