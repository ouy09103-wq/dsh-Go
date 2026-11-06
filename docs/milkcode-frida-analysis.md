# MilkCode 1.0.5 的 Frida 内置方案（反编译分析）

分析对象：`/sdcard/DSHA/MilkCode1.0.5.apk`（407,957,363 字节，831 个条目，21 个 dex）
方法：纯 Python DEX 解析（无 JVM），定位到 `classes5.dex` 的
`com.milkcode.agent.terminal.FridaDeviceInstaller`

## 一、它怎么打包

```
assets/frida/frida-server-17.23.3-android-arm64
  压缩方式  deflate（zip 默认），不是 xz
  压缩后    26,713,044 字节（26.7 MB）
  解压后    59,175,728 字节（59.2 MB）
  压缩比    2.22
  sha256    8887fb3dc84222416e2cdde706a10037c920df01d7858025168739d926c2fe17
```

**关键选择：用 deflate 而不是 xz。** 代价是 APK 大 2.5 MB，换来两件事：

1. 不需要 xz 解压库（我们的 APK 依赖 `org.tukaani:xz`，因为 rootfs 也用它，但单就
   frida 而言可以省掉）
2. 不需要「先解压到临时文件」这一步 —— Android 的 AssetManager 能直接流式读

## 二、它的完整流程

`FridaDeviceInstaller` 的 API：

```
ensureLocalBinary(Context): File     从 asset 解到应用私有目录
isVerified(File): Boolean            比对 size + sha256
sha256(File): String                 SHA-256 摘要
runRoot(String, long): Result       通过 BoundedRootCommandExecutor 跑 root 命令
buildLaunchScript(String): String   拼启动脚本
buildStatusScript(): String         拼状态查询脚本
installAndStart(Context, ...)       主流程
status(Context, ...)                状态查询
```

### 2.1 解压 + 校验

```
文件名校验   frida-server-17.23.3
临时后缀     .tmp
asset 路径   frida/frida-server-17.23.3-android-arm64
错误文案     frida-server 写入应用目录失败
            内置 frida-server 校验失败（size/sha256 不匹配）
```

流程：解压到 `<filesDir>/frida-server-17.23.3.tmp` → 校验 size + sha256 →
通过才 rename 成正式名。**先临时文件再 rename**，和我们修 ETXTBSY 的做法一样。

### 2.2 启动脚本（完整原文）

```sh
B=/data/local/tmp/frida-server;
cp <localBinaryPath> "$B.new" || exit 1;
chmod 0755 "$B.new" || exit 1;
mv -f "$B.new" "$B" || exit 1;
for P in $(pidof frida-server 2>/dev/null); do kill "$P" 2>/dev/null || true; done;
sleep 1;
for P in $(pidof frida-server 2>/dev/null); do kill -9 "$P" 2>/dev/null || true; done;
if command -v setsid >/dev/null 2>&1; then
  setsid "$B" </dev/null >/dev/null 2>&1 &
elif command -v nohup >/dev/null 2>&1; then
  nohup "$B" </dev/null >/dev/null 2>&1 &
else
  "$B" </dev/null >/dev/null 2>&1 &
fi;
sleep 2;
if [ -n "$(pidof frida-server 2>/dev/null)" ]; then echo FRIDA_RUNNING; exit 0; fi;
echo FRIDA_NOT_RUNNING; exit 1
```

三个值得抄的点：

- **`cp` 到 `$B.new` 再 `mv -f`** —— 这正是我们修 Text file busy 用的办法，它一直在用
- **`setsid` → `nohup` → 裸跑** 三级降级，不是只试 `setsid`
- **`</dev/null >/dev/null 2>&1`** 三处都加，避免管道卡死

### 2.3 状态查询脚本

```sh
if [ -x /data/local/tmp/frida-server ]; then
  if [ -n "$(pidof frida-server 2>/dev/null)" ]; then echo FRIDA_RUNNING;
  else echo FRIDA_STOPPED; fi;
else echo FRIDA_ABSENT; fi
```

注意它用 **`pidof`**，不是 `pgrep -f`。`pidof` 比对的是进程名，不会匹配到自己。
这正是我们踩过的坑 —— 用 `pgrep -f` 会匹配执行它的那个 shell。

### 2.4 状态枚举

```kotlin
enum class Status { UNKNOWN, ROOT_REQUIRED, NOT_INSTALLED, STOPPED, RUNNING }
```

返回值是 `Result(ok: Boolean, status: Status, detail: String)`。

`installAndStart` 的判定顺序：

```
1. root 不可用        → ROOT_REQUIRED，"需要设备 Root 授权"
2. 脚本输出 FRIDA_RUNNING   → 成功
3. 脚本输出 FRIDA_NOT_RUNNING → 失败
4. 解压/校验失败      → "内置 frida-server 解出失败"
```

日志格式：

```
frida_server_provision status=<x> exit=<n> timedOut=<bool>
Agent root command outcome=<completed|failed> exit=<n> timeout=<bool> output_chars=<n> truncated=<bool>
```

## 三、和我们的实现对比

| 项 | MilkCode | 我们 | 谁好 |
|---|---|---|---|
| 压缩 | deflate 26.7 MB | xz 24.2 MB | 我们小 2.5 MB |
| 解压依赖 | 无（AssetManager 直接读） | 需 xz 库 | MilkCode 更省 |
| 临时文件 | `.tmp` 再 rename | 同（已修） | 平 |
| sha256 校验 | 有 | 有 | 平 |
| ETXTBSY | `cp $B.new` + `mv` | 同（刚修） | 平 |
| 启动降级 | setsid → nohup → 裸跑 | 只 setsid → 裸跑 | **MilkCode 更稳** |
| 进程检测 | `pidof` | `pidof` + `pgrep -f [f]` | 我们更全 |
| 端口 | 默认 27042，不可配 | 可配 | **我们更灵活** |
| 隐身 | 无 | 改名 + 112 处字符串改写 | **我们更强** |
| 状态粒度 | 5 个枚举值 | 布尔 + 详情 | MilkCode 更结构化 |

## 四、值得吸收的三点

### 4.1 `nohup` 中间降级

我们只有 `setsid` 和裸跑。有些 ROM 的 toybox 没有 `setsid`，`nohup` 可能还在。
加上这一级能多一层保障。

### 4.2 状态用枚举而不是布尔

`ROOT_REQUIRED` / `NOT_INSTALLED` / `STOPPED` / `RUNNING` 四态比
`installed: bool` + `running: bool` 更清楚，模型不用自己组合。

### 4.3 `pidof` 而不是 `pgrep -f`

它全程用 `pidof`，天然避开自匹配。我们用 `pgrep -f` 是为了匹配改名后的
`netd_helper`，但可以先用 `pidof`，失败再退回 `pgrep`。

## 五、不建议抄的

- **deflate 而不是 xz**：APK 会大 2.5 MB。我们的 xz 库已经在用（rootfs 解压需要），
  没必要为了省这个换格式
- **固定端口 27042**：这正是 chunqiu 那份报告里被探测的点，我们可配更好
- **17.23.3 版本**：`pip3 install frida` 现在给 17.25.2，版本不匹配会让 attach 全超时
  （milkcode 那份报告实测 25 秒超时）。我们已经对齐到 17.25.2
