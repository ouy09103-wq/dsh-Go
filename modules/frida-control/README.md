# Frida Control v2

给 DSH Go 提供 frida-server 的 KernelSU / APatch 模块。

## 为什么用模块

目标 App 的检测判据是「存在可执行段但没有对应磁盘文件」。frida-server 注入时 agent 走
memfd，`/proc/self/maps` 里天生是 `/memfd:frida-agent-64.so (deleted)` —— 改名、改线程名、
mprotect、替换 abort 全部无效，因为它读内核原始 maps。

模块能做的是挡住**进程/路径扫描**：二进制放 `/data/adb/frida-hide/`（0700，非 root 内核层
就被拒），改名 `ksud_helper`，进程名和路径都不含 frida。

## 安装

1. 刷入本模块
2. 把 frida-server（17.25.x，arm64）放到 `/data/adb/frida-hide/ksud_helper`，`chmod 700`
3. `sh /data/adb/modules/frida-control/ctl.sh start`

## 接口（v2 起输出 JSON）

```sh
ctl.sh start      # 启动，返回 JSON；退出码 0/3/4/5
ctl.sh stop       # 停止，返回 JSON
ctl.sh restart    # 重启
ctl.sh json       # 状态 JSON（机器可读，stdout 只有 JSON）
ctl.sh status     # 人读状态到 stderr + JSON 到 stdout
ctl.sh probe      # 只读探测，不起进程
ctl.sh get PORT   # 读配置
ctl.sh set PORT 27043   # 写配置（带校验）
ctl.sh log [n]    # 最近 n 行日志
```

## v2 相对 v1 的改动

| # | v1 的问题 | v2 的做法 |
|---|---|---|
| 1 | 默认端口 27042（目标会探测后 abort） | 默认 **27043** |
| 2 | 启动不补 BOOTCLASSPATH，frida-server 直接 abort | `ART_ENV=1` 时从 zygote 读，拿不到用 apex jar 兜底 |
| 3 | 失败只说「启动失败，日志: …」 | JSON 里给 `error` + `errorCode` |
| 4 | `pidof -x` / `ps\|awk` 兜底会匹配到自己 | 只比 argv[0]，排除 `$$` 与父进程 |
| 5 | 只看进程在不在 | 进程 + 端口双就绪才算成功 |
| 6 | 输出中文，调用方要解析 | `json` / `probe` 输出机器可读 JSON |
| 7 | 配置只能手改文件 | `ctl.sh get/set`，带校验 |
| 8 | 端口被占时静默失败 | 启动前检查，报 `port_busy` |

## 配置（config.prop）

| 键 | 默认 | 说明 |
|---|---|---|
| `MODE` | `local` | `local` / `usb` / `wifi` |
| `PORT` | `27043` | 监听端口 |
| `AUTOSTART` | `0` | 开机自启，建议保持 0 |
| `HIDE` | `1` | 藏到 `/data/adb/frida-hide/ksud_helper` |
| `ART_ENV` | `1` | 启动时补 ART 环境 |
| `START_WAIT_MS` | `8000` | 等就绪的上限 |
