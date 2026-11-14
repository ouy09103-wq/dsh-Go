package io.github.ouy09103.dsh_go_root.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuCandidatesTest {

    @Test fun theFirstCandidateTheCheckAcceptsWins() {
        val seen = ArrayList<String>()
        val found = SuCandidates.firstVerified { path ->
            seen.add(path)
            path == "/system/xbin/su"
        }
        assertEquals("/system/xbin/su", found)
        // Discovery stops at the first hit: later entries are never even inspected.
        assertEquals(listOf("/system/bin/su", "/system/xbin/su"), seen)
    }

    @Test fun aCandidateThatFailsTheCheckIsSkippedNotUsed() {
        // /system/bin/su exists but fails verification (ownership or mode); the next one wins.
        val found = SuCandidates.firstVerified { path -> path == "/data/adb/ksu/bin/su" }
        assertEquals("/data/adb/ksu/bin/su", found)
    }

    @Test fun noVerifiedCandidateMeansNoSu() {
        assertNull(SuCandidates.firstVerified { false })
    }

    @Test fun candidatesAreFixedPathsAndNeverComeFromPath() {
        for (path in SuCandidates.PATHS) {
            assertEquals("candidate must be absolute: $path", true, path.startsWith("/"))
        }
    }
}