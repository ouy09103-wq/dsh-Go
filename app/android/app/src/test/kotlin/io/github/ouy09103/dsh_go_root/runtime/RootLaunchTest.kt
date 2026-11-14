package io.github.ouy09103.dsh_go_root.runtime

import java.io.File
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

    @Test fun theEngineRunsThroughSuWithItsPidWrittenFirst() {
        val plan = RootLaunch.prepare(File(rootfs), direct, su)
        assertEquals(listOf(su, "-c"), plan.argv.take(2))
        val script = plan.argv[2]
        assertTrue(script, script.contains("echo \$\$ > '$rootfs/tmp/dsh-root-engine.pid'"))
        assertTrue(script, script.contains("exec '${direct.argv.first()}'"))
        // The pid file is written before exec, so the launcher can stop a uid 0 engine it cannot read.
        assertTrue(script.indexOf("dsh-root-engine.pid") < script.indexOf("exec "))
    }

    @Test fun theEnvironmentIsReExportedBecauseAManagerMayHandSuABareOne() {
        val script = RootLaunch.prepare(File(rootfs), direct, su).argv[2]
        assertTrue(script, script.contains("export HOME='$rootfs/root'"))
        assertTrue(script, script.contains("export DSH_GO_RUNTIME_ROOT='$rootfs'"))
        assertTrue(script, script.contains("export TMPDIR='$rootfs/tmp'"))
    }

    @Test fun nothingFromTheProotWorldOrTheHostLinkerIsExported() {
        val script = RootLaunch.prepare(File(rootfs), direct, su).argv[2]
        assertFalse(script, script.contains("LD_PRELOAD"))
        assertFalse(script, script.contains("LD_LIBRARY_PATH"))
        assertFalse(script, script.contains("PROOT_TMP_DIR"))
        assertFalse(script, script.contains("odd name"))
    }

    @Test fun everyWordIsQuotedSoAnApostropheCannotBreakOut() {
        val plan = RootLaunch.prepare(File(rootfs), direct.copy(environment = mapOf("HOME" to "/root/it's")), su)
        assertTrue(plan.argv[2], plan.argv[2].contains("export HOME='/root/it'\\''s'"))
    }

    @Test fun theSpawnEnvironmentIsThePlansOwn() {
        val plan = RootLaunch.prepare(File(rootfs), direct, su)
        assertEquals(direct.environment, plan.environment)
        assertEquals(File("$rootfs/root"), plan.cwd)
    }
}
