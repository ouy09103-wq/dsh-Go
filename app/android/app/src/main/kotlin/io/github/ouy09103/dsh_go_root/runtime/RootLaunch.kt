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
 * Three small scripts instead of one nested `sh -c` string. Quoting a shell command that is
 * itself a shell command, through su, is where a single wrong quote turns into a wrong mount;
 * each script here is one level deep and readable on its own.
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

    private const val LAUNCH_SCRIPT = "dsh-root-engine.sh"
    private const val MOUNT_SCRIPT = "dsh-root-engine-mount.sh"

    /** Where the guest-side command script lives, as the guest sees it. */
    private const val GUEST_COMMAND = "/tmp/dsh-root-engine-cmd.sh"

    data class Plan(val argv: List<String>, val environment: Map<String, String>, val cwd: File)

    /**
     * [direct] was prepared against device paths (`<rootfs>/opt/node/bin/node`); inside the
     * chroot those prefixes must go, or every path would name `/data/user/0/...` twice.
     */
    fun prepare(rootfs: File, direct: DirectLaunch.Plan, su: String, dir: File): Plan {
        val root = rootfs.absolutePath
        /**
         * A path under the runtime is the same path in the guest without the prefix; everything
         * else (a flag, a bare word, a path outside the runtime) must be passed through as it is.
         * Blanket-prefix-stripping turned `--host 127.0.0.1` into `/`, which no shell could run.
         */
        fun guest(value: String): String = when {
            value == root -> "/"
            value.startsWith("$root/") -> value.removePrefix(root)
            // `--import=<root>/…`: the flag and its path arrive as one word.
            value.startsWith("--") && value.substringAfter('=', "").startsWith("$root/") ->
                value.substringBefore('=') + "=" + value.substringAfter('=').removePrefix(root)
            else -> value
        }
        val quote = DirectLaunch::quote

        val guestEnv = direct.environment
            .filterKeys { NAME.matches(it) && it !in SKIP && !it.startsWith("PROOT_") }
            .mapValues { (_, value) -> guest(value) }
        // The guest script: plain exports, then the engine. One quoting level, no nesting.
        val command = buildString {
            append("#!/bin/sh\n")
            append("set -e\n")
            guestEnv.forEach { (name, value) -> append("export ").append(name).append('=').append(quote(value)).append('\n') }
            // `$$` here is the shell that the chroot's /bin/sh forked for this script.
            append("echo \$\$ > /tmp/dsh-root-engine.pid\n")
            append("exec ").append(direct.argv.joinToString(" ") { quote(guest(it)) }).append('\n')
        }
        val guestScript = File(rootfs, GUEST_COMMAND.removePrefix("/"))

        // Runs inside the private namespace: bind the three device trees, then enter the guest.
        val mounts = buildString {
            append("#!/system/bin/sh\n")
            append("set -e\n")
            append("R=").append(quote(root)).append('\n')
            // Never let these mounts propagate back into the device's own mount tree.
            append("mount --make-rslave / 2>/dev/null || true\n")
            for (path in MOUNTS) {
                append("mount --bind ").append(path).append(" \"\$R").append(path).append('"')
                append(" || exit ").append(EXIT_MOUNT_FAILED).append('\n')
            }
            append("exec chroot \"\$R\" /bin/sh ").append(quote(GUEST_COMMAND)).append('\n')
        }

        // Started by su: refuse a device without unshare instead of mounting into the shared tree.
        val launcher = buildString {
            append("#!/system/bin/sh\n")
            append("command -v unshare >/dev/null 2>&1 || { echo ")
            append(quote(NO_NAMESPACE)).append(" >&2; exit ").append(EXIT_NO_NAMESPACE).append("; }\n")
            append("exec unshare -m /system/bin/sh ").append(quote(File(dir, MOUNT_SCRIPT).absolutePath)).append('\n')
        }

        write(File(dir, MOUNT_SCRIPT), mounts)
        write(File(dir, LAUNCH_SCRIPT), launcher)
        // Written last: the launcher is useless without the other two.
        write(guestScript, command)
        return Plan(RootIdentity.argv(su, "exec /system/bin/sh ${quote(File(dir, LAUNCH_SCRIPT).absolutePath)}"), guestEnv, direct.cwd)
    }

    private fun write(file: File, text: String) {
        val dir = file.absoluteFile.parentFile
        check(dir.isDirectory || dir.mkdirs()) { "root engine: cannot create ${dir.path}" }
        val temp = File(dir, "${file.name}.tmp")
        temp.writeText(text)
        // su runs as uid 0, so every one of these must be readable by anyone, and runnable.
        temp.setReadable(true, false)
        temp.setExecutable(true, false)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
