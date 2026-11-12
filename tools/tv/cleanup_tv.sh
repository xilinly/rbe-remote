#!/system/bin/sh
# 清理本轮 BLE 关机调试在电视上留下的所有东西（逐条显式路径，不用通配符）。
BB=/system/bin/busybox

echo "== 1. 卸载所有键位表 bind mount（应为空）=="
for f in Vendor_0416_Product_0300.kl CH_BT_RC.kl Generic.kl; do
    $BB umount "/system/usr/keylayout/$f" 2>/dev/null && echo "  已卸载 $f"
done
$BB umount /system/usr/keylayout 2>/dev/null && echo "  已卸载 /system/usr/keylayout 目录挂载"
$BB mount | $BB grep keylayout || echo "  当前无 keylayout 挂载"

echo "== 2. 删除 /data/local/keylayout（脚本 + 副本 + 日志）=="
if [ -d /data/local/keylayout ]; then
    $BB rm -rf /data/local/keylayout && echo "  已删除 /data/local/keylayout"
else
    echo "  (不存在)"
fi

echo "== 3. 删除键位表备份 =="
$BB rm -f /data/local/backup/Vendor_0416_Product_0300.kl.orig && echo "  已删除 backup/Vendor_0416_Product_0300.kl.orig"
$BB rmdir /data/local/backup 2>/dev/null && echo "  已删除空目录 /data/local/backup"

echo "== 4. 删除 /data/system/devices（我建的键位表覆盖目录）=="
if [ -d /data/system/devices ]; then
    $BB rm -rf /data/system/devices && echo "  已删除 /data/system/devices"
else
    echo "  (不存在)"
fi

echo "== 5. 删除 /data/local/tmp 里我放的东西 =="
for f in bind.sh boot_hook.sh bt_config.clean.xml check_ns.sh diag.sh final_power.txt \
         fix.sh in.txt input.txt l.txt power_test.txt probe.sh; do
    if [ -e "/data/local/tmp/$f" ]; then
        $BB rm -f "/data/local/tmp/$f" && echo "  已删除 tmp/$f"
    fi
done
if [ -d /data/local/tmp/powerkl ]; then
    $BB rm -rf /data/local/tmp/powerkl && echo "  已删除 tmp/powerkl"
fi
if [ -d /data/local/tmp/rcu_power ]; then
    $BB rm -rf /data/local/tmp/rcu_power && echo "  已删除 tmp/rcu_power"
fi

echo "== 6. 撤销 webremote/boot.sh 里的开机钩子 =="
if [ -f /data/local/webremote/boot.sh.bak.klbind ]; then
    $BB cp /data/local/webremote/boot.sh.bak.klbind /data/local/webremote/boot.sh \
        && $BB rm -f /data/local/webremote/boot.sh.bak.klbind \
        && echo "  已从备份还原 boot.sh 并删除备份"
else
    if $BB grep -q "klbind" /data/local/webremote/boot.sh 2>/dev/null; then
        $BB sed -i '/BLE 电源开机钩子/,+4d' /data/local/webremote/boot.sh && echo "  已用 sed 去掉钩子段落"
    else
        echo "  (boot.sh 里没有钩子)"
    fi
fi

echo "== 7. 删除蓝牙配置备份 =="
$BB rm -f /data/misc/bluedroid/bt_config.xml.bak.klfix && echo "  已删除 bt_config.xml.bak.klfix（保留清理后的 bt_config.xml）"

echo
echo "===== 复核 ====="
echo "-- 挂载 --"; $BB mount | $BB grep keylayout || echo "  无"
echo "-- 原厂键位表 116 状态 --"; $BB grep -n -E '^(# )?key +116' /system/usr/keylayout/Vendor_0416_Product_0300.kl
echo "-- boot.sh 尾部 --"; $BB tail -4 /data/local/webremote/boot.sh
echo "-- 残留检查 --"
for p in /data/local/keylayout /data/system/devices /data/local/backup /data/local/tmp/powerkl; do
    [ -e "$p" ] && echo "  仍存在: $p" || echo "  已清除: $p"
done
