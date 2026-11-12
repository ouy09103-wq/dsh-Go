#!/system/bin/sh
# Frida Control v2 — KernelSU「操作」按钮
# 非交互: action.sh on|off|status|json|local|wifi
MODDIR=${0%/*}
case "$MODDIR" in */*) ;; *) MODDIR=/data/adb/modules/frida-control ;; esac
ctl() { sh "$MODDIR/ctl.sh" "$@"; }

case "$1" in
  on)     rm -f "$MODDIR/disable" 2>/dev/null; ctl start; exit $? ;;
  off)    ctl stop; exit $? ;;
  status) ctl status; exit $? ;;
  json)   ctl json; exit $? ;;
  local)  ctl set MODE local >/dev/null; ctl restart; exit $? ;;
  wifi)   ctl set MODE wifi  >/dev/null; ctl restart; exit $? ;;
esac

echo "======================================="
echo "          Frida Control v2"
echo "======================================="
sh "$MODDIR/ctl.sh" status 2>&1 | sed -n '2,9p'
echo ""
echo "  1) 启动"
echo "  2) 停止"
echo "  3) 切到 local 模式（仅设备自身）"
echo "  4) 切到 wifi 模式（局域网）"
echo "  5) 完整状态"
echo "  6) 最近日志"
echo "  0) 退出"
echo ""
printf "请选择 [0-6]: "
read choice
case "$choice" in
  1) rm -f "$MODDIR/disable" 2>/dev/null; ctl start ;;
  2) ctl stop ;;
  3) ctl set MODE local >/dev/null; ctl restart ;;
  4) ctl set MODE wifi  >/dev/null; ctl restart ;;
  5) ctl status ;;
  6) ctl log 30 ;;
  0|"") echo "已取消" ;;
  *) echo "无效选择: $choice" ;;
esac
