package io.github.ouy09103.dsh_go.phone.background

import io.github.ouy09103.dsh_go.root.RootSpawn
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bounded root command execution.
 *
 * The naive `ProcessBuilder(...).start()` + `waitFor()` + `readText()` sequence has three
 * problems that only show up once a command produces real output:
 *
 *  1. A pipe holds ~64 KiB. Past that the child blocks on write and can never exit, so
 *     `waitFor` waits until the timeout and then kills a command that had already finished.
 *     `logcat -d`, `dumpsys` and `find /` all cross that line.
 *  2. A reader started after `waitFor` reads a pipe nobody was draining, so the tail arrives
 *     after the kill: the caller gets "timed out" and no output.
 *  3. Killing the direct child is not enough. `su` hands the command to a root shell; its
 *     children are also root, the app's uid cannot signal them, and they keep the pipe open.
 *
 * Output therefore goes to a temp file, not a pipe: there is no buffer to fill, so the child
 * never blocks, and no drain thread is needed at all. The file is capped by a watchdog so a
 * runaway command cannot fill storage.
 */
object BoundedShell {

    /** Start failure (missing binary, no su). Distinct from any real exit code. */
    const val EXIT_START_FAILED = -2

    /** Killed because it ran past its timeout. */
    const val EXIT_TIMEOUT = -1

    /** Hard cap on the temp file. A command that writes more is killed and reported truncated. */
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** How often the watchdog checks size and the deadline. */
    private const val WATCHDOG_MS = 50L

    /** Stored bytes per kept char, enough for 4-byte UTF-8. */
    private const val BYTES_PER_CHAR = 4

    class Result(
        val timedOut: Boolean,
        val exitCode: Int,
        val text: String,
        /** Bytes the command produced, including those dropped by the cap. */
        val totalBytes: Long,
        val truncated: Boolean,
        /**
         * Where the whole output was left when it did not fit in [text], or null when [text] is
         * all of it. The model gets the path, so a truncated answer can say where the rest is
         * instead of pretending the command printed this much.
         */
        val spillPath: String? = null,
    ) {
        val ok: Boolean get() = !timedOut && exitCode == 0
    }

