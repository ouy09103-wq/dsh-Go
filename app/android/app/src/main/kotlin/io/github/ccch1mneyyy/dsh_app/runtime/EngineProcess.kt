package io.github.ccch1mneyyy.dsh_app.runtime

/**
 * The engine process abstraction: whatever actually runs `dsh web`.
 *
 * Three implementations, one launch command template:
 *  - [DshProcess]      proot (default; fake uid 0, no root needed)
 *  - [DshProcess]      direct-glibc (opt-in; app uid, no proot)
 *  - [RootDshProcess]  root-chroot (su + chroot; **real** uid 0)
 *
 * The controller only sees this interface, so safe mode, crash classification, the foreground
 * service and the Flutter status all work the same whichever engine is up.
 */
interface EngineProcess {
    /** Native observation of the running process: `proot`, `direct` or `root`. */
    val executionMode: String?

    /** This attempt's diagnostics (selected mode, spawn, failure). */
    val launchDiagnostics: LaunchDiagnostics

    /** Set when the launch failed locally (not a DSH crash). */
    val startupError: String?

    /** Direct-glibc launch (see [DirectLaunch]). */
    val direct: Boolean

    /** The phone-control overlay got into the launch command. */
    val phoneMounted: Boolean

    /** The guest runs as real uid 0 through chroot. */
    val rootEngine: Boolean

    /**
     * [safe] boots the rescue profile instead of `web`; [phone] adds the phone overlay;
     * [onLaunchMethod] reports whether the launch ended up on the direct engine (always false for root).
     */
    fun start(
        guestEnv: Map<String, String>,
        binds: List<String> = emptyList(),
        safe: Boolean = false,
        phone: Boolean = false,
        directEngine: Boolean = false,
        directFailed: Boolean = false,
        onLaunchMethod: ((Boolean) -> Unit)? = null,
    )

    fun stop(expected: Boolean = true)
    fun awaitExit(millis: Long = 0): Boolean
    fun failStart(message: String)
    fun killStrays(): Boolean
}
