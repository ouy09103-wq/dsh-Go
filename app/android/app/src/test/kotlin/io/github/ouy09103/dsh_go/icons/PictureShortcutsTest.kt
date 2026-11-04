package io.github.ouy09103.dsh_go.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureShortcutsTest {
    @Test fun anEmptyNameIsTheApps() {
        assertEquals("DSH Go Root", PictureShortcuts.label(null))
        assertEquals("DSH Go Root", PictureShortcuts.label("   "))
    }

    @Test fun namesAreOneLineAndCutByCharacter() {
        assertEquals("我的 助手", PictureShortcuts.label("  我的\n 助手 "))
        val long = "鲸".repeat(30)
        assertEquals("鲸".repeat(24), PictureShortcuts.label(long))
        // A surrogate pair is never split.
        val emoji = "🐋".repeat(30)
        assertEquals("🐋".repeat(24), PictureShortcuts.label(emoji))
    }

    @Test fun eachPictureIsItsOwnShortcut() {
        assertNotEquals(PictureShortcuts.id(1), PictureShortcuts.id(2))
        assertTrue(PictureShortcuts.id(5).startsWith(PictureShortcuts.PREFIX))
        // Not the recent conversations' ids (ShortcutsPlugin.RECENT_PREFIX).
        assertTrue(!PictureShortcuts.id(5).startsWith("chat:"))
    }
}