    /**
     * Runs [argv] to completion, or kills it at [timeoutMs].
     *
     * @param maxChars characters kept in [Result.text]; the rest is counted but dropped.
     * @param cwd working directory; null keeps the caller's.
     * @param env full environment; null keeps the caller's.
     */
    fun run(
        argv: List<String>,
        timeoutMs: Long,
        maxChars: Int,
        cwd: String? = null,
        env: Map<String, String>? = null
    ): Result {
        val outFile = try {
            File.createTempFile("dsh-shell-", ".out")
        } catch (e: Exception) {
            return Result(false, EXIT_START_FAILED,
                "${e.javaClass.simpleName}: ${e.message}", 0, false)
        }

        val process = try {
            // stdout to the temp file (stderr joins it), stdin from /dev/null: an interactive `su`
            // waiting on a pipe that never closes would sit until the timeout.
            RootSpawn.toFile(argv, outFile, cwd, env)
        } catch (e: Exception) {
            outFile.delete()
            return Result(false, EXIT_START_FAILED,
                "${e.javaClass.simpleName}: ${e.message}", 0, false)
        }

        var timedOut = false
        var oversized = false
        try {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                // Poll instead of one long waitFor: the watchdog has to look at the file while
                // the command runs, or an unbounded writer fills the disk.
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) {
                    timedOut = true
                    killTree(process)
                    // The verdict is already certain; waiting longer here would only delay the
                    // reply. The tree is killed either way (see [killTree]).
                    process.waitFor(300, TimeUnit.MILLISECONDS)
                    break
                }
                if (process.waitFor(minOf(left, WATCHDOG_MS), TimeUnit.MILLISECONDS)) break
                if (outFile.length() > MAX_FILE_BYTES) {
                    oversized = true
                    killTree(process)
                    process.waitFor(2, TimeUnit.SECONDS)
                    break
                }
            }
        } catch (e: InterruptedException) {
            timedOut = true
            killTree(process)
            Thread.currentThread().interrupt()
        }

        val total = outFile.length()
        val bytes: ByteArray = try {
            val want = minOf(total, maxOf(0, maxChars).toLong() * BYTES_PER_CHAR)
            readPrefix(outFile, want)
        } catch (e: Exception) {
            ByteArray(0)
        }

        var text = String(bytes, Charsets.UTF_8)
        if (text.length > maxChars) text = text.substring(0, maxOf(0, maxChars))
        val truncated = total > bytes.size.toLong() || oversized
        // A truncated answer keeps the file and names it; the caller can read the rest with
        // `shell_read` instead of re-running a command that may not be repeatable.
        val spill = if (truncated) {
            val kept = File(outFile.parentFile, "dsh-shell-${System.currentTimeMillis()}.log")
            if (runCatching { outFile.renameTo(kept) }.getOrDefault(false)) kept.absolutePath else null
        } else {
            null
        }
        if (spill == null) outFile.delete()
        val code = when {
            timedOut -> EXIT_TIMEOUT
            else -> runCatching { process.exitValue() }.getOrDefault(EXIT_START_FAILED)
        }
        return Result(timedOut, code, text, total, truncated, spill)
    }

    /** First [count] bytes of [file], without loading the whole thing. */
    private fun readPrefix(file: File, count: Long): ByteArray {
        if (count <= 0) return ByteArray(0)
        return file.inputStream().use { input ->
            val buffer = ByteArray(count.toInt())
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            if (read == buffer.size) buffer else buffer.copyOf(read)
        }
    }

    /**
     * Kills the process and everything it started.
     *
     * `destroyForcibly` signals one pid. A shell that forked a background job leaves children
     * holding the output file; those are root, so the app's own uid cannot signal them, and the
     * direct child being gone is not enough to stop the work. Children are walked first (they get
     * reparented once the parent dies), then the parent.
     *
     * This is best effort: /proc visibility is restricted on some devices (hidepid), and the
     * output file means a surviving grandchild can no longer block the caller — it only keeps
     * writing to an already-deleted inode.
     */
    private fun killTree(process: Process) {
        val pid = pidOf(process)
        if (pid > 0) {
            for (child in descendants(pid)) sendSignal(child, 9)
        }
        process.destroyForcibly()
    }

    /**
     * SIGKILL a pid.
     *
     * On Android `android.os.Process.sendSignal` is the supported call and exists from API 1.
     * On a desktop JVM it doesn't exist, so `ProcessHandle` (Java 9) is the fallback. Both are
     * reached reflectively: compiling against either one breaks the other target.
     */
    private fun sendSignal(pid: Long, signal: Int) {
        if (pid <= 0) return
        if (sendViaAndroidProcess(pid, signal)) return
        runCatching {
            val cls = Class.forName("java.lang.ProcessHandle")
            val instance = cls.getMethod("of", Long::class.javaPrimitiveType).invoke(null, pid)
                ?: return
            cls.getMethod("destroyForcibly").invoke(instance)
        }
    }

    private fun sendViaAndroidProcess(pid: Long, signal: Int): Boolean = runCatching {
        val cls = Class.forName("android.os.Process")
        cls.getMethod("sendSignal", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(null, pid.toInt(), signal)
        true
    }.getOrDefault(false)

    /** Reads the pid without reflection: `toString` is `Process[pid=..., exitValue=...]`. */
    private fun pidOf(process: Process): Long = runCatching {
        Regex("pid=(\\d+)").find(process.toString())?.groupValues?.get(1)?.toLongOrNull() ?: -1L
    }.getOrDefault(-1L)

    /** Direct and indirect children of [rootPid], from /proc/<pid>/stat. */
    private fun descendants(rootPid: Long): List<Long> {
        val procDir = File("/proc")
        val entries = procDir.list() ?: return emptyList()
        val parents = HashMap<Long, Long>()
        for (entry in entries) {
            val pid = entry.toLongOrNull() ?: continue
            val line = runCatching { File(procDir, "$entry/stat").readText() }.getOrNull() ?: continue
            // "pid (comm) state ppid ..." — comm may contain spaces and parentheses.
            val rparen = line.lastIndexOf(')')
            if (rparen < 0 || rparen + 2 >= line.length) continue
            val fields = line.substring(rparen + 2).trim().split(' ')
            if (fields.size < 2) continue
            val ppid = fields[1].toLongOrNull() ?: continue
            parents[pid] = ppid
        }
        val out = ArrayList<Long>()
        var frontier = setOf(rootPid)
        for (round in 0 until 16) {
            val next = HashSet<Long>()
            for ((pid, ppid) in parents) {
                if (ppid in frontier && pid != rootPid && pid !in out) next.add(pid)
            }
            if (next.isEmpty()) break
            out.addAll(next)
            frontier = next
        }
        return out
    }
}
