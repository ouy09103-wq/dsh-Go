package io.github.ccch1mneyyy.dsh_app.root

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One interactive root shell: `su -c 'exec sh'` on private pipes. Output is buffered
 * until the Dart side polls for it, so a burst never blocks the child.
 */
internal class RootShell private constructor(
    private val process: Process,
    private val pidFile: File,
) {

    /**
     * The shell's pid. The shell writes it to [pidFile] itself (`echo $$`), which works on every
     * API level; `Process.pid()` only exists from Android 16, so it is the fallback, not the source.
     */
    val pid: Int
        get() {
            val fromFile = runCatching { pidFile.readText().trim().toIntOrNull() }.getOrNull()
            if (fromFile != null && fromFile > 0) return fromFile
            return runCatching {
                val method = Process::class.java.getDeclaredMethod("pid")
                (method.invoke(process) as Number).toInt()
            }.getOrDefault(-1)
        }

    /** Waits for `echo $$` to land, so the first status call already has a pid. */
    fun awaitPid(millis: Long = 2_000) {
        val deadline = System.nanoTime() + millis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (pid > 0 || !process.isAlive) return
            Thread.sleep(25)
        }
    }

    private val out = StringBuilder()
    private val err = StringBuilder()
    private var closed = false

    init {
        pump(process.inputStream, out)
        pump(process.errorStream, err)
    }

    fun alive(): Boolean = !closed && process.isAlive

    fun write(text: String): Boolean = try {
        process.outputStream.write(text.toByteArray())
        process.outputStream.flush()
        true
    } catch (_: IOException) {
        false
    }

    /** Everything written since the previous poll; `closed` once the shell is gone. */
    fun poll(): Map<String, Any?> = synchronized(this) {
        val map = mapOf<String, Any?>(
            "stdout" to out.toString(),
            "stderr" to err.toString(),
            "closed" to (!alive()),
        )
        out.setLength(0)
        err.setLength(0)
        map
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { process.outputStream.write("exit\n".toByteArray()); process.outputStream.flush() }
        if (!process.waitFor(700, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        runCatching { pidFile.delete() }
    }

    private fun pump(stream: java.io.InputStream, into: StringBuilder) {
        thread(isDaemon = true, name = "dsh-root-shell") {
            runCatching {
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    synchronized(this) { into.append(String(buffer, 0, read)) }
                }
            }
        }
    }

    companion object {
        /**
         * Opens one persistent root shell. [dir] must be writable by this app (the plugin passes
         * `cacheDir`): the shell writes its pid there before replacing itself with `/system/bin/sh`.
         */
        fun open(su: String, cwd: String?, dir: File): RootShell {
            dir.mkdirs()
            val pidFile = File(dir, "root-shell-${System.nanoTime()}.pid")
            val script = "echo \$\$ > ${quote(pidFile.absolutePath)}; exec /system/bin/sh"
            val builder = ProcessBuilder(su, "-c", script)
            if (cwd != null) builder.directory(File(cwd))
            val shell = RootShell(builder.start(), pidFile)
            shell.awaitPid()
            return shell
        }

        private fun quote(word: String): String = "'" + word.replace("'", "'\\''") + "'"
    }
}
