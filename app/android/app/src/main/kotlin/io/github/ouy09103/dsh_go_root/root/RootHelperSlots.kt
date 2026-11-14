package io.github.ouy09103.dsh_go_root.root

/**
 * Concurrency slots for the root helper — DSHA v0.2.2's `RootHelperSlots`, same rule.
 *
 * The app's uid cannot signal a uid 0 child (EPERM), so a helper that missed its deadline is
 * still running while the request that started it is already gone. Slots are therefore released
 * on the process *really* exiting: a helper that is still alive keeps its slot as *lingering*,
 * and the next request is refused before su is started (nothing sent, safe to retry) instead of
 * stacking another root process on top.
 *
 * Pure logic: whether a process is alive is the caller's predicate, so this is testable off a
 * device.
 */
internal class RootHelperSlots<P>(private val capacity: Int, private val alive: (P) -> Boolean) {

    init {
        require(capacity in 1..MAX_CAPACITY) { "ROOT_SLOT_CAPACITY" }
        requireNotNull(alive) { "ROOT_SLOT_ALIVE" }
    }

    /** One occupied slot. [lingeringSince] is set once the request ended but the process lived. */
    class Ticket<P>(val acquiredAt: Long, val label: String) {
        internal var process: P? = null
        internal var lingeringSince: Long = -1L

        val lingering: Boolean get() = lingeringSince >= 0
    }

    /** Read-only view for diagnostics and for the "nothing was sent" message. */
    class Snapshot(
        val capacity: Int,
        val active: Int,
        val lingering: Int,
        val oldestLingeringMs: Long,
        val labels: List<String>,
    ) {
        val full: Boolean get() = active + lingering >= capacity

        fun render(): String = buildString {
            append("capacity=").append(capacity)
            append(" active=").append(active)
            append(" lingering=").append(lingering)
            if (lingering > 0) append(" oldestLingeringMs=").append(oldestLingeringMs)
            if (labels.isNotEmpty()) append(" labels=").append(labels.joinToString(","))
        }
    }

    private val tickets = ArrayList<Ticket<P>>()

    /** Reclaims helpers that exited while lingering; returns null when every slot is taken. */
    @Synchronized
    fun tryAcquire(label: String, now: Long): Ticket<P>? {
        prune()
        if (tickets.size >= capacity) return null
        val ticket = Ticket<P>(now, if (label.isEmpty()) "exec" else label)
        tickets.add(ticket)
        return ticket
    }

    /** Registers the process started for this ticket; a stale ticket is ignored. */
    @Synchronized
    fun attach(ticket: Ticket<P>?, process: P) {
        if (ticket != null && tickets.contains(ticket)) ticket.process = process
    }

    /** True when the process is still alive after the request ended, so the slot stays taken. */
    @Synchronized
    fun release(ticket: Ticket<P>?, now: Long): Boolean {
        if (ticket == null || !tickets.contains(ticket)) return false
        val process = ticket.process
        if (process != null && alive(process)) {
            if (ticket.lingeringSince < 0) ticket.lingeringSince = now
            return true
        }
        tickets.remove(ticket)
        return false
    }

    @Synchronized
    fun snapshot(now: Long): Snapshot {
        prune()
        var lingering = 0
        var oldest = 0L
        val labels = ArrayList<String>(tickets.size)
        for (ticket in tickets) {
            if (ticket.lingering) {
                lingering++
                val since = ticket.lingeringSince
                if (now - since > oldest) oldest = now - since
                labels.add(ticket.label + "(lingering)")
            } else {
                labels.add(ticket.label)
            }
        }
        return Snapshot(capacity, tickets.size - lingering, lingering, oldest, labels)
    }

    private fun prune() {
        val iterator = tickets.iterator()
        while (iterator.hasNext()) {
            val ticket = iterator.next()
            if (!ticket.lingering) continue
            val process = ticket.process
            if (process == null || !alive(process)) iterator.remove()
        }
    }

    private companion object {
        /** Above this a leaked helper would dwarf anything the phone tool actually runs. */
        const val MAX_CAPACITY = 16
    }
}