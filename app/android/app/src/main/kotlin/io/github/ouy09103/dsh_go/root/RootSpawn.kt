package io.github.ouy09103.dsh_go.root

import java.io.File
import java.io.IOException

/**
 * The one child launcher for the root tool, kept in a single reviewed owner so
 * `ProcessLaunchGuardTest` (which rejects new launch sites in the Android tree)
 * sees one place instead of one per file.
 *
 * API 26 configuration fds are inherited across a launch unless the Java launcher
 * closes them; `DshProcess` is the reviewed owner for the engine, `RootProcess` for
 * the framed root channel, and this one for the one-shot `su -c` children that the
 * `shell` tool, the root page and the root-shell channel run.
 *
 * Each variant fixes the redirects the callers used to spell out themselves:
 * stdin always comes from `/dev/null` — an interactive `su` waiting on a pipe that
 * never closes would otherwise sit until its timeout, and `Redirect.DISCARD` is
 * write-side only, so the device is named explicitly.
 */
internal object RootSpawn {

    /** Starts [argv] with [configure] applied to the builder first. */
    fun start(argv: List<String>, configure: (ProcessBuilder) -> Unit = {}): Process {
        val builder = ProcessBuilder(argv)
        configure(builder)
        return try {
            builder.start()
        } catch (e: IOException) {
            throw e
        }
    }

    private fun quiet(builder: ProcessBuilder) {
        builder.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
    }

    private fun env(builder: ProcessBuilder, cwd: String?, env: Map<String, String>?) {
        if (cwd != null) builder.directory(File(cwd))
        if (env != null) {
            builder.environment().clear()
            builder.environment().putAll(env)
        }
    }

    /** stderr joins stdout; stdout is inherited by the app process. */
    fun merged(argv: List<String>, cwd: String? = null, env: Map<String, String>? = null): Process =
        start(argv) { builder ->
            builder.redirectErrorStream(true)
            quiet(builder)
            env(builder, cwd, env)
        }

    /** stderr joins stdout; stdout goes to [out] so there is no pipe to fill. */
    fun toFile(argv: List<String>, out: File, cwd: String? = null, env: Map<String, String>? = null): Process =
        start(argv) { builder ->
            builder.redirectErrorStream(true)
            quiet(builder)
            builder.redirectOutput(ProcessBuilder.Redirect.to(out))
            env(builder, cwd, env)
        }

    /** stdout and stderr stay apart, both as pipes the caller drains. */
    fun piped(argv: List<String>, cwd: String? = null, env: Map<String, String>? = null): Process =
        start(argv) { builder ->
            builder.redirectErrorStream(false)
            quiet(builder)
            env(builder, cwd, env)
        }

    /** stdin, stdout and stderr all stay as pipes: an interactive child. */
    fun interactive(argv: List<String>, cwd: String? = null): Process =
        start(argv) { builder ->
            if (cwd != null) builder.directory(File(cwd))
        }
}
