#!/system/bin/sh
BB=/system/bin/busybox
echo "== /data/local/tmp =="
$BB ls -la /data/local/tmp
echo
echo "== 所有含 sh / busybox 的进程（含完整 cmdline）=="
for p in /proc/[0-9]*; do
    pid=${p#/proc/}
    c=$($BB tr '\0' ' ' < "$p/cmdline" 2>/dev/null)
    case "$c" in
        *busybox*|*sh\ *|*/sh*) echo "$pid: $c" ;;
    esac
done
echo
echo "== 我们的 HID 设备节点 =="
$BB ls -l /sys/class/input/event11/device/name 2>/dev/null
$BB cat /sys/class/input/event11/device/name 2>/dev/null
