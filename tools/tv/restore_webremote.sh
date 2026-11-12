#!/system/bin/sh
# 恢复 webremote：取消 boot.sh 里被注释的自启行，并立即启动服务。
BB=/system/bin/busybox
DIR=/data/local/webremote

echo "== 1. 恢复 boot.sh 自启行 =="
if $BB grep -q '^# disabled 2026-11-12: ' "$DIR/boot.sh"; then
    $BB sed -i 's|^# disabled 2026-11-12: ||' "$DIR/boot.sh"
    echo "  已去掉注释前缀"
else
    echo "  (没有需要恢复的注释行)"
fi
$BB grep -n 'setsid /system/bin/sh "\$DIR/start.sh"' "$DIR/boot.sh" || echo "  !! 没找到自启行，请人工检查"

echo "== 2. 启动 webremote =="
$BB killall httpd 2>/dev/null
$BB chmod 755 "$DIR"/*.sh "$DIR"/cgi-bin/* 2>/dev/null
$BB chmod 644 "$DIR"/index.html 2>/dev/null
$BB setsid /system/bin/sh "$DIR/start.sh" >> "$DIR/service.log" 2>&1
$BB sleep 2

echo "== 3. 复核 =="
$BB ps | $BB grep -E 'httpd|watchdog' | $BB grep -v grep || echo "  !! 进程没起来"
$BB wget -q -O - -T 3 "http://127.0.0.1:8080/cgi-bin/status?p=rbe9k2" && echo || echo "  !! 8080 无响应"
echo
$BB tail -3 "$DIR/service.log"
