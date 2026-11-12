#!/system/bin/sh
# Frida Control v2 — 控制脚本
#
# 用法:
#   ctl.sh start              启动（自动补 ART 环境）
#   ctl.sh stop               停止
#   ctl.sh restart            重启
#   ctl.sh status             状态（JSON，机器可读）
#   ctl.sh json               同 status，但永远输出合法 JSON
#   ctl.sh probe              只读探测，不启动任何进程
#   ctl.sh set PORT 27043     改配置项
#   ctl.sh get PORT           读配置项
#   ctl.sh log [n]            最近 n 行日志（默认 20）
#
# 退出码:
#   0 成功 / 2 配置错 / 3 二进制缺失 / 4 启动失败 / 5 端口被占
#
# 设计要点:
#   - status/json 输出 JSON，调用方不必解析中文
#   - start 自动补 BOOTCLASSPATH / DEX2OATBOOTCLASSPATH（否则 frida-server abort）
#   - 启动失败时把真实原因写进 JSON 的 error 字段
#   - 不写系统属性、不挂载路径、不加载 SELinux 规则

case "$PATH" in
  *"/data/adb/ksu/bin"*) ;;
  *) PATH="/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk:$PATH"; export PATH ;;
esac

MODDIR=${0%/*}
case "$MODDIR" in
  */*) ;;
  *) MODDIR=. ;;
esac
[ -f "$MODDIR/ctl.sh" ] || MODDIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
[ -f "$MODDIR/ctl.sh" ] || MODDIR=/data/adb/modules/frida-control

CONF="$MODDIR/config.prop"
STATE="$MODDIR/state"
LOG="$STATE/frida.log"
mkdir -p "$STATE" 2>/dev/null

# ---- 配置 ----
MODE=local
PORT=27043
AUTOSTART=0
HIDE=1
ART_ENV=1
START_WAIT_MS=8000
[ -f "$CONF" ] && . "$CONF" 2>/dev/null
[ -z "$MODE" ] && MODE=local
[ -z "$PORT" ] && PORT=27043
[ -z "$START_WAIT_MS" ] && START_WAIT_MS=8000

HIDE_DIR=/data/adb/frida-hide
HIDE_NAME=ksud_helper
LEGACY=/data/local/tmp/frida-server
[ "$HIDE" = "1" ] && BIN="$HIDE_DIR/$HIDE_NAME" || BIN="$LEGACY"

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null; }

# ---- JSON 工具 ----
# 转义成 JSON 字符串（去控制字符、转义引号反斜杠）
jstr() {
  printf '%s' "$1" | tr -d '\000-\037' | sed 's/\\/\\\\/g; s/"/\\"/g'
}
# 键值对：jk key value  （值按字符串处理；纯数字可用 jn）
jk() { printf '"%s":"%s"' "$(jstr "$1")" "$(jstr "$2")"; }
jn() { case "$2" in ''|*[!0-9-]*) printf '"%s":null' "$(jstr "$1")" ;; *) printf '"%s":%s' "$(jstr "$1")" "$2" ;; esac; }
jb() { [ "$2" = "1" ] && printf '"%s":true' "$(jstr "$1")" || printf '"%s":false' "$(jstr "$1")"; }

# ---- 进程 ----
# 只按 pidof 的进程名匹配，避免 ps|awk 把自己算进去
fpid() {
  for n in "$HIDE_NAME" frida-server; do
    p=$(pidof "$n" 2>/dev/null | awk '{print $1}')
    [ -n "$p" ] && { echo "$p"; return 0; }
  done
  # 兜底：扫 cmdline，用方括号避免匹配到本脚本
  # 兜底：扫 cmdline。必须排除自己和父进程，否则本脚本（cmdline 里含名字）会被算进去。
  ME=$$; PA=$(awk '{print $4}' "/proc/$$/stat" 2>/dev/null)
  for d in /proc/[0-9]*; do
    pid=${d#/proc/}
    case "$pid" in ''|*[!0-9]*) continue ;; esac
    [ "$pid" = "$ME" ] && continue
    [ "$pid" = "$PA" ] && continue
    [ -r "$d/cmdline" ] || continue
    # 只看可执行文件名（argv[0]），不看整个命令行
    a0=$(tr '\0' '\n' < "$d/cmdline" 2>/dev/null | head -1)
    case "${a0##*/}" in
      "$HIDE_NAME"|frida-server) echo "$pid"; return 0 ;;
    esac
  done
  echo ""
}

