#!/system/bin/sh
# Frida Control v2 — 安装脚本（KernelSU 独立模式执行）
ui_print "- Frida Control v2 正在安装..."

for f in service.sh action.sh ctl.sh uninstall.sh; do
  [ -f "$MODPATH/$f" ] && set_perm "$MODPATH/$f" 0 0 0755
done
[ -f "$MODPATH/config.prop" ] && set_perm "$MODPATH/config.prop" 0 0 0644
[ -f "$MODPATH/module.prop" ] && set_perm "$MODPATH/module.prop" 0 0 0644

HIDE_DIR=/data/adb/frida-hide
HIDE_NAME=ksud_helper
LEGACY=/data/local/tmp/frida-server

HIDE=$(sed -n 's/^HIDE=//p' "$MODPATH/config.prop" 2>/dev/null | head -n 1)
[ -z "$HIDE" ] && HIDE=1

if [ "$HIDE" = "1" ]; then
  mkdir -p "$HIDE_DIR" 2>/dev/null
  chmod 0700 "$HIDE_DIR" 2>/dev/null
  if [ -f "$HIDE_DIR/$HIDE_NAME" ]; then
    chmod 0700 "$HIDE_DIR/$HIDE_NAME" 2>/dev/null
    ui_print "- 二进制已就位: $HIDE_DIR/$HIDE_NAME"
    [ -f "$LEGACY" ] && { rm -f "$LEGACY" 2>/dev/null; ui_print "- 已清除 $LEGACY"; }
  elif [ -f "$LEGACY" ]; then
    if cp -f "$LEGACY" "$HIDE_DIR/$HIDE_NAME" 2>/dev/null; then
      chmod 0700 "$HIDE_DIR/$HIDE_NAME" 2>/dev/null
      rm -f "$LEGACY" 2>/dev/null
      ui_print "- 已迁移并隐藏到 $HIDE_DIR/$HIDE_NAME"
    else
      ui_print "! 迁移失败"
    fi
  else
    ui_print "! 未找到 frida-server"
    ui_print "! 请放到 $HIDE_DIR/$HIDE_NAME 并 chmod 700"
  fi
else
  if [ -f "$LEGACY" ]; then
    [ -x "$LEGACY" ] || chmod 0755 "$LEGACY"
    ui_print "- 隐藏关闭，使用 $LEGACY"
  else
    ui_print "! 隐藏关闭，但未找到 $LEGACY"
  fi
fi

ui_print "- 完成。点「操作」或跑 ctl.sh start 启动"
ui_print "- 默认端口 27043（27042 会被部分目标探测后 abort）"
