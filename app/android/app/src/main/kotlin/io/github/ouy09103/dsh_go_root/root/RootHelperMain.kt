package io.github.ouy09103.dsh_go_root.root

import android.os.Process as AndroidProcess
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The root side of the shell channel — the equivalent of DSHA v0.2.2's `RootShellMain`.
 *
 * Started by su as `app_process … RootHelperMain`, so this code runs as uid 0 and can do what the
 * app cannot: read another uid's `/proc`, kill the child it owns, and decide for itself when to
 * stop. One request arrives as JSON on stdin, one result leaves as a marker line on stdout; the
 * command's own output is inherited straight through, so a chatty command cannot fill a pipe and
 * look like a timeout.
 *
 * The app never calls this class directly and never needs to: it is an entry point for
 * app_process, kept from R8 by name in proguard-rules.pro.
 */
object RootHelperMain {

    /** Everything after the last occurrence of this on stdout is the encoded result. */
    const val RESULT = "DSH_GO_ROOT_RESULT="

    /** Used until the request says otherwise, so a helper that never gets a request still ends. */
    private const val BOOT_DEADLINE_MS = 90_000L

    /** A shell command longer than this is a mistake, not a request. */
    private const val MAX_REQUEST_BYTES = 65_536

    private const val SHELL = "/system/bin/sh"

    @Volatile
    private var child: Process? = null

    @Volatile
    private var deadlineMs = BOOT_DEADLINE_MS

    @Volatile
    private var owner: RootHelperWatchdog.Owner? = null

    @Volatile
    private var done = false

    @JvmStatic
    fun main(args: Array<String>) {
        val started = System.nanoTime()
        startWatchdog(started)
        var exitCode = 125
        var note = ""
        try {
            if (AndroidProcess.myUid() != 0) throw SecurityException("su 未授予 root 身份")
            val request = JSONObject(readRequest())
            deadlineMs = RootHelperWatchdog.deadlineMs(
                request.optLong("helperDeadlineMs", -1L),
                BOOT_DEADLINE_MS,
            )
            owner = RootHelperWatchdog.Owner(
                request.optInt("ownerPid", -1),
                request.optLong("ownerStarted", -1L),
            )
            val command = request.optString("command", "")
            if (command.isEmpty()) throw IllegalArgumentException("请求里没有 command")
            exitCode = runShell(command, request)
        } catch (error: Throwable) {
            note = error.message ?: error.javaClass.simpleName
            exitCode = 125
        } finally {
            done = true
            // A child that outlived its own shell would keep the slot taken for good.
            runCatching { child?.takeIf { it.isAlive }?.destroyForcibly() }
            emit(exitCode, note)
        }
    }

    /** One `/bin/sh -c`, inherited straight onto the helper's own stdout and stderr. */
    private fun runShell(command: String, request: JSONObject): Int {
        val builder = ProcessBuilder(SHELL, "-c", command)
        builder.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        request.optString("cwd", "").takeIf { it.isNotEmpty() }?.let { builder.directory(File(it)) }
        request.optJSONObject("env")?.let { env ->
            val keys = env.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                builder.environment()[key] = env.optString(key)
            }
        }
        val process = builder.start()
        child = process
        return process.waitFor()
    }

    /** Reads the JSON request, bounded; stdin is closed by the app as soon as it is written. */
    private fun readRequest(): String {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = System.`in`.read(buffer)
            if (read < 0) break
            if (bytes.size() + read > MAX_REQUEST_BYTES) {
                throw IllegalArgumentException("root 请求过长")
            }
            bytes.write(buffer, 0, read)
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    /**
     * A helper that outlives its request is the failure this exists for. It runs in root's own
     * process, so it can do what the app cannot: kill the child, then leave.
     */
    private fun startWatchdog(startedNanos: Long) {
        Thread({
            while (!done) {
                val elapsed = (System.nanoTime() - startedNanos) / 1_000_000L
                val current = owner
                val stat = current?.let { readStat(it.pid) }
                when (RootHelperWatchdog.check(elapsed, deadlineMs, current, stat)) {
                    RootHelperWatchdog.Verdict.DEADLINE,
                    RootHelperWatchdog.Verdict.OWNER_GONE,
                    -> {
                        runCatching { child?.takeIf { it.isAlive }?.destroyForcibly() }
                        Runtime.getRuntime().halt(125)
                    }
                    RootHelperWatchdog.Verdict.CONTINUE -> Unit
                }
                runCatching { Thread.sleep(500) }
            }
        }, "dsh-go-root-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    private fun readStat(pid: Int): String? = runCatching {
        String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/$pid/stat")))
    }.getOrNull()

    /**
     * The result line is the helper's last word: `halt` follows it so app_process does not wait
     * on a thread of ours, which would leave the slot looking occupied.
     */
    private fun emit(exitCode: Int, note: String) {
        val payload = JSONObject()
            .put("exit", exitCode)
            .put("note", note)
            .toString()
        val encoded = Base64.encodeToString(
            payload.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )
        System.out.println()
        System.out.println(RESULT + encoded)
        System.out.flush()
        Runtime.getRuntime().halt(0)
    }
}