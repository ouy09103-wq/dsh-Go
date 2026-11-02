package io.github.ccch1mneyyy.dsh_app.root

/**
 * The fixed tool set the root bridge serves. The model sees `mcp__root__<name>`.
 *
 * Fixed on purpose: names, descriptions and schemas enter every request, so a change invalidates
 * the model's prefix cache. A call that cannot run right now returns an actionable sentence.
 */
object RootToolCatalog {
    const val STATUS = "status"
    const val EXEC = "exec"
    const val GUEST = "guest"
    const val FRIDA_START = "frida_start"
    const val FRIDA_SCAN = "frida_scan"
    const val FRIDA_STOP = "frida_stop"
    const val FRIDA_STATUS = "frida_status"
    const val FRIDA_PS = "frida_ps"
    const val FRIDA_SCRIPT = "frida_script"

    class Tool(val name: String, val description: String, val properties: Map<String, Any?>, val required: List<String>) {
        fun toJson(): Map<String, Any?> = linkedMapOf(
            "name" to name,
            "description" to description,
            "inputSchema" to linkedMapOf<String, Any?>("type" to "object", "properties" to properties).apply {
                if (required.isNotEmpty()) put("required", required)
                put("additionalProperties", false)
            },
        )
    }

    private fun str(description: String) = linkedMapOf<String, Any?>("type" to "string", "description" to description)
    private fun int(description: String) = linkedMapOf<String, Any?>("type" to "integer", "description" to description)
    private fun bool(description: String) = linkedMapOf<String, Any?>("type" to "boolean", "description" to description)

    // l10n:keep agent tool catalog
    val tools: List<Tool> = listOf(
        Tool(
            STATUS,
            "设备 root 侧的状态：su 路径、管理器（Magisk / KernelSU / APatch）、内核、ABI、是否可用 chroot、当前引擎、frida-server 是否在跑。不执行任何改变。",
            emptyMap(),
            emptyList(),
        ),
        Tool(
            EXEC,
            "在设备本身上以 root 执行一条 shell 命令（不是 rootfs 里，是 Android 本体：/system、/data、/proc 都可见）。返回 stdout、stderr 和退出码。会真的改变设备，先读后写。",
            linkedMapOf(
                "command" to str("要执行的 shell 命令，例如 `ls -l /data/adb`"),
                "timeoutMs" to int("超时毫秒，默认 60000，最大 600000"),
            ),
            listOf("command"),
        ),
        Tool(
            GUEST,
            "在打包的 Ubuntu rootfs 里以 root 执行一条命令（chroot 引擎下就是真 root，proot 下是 proot 的 uid 0）。适合 apt、编译、看 /root 里的工程。",
            linkedMapOf(
                "command" to str("要执行的命令，例如 `apt-get install -y nmap`"),
                "timeoutMs" to int("超时毫秒，默认 120000，最大 600000"),
            ),
            listOf("command"),
        ),
        Tool(
            FRIDA_SCAN,
            "扫描设备上已有的 frida-server 二进制（/sdcard/Download、/data/local/tmp 等），列出路径与大小。装之前先看这个。",
            emptyMap(),
            emptyList(),
        ),
        Tool(
            FRIDA_START,
            "启动 frida-server，默认只监听 127.0.0.1:27042。优先用 path 指定的本地文件（或自动扫描到的），否则按 ABI 联网下载。",
            linkedMapOf(
                "path" to str("设备上已有的 frida-server 文件路径（可用 frida_scan 找），.xz 也行"),
                "version" to str("frida 版本号，如 17.25.1；仅在需要联网下载时用"),
                "port" to int("监听端口，默认 27042"),
            ),
            emptyList(),
        ),
        Tool(
            FRIDA_STOP,
            "停掉 frida-server 的所有进程。",
            emptyMap(),
            emptyList(),
        ),
        Tool(
            FRIDA_STATUS,
            "frida-server 是否在跑、PID、监听端口、版本。",
            emptyMap(),
            emptyList(),
        ),
        Tool(
            FRIDA_PS,
            "列出设备上的进程（pid · 名字），可以按关键字过滤，用来找要 attach 的目标。",
            linkedMapOf("filter" to str("只看名字里包含这段文字的进程")),
            emptyList(),
        ),
        Tool(
            FRIDA_SCRIPT,
            "用 frida 对某个进程跑一段 JS（attach 后 load 并 resume）。返回脚本的输出。需要 frida-server 已在跑，且 rootfs 里有 frida 的 python 包。",
            linkedMapOf(
                "target" to str("进程名或 PID"),
                "script" to str("Frida JS 脚本内容"),
                "durationMs" to int("跑多久后 detach，默认 3000"),
                "spawn" to bool("true 表示先 spawn 再注入，默认 false（attach）"),
            ),
            listOf("target", "script"),
        ),
    )

    fun listJson(): List<Map<String, Any?>> = tools.map { it.toJson() }
}
