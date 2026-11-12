#!/system/bin/sh
# Frida Control v2 — 开机脚本
# 默认什么都不做（AUTOSTART=0），避免 frida-server 常驻留下进程/端口特征。
MODDIR=${0%/*}
case "$MODDIR" in */*) ;; *) MODDIR=/data/adb/modules/frida-control ;; esac
CONF="$MODDIR/config.prop"
AUTOSTART=0
[ -f "$CONF" ] && . "$CONF" 2>/dev/null
[ "$AUTOSTART" = "1" ] || exit 0
[ -f "$MODDIR/disable" ] && exit 0
sh "$MODDIR/ctl.sh" start >/dev/null 2>&1
exit 0
