#!/system/bin/sh
# 用 pidof 精确定位 system_server，检查它命名空间里能否看到 bind mount。
BB=/system/bin/busybox
SS=$($BB pidof system_server)
echo "system_server pid = $SS"
for pid in $SS; do
    echo "== pid $pid 看到的关键行 =="
    $BB grep -n -E '^(# )?key +116' /proc/$pid/root/system/usr/keylayout/Vendor_0416_Product_0300.kl 2>/dev/null || echo "(读不到)"
    echo "== pid $pid 的 mountinfo keylayout 条目 =="
    $BB grep keylayout /proc/$pid/mountinfo 2>/dev/null || echo "(没有 bind mount 记录)"
done
echo "== adb shell 自己看到的 =="
$BB grep -n -E '^key +116' /system/usr/keylayout/Vendor_0416_Product_0300.kl
