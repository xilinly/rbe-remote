#!/system/bin/sh
# capture_rcu_power.sh —— 抓一次“实体遥控器按电源键”的完整证据链，用来判定电源到底走不走蓝牙。
#
# 用法（电视已开 adb、电视回到 192.168.3.x 网段）：
#   adb push tools/tv/capture_rcu_power.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/capture_rcu_power.sh
# 脚本开始后 25 秒内，用实体遥控器对着电视按一次电源键（电视会关机，属正常），
# 然后用实体遥控器再按一次把电视开回来，再执行：
#   adb shell sh /data/local/tmp/capture_rcu_power.sh report
#
# 判定标准：
#   A. getevent 里出现 EV_KEY KEY_POWER，且设备是 uhid/CH BT RC  → 电源走蓝牙，抓 DONGLE_SERVICE 帧即可复刻
#   B. KEY_POWER 出现在电视自己的 IR 接收节点（Hi keyboard / Changhong Smart TV Keypad）→ 电源是红外，BLE 做不到
#   C. 两份日志都没有 KEY_POWER，但 logcat 里有 DONGLE_SERVICE 的 [N bytes]: 帧 → 电源走厂商 BLE 报文，抄帧即可
#   D. 什么都没有 → 遥控器可能用的是 2.4G 私有协议或红外载波，BLE 这条路到头了

BB=/system/bin/busybox
OUT=/data/local/tmp/rcu_power

if [ "$1" = "report" ]; then
    echo "===== logcat 里的 HID / dongle 帧 ====="
    $BB grep -iE 'DONGLE_SERVICE|recv report id|bytes\]|HidService|New device' $OUT/logcat.txt 2>/dev/null | $BB tail -60
    echo
    echo "===== getevent 里的按键 ====="
    $BB grep -iE 'KEY_POWER|EV_KEY' $OUT/getevent.txt 2>/dev/null | $BB head -60
    echo
    echo "===== 电源相关 framework 日志 ====="
    $BB grep -iE 'goToSleep|ShutdownThread|keyCode=26|scancode=116|scanCode =116' $OUT/logcat.txt 2>/dev/null | $BB tail -20
    exit 0
fi

$BB rm -rf $OUT
$BB mkdir -p $OUT
echo "===== 开始抓包（25 秒）====="
echo "现在用实体遥控器对着电视按一次电源键，电视会关机，属于正常现象。"
$BB logcat -c 2>/dev/null
$BB logcat -v time -f $OUT/logcat.txt &
$BB getevent -lt > $OUT/getevent.txt 2>&1 &
$BB sleep 25
$BB kill $! 2>/dev/null
echo "===== 抓包结束 ====="
echo "文件："
$BB ls -l $OUT
echo
echo "如果用遥控器把电视开回来了，执行下面这条看报告："
echo "  adb shell sh /data/local/tmp/capture_rcu_power.sh report"
echo
echo "（来不及拉日志也没关系，文件在 /data/local/tmp/rcu_power 里，重启后会保留）"