# ---- 端口 ----
# 返回 0=未监听 1=仅回环 2=全部地址
port_state() {
  [ "$PORT" -gt 0 ] 2>/dev/null || { echo 0; return; }
  h=$(printf '%04X' "$PORT")
  r=$(awk -v h="$h" 'NR>1 { split($2,a,":"); if (toupper(a[2])==h && $4=="0A") print a[1] }' \
        /proc/net/tcp /proc/net/tcp6 2>/dev/null)
  [ -z "$r" ] && { echo 0; return; }
  case "$r" in
    *00000000*) echo 2 ;;
    *:)         echo 2 ;;
    *)          echo 1 ;;
  esac
}

conn_count() {
  [ "$PORT" -gt 0 ] 2>/dev/null || { echo 0; return; }
  h=$(printf '%04X' "$PORT")
  n=$(awk -v h="$h" 'NR>1 { split($2,a,":"); if (toupper(a[2])==h && $4=="01") c++ } END { print c+0 }' \
        /proc/net/tcp /proc/net/tcp6 2>/dev/null)
  case "$n" in ''|*[!0-9]*) n=0 ;; esac
  echo "$n"
}

# ---- 二进制 ----
ensure_hidden() {
  [ "$HIDE" = "1" ] || return 0
  if [ -f "$BIN" ]; then
    chmod 0700 "$BIN" 2>/dev/null
    chmod 0700 "$HIDE_DIR" 2>/dev/null
    [ -f "$LEGACY" ] && rm -f "$LEGACY" 2>/dev/null
    return 0
  fi
  [ -f "$LEGACY" ] || return 1
  mkdir -p "$HIDE_DIR" 2>/dev/null
  chmod 0700 "$HIDE_DIR" 2>/dev/null
  cp -f "$LEGACY" "$BIN" 2>/dev/null || return 1
  chmod 0700 "$BIN" 2>/dev/null
  rm -f "$LEGACY" 2>/dev/null
  log "MIGRATE $LEGACY -> $BIN"
  return 0
}

bin_version() {
  [ -x "$BIN" ] || { echo ""; return; }
  # 直接跑二进制读版本；失败给空
  v=$("$BIN" --version 2>/dev/null | tail -1)
  echo "$v" | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+' | head -1
}

