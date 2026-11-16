package io.github.ouy09103.dsh_go_root.root

/**
 * Fixed places a root manager may install su, in priority order — taken from DSHA v0.2.2's
 * `RootShellCandidates`.
 *
 * Discovery never invokes su and never trusts `PATH`: a manager on `PATH` can be replaced by a
 * file in the app's own writable directories. The caller still has to prove that the file is
 * root-owned, a regular file, not group/other-writable and executable; [firstVerified] only
 * fixes the order.
 */
internal object SuCandidates {

    val PATHS: List<String> = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/su/bin/su",
        "/data/adb/magisk/su",
        "/vendor/bin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
    )

    /**
     * The first candidate the caller's check accepts, fixed places first.
     *
     * Some managers expose su only through the shell environment (a custom Magisk mount, a
     * KernelSU build without /system/bin/su). Scanning PATH is the last resort, and only after
     * every fixed path failed: a PATH entry is the easier one to plant, so the caller's
     * root-owned, non-writable check still has to pass for it — this only widens where we look,
     * it does not weaken what counts as su.
     */
    fun firstVerified(verified: (String) -> Boolean): String? {
        for (path in PATHS) if (verified(path)) return path
        for (dir in pathDirs()) {
            val candidate = if (dir.endsWith("/")) "$dir" + "su" else "$dir/su"
            if (candidate !in PATHS && verified(candidate)) return candidate
        }
        return null
    }

    /**
     * Directories whose `su` belongs to a guest Linux distro, not to Android: under PRoot it
     * answers uid 0 from the emulated root, with none of the power the model asked for.
     */
    private val GUEST_SU_DIRS = setOf("/usr/bin", "/bin", "/usr/local/bin")

    /** PATH directories, absolute only (a relative entry means "wherever cwd is"). */
    private fun pathDirs(): List<String> =
        System.getenv("PATH").orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.startsWith("/") && '\u0000' !in it && it !in GUEST_SU_DIRS }
            .distinct()
}
