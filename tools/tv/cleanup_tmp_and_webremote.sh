#!/system/bin/sh
# 清理 1 + 3：删 /data/local/tmp 旧调试文件；停掉 webremote 服务并去掉开机自启。
BB=/system/bin/busybox
DIR=/data/local/webremote

echo "== 1. 删 /data/local/tmp 旧调试文件 =="
for f in _nh.log _st.log _wifi_connect.sh aerial_probe.txt aerial_probe2.txt ck.out d.sh \
         f.out fix4.out install-recovery.sh ipconfig.txt.static.bak pwr.txt qq.out \
         rrpre.out rs.out w2chk.out w2deploy.out wcheck.out wl2.out; do
    if [ -e "/data/local/tmp/$f" ]; then
        $BB rm -f "/data/local/tmp/$f" && echo "  已删 tmp/$f"
    fi
done
echo "  /data/local/tmp 剩余条目: $($BB ls /data/local/tmp | $BB wc -l)"

echo "== 3a. 停 webremote（watchdog + httpd）=="
for pid in $($BB pidof watchdog.sh 2>/dev/null) $($BB pidof httpd 2>/dev/null); do
    kill "$pid" 2>/dev/null && echo "  已结束 pid=$pid"
done
$BB killall httpd 2>/dev/null && echo "  killall httpd"
$BB rm -f "$DIR/.watchdog.pid"
$BB sleep 1
if $BB ps | $BB grep -E 'httpd|watchdog.sh' | $BB grep -v grep >/dev/null; then
    echo "  !! 仍有残留进程"
    $BB ps | $BB grep -E 'httpd|watchdog.sh' | $BB grep -v grep
else
    echo "  webremote 进程已清空"
fi

echo "== 3b. 去掉开机自启（注释 boot.sh 里的 start.sh 启动行）=="
if $BB grep -q 'setsid /system/bin/sh "$DIR/start.sh"' "$DIR/boot.sh"; then
    $BB cp "$DIR/boot.sh" "$DIR/boot.sh.bak.noweb"
    $BB sed -i 's|^\(\$BB setsid /system/bin/sh "\$DIR/start.sh".*\)$|# disabled 2026-11-12: \1|' "$DIR/boot.sh"
    echo "  已注释自启行（备份 boot.sh.bak.noweb）"
else
    echo "  (boot.sh 里没有自启行或已注释)"
fi

echo
echo "===== 复核 ====="
echo "-- boot.sh 尾部 --"; $BB tail -5 "$DIR/boot.sh"
echo "-- 端口 8080 --"; $BB wget -q -O /dev/null -T 2 http://127.0.0.1:8080/ && echo "  仍在监听" || echo "  已关闭"
echo "-- 进程 --"; $BB ps | $BB grep -E 'httpd|watchdog' | $BB grep -v grep || echo "  无"