# ---- ART 环境 ----
# frida-server 在 su -c 里起不来，因为 BOOTCLASSPATH 空。
# 从 zygote 拿真实值；拿不到就用 /apex/com.android.art/javalib/*.jar 兜底。
art_env() {
  [ "$ART_ENV" = "1" ] || return 0
  ZY=$(pidof zygote64 2>/dev/null | awk '{print $1}')
  [ -z "$ZY" ] && ZY=$(pidof zygote 2>/dev/null | awk '{print $1}')
  if [ -n "$ZY" ] && [ -r "/proc/$ZY/environ" ]; then
    for E in $(tr '\000' '\n' < "/proc/$ZY/environ" 2>/dev/null); do
      case "$E" in
        BOOTCLASSPATH=*|DEX2OATBOOTCLASSPATH=*|ANDROID_ROOT=*|ANDROID_DATA=*|ANDROID_ART_ROOT=*|ANDROID_I18N_ROOT=*|ANDROID_TZDATA_ROOT=*|ANDROID_STORAGE=*)
          export "$E" ;;
      esac
    done
  fi
  export ANDROID_ROOT="${ANDROID_ROOT:-/system}"
  export ANDROID_DATA="${ANDROID_DATA:-/data}"
  export ANDROID_ART_ROOT="${ANDROID_ART_ROOT:-/apex/com.android.art}"
  export ANDROID_I18N_ROOT="${ANDROID_I18N_ROOT:-/apex/com.android.i18n}"
  export ANDROID_TZDATA_ROOT="${ANDROID_TZDATA_ROOT:-/apex/com.android.tzdata}"
  export ANDROID_STORAGE="${ANDROID_STORAGE:-/storage}"
  if [ -z "$BOOTCLASSPATH" ]; then
    BOOTCLASSPATH=$(ls /apex/com.android.art/javalib/*.jar 2>/dev/null | tr '\n' ':')
    BOOTCLASSPATH="${BOOTCLASSPATH%%:}"
    export BOOTCLASSPATH
  fi
  [ -z "$DEX2OATBOOTCLASSPATH" ] && export DEX2OATBOOTCLASSPATH="$BOOTCLASSPATH"
  return 0
}

listen_addr() {
  case "$MODE" in
    wifi) echo "0.0.0.0" ;;
    *)    echo "127.0.0.1" ;;
  esac
}

# ---- 启动 ----
do_start() {
  ensure_hidden
  if [ ! -f "$BIN" ]; then
    ERR="二进制不存在：$BIN"
    ERR_CODE="no_binary"
    log "START FAIL $ERR"
    return 3
  fi
  [ -x "$BIN" ] || chmod 0700 "$BIN" 2>/dev/null

  # 已运行就先停，避免两个进程抢端口
  p=$(fpid)
  [ -n "$p" ] && do_stop >/dev/null 2>&1

  # 端口被别的程序占着：先说清楚，别让 frida 静默失败
  if [ "$(port_state)" != "0" ]; then
    ERR="端口 $PORT 已被占用"
    ERR_CODE="port_busy"
    log "START FAIL $ERR"
    return 5
  fi

  art_env
  addr=$(listen_addr)

  # setsid 让它脱离 su 会话；三路重定向避免管道填满阻塞
  if command -v setsid >/dev/null 2>&1; then
    setsid "$BIN" -D -l "$addr:$PORT" </dev/null >>"$LOG" 2>&1 &
  else
    nohup "$BIN" -D -l "$addr:$PORT" </dev/null >>"$LOG" 2>&1 &
  fi

  # 等进程 + 端口双就绪，别只看进程（进程在但没监听是常见失败）
  waited=0
  while [ "$waited" -lt "$START_WAIT_MS" ]; do
    sleep 0.25
    waited=$((waited + 250))
    p=$(fpid)
    [ -n "$p" ] && [ "$(port_state)" != "0" ] && break
  done

  p=$(fpid)
  if [ -z "$p" ]; then
    # 没进程：从日志尾巴里找可读原因
    tail_txt=$(tail -n 5 "$LOG" 2>/dev/null | tr '\n' ' ' | tr -d '\r')
    case "$tail_txt" in
      *BOOTCLASSPATH*) ERR="frida-server 缺 BOOTCLASSPATH 起不来（试试把 ART_ENV 设为 1）" ;;
      *"Permission denied"*) ERR="SELinux 或权限拒绝执行 $BIN" ;;
      *"Text file busy"*) ERR="二进制正被占用，先 stop 再试" ;;
      *"Address already in use"*) ERR="端口 $PORT 被占用" ;;
      "") ERR="进程没有起来，日志为空" ;;
      *) ERR="进程没有起来：$(printf '%s' "$tail_txt" | cut -c1-160)" ;;
    esac
    ERR_CODE="start_failed"
    log "START FAIL $ERR"
    return 4
  fi

  if [ "$(port_state)" = "0" ]; then
    ERR="进程在（pid=$p）但端口 $PORT 没有监听，可能还在初始化"
    ERR_CODE="not_listening"
    log "START WARN $ERR"
    return 4
  fi

  echo "$p" > "$STATE/pid" 2>/dev/null
  log "START pid=$p mode=$MODE addr=$addr:$PORT"
  ERR=""
  ERR_CODE=""
  return 0
}

do_stop() {
  p=$(fpid)
  if [ -z "$p" ]; then
    log "STOP (not running)"
    return 0
  fi
  kill "$p" 2>/dev/null
  i=0
  while [ "$i" -lt 20 ]; do
    sleep 0.25
    [ -z "$(fpid)" ] && break
    i=$((i + 1))
  done
  p=$(fpid)
  [ -n "$p" ] && kill -9 "$p" 2>/dev/null
  rm -f "$STATE/pid" 2>/dev/null
  log "STOP pid=$p"
  return 0
}

# ---- JSON 输出 ----
emit_json() {
  p=$(fpid)
  ps=$(port_state)
  cc=$(conn_count)
  [ -x "$BIN" ] && have_bin=1 || have_bin=0
  ver=$(bin_version)
  addr=$(listen_addr)
  listening=0
  [ "$ps" != "0" ] && listening=1
  running=0
  [ -n "$p" ] && running=1

  printf '{'
  printf '%s,' "$(jk module frida-control)"
  printf '%s,' "$(jk version v2.0.0)"
  printf '%s,' "$(jb present 1)"
  printf '%s,' "$(jb binary "$have_bin")"
  printf '%s,' "$(jk path "$BIN")"
  printf '%s,' "$(jk version_server "$ver")"
  printf '%s,' "$(jk mode "$MODE")"
  printf '%s,' "$(jn port "$PORT")"
  printf '%s,' "$(jk addr "$addr")"
  printf '%s,' "$(jb running "$running")"
  printf '%s,' "$(jk pid "$p")"
  printf '%s,' "$(jb listening "$listening")"
  printf '%s,' "$(jn port_state "$ps")"
  printf '%s,' "$(jn connections "$cc")"
  printf '%s,' "$(jb hidden "$HIDE")"
  printf '%s,' "$(jb autostart "$AUTOSTART")"
  printf '%s,' "$(jb art_env "$ART_ENV")"
  if [ -n "$p" ] && [ "$listening" = "1" ]; then
    printf '"state":"RUNNING"'
  elif [ -z "$p" ] && [ "$have_bin" = "0" ]; then
    printf '"state":"NO_BINARY"'
  elif [ -n "$p" ]; then
    printf '"state":"NOT_LISTENING"'
  else
    printf '"state":"STOPPED"'
  fi
  if [ -n "$ERR" ]; then
    printf ',"error":%s,"errorCode":%s' "$(jstr "$ERR" | sed 's/^/"/; s/$/"/')" "$(jstr "$ERR_CODE" | sed 's/^/"/; s/$/"/')"
  fi
  printf '}'
}

# ---- 人读状态 ----
do_status() {
  p=$(fpid)
  ps=$(port_state)
  cc=$(conn_count)
  ver=$(bin_version)
  addr=$(listen_addr)
  echo "========== Frida Control v2 =========="
  case "$MODE" in
    wifi)  echo "监听模式 : wifi   ($addr:$PORT，局域网可连)" ;;
    local) echo "监听模式 : local  ($addr:$PORT，仅设备自身)" ;;
    usb)   echo "监听模式 : usb    ($addr:$PORT，供 adb forward)" ;;
    *)     echo "监听模式 : $MODE  ($addr:$PORT)" ;;
  esac
  if [ -n "$p" ]; then
    echo "进程     : 运行中 (pid=$p)"
  else
    echo "进程     : 未运行"
  fi
  case "$ps" in
    2) echo "端口     : 监听中（全部地址）" ;;
    1) echo "端口     : 监听中（仅回环）" ;;
    *) echo "端口     : 未监听" ;;
  esac
  [ "$cc" -gt 0 ] 2>/dev/null && echo "连接     : $cc 条已建立"
  if [ -x "$BIN" ]; then
    echo "二进制   : $BIN"
    [ -n "$ver" ] && echo "版本     : $ver"
  else
    echo "二进制   : 缺失（$BIN）"
  fi
  [ "$HIDE" = "1" ] && echo "隐藏     : 开（0700，非 root 不可见）" || echo "隐藏     : 关（$LEGACY）"
  [ "$ART_ENV" = "1" ] && echo "ART 环境 : 自动补（BOOTCLASSPATH）" || echo "ART 环境 : 不补"
  [ "$AUTOSTART" = "1" ] && echo "开机自启 : 开" || echo "开机自启 : 关（建议保持，避免留特征）"
  if [ -n "$ERR" ]; then
    echo "错误     : $ERR"
  fi
  echo "======================================"
}

# ---- 配置读写 ----
do_get() {
  case "$1" in
    MODE) echo "$MODE" ;; PORT) echo "$PORT" ;; AUTOSTART) echo "$AUTOSTART" ;;
    HIDE) echo "$HIDE" ;; ART_ENV) echo "$ART_ENV" ;; START_WAIT_MS) echo "$START_WAIT_MS" ;;
    *) echo "未知配置项: $1" >&2; return 2 ;;
  esac
}

do_set() {
  k=$1; v=$2
  [ -z "$k" ] && { echo "用法: ctl.sh set <键> <值>" >&2; return 2; }
  case "$k" in
    MODE) case "$v" in local|usb|wifi) ;; *) echo "MODE 只能是 local/usb/wifi" >&2; return 2 ;; esac ;;
    PORT) case "$v" in ''|*[!0-9]*) echo "PORT 必须是数字" >&2; return 2 ;; esac ;;
    AUTOSTART|HIDE|ART_ENV) case "$v" in 0|1) ;; *) echo "$k 只能是 0 或 1" >&2; return 2 ;; esac ;;
    START_WAIT_MS) case "$v" in ''|*[!0-9]*) echo "START_WAIT_MS 必须是数字" >&2; return 2 ;; esac ;;
    *) echo "未知配置项: $k" >&2; return 2 ;;
  esac
  [ -f "$CONF" ] || : > "$CONF"
  if grep -q "^$k=" "$CONF" 2>/dev/null; then
    sed -i "s|^$k=.*|$k=$v|" "$CONF" 2>/dev/null
  else
    echo "$k=$v" >> "$CONF" 2>/dev/null
  fi
  grep -q "^$k=$v\$" "$CONF" 2>/dev/null || { echo "写入失败（$CONF 只读？）" >&2; return 2; }
  echo "$k=$v"
  return 0
}

# ---- 只读探测 ----
do_probe() {
  have=0; [ -x "$BIN" ] && have=1
  printf '{'
  printf '%s,' "$(jb present 1)"
  printf '%s,' "$(jb binary "$have")"
  printf '%s,' "$(jk path "$BIN")"
  printf '%s,' "$(jk version_server "$(bin_version)")"
  printf '%s,' "$(jn port "$PORT")"
  printf '%s,' "$(jk state "$( [ -n "$(fpid)" ] && echo RUNNING || echo STOPPED)")"
  printf '"readOnly":true}'
}

# ---- 主分发 ----
ERR=""
ERR_CODE=""
case "$1" in
  start)
    do_start
    rc=$?
    if [ $rc -ne 0 ]; then
      emit_json; echo; exit $rc
    fi
    emit_json; echo; exit 0
    ;;
  stop)
    do_stop
    emit_json; echo; exit 0
    ;;
  restart)
    do_stop >/dev/null 2>&1
    sleep 0.5
    do_start
    rc=$?
    emit_json; echo; exit $rc
    ;;
  status|json)
    # status 先在 stderr 给人看，stdout 只放 JSON，方便管道解析
    if [ "$1" = "status" ] && [ "$2" != "--json" ]; then
      do_status >&2
    fi
    emit_json; echo; exit 0
    ;;
  probe)
    do_probe; echo; exit 0
    ;;
  get)   do_get "$2" ;;
  set)   do_set "$2" "$3" ;;
  log)
    n=${2:-20}
    tail -n "$n" "$LOG" 2>/dev/null || echo "（没有日志）"
    ;;
  *)
    echo "用法: ctl.sh start|stop|restart|status|json|probe|get <键>|set <键> <值>|log [n]" >&2
    exit 2
    ;;
esac
