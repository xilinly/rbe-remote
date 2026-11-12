#!/system/bin/sh
# 诊断：EventHub 为什么给 CH BT RC 解析不出键位表
BB=/system/bin/busybox
SS=$($BB pidof system_server)
echo "system_server pid = $SS"
echo "== 三个候选文件的属性（从 adb shell 看）=="
$BB ls -l /system/usr/keylayout/Vendor_0416_Product_0300.kl /system/usr/keylayout/CH_BT_RC.kl /system/usr/keylayout/Generic.kl
echo
echo "== 从 system_server 的根看（/proc/PID/root）=="
for pid in $SS; do
    for f in Vendor_0416_Product_0300.kl CH_BT_RC.kl Generic.kl; do
        P=/proc/$pid/root/system/usr/keylayout/$f
        if [ -r "$P" ]; then
            echo "pid $pid $f: 可读, 前两个 key 行: $($BB grep -m2 -E '^key +116|^key +183' "$P" | $BB tr '\n' ' ')"
        else
            echo "pid $pid $f: 不可读"
        fi
    done
done
echo
echo "== 让 shell 以 system 身份读一次（验证权限而不是 SELinux）=="
$BB setpriv --help >/dev/null 2>&1 && echo "(setpriv 存在)" || echo "(没有 setpriv，跳过)"
echo
echo "== 最近的 New device 行 =="
$BB logcat -d | $BB grep 'New device' | $BB tail -3
echo
echo "== 设备节点 =="
$BB ls -l /dev/input/event11 2>/dev/null
