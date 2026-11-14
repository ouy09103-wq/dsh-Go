package io.github.ouy09103.dsh_go_root.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootHelperSlotsTest {

    private class Fake(var alive: Boolean)

    private fun slots(capacity: Int, alive: (Fake) -> Boolean = { it.alive }) =
        RootHelperSlots<Fake>(capacity, alive)

    @Test fun fillsToCapacityThenRefusesBeforeAnythingIsSent() {
        val slots = slots(2)
        assertNotNull(slots.tryAcquire("exec", 0))
        assertNotNull(slots.tryAcquire("exec", 0))
        assertNull(slots.tryAcquire("exec", 0))
        assertTrue(slots.snapshot(0).full)
    }

    @Test fun aDeadProcessFreesItsSlot() {
        val slots = slots(1)
        val ticket = slots.tryAcquire("exec", 0)!!
        slots.attach(ticket, Fake(false))
        assertFalse(slots.release(ticket, 1))
        assertEquals(0, slots.snapshot(1).active)
        assertNotNull(slots.tryAcquire("exec", 2))
    }

    @Test fun aProcessThatOutlivesItsRequestKeepsTheSlot() {
        val slots = slots(1)
        val ticket = slots.tryAcquire("exec", 0)!!
        slots.attach(ticket, Fake(true))
        // true means "still alive", so the slot is not returned to the pool.
        assertTrue(slots.release(ticket, 100))
        val snapshot = slots.snapshot(200)
        assertEquals(0, snapshot.active)
        assertEquals(1, snapshot.lingering)
        assertEquals(100L, snapshot.oldestLingeringMs)
        // The point of the lingering count: refuse the next request instead of stacking a
        // second root process on top of the one that would not die.
        assertNull(slots.tryAcquire("exec", 200))
    }

    @Test fun aLingeringHelperIsReclaimedOnceItReallyExits() {
        val slots = slots(1)
        val ticket = slots.tryAcquire("exec", 0)!!
        val process = Fake(true)
        slots.attach(ticket, process)
        assertTrue(slots.release(ticket, 100))
        assertEquals(1, slots.snapshot(200).lingering)
        process.alive = false
        val reclaimed = slots.snapshot(300)
        assertEquals(0, reclaimed.lingering)
        assertEquals(0, reclaimed.active)
        assertNotNull(slots.tryAcquire("exec", 300))
    }

    @Test fun labelIsRecordedForDiagnostics() {
        val slots = slots(1)
        val ticket = slots.tryAcquire("guest", 0)!!
        slots.attach(ticket, Fake(true))
        assertEquals("guest", ticket.label)
        assertFalse(ticket.lingering)
        assertTrue(slots.snapshot(0).render().contains("labels=guest"))
    }
}