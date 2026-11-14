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

    /** The first candidate the caller's check accepts, or null when this device has no su there. */
    fun firstVerified(verified: (String) -> Boolean): String? {
        for (path in PATHS) if (verified(path)) return path
        return null
    }
}