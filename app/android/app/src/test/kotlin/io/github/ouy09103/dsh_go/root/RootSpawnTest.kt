package io.github.ouy09103.dsh_go.root

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one thing that went wrong in the root launcher and cost a working build: clearing the
 * inherited environment.
 *
 * `su -c "id -u"` needs the environment it inherits to find `id`; when the pump cleared it, every
 * root command came back as `/system/bin/sh: id: inaccessible or not found`. These tests pin the
 * three launchers' environment behaviour on the build machine, where `sh` and `env` exist.
 */
class RootSpawnTest {

    private fun read(process: Process): String =
        process.inputStream.bufferedReader().use { it.readText() }.trim()

    @Test fun pipedKeepsTheInheritedEnvironment() {
        // The regression: an empty override map must not empty the environment.
        val process = RootSpawn.piped(listOf("sh", "-c", "echo \$PATH"), env = emptyMap())
        process.waitFor(10, TimeUnit.SECONDS)
        val path = read(process)
        assertFalse("the inherited PATH must survive", path.isBlank())
        assertTrue("PATH should name system bin dirs, got: $path", path.contains("/bin") || path.contains("/usr"))
    }

    @Test fun pipedAddsOverridesWithoutDroppingTheRest() {
        val process = RootSpawn.piped(listOf("sh", "-c", "echo \$DSH_PROBE-\$PATH"), env = mapOf("DSH_PROBE" to "x"))
        process.waitFor(10, TimeUnit.SECONDS)
        val out = read(process)
        assertTrue("the added variable must be there: $out", out.startsWith("x-"))
        assertTrue("the inherited PATH must still be there: $out", out.length > 2)
    }

    @Test fun pipedStartsFromAnExplicitEnvironmentWhenOneIsGiven() {
        // toFile is the bounded shell's launcher: it spells out everything and wants nothing else.
        val out = File.createTempFile("dsh-rootspawn-", ".out")
        try {
            val process = RootSpawn.toFile(
                listOf("sh", "-c", "echo \$PATH; echo \$DSH_NOPE"),
                out,
                env = mapOf("PATH" to "/only/this"),
            )
            process.waitFor(10, TimeUnit.SECONDS)
            val text = out.readText().trim().lines()
            assertEquals("/only/this", text.firstOrNull())
            assertEquals("", text.getOrElse(1) { "" })
        } finally {
            out.delete()
        }
    }

    @Test fun mergedJoinsBothStreamsAndKeepsTheEnvironment() {
        val process = RootSpawn.merged(listOf("sh", "-c", "echo out; echo err >&2; echo \$PATH | head -c 1"))
        process.waitFor(10, TimeUnit.SECONDS)
        val text = read(process)
        assertTrue("stderr must be joined into stdout: $text", text.contains("err"))
        assertTrue("stdout must be there: $text", text.contains("out"))
        // The third line prints PATH's first character: evidence the environment survived.
        assertTrue("PATH must still be set: $text", text.lines().size >= 3)
    }

    @Test fun mergedTakesStdinFromDevNull() {
        // An interactive child waiting on a pipe that never closes would sit until its timeout;
        // `cat` reading /dev/null must see EOF at once instead of hanging.
        val process = RootSpawn.merged(listOf("cat"))
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("cat should have seen EOF", finished)
    }

    @Test fun rootPathCoversEveryManager() {
        val path = RootExec.ROOT_PATH.split(":")
        for (dir in listOf("/system/bin", "/data/adb/magisk", "/data/adb/ksu/bin", "/data/adb/ap/bin")) {
            assertTrue("$dir missing from ROOT_PATH", dir in path)
        }
        // One definition: the tool's shell and the probe must not drift apart.
        assertEquals(RootExec.ROOT_PATH, io.github.ouy09103.dsh_go.phone.background.RootShell.ROOT_PATH)
    }
}
