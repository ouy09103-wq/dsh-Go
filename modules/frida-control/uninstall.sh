#!/system/bin/sh
# Frida Control v2 — 卸载：停进程，保留二进制供手动使用
MODDIR=${0%/*}
case "$MODDIR" in */*) ;; *) MODDIR=/data/adb/modules/frida-control ;; esac
sh "$MODDIR/ctl.sh" stop >/dev/null 2>&1
rm -rf "$MODDIR/state" 2>/dev/null
exit 0
