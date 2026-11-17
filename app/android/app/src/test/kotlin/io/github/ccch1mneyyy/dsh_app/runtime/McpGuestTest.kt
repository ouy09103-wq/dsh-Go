package io.github.ccch1mneyyy.dsh_app.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpGuestTest {
    private val launch = "echo $$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --patch /etc/dsh-app/dsh-web.patch.yml --host 127.0.0.1 --port 3080 --no-open"

    private val servers = listOf(
        McpGuest.Server(name = "docs", url = "https://mcp.example.com/v1", token = "tok-1", enabled = true),
        McpGuest.Server(name = "off", url = "https://x.example.com", token = "", enabled = false),
    )

    @Test fun addsTheOverlayRightAfterTheAppsOwnPatch() {
        assertEquals(
            "echo $$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --patch /etc/dsh-app/dsh-web.patch.yml " +
                "--patch /opt/dsh-app/mcp.patch.yml --host 127.0.0.1 --port 3080 --no-open",
            McpGuest.launchArg(launch),
        )
    }

    @Test fun chainsAfterTheOtherOverlays() {
        val searched = SessionSearchGuest.launchArg(launch)!!
        val both = McpGuest.launchArg(searched)!!
        assertTrue(both.contains("--patch /opt/dsh-app/search.patch.yml"))
        assertTrue(both.contains("--patch /opt/dsh-app/mcp.patch.yml"))
        assertEquals(1, Regex("mcp\\.patch\\.yml").findAll(both).count())
    }

    @Test fun leavesOtherArgumentsAndSafeModeAlone() {
        assertNull(McpGuest.launchArg("/usr/bin/env"))
        assertNull(McpGuest.launchArg("exec /usr/local/bin/dsh web --profile safe --port 1"))
    }

    @Test fun overlayListsOneEnabledServerAsStreamableHttp() {
        val yaml = McpGuest.patchYaml(servers)!!
        assertTrue(yaml.contains("serverName: docs"))
        assertTrue(yaml.contains("transport: streamable-http"))
        assertTrue(yaml.contains("url: https://mcp.example.com/v1"))
        assertTrue(yaml.contains("Authorization: Bearer tok-1"))
        assertTrue(yaml.contains("failOnStartupError: false"))
        // disabled servers stay out
        assertFalse(yaml.contains("off"))
    }

    @Test fun noServersMeansNoOverlay() {
        assertNull(McpGuest.patchYaml(emptyList()))
        assertNull(McpGuest.patchYaml(listOf(servers[1])))
        assertFalse(McpGuest.writeFiles(Files.createTempDirectory("mcp-guest").toFile(), listOf(servers[1])))
    }

    @Test fun invalidNamesAreDropped() {
        assertFalse(McpGuest.validName(""))
        assertFalse(McpGuest.validName("bad name"))
        assertFalse(McpGuest.validName("a".repeat(33)))
        assertTrue(McpGuest.validName("docs"))
        assertTrue(McpGuest.validName("a".repeat(32)))
        assertTrue(McpGuest.validName("A-z_09"))
    }

    @Test fun serverListRoundTripsThroughJson() {
        val json = McpGuest.serializeServers(servers)
        assertEquals(servers, McpGuest.parseServers(json))
        assertEquals(emptyList<McpGuest.Server>(), McpGuest.parseServers("not json"))
        assertEquals(emptyList<McpGuest.Server>(), McpGuest.parseServers(""))
        assertEquals(emptyList<McpGuest.Server>(), McpGuest.parseServers("{}"))
    }

    @Test fun writeFilesCreatesTheOverlayAndRewritesAStaleOne() {
        val root = Files.createTempDirectory("mcp-guest").toFile()
        try {
            assertTrue(McpGuest.writeFiles(root, servers))
            val file = McpGuest.file(root)
            assertEquals(McpGuest.patchYaml(servers), file.readText())
            file.writeText("stale")
            assertTrue(McpGuest.writeFiles(root, servers))
            assertEquals(McpGuest.patchYaml(servers), file.readText())
            assertFalse(root.resolve("opt/dsh-app").listFiles()!!.any { it.name.endsWith(".tmp") })
            // switching to nothing removes the overlay so a later start doesn't mount it
            assertTrue(McpGuest.writeFiles(root, listOf(servers[1])))
            assertFalse(McpGuest.file(root).isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
