package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File

/**
 * 手机共享存储里，每个 App 在 `Android/data`、`Android/obb` 下有一个只有它能写的私有目录。
 *
 * Android 把那个目录的属主记成该 App 自己的 uid（外加 `ext_data_rw` / `ext_obb_rw` 组），
 * 只有属主能写。而 guest 里干的活不是以那个 App 的身份写的：proot / direct 引擎下写入者是
 * 本应用的 uid，root 引擎下是 uid 0。于是「把游戏数据搬进去 / 恢复某个 App 的目录 / 解压到
 * 别人的私有目录」这类操作会在那个 App 的目录里留下我们名下（或 root 名下）的文件与子目录，
 * 权限位又是 0770 —— 原 App 从此写不动自己的目录：
 *
 * - 游戏客户端的热更器建不了自己的临时文件，报 `create file failed ->13`（EACCES），
 *   热更初始化失败（70254604），弹「请检查储存权限或磁盘空间」，进不去游戏；
 * - QQ 存不下图片，发图直接失败。
 *
 * 唯一的修法是 chown 回去，而 chown 需要 root，所以它挂在 root 侧：root 引擎起来时自动跑
 * 一遍（把上一次留下的收干净），也可以在设置里或让模型手动触发。
 *
 * 只动属主，不动内容、不动权限位；不属于任何已安装 App 的目录只报告、不碰。
 */
internal object ExternalStorage {

    /** `Android/data`：AID_EXT_DATA_RW；`Android/obb`：AID_EXT_OBB_RW。 */
    private val TREES = listOf("data" to 1078, "obb" to 1079)

    /** 一次修复最多看这么多条目，避免异常目录把扫描拖死。 */
    private const val DEFAULT_BUDGET = 400_000

    /** 单条 chown 命令最多带这么多路径（su 每次调用都要付一次进程开销）。 */
    private const val CHUNK = 128

    data class Report(
        val entries: Int,
        val packages: Int,
        val fixedPackages: Int,
        val fixedEntries: Int,
        val skipped: List<String>,
        val truncated: Boolean,
    ) {
        fun text(): String = buildString {
            append("扫描 $entries 个条目、$packages 个 App 目录")
            if (fixedEntries == 0) {
                append("，属主都正确，没有需要修的")
            } else {
                append("；修复 $fixedPackages 个 App 目录、共 $fixedEntries 个条目")
            }
            if (truncated) append("（达到扫描上限，可能还有剩余，再跑一次即可）")
            if (skipped.isNotEmpty()) append("；跳过 ${skipped.size} 个未安装的目录")
        }
    }

    /** 全量修复一次。阻塞，调用方自己放到后台线程。 */
    fun repair(context: Context, budget: Int = DEFAULT_BUDGET): Report {
        val root = Environment.getExternalStorageDirectory()?.absolutePath
            ?: return Report(0, 0, 0, 0, listOf("no shared storage"), false)
        val pm = context.packageManager
        val mine = Process.myUid()
        val skipped = ArrayList<String>()
        var entries = 0
        var packages = 0
        var fixedPackages = 0
        var fixedEntries = 0
        var left = budget

        for ((tree, gid) in TREES) {
            if (left <= 0) break
            val base = File(root, "Android/$tree")
            val children = base.listFiles() ?: continue
            for (dir in children) {
                if (!dir.isDirectory || isLink(dir)) continue
                packages++
                val uid = uidOf(pm, dir.name) ?: run { skipped += dir.name; continue }
                // 自己的目录本来就是自己的，别人的目录才需要检查。
                if (uid == mine) continue
                val mismatched = ArrayList<String>()
                val visited = IntArray(1)
                collect(dir, uid, mismatched, minOf(left, budget - entries), visited)
                entries += visited[0]
                left -= visited[0]
                if (mismatched.isEmpty()) continue
                if (chown(uid, gid, mismatched)) {
                    fixedPackages++
                    fixedEntries += mismatched.size
                }
                if (left <= 0) break
            }
        }
        return Report(entries, packages, fixedPackages, fixedEntries, skipped, left <= 0)
    }

    /**
     * 收集 [dir] 下属主不是 [uid] 的条目（含 [dir] 自己），最多看 [budget] 个。
     * 用 lstat，不跟符号链接；条目在这中间消失（App 正在写）就当没有。
     */
    private fun collect(dir: File, uid: Int, sink: MutableList<String>, budget: Int, visited: IntArray) {
        if (budget <= 0) return
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty() && visited[0] < budget) {
            val file = stack.removeLast()
            visited[0]++
            val st = stat(file) ?: continue
            if (st.st_uid != uid) sink += file.absolutePath
            if (OsConstants.S_ISDIR(st.st_mode)) {
                file.listFiles()?.let { for (child in it) stack.addLast(child) }
            }
        }
    }

    /** `chown -h uid:gid <paths>`，分块跑；任何一块失败都不算修好。 */
    private fun chown(uid: Int, gid: Int, paths: List<String>): Boolean {
        var ok = true
        for (chunk in paths.chunked(CHUNK)) {
            val command = buildString {
                append("chown -h $uid:$gid")
                for (path in chunk) append(' ').append(RootEngine.quote(path))
                append(" 2>/dev/null || true")
            }
            val result = RootExec.run(command, 120_000)
            if (!result.ok) ok = false
        }
        return ok
    }

    private fun uidOf(pm: PackageManager, pkg: String): Int? = try {
        pm.getApplicationInfo(pkg, 0).uid
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun stat(file: File): StructStat? = try {
        Os.lstat(file.absolutePath)
    } catch (_: Exception) {
        null
    }

    private fun isLink(file: File): Boolean = stat(file)?.let { OsConstants.S_ISLNK(it.st_mode) } ?: false
}
