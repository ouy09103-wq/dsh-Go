package io.github.ouy09103.dsh_go_root.root

/**
 * What the kernel says about one su child, shared by every root entry point: the Android
 * Shell root channel and the engine's root launch.
 *
 * `id` is not consulted. A guest under proot answers `id` for its faked uid, and a shell that
 * was handed the app identity answers it just as confidently. The `Uid:` row of
 * /proc/self/status and /proc/self/attr/current are the kernel's own words instead, and a uid 0
 * child still sitting in an app SELinux domain has no power over the files it claims to own.
 */
internal object RootIdentity {

    /** [failure] is null only when the child is really uid 0 outside an app domain. */
    data class Result(val failure: String?, val uid: Int, val domain: String) {
        val granted: Boolean get() = failure == null
    }

    const val SU_MISSING = "suMissing"
    const val SU_NOT_EXECUTABLE = "suNotExecutable"
    const val SU_FAILED = "rootSuFailed"
    const val NOT_GRANTED = "rootNotGranted"
    const val NOT_ROOT = "rootNotRoot"
    const val DOMAIN_UNKNOWN = "rootDomainUnknown"
    const val WRONG_DOMAIN = "rootWrongDomain"

    const val DEFAULT_TIMEOUT_MS = 20_000L

    /** Splits the status dump from the domain read, so neither is guessed from the other. */
    private const val MARK = "DSH_IDENTITY_DOMAIN"

    private const val START_FAILED = -1
    private const val TIMEOUT = "timeout"
    private const val NO_SU = "no su on this device"
    private const val UNTRUSTED = "untrusted_app"
    private val DENIED = listOf("permission denied", "not granted", "not allowed", "denied")
    private val UID = Regex("^Uid:\\s*(\\d+)", RegexOption.MULTILINE)

    /**
     * Both facts in one su call, separated by a marker. `head` and `cat` only: a toybox without
     * `grep -m` must not turn a granted su into a failure. The trailing `true` keeps a failing
     * read from masking a uid that was already printed.
     */
    private const val READ_IDENTITY =
        "head -n 20 /proc/self/status 2>/dev/null; echo ${MARK}; cat /proc/self/attr/current 2>/dev/null; true"

    /** Asks su once. Each call is one authorization prompt, so callers cache deliberately. */
    fun probe(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        if (RootExec.suPath() == null) return Result(SU_MISSING, -1, "")
        val run = RootExec.run(READ_IDENTITY, timeoutMs)
        return parse(run.stdout, run.stderr, run.exitCode)
    }

    /**
     * Reads one su answer. [exitCode] is [START_FAILED] when su never produced a status, which
     * keeps the launch failures apart from a manager that refused.
     */
    fun parse(stdout: String, stderr: String, exitCode: Int): Result {
        if (exitCode == START_FAILED) {
            // No su at all, a su that never answers, and a su the kernel refuses to execute
            // (a stale /system/bin/su entry) are three different fixes.
            val text = stderr.trim()
            val failure = when {
                text.contains(NO_SU) -> SU_MISSING
                text == TIMEOUT -> SU_FAILED
                else -> SU_NOT_EXECUTABLE
            }
            return Result(failure, -1, "")
        }
        val status = stdout.substringBefore(MARK, stdout)
        val uid = UID.find(status)?.groupValues?.get(1)?.toIntOrNull()
        if (uid == null) {
            // su answered, but not with a kernel-readable identity: either the manager said no
            // or the child died before /proc/self could be read.
            val text = (stdout + " " + stderr).lowercase()
            return Result(if (DENIED.any { text.contains(it) }) NOT_GRANTED else SU_FAILED, -1, "")
        }
        // A su that answers with the app uid never reached the root domain: never call that root.
        if (uid != 0) return Result(NOT_ROOT, uid, "")
        val domain = stdout.substringAfter(MARK, "").lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (domain.isBlank()) return Result(DOMAIN_UNKNOWN, 0, "")
        if (domain.contains(UNTRUSTED)) return Result(WRONG_DOMAIN, 0, domain)
        return Result(null, 0, domain)
    }

    /** su as one argv: the inner string is a shell script, so callers quote their own words. */
    fun argv(su: String, inner: String): List<String> = listOf(su, "-c", inner)

    /** For logs and diagnostics; never claims root that the kernel did not confirm. */
    fun describe(result: Result): String = when (result.failure) {
        null -> "uid=0 · ${result.domain}"
        NOT_ROOT -> "uid=${result.uid}（非 root）"
        else -> result.failure.orEmpty()
    }
}
