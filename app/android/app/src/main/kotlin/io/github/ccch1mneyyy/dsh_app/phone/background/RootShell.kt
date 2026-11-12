package io.github.ccch1mneyyy.dsh_app.phone.background

import io.github.ccch1mneyyy.dsh_app.root.RootExec
import io.github.ccch1mneyyy.dsh_app.root.RootSpawn
import java.io.File

/**
 * Runs one shell command as root through `su`.
 *
 * Every failure mode comes back as a sentence the model can act on, not as an exception: no root
 * manager, permission denied, a command that hangs, output too large, and — the one that matters
 * most on this platform — a `su` that reports `uid=0` without being Android root.
 */
object RootShell {

    /**
     * Where root managers install `su`, in the order worth trying.
     *
     * A file existing and being executable is not proof that it can run: under PRoot `isFile`
     * and `canExecute` both say yes for a path that fails with ENOENT on exec. The only reliable
     * test is to start it, so candidates are tried in order and a start failure moves on.
     */
    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk/su"
    )

    /**
     * `su` binaries that belong to a guest Linux distro, not to Android.
     *
     * Under PRoot the guest ships its own `/usr/bin/su`, which answers `uid=0` from PRoot's
     * emulated root: no capabilities, no mount namespace, nothing the model asked for. Reporting
     * success for it would be worse than reporting no root at all.
     */
    private val NOT_ANDROID_SU = setOf("/usr/bin/su", "/bin/su", "/usr/local/bin/su")

    /** A PATH that covers AOSP, Magisk, KernelSU and APatch layouts (one definition: RootExec). */
    const val ROOT_PATH = RootExec.ROOT_PATH

    /** Output cap for the model. Bigger than a screenful, small enough to stay in context. */
    const val MAX_CHARS = 8_000

    /** Longest a command may run. `logcat` and package installs need room; nothing should hang. */
    const val MAX_TIMEOUT_MS = 120_000L

    const val DEFAULT_TIMEOUT_MS = 20_000L

    /** Written by the probe when the root shell is still inside PRoot's trace. */
    private const val PROOT_MARKER = "proot"

    class Output(
        val exitCode: Int,
        val text: String,
        val timedOut: Boolean,
        val truncated: Boolean,
        /** Bytes the command produced, including those dropped by the character cap. */
        val totalBytes: Long,
        /** A sentence for the model when the call could not run at all; null otherwise. */
        val failure: String?,
        /** Where the full output went when it did not fit; null when [text] is all of it. */
        val spillPath: String? = null,
        /** Said so when [timeoutMs] had to be clamped; null when it was used as given. */
        val timeoutNote: String? = null,
    )

    /**
     * Runs [command] as root.
     *
     * The command goes to `su -c` as a single argv element, so nothing re-quotes it on the way:
     * whatever the model wrote is what the root shell sees. PATH is exported first because `su`
     * gives its shell a minimal environment where `ls`, `grep` and `pm` would be
     * command-not-found.
     */
    fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Output {
        val capped = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)
        val marker = try {
            File.createTempFile("dsh-rootprobe-", ".flag")
        } catch (e: Exception) {
            null
        }
        marker?.delete()

        // PATH first, the command verbatim, then a probe that costs nothing extra: it rides along
        // in the same su invocation, so the person is not prompted twice.
        val probe = marker?.let {
            "; { grep -qs libproot /proc/self/maps && echo $PROOT_MARKER > ${shellQuote(it.absolutePath)}; } 2>/dev/null || true"
        } ?: ""
        val script = "export PATH=$ROOT_PATH; $command$probe"

        var startFailure: String? = null
        try {
            for (candidate in candidates()) {
                val result = BoundedShell.run(
                    listOf(candidate, "-c", script),
                    timeoutMs = capped,
                    maxChars = MAX_CHARS,
                    cwd = "/",
                    env = mapOf("PATH" to ROOT_PATH, "HOME" to "/", "LANG" to "C.UTF-8")
                )

                // Only a start failure means "this candidate isn't usable". A su that started and
                // exited non-zero has already shown the person a prompt; retrying asks twice.
                if (result.exitCode == BoundedShell.EXIT_START_FAILED) {
                    startFailure = result.text.trim()
                    continue
                }

                val prootRoot = marker?.let { looksLikeProotRoot(it) } ?: false
                val failure = when {
                    result.timedOut ->
                        "命令超过 ${capped}ms 没结束，已终止。要跑更久的命令，加 timeoutMs（上限 $MAX_TIMEOUT_MS）。"
                    prootRoot ->
                        "这个 su 是 PRoot 里的假 root：uid 显示 0，但没有真正的 Android root 权限，" +
                            "命令没有实际效果。要做需要 root 的事，请用户在 root 管理器里给 DSH Go 授权，" +
                            "或者用 handoff 交给用户。"
                    result.exitCode != 0 && result.text.contains("Permission denied", ignoreCase = true) ->
                        "su 拒绝了这次调用：root 管理器里 DSH Go 的授权可能被撤销了。"
                    else -> null
                }
                // Say so when the caller's number wasn't the one used: a model that asked for
                // 500 ms and got 1 s should know why, instead of trusting a wrong deadline.
                val clamped = when {
                    timeoutMs > MAX_TIMEOUT_MS -> "[timeoutMs 超过了上限，按 ${MAX_TIMEOUT_MS}ms 跑]"
                    timeoutMs < 1_000L -> "[timeoutMs 太短，按 1000ms 跑]"
                    else -> null
                }
                return Output(result.exitCode, result.text, result.timedOut, result.truncated,
                    result.totalBytes, failure, result.spillPath, clamped)
            }
        } finally {
            marker?.delete()
        }

        return Output(
            BoundedShell.EXIT_START_FAILED, "", false, false, 0L,
            "这台手机上没有可用的 su" +
                (startFailure?.let { "（最后一次尝试：$it）" } ?: "") +
                "。没有 root，或 root 管理器没把 su 暴露出来。" +
                "需要 root 才能做的事，改用 handoff 交给用户。"
        )
    }

    /** Candidates that at least look runnable, then PATH, deduplicated. */
    private fun candidates(): List<String> {
        val out = LinkedHashSet<String>()
        for (path in SU_CANDIDATES) {
            val f = File(path)
            if (f.isFile && f.canExecute()) out.add(path)
        }
        // Some managers expose su only through the shell environment.
        runCatching {
            val p = RootSpawn.merged(listOf("sh", "-c", "command -v su"))
            val found = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor()
            // Absolute paths only, and never the guest distro's own su.
            if (found.startsWith("/") && found !in NOT_ANDROID_SU) out.add(found)
        }
        // Even when nothing looks runnable, the bare names are worth a try: the file check above
        // is unreliable exactly on the devices where this matters.
        if (out.isEmpty()) {
            out.addAll(SU_CANDIDATES.take(3))
            out.add("su")
        }
        return out.toList()
    }

    /**
     * True when the probe file says the root shell was still inside PRoot's trace.
     *
     * PRoot maps libproot into every tracee, and a real `su -c` leaves that trace, so a root
     * shell whose own maps mention libproot is not Android root.
     */
    private fun looksLikeProotRoot(marker: File): Boolean =
        runCatching { marker.readText().trim() == PROOT_MARKER }.getOrDefault(false)

    /** Single-quotes [value] for sh, escaping embedded quotes. */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /** The tool result the model sees. */
    fun describe(command: String, output: Output): String {
        output.failure?.let { return it }
        val body = if (output.text.isBlank()) "(没有输出)" else output.text.trimEnd()
        val notes = ArrayList<String>(3)
        if (output.truncated) {
            val where = output.spillPath
            notes.add(
                if (where != null) {
                    "[输出超过 $MAX_CHARS 字符，只保留开头部分；全文 ${output.totalBytes} 字节在 $where，" +
                        "用 shell_read 读它]"
                } else {
                    "[输出超过 $MAX_CHARS 字符，只保留开头部分]"
                }
            )
        }
        if (output.timedOut) notes.add("[超时终止]")
        output.timeoutNote?.let { notes.add(it) }
        val head = "$ $command\n[exit ${output.exitCode}]"
        return if (notes.isEmpty()) "$head\n$body"
        else "$head\n${notes.joinToString("\n")}\n$body"
    }
}
