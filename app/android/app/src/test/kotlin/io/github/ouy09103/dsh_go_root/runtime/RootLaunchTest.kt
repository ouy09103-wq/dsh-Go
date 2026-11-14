package io.github.ouy09103.dsh_go_root.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootLaunchTest {
    private val rootfs = "/data/user/0/io.github.ouy09103.dsh_go_root/files/runtime/rootfs"
    private val su = "/system/bin/su"
    private val direct = DirectLaunch.Plan(
        argv = listOf(
            "$rootfs/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            "--library-path", "$rootfs/usr/lib/aarch64-linux-gnu",
            "$rootfs/opt/node/bin/node",
            "--import=$rootfs/opt/dsh-go/direct/preload.mjs",
            "$rootfs/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js",
            "web", "--no-open", "--host", "127.0.0.1", "--port", "3080",
        ),
        environment = linkedMapOf(
            "HOME" to "$rootfs/root",
            "PATH" to "$rootfs/opt/dsh-app/direct/bin:/usr/bin:/bin",
            "DSH_GO_RUNTIME_ROOT" to rootfs,
            "LD_PRELOAD" to "/data/app/x/lib/arm64/libsomething.so",
            "LD_LIBRARY_PATH" to "/data/app/x/lib/arm64",
            "PROOT_TMP_DIR" to "/data/user/0/x/cache/proot-tmp",
            "TMPDIR" to "$rootfs/tmp",
            "odd name" to "value",
        ),
        cwd = File("$rootfs/root"),
    )

    private fun plan(script: File) = RootLaunch.prepare(File(rootfs), direct, su, script)

    private fun freshScript(): File {
        val dir = Files.createTempDirectory("rootlaunch").toFile()
        dir.deleteOnExit()
        return File(dir, "dsh-root-engine.sh")
    }

    @Test fun theEngineRunsUnderSuThroughTheScript() {
        val script = freshScript()
        val plan = plan(script)
        assertEquals(listOf(su, "-c"), plan.argv.take(2))
        assertTrue(plan.argv[2], plan.argv[2].contains("exec /system/bin/sh"))
        assertTrue(plan.argv[2], plan.argv[2].contains(script.absolutePath))
        assertTrue("the script is written", script.isFile)
        assertTrue("and is executable", script.canExecute())
    }

    @Test fun theEngineOnlyEverMountsInsideAPrivateNamespace() {
        val script = freshScript()
        plan(script)
        val text = script.readText()
        // Mounting in the shared namespace would leave /proc, /dev and /sys on the device.
        assertTrue(text, text.contains("unshare -m"))
        assertEquals(
            "every mount happens after the namespace exists",
            1,
            text.lines().count { it.contains("mount --bind") } - 2,
        )
        assertTrue(text, text.indexOf("unshare -m") < text.indexOf("mount --bind"))
        assertTrue("no propagation back to the device", text.contains("mount --make-rslave /"))
    }

    @Test fun aDeviceWithoutUnshareIsRefusedRatherThanPolluted() {
        val script = freshScript()
        plan(script)
        val text = script.readText()
        assertTrue(text, text.contains(RootLaunch.NO_NAMESPACE))
        assertTrue(text, text.contains("exit ${RootLaunch.EXIT_NO_NAMESPACE}"))
        assertTrue("the refusal comes before any mount", text.indexOf("exit 78") < text.indexOf("mount --bind"))
    }

    @Test fun everyGuestPathIsRewrittenRelativeToTheChroot() {
        val script = freshScript()
        plan(script)
        val text = script.readText()
        val exec = text.substringAfter("exec chroot")
        assertFalse("no device path survives into the guest", exec.contains(rootfs))
        assertTrue(exec, exec.contains("'/opt/node/bin/node'"))
        assertTrue(exec, exec.contains("'/opt/dsh-go/direct/preload.mjs'"))
        assertTrue(exec, exec.contains("'/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1'"))
    }

    @Test fun theEnvironmentIsReExportedWithGuestPaths() {
        val script = freshScript()
        val plan = plan(script)
        val text = script.readText()
        assertTrue(text, text.contains("export HOME='/root'"))
        assertTrue(text, text.contains("export TMPDIR='/tmp'"))
        assertTrue(text, text.contains("export DSH_GO_RUNTIME_ROOT='/'"))
        assertFalse("the child never sees a device path", plan.environment.values.any { it.startsWith(rootfs) })
    }

    @Test fun nothingFromTheProotWorldIsExported() {
        val script = freshScript()
        plan(script)
        val text = script.readText()
        assertFalse(text, text.contains("LD_PRELOAD"))
        assertFalse(text, text.contains("LD_LIBRARY_PATH"))
        assertFalse(text, text.contains("PROOT_TMP_DIR"))
        assertFalse(text, text.contains("odd name"))
    }

    @Test fun thePidFileIsWrittenInsideTheGuest() {
        val script = freshScript()
        plan(script)
        val text = script.readText()
        assertTrue(text, text.contains("echo \$\$ > /tmp/dsh-root-engine.pid"))
    }

    @Test fun anApostropheInAPathCannotBreakOutOfTheScript() {
        val script = freshScript()
        val tricky = direct.copy(environment = mapOf("HOME" to "/root/it's"))
        RootLaunch.prepare(File(rootfs), tricky, su, script)
        assertTrue(script.readText(), script.readText().contains("'/root/it'\\''s'"))
    }
}
