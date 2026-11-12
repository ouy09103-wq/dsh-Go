package io.github.ccch1mneyyy.dsh_app.phone

/**
 * How often the root tool may run, per task.
 *
 * A root shell has no natural speed limit: one task can loop `shell` a hundred times and each
 * call is a fresh su invocation with the same privileges as the last. Two limits, both cheap:
 *
 *  - **one at a time** — the tool is already serialised by the worker, but a call that runs a
 *    background job can return while its children keep writing; a second call starting now would
 *    race the first one's output file. Refusing is clearer than queueing, because the model can
 *    then combine its commands instead of waiting on an unclear delay.
 *  - **60 a minute** — a sliding window, so a burst of 60 is fine and 120 in two minutes is not.
 *    Past it the model is told to combine commands, which is what a person would say.
 *
 * Deliberately per process, not per task id: phone control is one engine, one person.
 */
class ShellBudget(private val clock: () -> Long = System::currentTimeMillis) {
    private val concurrent = java.util.concurrent.atomic.AtomicInteger(0)
    private val started = ArrayDeque<Long>()

    /** Takes a slot, or refuses without taking one. Always pair with [release]. */
    fun tryAcquire(): Boolean {
        if (concurrent.incrementAndGet() > 1) {
            concurrent.decrementAndGet()
            return false
        }
        val now = clock()
        synchronized(started) {
            while (started.isNotEmpty() && now - started.first() > WINDOW_MS) started.removeFirst()
            if (started.size >= PER_MINUTE) {
                concurrent.decrementAndGet()
                return false
            }
            started.addLast(now)
        }
        return true
    }

    fun release() {
        if (concurrent.get() > 0) concurrent.decrementAndGet()
    }

    companion object {
        const val PER_MINUTE = 60
        private const val WINDOW_MS = 60_000L
    }
}
