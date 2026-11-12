#!/system/bin/sh
# klbind.sh —— 用 bind mount 把"打开 key 116 POWER"的键位表盖到 /system 上。
# 不改 /system 内容、不重挂分区；可被 boot.sh 开机调用，也可手动执行。
#
#   sh /data/local/keylayout/klbind.sh install   # 挂上（幂等）
#   sh /data/local/keylayout/klbind.sh status    # 查看当前状态
#   sh /data/local/keylayout/klbind.sh remove    # 卸掉，恢复原厂键位表
#
# 原理：这台电视 /system 只读且 remount 被拒（Operation not permitted），但 bind mount 允许，
# 于是把 /data 上的改好副本盖到 /system/usr/keylayout/*.kl 上。EventHub 在输入设备创建时
# 读取该路径，所以改完要让 HID 设备重建（手机端"断开重连"）才生效。
# 副作用范围：只多出 key 116/152 = POWER 两行，厂商键位一行不改。

BB=/system/bin/busybox
SRC_DIR=/system/usr/keylayout
BASE=/data/local/keylayout
WORK=$BASE/work
LOG=$BASE/klbind.log
TARGETS="Vendor_0416_Product_0300.kl CH_BT_RC.kl"

log() { echo "[$($BB date '+%F %T')] $*" >> "$LOG"; }

case "$1" in
status)
    echo "-- mount --"
    $BB mount | $BB grep keylayout || echo "(没有 bind mount)"
    echo "-- 生效内容 --"
    for f in $TARGETS; do
        [ -f "$SRC_DIR/$f" ] || continue
        echo "$f: $($BB grep -n -E '^key +116' "$SRC_DIR/$f" 2>/dev/null || echo '(116 未开启)')"
    done
    exit 0
    ;;
remove)
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
    # 已有挂载先摘掉，否则 cp 会把我们自己的副本读回来
    $BB umount "$SRC" 2>/dev/null
    $BB cp "$SRC" "$WORK/$f" || { log "cp failed $f"; continue; }
    $BB sed -i 's|^# *key 116  *POWER|key 116   POWER|; s|^# *key 152  *POWER|key 152   POWER|' "$WORK/$f"
    $BB chmod 644 "$WORK/$f"
done

for f in $TARGETS; do
    SRC="$SRC_DIR/$f"
    [ -f "$WORK/$f" ] || continue
    if $BB mount -o bind "$WORK/$f" "$SRC" 2>/dev/null; then
        log "bind ok $f"
    else
        log "bind FAILED $f"
    fi
done

for f in $TARGETS; do
    echo "$f: $($BB grep -n -E '^key +116' "$SRC_DIR/$f" 2>/dev/null || echo '(未开启)')"
done
log "install done"
