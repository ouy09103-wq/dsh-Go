package io.github.ouy09103.dsh_go_root.phone

/**
 * root 命令的三档拦截档位，跟 DSHA 的「严格 / 放宽 / 完全放宽」一个意思。
 *
 * 档位只作用于 `shell`（真 root 通道）和 Android Shell 的 root 后端，
 * 不改 Shizuku / ADB 通道，也不改短信读取这类原生授权闸门。
 *
 *  - **严格 [STRICT]**：每条命令都走 [RootCommandPolicy]——不碰名单、系统目录、
 *    凭据库、本 App 存储都会被拒，每个写入还要按「每次写入都确认」的规则问一次。
 *  - **放宽 [RELAXED]**（默认）：命令本身不再按内容逐条判，只拦不可逆的操作
 *    （格式化、刷机、清数据）。打开 root 开关本身就是一句允许，这一档是它的本意。
 *  - **完全放宽 [FULL]**：命令内容完全不看，只剩短信这类需要单独原生授权的闸门。
 *
 * 非法或缺失一律回落到 [RELAXED]：配置被写坏时应该回到默认，而不是顺手把防护降到最低。
 */
enum class RootPolicyMode(val id: String) {
    STRICT("strict"),
    RELAXED("relaxed"),
    FULL("full");

    companion object {
        /** 默认档位：任意命令都跑，不可逆操作仍拦。 */
        val DEFAULT = RELAXED

        fun parse(value: String?): RootPolicyMode {
            val normalized = value?.trim()?.lowercase() ?: return DEFAULT
            return entries.firstOrNull { it.id == normalized } ?: DEFAULT
        }

        fun of(mode: RootPolicyMode?): String = (mode ?: DEFAULT).id
    }
}

/**
 * 「完全放宽」也还要拦的那几样：做下去就回不来了。
 *
 * 匹配的是命令里出现的词，宁可多拦一条，也不能让一条 `mkfs` 从这里过去。
 */
object IrreversibleCommands {
    private val PATTERNS = listOf(
        "mkfs", "wipe ", "wipe-data", "fastboot", "flash ", "format ", "mke2fs",
        "dd if=", "dd of=/dev/block", "shred ", "rm -rf /", "rm -rf /*",
        "recovery --wipe", "pm clear", "settings delete", "locksettings clear",
        "srm ", "nvram", "flash_image",
    )

    /** The first irreversible thing [command] does, or null when it does none of them. */
    fun hit(command: String): String? {
        val text = command.lowercase()
        return PATTERNS.firstOrNull { text.contains(it) }
    }
}
