package io.github.ouy09103.dsh_go_root.runtime

import io.github.ouy09103.dsh_go_root.root.RootEngine
import io.github.ouy09103.dsh_go_root.root.RootIdentity
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Direct's plan, started through su inside `chroot <rootfs>`: the same loader and the same
 * rootfs, but the engine is uid 0 *and* confined to the guest.
 *
 * The confinement is the point, not a nicety. Without it a uid 0 engine resolves every absolute
 * path against the device: `/tmp`, `/var`, `/root/.dsh` became Android's own directories, and a
 * guest that believed it was tidying its own tree was tidying the device's — which is how one
 * launch destroyed the rootfs it was running from. Under `chroot` those names are the guest's
 * again, and the only device paths the engine can reach are the three mounted in deliberately.
 *
 * The mounts live in a private mount namespace on purpose. Mounting `/proc`, `/dev` and `/sys`
 * into the shared namespace would leave them on the device after the engine exits — a leftover
 * that outlives the app and can wedge the next start. When the device cannot give a private
 * namespace this launcher refuses to run; it never mounts into the shared one.
 *
 * The command lives in a script file rather than one nested `sh -c` string: three levels of
 * quoting through su is exactly where a wrong word becomes a wrong mount.
 */
internal object RootLaunch {

    /** su ran, but the device cannot give a private mount namespace. */
    const val NO_NAMESPACE = "rootNoNamespace"

    /** 78 is "config error" in sysexits, and distinct enough to name in a failure card. */
    const val EXIT_NO_NAMESPACE = 78
    const val EXIT_MOUNT_FAILED = 79

    /** proot's linker world must not leak into a real root child: its loader would re-enter it. */
    private val SKIP = setOf("LD_PRELOAD", "LD_LIBRARY_PATH", "PROOT_LOADER", "PROOT_L2S_DIR", "PROOT_TMP_DIR")
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * The only device paths the guest gets. `/proc` and `/dev` are required by Node itself and by
     * the pid file; `/sys` carries the affinity and thermal reads the engine makes. Nothing else
     * is mounted in — `/data` and `/system` stay out of reach, which is the point of chroot.
     */
    private val MOUNTS = listOf("/dev", "/proc", "/sys")

    data class Plan(val argv: List<String>, val environment: Map<String, String>, val cwd: File)

    /**
     * [direct] was prepared against device paths (`<rootfs>/opt/node/bin/node`); inside the
     * chroot those prefixes must go, or every path would name `/data/user/0/...` twice.
     */
    fun prepare(rootfs: File, direct: DirectLaunch.Plan, su: String, scriptFile: File): Plan {
        val root = rootfs.absolutePath
        fun guest(path: String) = path.removePrefix(root).ifEmpty { "/" }
        val quote = DirectLaunch::quote
        val guestEnv = direct.environment
            .filterKeys { NAME.matches(it) && it !in SKIP && !it.startsWith("PROOT_") }
            .mapValues { (_, value) -> guest(value) }
        // `exec sh` keeps one shell in the chain, so the pid file can be the engine's own.
        val inner = guestEnv.entries.joinToString(" ") { (name, value) -> "export $name=${quote(value)}" }
        val command = direct.argv.joinToString(" ") { quote(guest(it)) }
        val script = buildString {
            append("#!/system/bin/sh\n")
            append("set -e\n")
            append("R=").append(quote(root)).append('\n')
            // Refuse rather than mount into the shared namespace and leave /proc on the device.
            append("command -v unshare >/dev/null 2>&1 || { echo ")
            append(quote(NO_NAMESPACE)).append(" >&2; exit ").append(EXIT_NO_NAMESPACE).append("; }\n")
            append("exec unshare -m /system/bin/sh -c ").append(quote(buildString {
                // Stop these mounts from propagating back into the device's own mount tree.
                append("mount --make-rslave / 2>/dev/null || true; ")
                for (path in MOUNTS) {
                    append("mount --bind ").append(path).append(' ')
                    append(quote(root)).append(path).append(" || exit ").append(EXIT_MOUNT_FAILED).append("; ")
                }
                append("exec chroot ").append(quote(root)).append(" /bin/sh -c ")
                append(quote(
                    "export $inner; echo \$\$ > /tmp/dsh-root-engine.pid; exec $command",
                ))
            })).append('\n')
        }
        write(scriptFile, script)
        return Plan(RootIdentity.argv(su, "exec /system/bin/sh ${quote(scriptFile.absolutePath)}"), guestEnv, direct.cwd)
    }

    private fun write(file: File, text: String) {
        val dir = file.absoluteFile.parentFile
        check(dir.isDirectory || dir.mkdirs()) { "root engine: cannot create ${dir.path}" }
        val temp = File(dir, "${file.name}.tmp")
        temp.writeText(text)
        temp.setReadable(true, false)
        temp.setExecutable(true, false)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
