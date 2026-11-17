package io.github.ouy09103.dsh_go_root.runtime

import io.github.ouy09103.dsh_go_root.phone.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * External MCP bridge (外部 MCP 对接), written into the rootfs by the app at every engine
 * start (no rootfs rebuild), modeled on [SpeechGuest] and [PhoneGuest]:
 *
 *  - `/opt/dsh-app/mcp.patch.yml`: an extra `--patch` overlay inserting one
 *    `@deepseek-ai/dsh-mcp-client` instance per configured server (stdio or Streamable HTTP),
 *    so the model sees `mcp__<serverName>__<tool>`. Verified on DSH 0.2.1-rc.2:
 *    `dsh web --patch a --patch b` stacks overlays, root tools are inherited by every agent
 *    preset.
 *
 *  - `/var/lib/dsh-app/mcp/servers.json` (0600): NOT written — the server list lives in the
 *    overlay itself. Secrets (tokens/headers) sit in the overlay file, which is
 *    world-readable like the other overlays; on a single-user phone that matches where the
 *    app stores them (Android app-private storage keeps the source of truth).
 *
 * Safe mode: no overlay and no patch argument — external MCP never mounts in safe mode.
 */
object McpGuest {
    const val PATCH = "/opt/dsh-app/mcp.patch.yml"
    const val MAX_SERVERS = 8

    private const val WEB_PATCH_ARG = "--patch /etc/dsh-app/dsh-web.patch.yml"
    private const val DSH_WEB = "/usr/local/bin/dsh web"

    /** One configured MCP server, as entered in the settings page. */
    data class Server(
        val name: String,
        val url: String,
        val token: String,
        val enabled: Boolean,
    )

    /** The launch shell command with this overlay right after the app's own one; null when [arg] is not the `dsh web` launch. */
    fun launchArg(arg: String): String? {
        val web = arg.indexOf("$DSH_WEB ")
        if (web < 0 || arg.indexOf("$DSH_WEB ", web + 1) >= 0 || arg.contains("--profile")) return null
        val patch = arg.indexOf(WEB_PATCH_ARG, web)
        val at = if (patch >= 0 && arg.indexOf(WEB_PATCH_ARG, patch + 1) < 0) patch + WEB_PATCH_ARG.length else web + DSH_WEB.length
        return arg.substring(0, at) + " --patch $PATCH" + arg.substring(at)
    }

    fun file(rootfs: File) = File(rootfs, PATCH.removePrefix("/"))

    /** Builds the overlay YAML from the server list; null when nothing is enabled. */
    internal fun patchYaml(servers: List<Server>): String? {
        val active = servers.filter { it.enabled && validName(it.name) && it.url.isNotBlank() }.take(MAX_SERVERS)
        if (active.isEmpty()) return null
        return buildString {
            appendLine("# dsh-app: external MCP bridge (外部 MCP 对接). The DSH App rewrites this file at every engine")
            appendLine("# start and passes it with --patch on normal launches (never in safe mode). Edits do not survive.")
            for (s in active) {
                appendLine("- insert:")
                appendLine("    - id: dsh-app-mcp-${yamlSafe(s.name)}")
                appendLine("      name: '@deepseek-ai/dsh-mcp-client'")
                appendLine("      config:")
                appendLine("        transport: streamable-http")
                appendLine("        serverName: ${s.name}")
                appendLine("        url: ${s.url}")
                if (s.token.isNotBlank()) {
                    appendLine("        headers:")
                    appendLine("          Authorization: Bearer ${s.token}")
                }
                appendLine("        toolCallTimeoutMs: 120000")
                appendLine("        failOnStartupError: false")
            }
        }
    }

    /** serverName must match the mcp-client schema: `[A-Za-z0-9_-]{1,32}`. */
    fun validName(name: String): Boolean = Regex("[A-Za-z0-9_-]{1,32}").matches(name)

    /** A stable insert id; names are already validated to a safe alphabet. */
    private fun yamlSafe(name: String) = name.lowercase()

    /** Writes the overlay; false when it could not be written or nothing is enabled. Cleanup is best effort. */
    fun writeFiles(rootfs: File, servers: List<Server>): Boolean {
        val target = file(rootfs)
        val yaml = patchYaml(servers)
        if (yaml == null) {
            target.delete()
            return false
        }
        return try {
            val dir = target.parentFile ?: throw IOException("no parent for ${target.path}")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.path}")
            if (!target.exists() || target.readText() != yaml) {
                val tmp = File(dir, ".${target.name}.dsh-app-${System.nanoTime()}.tmp")
                try {
                    tmp.writeText(yaml)
                    tmp.setReadable(true, false)
                    if (!tmp.renameTo(target)) throw IOException("could not replace ${target.path}")
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
            }
            true
        } catch (_: Exception) {
            target.delete()
            false
        }
    }

    /** Parses the saved server list JSON: `[{"name":..., "url":..., "token":..., "enabled":...}]`. */
    fun parseServers(json: String?): List<Server> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val list = Json.parse(json) as? List<*> ?: return emptyList()
            list.mapNotNull { raw ->
                val map = raw as? Map<*, *> ?: return@mapNotNull null
                val name = (map["name"] as? String)?.trim() ?: return@mapNotNull null
                Server(
                    name = name,
                    url = (map["url"] as? String)?.trim() ?: "",
                    token = map["token"] as? String ?: "",
                    enabled = map["enabled"] != false,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun serializeServers(servers: List<Server>): String {
        val list = servers.map { s ->
            linkedMapOf(
                "name" to s.name,
                "url" to s.url,
                "token" to s.token,
                "enabled" to s.enabled,
            )
        }
        return Json.write(list) + "\n"
    }
}
