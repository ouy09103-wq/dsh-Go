# dsh-go root 版

官方 dsh-go 是**免 root** 的：打包的 Ubuntu 跑在 proot 里，`id -u` 是假的 0。这个分支把它换成**真 root**。

## 和官方版的区别（不是加壳，是换引擎）

| | 官方版 | 这个 root 版 |
|---|---|---|
| 引擎默认 | proot（fake uid 0） | proot，可切 **su + chroot（真 uid 0）** |
| 进程身份 | App uid | 真 root，持 root 能力 |
| seccomp | 受 App 的过滤器约束 | 不受（su 由 magiskd/ksud 拉起） |
| 设备本体 | 只能看 rootfs | 整个 `/data`、`/system`、`/proc` 都真 |
| 模型工具 | `mcp__phone__*` | 另有 `mcp__root__*` 九个工具 |
| Frida | 无 | 内置下载 + 启停 + 注入脚本 |
| applicationId | `io.github.ccch1mneyyy.dsh_app` | `io.github.ccch1mneyyy.dsh_root`（可共存） |

## 引擎怎么切

设置 → Root 权限 → 真 root 引擎。第一次点会弹管理器的授权框；探针真跑
`id -u` / `command -v chroot` / `uname -r`，三样都过了才允许打开。开关只在引擎停止时可改，
下次启动生效；安全模式永远走 proot。

启动脚本是一整段 `su -c`：

```
mkdir -p $R/{proc,sys,dev,dev/shm,tmp,root}
trap cleanup EXIT INT TERM HUP
mount -t proc proc $R/proc; mount --bind /dev $R/dev; mount --bind /sys $R/sys
mount -t tmpfs tmpfs $R/dev/shm
<每个 -b 绑定：mount --bind host $R/guest>
chroot $R /usr/bin/env -i HOME=/root … /bin/sh -c 'echo $$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web …'
```

停止时读 rootfs 里的 `/tmp/dsh-web.pid`，SIGTERM 让 dsh 自己收尾，再 SIGKILL，最后
lazy umount（脚本 trap + 兜底的 `cleanupMounts`）。

## 模型能用的 root 工具

`mcp__root__*`，由 `RootBridge`（abstract unix socket + token + MCP）提供给 DSH：

- `status` — su 路径、管理器、内核、ABI、引擎、frida 状态
- `exec` — 设备上以 root 跑一条命令
- `guest` — rootfs 里以 root 跑一条命令
- `storage_fix` — 把 `Android/data`、`Android/obb` 里被我们写坏的属主还给对应的 App
- `frida_start` / `frida_stop` / `frida_status` / `frida_ps`
- `frida_script` — 对进程 attach/spawn 跑一段 Frida JS

总开关在设置页；关掉后 overlay 不注入，模型完全看不到这些工具。

## 别的 App 忽然写不了自己的目录

每个 App 在共享存储上的私有目录（`/sdcard/Android/data/<包名>`、`Android/obb/<包名>`）属主必须
是那个 App 自己的 uid（组 `ext_data_rw` / `ext_obb_rw`，权限 0770），只有属主能写。

guest 里干的活不是以那个 App 的身份写的：proot / direct 引擎下写入者是本应用的 uid，root 引擎下
是 uid 0。所以往别人的私有目录里搬数据、解压、恢复，会在那里留下我们名下（或 root 名下）的文件和
子目录，原 App 从此写不动自己的目录——典型症状：

- 游戏客户端热更器建不了临时文件：`create file failed ... ->13`（EACCES）→ 热更初始化失败
  （错误码 `70254604`）→ 弹「请检查储存权限或磁盘空间」，进不去游戏；
- QQ 存不下图片，发图直接失败。

`ExternalStorage.repair()` 用 root 把这些条目 chown 回 `<该包 uid>:1078`（obb 是 `:1079`），只改
属主、不动内容和权限位，未安装的目录只报告不碰。它有两个入口：

1. root 引擎每次启动时自动在后台跑一遍（把上一次会话留下的收干净）；
2. 设置 → Root 权限 → 「修复 rootfs 与外部存储（挂载 + 属主）」按一下，或让模型调
   `mcp__root__storage_fix`。

## 自己构建

推 `root-build` 分支就会跑 `.github/workflows/build-root-apk.yml`：
取 1.0.9 的预编译运行时 → `flutter build apk` → 产出 `dsh-go-root-<版本>-arm64.apk`。

带自己的签名就加四个 secret：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、
`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；不加就是 debug 签名。

## 装之前

- 需要 Magisk / KernelSU / APatch 之一，且给这个 App 授予 root。
- 第一次启动仍会解压 rootfs（约 150 MB 压缩、800 MB 展开），和官方版一样。
- 打开真 root 引擎后，本机 `/data` 对它可见——这是这个版本的目的，也意味着别让模型乱跑命令。
