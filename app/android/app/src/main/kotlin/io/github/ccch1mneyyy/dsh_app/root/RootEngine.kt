package io.github.ccch1mneyyy.dsh_app.root

import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 真 root 引擎：把打包的 Ubuntu rootfs 用 chroot(2) 直接跑在设备的 su 下。
 *
 * 和 proot 的区别是本质的：进程真的是 uid 0、真持 root 能力，glibc 也不再受 App 的
 * seccomp 过滤（su 由 magiskd / ksud 拉起，不继承 zygote 的过滤器）。原先在部分机器上起不来的
 * glibc Node 在这里能正常跑，Node 的权限也变成真的。
 *
 * 一次 `su -c` 里做完：建挂载点 → bind /proc /sys /dev → bind 额外目录 → chroot → 跑 guest
 * 命令 → 退出时 lazy umount。停止时先 SIGTERM guest 的 node，脚本自然收尾并卸载。
 *
 * 可用性不猜：真跑一遍 `id -u` 与 `command -v chroot` 才认。
 */
object RootEngine {

    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk/su",
    )

    fun suPath(): String? = SU_CANDIDATES.firstOrNull { File(it).exists() }

    fun manager(): String = when {
        File("/data/adb/ksu").exists() -> "KernelSU"
        File("/data/adb/ap").exists() -> "APatch"
        File("/data/adb/magisk").exists() -> "Magisk"
        suPath() != null -> "su"
        else -> ""
    }

    /** Result of the one-time probe; [usable] is what the switch reads. */
    data class Support(
        val su: String?,
        val uid: Int,
        val chroot: Boolean,
        val mount: Boolean,
        val kernel: String,
        val abi: String,
        val reason: String?,
        val manager: String = "",
        val sdk: Int = Build.VERSION.SDK_INT,
    ) {
        val usable: Boolean get() = su != null && uid == 0 && chroot

        fun map(): Map<String, Any?> = linkedMapOf(
            "su" to su,
            "manager" to manager,
            "uid" to uid,
            "chroot" to chroot,
            "mount" to mount,
            "kernel" to kernel,
            "abi" to abi,
            "sdk" to sdk,
            "usable" to usable,
            "reason" to reason,
        )
    }

    @Volatile private var cached: Support? = null

    fun cachedSupport(): Support? = cached

    /** Runs the probe once per app process; [force] re-probes after a fresh grant. Blocking. */
    fun probe(force: Boolean = false): Support {
        cached?.let { if (!force) return it }
        val su = suPath()
        if (su == null) {
            return Support(
                null, -1, false, false, "", Build.SUPPORTED_ABIS.firstOrNull() ?: "", "noSu", manager(),
            ).also { cached = it }
        }
        val out = RootExec.run(
            "id -u; command -v chroot || echo NOCHROOT; command -v mount || echo NOMOUNT; uname -r",
            60_000,
        )
        val lines = out.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val uid = lines.firstOrNull()?.toIntOrNull() ?: -1
        val chroot = lines.any { it.endsWith("/chroot") }
        val mount = lines.any { it.endsWith("/mount") }
        val kernel = lines.lastOrNull { it.contains(".") && !it.contains("/") } ?: ""
        val reason = when {
            uid != 0 -> "notGranted"
            !chroot -> "noChroot"
            else -> null
        }
        return Support(
            su, uid, chroot, mount, kernel, Build.SUPPORTED_ABIS.firstOrNull() ?: "", reason, manager(),
        ).also { cached = it }
    }

    fun quote(word: String): String =
        if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in "/._-:=@%+," }) word
        else "'" + word.replace("'", "'\\''") + "'"

    /**
     * The whole launch as one shell script for `su -c`.
     *
     * [guestArgv] starts at the guest's `/usr/bin/env` (manifest template, expanded, overlays in).
     * [binds] are `src` or `src:dst` mounts to add inside the rootfs (phone storage).
     */
    fun launchScript(
        rootfs: File,
        guestArgv: List<String>,
        binds: List<String>,
        logFile: String? = null,
        nested: Boolean = false,
    ): String {
        // Keep the raw path and quote every composed path once: quoting an already-quoted prefix
        // would nest quotes and hand the shell a literal that no longer names the directory.
        val root = rootfs.absolutePath
        fun at(guestPath: String): String = quote(root + guestPath)

        // A nested run shares the mounts the engine already made. Mounting again would stack a
        // second /proc on top of the first on every tool call, so it only mounts what is missing.
        val base = listOf(
            "mountpoint -q ${at("/proc")} || mount -t proc proc ${at("/proc")}",
            "mountpoint -q ${at("/dev")} || mount --bind /dev ${at("/dev")}",
            "mountpoint -q ${at("/sys")} || mount --bind /sys ${at("/sys")}",
        )
        val extra = ArrayList<Pair<String, String>>()
        for (bind in binds) {
            val parts = bind.split(':', limit = 2)
            if (parts.isEmpty()) continue
            val src = parts[0].trimEnd('/').ifEmpty { "/" }
            val dst = parts.getOrElse(1) { parts[0] }.trimEnd('/').ifEmpty { "/" }
            if (dst == "/" || !dst.startsWith("/")) continue
            extra += src to dst
        }
        val extraLines = extra.joinToString("\n") { (src, dst) ->
            val host = quote(src)
            val guest = at(dst)
            buildString {
                append("if [ -e $host ]; then\n")
                append("  mkdir -p \$(dirname $guest) 2>/dev/null || true\n")
                append("  if [ -d $host ]; then mkdir -p $guest 2>/dev/null || true; else touch $guest 2>/dev/null || true; fi\n")
                append("  if mountpoint -q $guest 2>/dev/null; then :; elif mount --bind $host $guest 2>/dev/null; then echo $guest >> \$R/.dsh-root-mounts; fi\n")
                append("fi")
            }
        }
        // Unmount deepest-first: a nested bind cannot be released before the mount on top of it.
        val unmount = extra
            .sortedByDescending { (_, dst) -> dst.count { it == '/' } }
            .joinToString("\n") { (_, dst) -> "umount -l ${at(dst)} 2>/dev/null || true" }
        val env = guestArgv.joinToString(" ", transform = ::quote)
        val redirect = if (logFile != null) {
            " 2>&1 | tee -a ${quote(logFile)}; code=\${PIPESTATUS:-0}"
        } else {
            "; code=\$?"
        }
        return buildString {
            append("set -u\n")
            append("R=${quote(root)}\n")
            append("mkdir -p \$R/proc \$R/sys \$R/dev \$R/dev/shm \$R/tmp \$R/root 2>/dev/null || true\n")
            if (!nested) {
                // The ledger belongs to the launch that owns the mounts. A nested run (a `guest`
                // tool call while the engine is up) shares those mounts and must not reset or
                // unmount them: it would tear down the running engine's /proc, /dev and /sdcard.
                append(": > \$R/.dsh-root-mounts 2>/dev/null || true\n")
            }
            append("cleanup() {\n")
            if (unmount.isNotEmpty()) {
                append("  ").append(unmount.replace("\n", "\n  ")).append("\n")
            }
            if (!nested) {
                append("  umount -l \$R/sys 2>/dev/null || true\n")
                append("  umount -l \$R/dev/shm 2>/dev/null || true\n")
                append("  umount -l \$R/dev 2>/dev/null || true\n")
                append("  umount -l \$R/proc 2>/dev/null || true\n")
            }
            append("}\n")
            // A signal must end the script, not merely run cleanup: only EXIT guarantees the trap
            // fires exactly once, after the guest is gone.
            append("trap cleanup EXIT\n")
            append("trap 'exit 143' INT TERM HUP\n")
            for (line in base) {
                append("$line 2>/dev/null || true\n")
            }
            append("mountpoint -q \$R/dev/shm || mount -t tmpfs tmpfs \$R/dev/shm 2>/dev/null || true\n")
            if (extraLines.isNotEmpty()) {
                append(extraLines)
                append("\n")
            }
            append("chroot \$R $env$redirect\n")
            append("exit \$code\n")
        }
    }

    /**
     * The guest pid (`echo $$ > /tmp/dsh-web.pid`).
     *
     * The root engine runs the guest as uid 0 and `adopt()` chowns `/root` and `/tmp` to 0:0, so
     * `/tmp/dsh-web.pid` ends up mode 0600 owned by root. Reading it from the app (uid 10428) fails
     * with EACCES, [guestPid] then returns null, and `terminate()` silently gives up — the engine
     * keeps running as an orphan. Read through su instead, which is what the root engine is for.
     */
    fun guestPid(rootfs: File): Int? {
        val file = File(rootfs, "tmp/dsh-web.pid")
        // The fast path still works when the file happens to be readable by this app.
        val direct = try {
            file.readText().trim().toIntOrNull()
        } catch (_: Exception) {
            null
        }
        if (direct != null && direct > 0) return direct
        val out = try {
            RootExec.run("cat ${quote(file.absolutePath)} 2>/dev/null || echo 0", 20_000).stdout
        } catch (_: Exception) {
            return null
        }
        return out.trim().lines().firstOrNull()?.toIntOrNull()?.takeIf { it > 0 }
    }

    /** SIGTERM node (dsh exits 0), then SIGKILL; the launch script's trap unmounts. */
    fun terminate(rootfs: File, waitMs: Long = 6_000): Boolean {
        val pid = guestPid(rootfs) ?: return false
        // Never signal a recycled pid: the file survives a crash, and the pid may since belong
        // to another app's process. Only a live node inside this rootfs is ours to end.
        if (!isGuestNode(pid)) {
            runCatching { File(rootfs, "tmp/dsh-web.pid").delete() }
            return false
        }
        RootExec.run("kill -TERM $pid 2>/dev/null; true", 20_000)
        val deadline = System.nanoTime() + waitMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (!alive(pid)) return true
            Thread.sleep(120)
        }
        RootExec.run("kill -KILL $pid 2>/dev/null; true", 20_000)
        return true
    }

    /**
     * True when [pid] is a DSH guest `node`: the process name is node, it runs DSH's own bin, and
     * its working directory is a `runtime/rootfs` tree (an earlier install's rootfs, since deleted,
     * shows up as `…/rootfs (deleted)` — still ours to reap).
     *
     * This guards against a recycled pid: `/tmp/dsh-web.pid` survives a crash, and the pid it
     * names may since belong to something unrelated.
     */
    private fun isGuestNode(pid: Int): Boolean {
        val out = RootExec.run(
            "if [ -d /proc/$pid ]; then " +
                "tr '\\0' '\\n' < /proc/$pid/cmdline 2>/dev/null | head -3; " +
                "readlink /proc/$pid/cwd 2>/dev/null; fi",
            20_000,
        ).stdout
        val text = out.trim()
        if (text.isEmpty()) return false
        val isNode = Regex("""^\s*(/opt/node/bin/node|node)$""", RegexOption.MULTILINE).containsMatchIn(text) ||
            text.contains("/opt/node/bin/node")
        if (!isNode || !text.contains("/opt/dsh/node_modules/@deepseek-ai/dsh")) return false
        return Regex("""runtime/rootfs(\s*\(deleted\))?$""", RegexOption.MULTILINE).containsMatchIn(text)
    }

    fun alive(pid: Int): Boolean = try {
        RootExec.run("test -d /proc/$pid && echo yes || echo no", 15_000).stdout.contains("yes")
    } catch (_: Exception) {
        false
    }

    /**
     * True while a guest `node` is alive for this rootfs.
     *
     * Its mounts are in use then: unmounting them under a running engine takes away its `/proc`,
     * `/dev` and `/sdcard` until the next restart, and chowning the tree races the writes it is
     * doing. Everything below that touches mounts or ownership checks this first.
     */
    fun engineRunning(rootfs: File): Boolean {
        val pid = guestPid(rootfs) ?: return false
        return alive(pid) && isGuestNode(pid)
    }

    /** Lazy-umounts whatever an interrupted launch left inside this rootfs. */
    fun cleanupMounts(rootfs: File) {
        // Never yank the mounts out from under a live engine: the caller wants the leftovers of a
        // dead one, and the live one owns the same mountpoints.
        if (engineRunning(rootfs)) return
        val r = quote(rootfs.absolutePath)
        RootExec.run(
            // Deepest first: a bind mounted on top of another cannot be released before it, and
            // the ledger also holds the phone-storage binds that are not under $r/proc etc.
            "for m in \$(cat $r/.dsh-root-mounts 2>/dev/null | sort -r); do umount -l \"\$m\" 2>/dev/null; done; " +
                "umount -l $r/sdcard 2>/dev/null; umount -l $r/storage 2>/dev/null; " +
                "umount -l $r/sys 2>/dev/null; umount -l $r/dev/shm 2>/dev/null; " +
                "umount -l $r/dev 2>/dev/null; umount -l $r/proc 2>/dev/null; true",
            60_000,
        )
    }

    /** Makes the guest's own dirs root-owned (cosmetic; root ignores the app's modes anyway). */
    fun adopt(rootfs: File) {
        // Deliberately empty.
        //
        // It used to `chown -R 0:0 /root /tmp /var/lib/dsh-app`. Root ignores ownership, so the
        // guest never needed it — but the *app* does: while the root engine runs, the app still
        // writes `$DSH_HOME/AGENTS.md`, `var/lib/dsh-app/root/bridge.json` and
        // `var/lib/dsh-app/phone/bridge.json`, and reads `tmp/dsh-web.pid`. Once those trees were
        // chowned to 0:0 the app got EACCES on all of them: the root-tools overlay went stale (the
        // model never saw the `mcp__root__*` section), bridge config writes failed, and `terminate()`
        // could not read the pid, so stopped engines were left running as orphans.
        //
        // Guest writes still create root-owned files; `releaseTo()` hands the tree back to the app
        // before any non-root launch, which is the case that actually needs fixing.
        if (!rootfs.isDirectory) return
    }

    /**
     * Hands back only the trees the *app* still writes while the root engine is up, leaving the
     * rest of the guest (sessions, packages, workspace) as uid 0 wrote it.
     *
     * Root ignores ownership, so the guest keeps working; the app needs this to write its own
     * managed files (`AGENTS.md`, the two `bridge.json` files, the frida script under `tmp/`) and
     * to read `tmp/dsh-web.pid`. Without it those writes fail with EACCES and the model silently
     * loses the root / phone sections of `AGENTS.md` and the MCP overlays.
     */
    fun shareWithApp(rootfs: File, uid: Int) {
        if (!rootfs.isDirectory) return
        val r = quote(rootfs.absolutePath)
        // `/root` itself is in the list: it is the parent of every path below, and while it is
        // 0:0 0700 the app cannot even traverse into `$DSH_HOME` — every read fails with EACCES
        // no matter how correct the children are. The walk also has no `-maxdepth`: the files the
        // app must read sit deeper than two levels (`profiles/web/cordis.patch.yml`,
        // `storages/session_projcache/sessions/*.json`), and a bounded walk silently missed them.
        RootExec.run(
            "for d in $r/root $r/root/.dsh $r/var/lib/dsh-app $r/tmp $r/opt/dsh-app; do " +
                "if [ -d \"\$d\" ]; then find \"\$d\" -xdev -exec chown -h $uid:$uid {} + 2>/dev/null; fi; " +
                "done; " +
                relabelCommand(r) +
                "true",
            180_000,
        )
    }

    /**
     * SELinux: a file created by the root engine (uid 0, kernel context) lands as
     * `u:object_r:app_data_file:s0` — the *category-less* label. The app runs in
     * `u:r:untrusted_app_27:s0:c175,c257,c512,c768`, and MLS forbids reading a category-less
     * file from a categorized domain, so the app gets EACCES even when the file is owned by its
     * own uid and mode 0600. chown alone therefore never fixes a root run; the labels have to be
     * restored too. `restorecon` reads the device's own `file_contexts`, so the exact categories
     * stay whatever this ROM assigned to this app's data dir.
     */
    internal fun relabelCommand(root: String): String =
        "if command -v restorecon >/dev/null 2>&1; then " +
            "for d in $root/root $root/var/lib/dsh-app $root/tmp $root/opt/dsh-app $root/etc/resolv.conf; do " +
            "restorecon -R \"\$d\" >/dev/null 2>&1 || true; done; " +
            "fi; "

    /** The uid the rootfs should belong to whenever the engine runs as the app (proot / direct). */
    fun ownerOf(rootfs: File): Int? = try {
        RootExec.run("stat -c %u ${quote(rootfs.absolutePath)} 2>/dev/null || echo -1", 20_000)
            .stdout.trim().toIntOrNull()?.takeIf { it >= 0 }
    } catch (_: Exception) {
        null
    }

    /**
     * Hands the whole rootfs back to [uid].
     *
     * The root engine writes as uid 0 (dsh config, packages, session files). proot and direct run
     * as the app, so without this a proot launch after a root run cannot write its own runtime —
     * sessions, plugins and the LKG profile would fail. Root ignores ownership, so the app-owned
     * files keep working under the root engine too.
     *
     * `chown -R` follows bind mounts. The rootfs is a plain directory whenever this runs, but a
     * launch killed mid-way can leave `/sdcard`, `/storage`, `/proc` or `/dev` mounted inside it,
     * and chowning those rewrites the ownership of the phone's whole shared storage.
     *
     * Neither `find -xdev` nor toybox's `mountpoint` catches this: a bind mount has the same
     * `st_dev` as the rootfs, so `-xdev` walks through it and `mountpoint` answers "not a
     * mountpoint" (both measured on a real device). The walk is therefore pruned from
     * `/proc/mounts`, which does list same-device binds, and the leftover mounts are cleared first.
     *
     * Returns whether anything was changed; [ownerOf] avoids a pointless 38k-file walk.
     */
    fun releaseTo(rootfs: File, uid: Int, force: Boolean = false): Boolean {
        if (!rootfs.isDirectory) return false
        // A live engine keeps the tree busy: chowning it would race its writes, and clearing the
        // mounts would leave it without /proc and /sdcard. It has to be stopped first.
        if (engineRunning(rootfs)) return false
        val r = quote(rootfs.absolutePath)
        if (!force && ownerOf(rootfs) == uid) {
            // Ownership already matches, but a root run may still have left category-less labels
            // behind (they are invisible to `stat %u`). Relabel, then report "nothing chowned".
            RootExec.run(relabelCommand(r) + "true", 180_000)
            return false
        }
        // Anything still mounted inside the rootfs goes first, so the walk below sees one tree.
        cleanupMounts(rootfs)
        // Every mount below the rootfs becomes `-path <mount> -prune -o`, so find never descends
        // into a bind. awk keeps each path a single word; `r "/"` restricts it to this rootfs.
        // Single-quoted for the shell, so `$2` and `040` reach awk verbatim: Kotlin's own `$`
        // interpolation would otherwise eat them, which is why the pieces are plain literals.
        val awk = "awk -v r=" + r + " " +
            "'{ gsub(/\\040/, \" \", ${'$'}2); " +
            "if (index(${'$'}2, r \"/\") == 1) printf \" -path %s -prune -o\", ${'$'}2 }' /proc/mounts"
        val prune = "\$(" + awk + ")"
        fun walk(action: String): String = "find " + r + " " + prune + " " + action + " 2>/dev/null; "
        RootExec.run(
            walk("-exec chown -h " + uid + ":" + uid + " {} +") +
                walk("-type d -exec chmod u+rwx {} +") +
                walk("-type f -exec chmod u+rw {} +") +
                // chown alone is not enough after a root run: the guest's files carry the
                // category-less `app_data_file:s0` label, which the app's own categorized domain
                // may not read. See [relabelCommand].
                relabelCommand(r) +
                "true",
            600_000,
        )
        return true
    }

    internal fun waitFor(process: Process, millis: Long): Boolean = try {
        process.waitFor(millis, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        false
    }
}
