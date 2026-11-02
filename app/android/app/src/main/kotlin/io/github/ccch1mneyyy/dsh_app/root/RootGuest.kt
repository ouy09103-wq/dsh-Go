package io.github.ccch1mneyyy.dsh_app.root

import io.github.ccch1mneyyy.dsh_app.phone.Json
import io.github.ccch1mneyyy.dsh_app.phone.PhoneGuest
import io.github.ccch1mneyyy.dsh_app.phone.PhoneGuest.Endpoint
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The DSH side of the root tools, written into the rootfs at every engine start (no rebuild):
 *
 *  - `/opt/dsh-app/root/relay.cjs`: stdio ⇄ the app's root bridge socket (copied verbatim from
 *    phone control's relay; it carries no tool definitions).
 *  - `/opt/dsh-app/root/root.patch.yml`: an extra `--patch` overlay that inserts DSH's
 *    `@deepseek-ai/dsh-mcp-client` at the root of the tree as server `root`, so the model sees
 *    `mcp__root__*` in every preset.
 *  - `/var/lib/dsh-app/root/bridge.json` (0600): this launch's socket name and token.
 *
 * Off (switch off, or the engine is proot/direct without real root): the overlay is not passed
 * and the config file is removed, so the model sees no root tools at all.
 */
object RootGuest {
    const val GUEST_DIR = "/opt/dsh-app/root"
    const val RELAY = "$GUEST_DIR/relay.cjs"
    const val PATCH = "$GUEST_DIR/root.patch.yml"
    const val STATE_DIR = "/var/lib/dsh-app/root"
    const val CONFIG = "$STATE_DIR/bridge.json"

    /** Where the overlay goes in the launch command: next to the app's own overlay. */
    private const val WEB_PATCH_ARG = "--patch /etc/dsh-app/dsh-web.patch.yml"
    private const val DSH_WEB = "/usr/local/bin/dsh web"

    /** MCP server name: the model sees `mcp__root__<tool>`. */
    const val SERVER_NAME = "root"

    /** A guest command may run long (apt, builds); the native side caps it at 10 min. */
    const val TOOL_CALL_TIMEOUT_MS = 660_000L

    /**
     * The launch shell command with the root overlay added, or null when [arg] has no anchor.
     * Launcher flags must come before the first app flag, so the overlay goes next to the existing one.
     */
    fun launchArg(arg: String): String? {
        val web = arg.indexOf("$DSH_WEB ")
        if (web < 0 || arg.indexOf("$DSH_WEB ", web + 1) >= 0 || arg.contains("--profile")) return null
        val patch = arg.indexOf(WEB_PATCH_ARG, web)
        val at = if (patch >= 0 && arg.indexOf(WEB_PATCH_ARG, patch + 1) < 0) patch + WEB_PATCH_ARG.length else web + DSH_WEB.length
        return arg.substring(0, at) + " --patch $PATCH" + arg.substring(at)
    }

    // l10n:keep agent config comment
    val PATCH_YAML = """# dsh-app: root tools (真 root). The DSH App rewrites this file at every engine start and
# passes it with --patch only while the root engine is up. Edits do not survive.
- insert:
    - id: dsh-app-root
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: $SERVER_NAME
        transport: stdio
        command: !!js process.execPath
        args:
          - $RELAY
          - $CONFIG
        toolCallTimeoutMs: $TOOL_CALL_TIMEOUT_MS
        failOnStartupError: false
"""

    /** Appended to the managed `$DSH_HOME/AGENTS.md` while the root engine is up. */
    // l10n:keep agent prompt
    val AGENTS_SECTION = """
        |
        |## 真 root（chroot 引擎）
        |
        |- 这个环境是真 root 起的：`id -u` 是 0，文件权限和 proot 的假 root 不同；不要在 shell 里重复 `su`。
        |- 需要看设备本身的 root 侧（不限于 rootfs）时用 `mcp__root__*`：`status` 看 su/管理器/frida，`exec` 在设备上以 root 跑一条命令，`guest` 在 rootfs 里以 root 跑，`frida_start` / `frida_stop` / `frida_status` 管 frida-server，`frida_ps` 列进程。
        |- `exec` 会真的改设备：先读、先 `ls`，需要写入或删除时说明清楚再动；不要 `rm -rf /`、不要动 `/system`、`/vendor` 和别人的 `/data/data`。
        |- frida-server 默认只监听 127.0.0.1:27042；要给电脑上的 frida 用，用 `frida_status` 拿端口，再由用户自己决定是否 `adb forward`。
        |""".trimMargin()

    fun config(endpoint: Endpoint): String = Json.write(
        linkedMapOf(
            "v" to 1,
            "abstract" to endpoint.abstractName,
            "tcpPort" to endpoint.tcpPort,
            "token" to endpoint.token,
        ),
    ) + "\n"

    /** Writes the relay and the overlay (world-readable, no secrets). */
    fun writeFiles(rootfs: File) {
        val dir = realDir(guestFile(rootfs, GUEST_DIR), ownerOnly = false)
        writeAtomic(File(dir, "relay.cjs"), PhoneGuest.RELAY_JS, ownerOnly = false)
        writeAtomic(File(dir, "root.patch.yml"), PATCH_YAML, ownerOnly = false)
    }

    fun writeConfig(rootfs: File, endpoint: Endpoint) {
        val dir = realDir(guestFile(rootfs, STATE_DIR), ownerOnly = true)
        writeAtomic(File(dir, "bridge.json"), config(endpoint), ownerOnly = true)
    }

    fun clearConfig(rootfs: File) {
        try {
            guestFile(rootfs, CONFIG).delete()
        } catch (_: Exception) {
        }
    }

    fun guestFile(rootfs: File, guestPath: String): File = File(rootfs, guestPath.removePrefix("/"))

    private fun realDir(dir: File, ownerOnly: Boolean): File {
        if (Files.isSymbolicLink(dir.toPath()) || (dir.exists() && !dir.isDirectory)) dir.delete()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.path}")
        if (ownerOnly) restrict(dir, exec = true)
        return dir
    }

    private fun restrict(f: File, exec: Boolean) {
        f.setReadable(false, false)
        f.setWritable(false, false)
        f.setExecutable(false, false)
        f.setReadable(true, true)
        f.setWritable(true, true)
        if (exec) f.setExecutable(true, true)
    }

    private fun writeAtomic(file: File, text: String, ownerOnly: Boolean) {
        val tmp = File(file.parentFile, ".${file.name}.dsh-app-${System.nanoTime()}.tmp")
        try {
            tmp.createNewFile()
            if (ownerOnly) restrict(tmp, exec = false) else tmp.setReadable(true, false)
            tmp.writeText(text)
            if (!tmp.renameTo(file)) throw IOException("could not replace ${file.path}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
