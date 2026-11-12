#!/system/bin/sh
# 把 klbind 安装挂进电视的开机流程（/data/local/webremote/boot.sh），幂等。
BB=/system/bin/busybox
BOOT=/data/local/webremote/boot.sh
MARK="klbind.sh install"

if [ ! -f "$BOOT" ]; then
    echo "找不到 $BOOT"
    exit 1
fi

if $BB grep -q "$MARK" "$BOOT"; then
    echo "开机钩子已存在，无需重复添加"
    exit 0
fi

$BB cp "$BOOT" "$BOOT.bak.klbind" && echo "已备份 $BOOT.bak.klbind"

$BB cat >> "$BOOT" <<'EOF'

# --- BLE 电源开机钩子：把"打开 key 116 POWER"的键位表 bind mount 回 /system ---
# 逻辑在 /data/local/keylayout/klbind.sh，可用 `sh /data/local/keylayout/klbind.sh remove` 还原
if [ -f /data/local/keylayout/klbind.sh ]; then
  /system/bin/busybox sh /data/local/keylayout/klbind.sh install >> /data/local/keylayout/boot.log 2>&1
fi
EOF

echo "已追加开机钩子，尾部内容："
$BB tail -7 "$BOOT"
