package io.github.ouy09103.dsh_go_root.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootLaunchTest {
    private val su = "/system/bin/su"

    /** A throwaway rootfs: the guest script is written into it, so the real one must not be used. */
    private fun freshRootfs(): File = Files.createTempDirectory("rootfs").toFile().apply { deleteOnExit() }

    private fun freshDir(): File = Files.createTempDirectory("rootlaunch").toFile().apply { deleteOnExit() }

    private fun direct(rootfs: String) = DirectLaunch.Plan(
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

    private class Prepared(val plan: RootLaunch.Plan, val rootfs: File, val dir: File) {
        fun launcher() = File(dir, "dsh-root-engine.sh").readText()
        fun mounts() = File(dir, "dsh-root-engine-mount.sh").readText()
        fun guest() = File(rootfs, "tmp/dsh-root-engine-cmd.sh").readText()
    }

    private fun prepare(env: Map<String, String>? = null): Prepared {
        val rootfs = freshRootfs()
        val dir = freshDir()
        val base = direct(rootfs.absolutePath)
        val plan = RootLaunch.prepare(rootfs, if (env == null) base else base.copy(environment = env), su, dir)
        return Prepared(plan, rootfs, dir)
    }

    @Test fun theEngineRunsUnderSuThroughTheLauncher() {
        val p = prepare()
        assertEquals(listOf(su, "-c"), p.plan.argv.take(2))
        assertTrue(p.plan.argv[2], p.plan.argv[2].contains("dsh-root-engine.sh"))
        assertTrue("the launcher is written", File(p.dir, "dsh-root-engine.sh").isFile)
        assertTrue("and is executable", File(p.dir, "dsh-root-engine.sh").canExecute())
    }

    @Test fun suIsHandedAPathNotANestedShellProgram() {
        // One quoting level through su: the argument names a file, it is not a program.
        val inner = prepare().plan.argv[2]
        assertFalse("no shell program in the su argument", inner.contains("unshare"))
        assertFalse("no shell program in the su argument", inner.contains("chroot"))
        assertFalse("no shell program in the su argument", inner.contains("mount"))
    }

    @Test fun theLauncherRefusesADeviceWithoutUnshare() {
        val text = prepare().launcher()
        assertTrue(text, text.contains(RootLaunch.NO_NAMESPACE))
        assertTrue(text, text.contains("exit ${RootLaunch.EXIT_NO_NAMESPACE}"))
        assertTrue(text, text.contains("command -v unshare"))
        // It refuses before anything is mounted, so a shared tree is never touched.
        assertFalse("the launcher mounts nothing itself", text.contains("mount --bind"))
    }

    @Test fun theLauncherRunsTheMountScriptInsideAPrivateNamespace() {
        val text = prepare().launcher()
        assertTrue(text, text.contains("unshare -m /system/bin/sh"))
        assertTrue(text, text.contains("dsh-root-engine-mount.sh"))
        assertTrue("the namespace wraps the mount script", text.indexOf("unshare -m") < text.indexOf("mount.sh"))
    }

    @Test fun everyMountHappensInAPrivateNamespace() {
        val text = prepare().mounts()
        assertEquals("exactly the three device trees", 3, text.lines().count { it.trimStart().startsWith("mount --bind") })
        assertTrue("no propagation back to the device", text.contains("mount --make-rslave /"))
        for (path in listOf("/dev", "/proc", "/sys")) {
            assertTrue("$path is bound", text.contains("mount --bind $path "))
        }
    }

    @Test fun nothingOutsideTheThreeTreesIsMountedIn() {
        val text = prepare().mounts()
        assertFalse("/data stays out of reach", text.contains("mount --bind /data"))
        assertFalse("/system stays out of reach", text.contains("mount --bind /system"))
        assertFalse("/apex stays out of reach", text.contains("mount --bind /apex"))
    }

    @Test fun aFailedMountIsNotSilentlyIgnored() {
        assertTrue(prepare().mounts(), prepare().mounts().contains("|| exit ${RootLaunch.EXIT_MOUNT_FAILED}"))
    }

    @Test fun flagsAndBareWordsAreNotRewritten() {
        // A blanket prefix-strip once turned `--host 127.0.0.1` into `/`, which nothing can run.
        val guest = prepare().guest()
        assertTrue(guest, guest.contains("'--library-path'"))
        assertTrue(guest, guest.contains("'--no-open'"))
        assertTrue(guest, guest.contains("'--host' '127.0.0.1'"))
        assertTrue(guest, guest.contains("'--port' '3080'"))
        assertTrue(guest, guest.contains("'web'"))
        assertFalse("no bare argument became a root path", guest.contains("'/' '/'"))
    }

    @Test fun aFlagCarryingAPathIsRewrittenWithItsFlag() {
        val guest = prepare().guest()
        assertTrue(guest, guest.contains("'--import=/opt/dsh-go/direct/preload.mjs'"))
    }

    @Test fun theGuestIsEnteredWithItsOwnPaths() {
        val p = prepare()
        val text = p.mounts()
        assertTrue(text, text.contains("chroot \"\$R\" /bin/sh '/tmp/dsh-root-engine-cmd.sh'"))
        val guest = p.guest()
        assertFalse("no device path survives into the guest", guest.contains(p.rootfs.absolutePath))
        assertTrue(guest, guest.contains("'/opt/node/bin/node'"))
        assertTrue(guest, guest.contains("'/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1'"))
        assertTrue(guest, guest.contains("'/opt/dsh-go/direct/preload.mjs'"))
    }

    @Test fun thePidFileNamesTheEngineItself() {
        val guest = prepare().guest()
        assertTrue(guest, guest.contains("echo \$\$ > /tmp/dsh-root-engine.pid"))
        assertTrue("the pid is written before exec replaces the shell", guest.indexOf("dsh-root-engine.pid") < guest.indexOf("exec "))
    }

    @Test fun theEnvironmentIsReExportedWithGuestPaths() {
        val p = prepare()
        assertFalse("the child never sees a device path", p.plan.environment.values.any { it.startsWith(p.rootfs.absolutePath) })
        assertEquals("/root", p.plan.environment["HOME"])
        assertEquals("/tmp", p.plan.environment["TMPDIR"])
        assertEquals("/", p.plan.environment["DSH_GO_RUNTIME_ROOT"])
        assertEquals("/opt/dsh-app/direct/bin:/usr/bin:/bin", p.plan.environment["PATH"])
        assertTrue(p.guest(), p.guest().contains("export HOME='/root'"))
    }

    @Test fun nothingFromTheProotWorldIsExported() {
        val p = prepare()
        assertFalse(p.plan.environment.containsKey("LD_PRELOAD"))
        assertFalse(p.plan.environment.containsKey("LD_LIBRARY_PATH"))
        assertFalse(p.plan.environment.containsKey("PROOT_TMP_DIR"))
        assertFalse(p.guest(), p.guest().contains("LD_PRELOAD"))
        assertFalse(p.guest(), p.guest().contains("PROOT_TMP_DIR"))
        assertFalse(p.guest(), p.guest().contains("odd name"))
    }

    @Test fun anApostropheInAValueCannotBreakOutOfTheGuestScript() {
        val rootfs = freshRootfs().absolutePath
        val p = prepare(mapOf("HOME" to "$rootfs/root/it's"))
        assertTrue(p.guest(), p.guest().contains("export HOME='/root/it'\\''s'"))
    }
}
