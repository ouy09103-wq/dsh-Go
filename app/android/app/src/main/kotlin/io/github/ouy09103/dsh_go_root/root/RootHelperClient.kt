package io.github.ouy09103.dsh_go_root.root

import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * The app side of the shell channel — DSHA v0.2.2's `RootShell` privileged path, shrunk to what
 * this app runs.
 *
 * `su -c "<command>"` used to be the whole channel: the app waited, gave up, called
 * `destroyForcibly()` on a uid 0 child, and got EPERM. The command kept running, nobody recorded
 * it, and the next command started a second root process on top. Here the su child is a helper
 * (see [RootHelperMain]) that owns the command and can kill it, a slot is taken before su is
 * started so a leak cannot stack up, and a helper that misses its deadline keeps its slot until
 * it is really gone.
 */
internal object RootHelperClient {

    /** Prefix of the "nothing was sent" result; callers may report it as not executed. */
    const val BUSY = "[ROOT_BUSY]"

    /** Result when the helper never produced its marker: the command may have run. */
    const val UNKNOWN = "[EXECUTION_UNKNOWN]"

    /** Root helpers at once. Each is one su grant plus one app_process JVM. */
    private const val CAPACITY = 3

    /** Enough for a full UI dump; beyond this the result is marked truncated, not silently cut. */
    private const val MAX_STREAM = 400 * 1024

    /**
     * How much longer the helper may live than the caller waits, so it can still kill its child
     * and report. Same idea as DSHA's margin, smaller because this helper never runs a session.
     */
    private const val HELPER_SLACK_MS = 10_000L

    private const val APP_PROCESS = "/system/bin/app_process"
    private const val APP_PROCESS_CWD = "/system/bin"

    private val slots = RootHelperSlots<Process>(CAPACITY) { process -> process.isAlive }

    class Outcome(
        val output: String,
        val stderr: String,
        val exitCode: Int,
        val timedOut: Boolean = false,
        val busy: Boolean = false,
        val truncated: Boolean = false,
        val note: String = "",
    )

    /** Slot state for diagnostics; no command content. */
    fun snapshot(): String = slots.snapshot(now()).render()

    fun run(
        apkPath: String,
        su: String,
        command: String,
        timeoutMs: Long,
        cwd: String?,
        env: Map<String, String>,
    ): Outcome {
        val ticket = slots.tryAcquire("exec", now())
            ?: return Outcome("", busyText(), 124, busy = true, note = busyText())
        var spawned: Process? = null
        try {
            // Only the fixed entry point and the installed APK path go into su's word list; the
            // command itself travels on stdin, so quoting can never turn it into something else.
            val entry = "CLASSPATH=" + quote(apkPath) + " " + APP_PROCESS + " " + APP_PROCESS_CWD +
                " " + RootHelperMain::class.java.name
            val process = RootSpawn.helper(listOf(su, "-c", entry), cwd)
            spawned = process
            slots.attach(ticket, process)
            val payload = request(command, timeoutMs, env).toString()
                .toByteArray(StandardCharsets.UTF_8)
            process.outputStream.use { it.write(payload) }

            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = pump(process.inputStream, out)
            val errThread = pump(process.errorStream, err)
            val finished = process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            if (!finished) {
                // Expected to fail: the app's uid cannot signal a root child. The helper's own
                // watchdog is what actually ends it, which is why the slot is not released here.
                runCatching { process.destroyForcibly() }
            }
            outThread.join(1_000)
            errThread.join(1_000)

            val text = out.toString()
            val stderr = err.toString()
            val marker = text.lastIndexOf(RootHelperMain.RESULT)
            if (finished && marker >= 0) {
                val decoded = decode(text.substring(marker + RootHelperMain.RESULT.length).trim())
                if (decoded != null) {
                    return Outcome(
                        output = text.substring(0, marker).removeSuffix("\n"),
                        stderr = stderr,
                        exitCode = decoded.optInt("exit", 125),
                        note = decoded.optString("note", ""),
                    )
                }
            }
            return Outcome(
                output = text,
                stderr = stderr,
                exitCode = 125,
                timedOut = !finished,
                note = if (finished) {
                    UNKNOWN + " root helper 未返回结果（退出码异常），命令可能已执行，不会自动重试"
                } else {
                    UNKNOWN + " root helper 在 " + timeoutMs +
                        "ms 内未结束，命令可能已执行，不会自动重试"
                },
            )
        } catch (error: IOException) {
            return Outcome("", "", -1, note = error.message ?: "su 未能启动")
        } catch (error: Throwable) {
            return Outcome(
                "", "", -1,
                note = error.message ?: (error.javaClass.simpleName + "（root helper 未能启动）"),
            )
        } finally {
            // Streams of a lingering helper stay owned by the helper; closing them here would
            // only throw. The slot decides: alive means the ticket stays, and says so.
            slots.release(ticket, now())
            val helper = spawned
            if (helper != null && !helper.isAlive) {
                runCatching { helper.inputStream.close() }
                runCatching { helper.errorStream.close() }
                runCatching { helper.outputStream.close() }
            }
        }
    }

    /**
     * The asking process, so the helper can stop when it is gone, and a deadline past the
     * caller's own wait, so the helper is still there to clean up after the caller gave up.
     */
    private fun request(command: String, timeoutMs: Long, env: Map<String, String>): JSONObject {
        val pid = android.os.Process.myPid()
        val started = runCatching {
            ProcStat.parse(File("/proc/self/stat").readText())?.started ?: -1L
        }.getOrDefault(-1L)
        val request = JSONObject()
            .put("command", command)
            .put("ownerPid", pid)
            .put("ownerStarted", started)
            .put(
                "helperDeadlineMs",
                RootHelperWatchdog.deadlineMs(
                    timeoutMs + HELPER_SLACK_MS,
                    timeoutMs + HELPER_SLACK_MS,
                ),
            )
        if (env.isNotEmpty()) {
            val objectEnv = JSONObject()
            env.forEach { (key, value) -> objectEnv.put(key, value) }
            request.put("env", objectEnv)
        }
        return request
    }

    private fun decode(encoded: String): JSONObject? = runCatching {
        JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8))
    }.getOrNull()

    private fun pump(stream: InputStream, into: StringBuilder): Thread = Thread {
        runCatching {
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                synchronized(into) {
                    val room = MAX_STREAM - into.length
                    if (room <= 0) return@runCatching
                    into.append(String(buffer, 0, minOf(room, read)))
                }
            }
        }
    }.apply {
        isDaemon = true
        name = "dsh-root-helper-drain"
        start()
    }

    private fun busyText(): String {
        val snapshot = slots.snapshot(now())
        return BUSY + " root 通道已有 " + (snapshot.active + snapshot.lingering) +
            " 个 root 进程未结束（其中 " + snapshot.lingering +
            " 个已超时仍在运行），本次命令未发送，可稍后重试。槽位 " + snapshot.render()
    }

    /** One shell word, single-quoted: the APK path is a device path, never a pattern. */
    private fun quote(word: String): String =
        "'" + word.replace("'", "'\\''") + "'"

    private fun now(): Long = SystemClock.elapsedRealtime()
}