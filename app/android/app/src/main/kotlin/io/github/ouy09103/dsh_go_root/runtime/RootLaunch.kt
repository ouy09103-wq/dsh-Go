package io.github.ouy09103.dsh_go_root.runtime

import io.github.ouy09103.dsh_go_root.root.RootEngine
import io.github.ouy09103.dsh_go_root.root.RootIdentity
import java.io.File

/**
 * Direct's plan, started through su: the same loader and the same rootfs, but the Node process
 * really is uid 0 instead of proot's imitation.
 *
 * Nothing about the plan's shape changes — only who owns the process. The script writes its own
 * pid before `exec` replaces the shell, so [RootEngine] can stop an engine the app itself cannot
 * read under /proc, and it re-exports the plan's environment because a root manager is free to
 * hand its shell a bare one.
 */
internal object RootLaunch {

    /** su existed but the launch did not survive; retried compatibly, like Direct. */
    const val FAILED = "rootFailed"

    /** proot's linker world must not leak into a real root child: its loader would re-enter it. */
    private val SKIP = setOf("LD_PRELOAD", "LD_LIBRARY_PATH")
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    data class Plan(val argv: List<String>, val environment: Map<String, String>, val cwd: File)

    fun prepare(rootfs: File, direct: DirectLaunch.Plan, su: String): Plan {
        val pid = DirectLaunch.quote(RootEngine.pidFile(rootfs).absolutePath)
        val script = buildString {
            direct.environment.forEach { (name, value) ->
                if (NAME.matches(name) && name !in SKIP && !name.startsWith("PROOT_")) {
                    append("export ").append(name).append('=').append(DirectLaunch.quote(value)).append('\n')
                }
            }
            // `$$` is this shell; `exec` keeps the pid, so the file names the engine itself.
            append("echo \$\$ > ").append(pid).append('\n')
            append("exec ")
            append(direct.argv.joinToString(" ") { DirectLaunch.quote(it) })
            append('\n')
        }
        require('\u0000' !in script) { "root engine: invalid launch script" }
        return Plan(RootIdentity.argv(su, script), direct.environment, direct.cwd)
    }
}
