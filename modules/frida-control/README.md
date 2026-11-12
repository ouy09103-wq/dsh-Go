# Frida Control 模块（App 对接用）

App 不再内置 frida-server，改为调用本模块的 `ctl.sh`。

## 安装

1. 把 `frida-control-v1.3.0.zip` 放进 KernelSU / APatch 刷入
2. 把 frida-server（17.25.x，arm64）放到：
   ```
   /data/adb/frida-hide/ksud_helper
   ```
   然后 `chmod 700 /data/adb/frida-hide/ksud_helper`
3. 回到 App 点「启用 Frida」

## App 用到的接口

| 命令 | 作用 |
|---|---|
| `sh ctl.sh start` | 起 frida（模块负责搬文件、改名、起进程） |
| `sh ctl.sh stop` | 停 |
| `sh ctl.sh status` | 状态（App 解析其中的 `frida进程` / `连接模式` / `开机自启`） |
| `sh ctl.sh set-mode usb\|wifi\|local` | 切监听地址 |

App 启动前会把 `config.prop` 的 `PORT` 改成 **27043**：27042 会被部分目标主动探测后 abort
（实测 27042 存活 2/10，27043 是 10/10）。

## 为什么模块能绕开检测

目标判据是「存在可执行段但没有对应磁盘文件」。frida-server 注入时 agent 走 memfd，
`/proc/self/maps` 里天生是 `/memfd:frida-agent-64.so (deleted)`，改名字改线程名都没用。

模块把二进制放在 `/data/adb/frida-hide/`（0700，非 root 进程内核层被拒），改名
`ksud_helper`，进程名与路径都不含 frida —— 这挡住的是**进程/路径扫描**，
memfd 那一层仍然需要 gadget + 自定义 linker 路线（见交接记录第九节）。
