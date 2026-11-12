#!/system/bin/sh
# klbind2.sh —— 只覆盖「厂商表 + 按名字匹配的表」，打开 key 116/152 POWER。
# 不动 Generic.kl（那会引起 EventHub 解析异常）。restore 可完全还原。
BB=/system/bin/busybox
SRC_DIR=/system/usr/keylayout
WORK=/data/local/keylayout/work2
LOG=/data/local/keylayout/klbind2.log
TARGETS="Vendor_0416_Product_0300.kl CH_BT_RC.kl"

log() { echo "[$($BB date '+%F %T')] $*" >> "$LOG"; }

case "$1" in
status)
    $BB mount | $BB grep keylayout || echo "(没有 bind mount)"
    for f in $TARGETS; do
        echo "$f: $($BB grep -n -E '^key +116' "$SRC_DIR/$f" 2>/dev/null || echo '(116 未开启)')"
    done
    exit 0
    ;;
restore)
    for f in $TARGETS; do
        $BB umount "$SRC_DIR/$f" 2>/dev/null && { echo "已卸载 $f"; log "umount $f"; }
    done
    exit 0
    ;;
esac

$BB mkdir -p "$WORK"
for f in $TARGETS; do
    SRC="$SRC_DIR/$f"
    [ -f "$SRC" ] || continue
    $BB umount "$SRC" 2>/dev/null
    $BB cp "$SRC" "$WORK/$f" || { log "cp failed $f"; continue; }
    $BB sed -i 's|^# *key 116  *POWER|key 116   POWER|; s|^# *key 152  *POWER|key 152   POWER|' "$WORK/$f"
    $BB chmod 644 "$WORK/$f"
    $BB mount -o bind "$WORK/$f" "$SRC" && log "bind ok $f" || log "bind FAILED $f"
done
for f in $TARGETS; do
    echo "$f: $($BB grep -n -E '^key +116' "$SRC_DIR/$f" 2>/dev/null || echo '(未开启)')"
done
log "install2 done"
