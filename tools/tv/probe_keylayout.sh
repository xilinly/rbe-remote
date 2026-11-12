#!/system/bin/sh
# probe_keylayout.sh —— 只读探测 + 无害的写入可行性测试，用来确定这台电视到底能不能改键位表。
# 用法： adb push tools/tv/probe_keylayout.sh /data/local/tmp/ && adb shell sh /data/local/tmp/probe_keylayout.sh
# 它不会真的改任何系统文件（只有在 /system 可写时才会在 /system/usr/keylayout 下建一个临时测试文件并立刻删除）。

BB=/system/bin/busybox
say() { echo; echo "===== $* ====="; }

say "1. 身份与权限"
id
$BB cat /proc/self/status | $BB grep -E 'CapEff|CapBnd'

say "2. /system 挂载状态"
$BB mount | $BB grep -E ' /system | /data | / ' 

say "3. 键位表清单（vendor 0416 / CH 相关）"
$BB ls -l /system/usr/keylayout/ | $BB grep -iE '0416|CH_|chq|Generic'
echo "-- 是否存在 vendor 级 Vendor_0416.kl --"
$BB ls -l /system/usr/keylayout/Vendor_0416.kl 2>/dev/null || echo "(不存在)"

say "4. 各候选键位表里的 POWER 行"
for f in /system/usr/keylayout/Vendor_0416_Product_0300.kl \
         /system/usr/keylayout/Vendor_0416.kl \
         /system/usr/keylayout/CH_BT_RC.kl \
         /system/usr/keylayout/Generic.kl ; do
    [ -f "$f" ] || continue
    echo "-- $f"
    $BB grep -n -i 'power' "$f" | $BB grep -E '116|152|356' || echo "   (没有 116/152/356 的 POWER 行)"
done

say "5. 写 /system 的三种尝试（只读测试，不改内容）"
echo "-- 5a 直接 remount --"
$BB mount -o remount,rw /system 2>&1 && echo "   remount OK" || echo "   remount 失败"
if $BB touch /system/usr/keylayout/.probe 2>/dev/null; then
    echo "   /system 可写"
    $BB rm -f /system/usr/keylayout/.probe
    $BB mount -o remount,ro /system 2>/dev/null
else
    echo "   /system 不可写"
fi

echo "-- 5b nsenter 进 init 的挂载命名空间再 remount --"
if $BB nsenter --help >/dev/null 2>&1; then
    $BB nsenter -t 1 -m -- $BB mount -o remount,rw /system 2>&1 && echo "   nsenter remount OK" || echo "   nsenter remount 失败"
else
    echo "   busybox 没有 nsenter"
fi

echo "-- 5c bind mount 一个文件盖到键位表上 --"
echo "test" > /data/local/tmp/probe.kl
$BB mount -o bind /data/local/tmp/probe.kl /system/usr/keylayout/Vendor_0416_Product_0300.kl 2>&1 \
    && { echo "   bind mount OK（会立刻卸载）"; $BB umount /system/usr/keylayout/Vendor_0416_Product_0300.kl; } \
    || echo "   bind mount 失败"

say "6. /data/system/devices/keylayout 是否被采纳（EventHub 的候选目录）"
$BB ls -ld /data/system/devices /data/system/devices/keylayout 2>/dev/null || echo "(目录不存在)"
echo "-- 该 ROM 的 EventHub 实际解析路径请对照 logcat: logcat -d | grep 'New device' --"
$BB logcat -d 2>/dev/null | $BB grep 'New device' | $BB tail -3

say "7. 当前 HID 设备能力（找 event 节点）"
$BB getevent -i 2>/dev/null | $BB grep -A3 -B8 'CH BT RC' | $BB tail -20

echo
echo "把上面的输出整段贴回来即可判断走哪条路。"
