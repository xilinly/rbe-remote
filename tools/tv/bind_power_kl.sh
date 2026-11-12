#!/system/bin/sh
# bind_power_kl.sh —— 用 bind mount 把改好的键位表盖到 /system 上，打开 key 116 POWER。
# 不改 /system 内容、不重挂分区；重启电视后自动失效（需要的话再加开机脚本）。
#
#   sh /data/local/tmp/bind_power_kl.sh install   # 装上
#   sh /data/local/tmp/bind_power_kl.sh status    # 查看
#   sh /data/local/tmp/bind_power_kl.sh remove    # 卸掉

BB=/system/bin/busybox
SRC_DIR=/system/usr/keylayout
WORK=/data/local/tmp/powerkl
TARGETS="Vendor_0416_Product_0300.kl CH_BT_RC.kl"

case "$1" in
status)
    echo "-- mount --"
    $BB mount | $BB grep keylayout || echo "(没有 bind mount)"
    echo "-- 生效内容 --"
    for f in $TARGETS; do
        [ -f "$SRC_DIR/$f" ] || continue
        echo "$f: $($BB grep -n -E '^key +116' "$SRC_DIR/$f" || echo '(116 未开启)')"
    done
    exit 0
    ;;
remove)
    for f in $TARGETS; do
        $BB umount "$SRC_DIR/$f" 2>/dev/null && echo "已卸载 $f"
    done
    exit 0
    ;;
esac

$BB mkdir -p $WORK
for f in $TARGETS; do
    SRC="$SRC_DIR/$f"
    [ -f "$SRC" ] || continue
    # 先确认没有旧的 bind mount，否则 cp 会把我们自己的临时文件读回来
    $BB umount "$SRC" 2>/dev/null
    $BB cp "$SRC" "$WORK/$f"
    # 打开 116 / 152 两行 POWER（原文件里是 "# key 116   POWER             WAKE"）
    $BB sed -i 's|^# *key 116  *POWER|key 116   POWER|; s|^# *key 152  *POWER|key 152   POWER|' "$WORK/$f"
    echo "-- $f 改后 --"
    $BB grep -n -E '^key +(116|152)' "$WORK/$f" || echo "   (没改到，检查原文件格式)"
    $BB chmod 644 "$WORK/$f"
done

for f in $TARGETS; do
    SRC="$SRC_DIR/$f"
    [ -f "$WORK/$f" ] || continue
    $BB mount -o bind "$WORK/$f" "$SRC" 2>&1 && echo "已 bind mount: $f" || echo "bind mount 失败: $f"
done

echo
echo "-- 结果 --"
for f in $TARGETS; do
    echo "$f: $($BB grep -n -E '^key +116' "$SRC_DIR/$f" 2>/dev/null || echo '(未开启)')"
done
echo
echo "接着要在手机端“停止”→再“连接电视（克隆广播）”，让电视重建输入节点后才会读到新键位表。"
