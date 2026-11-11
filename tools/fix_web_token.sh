#!/system/bin/sh
# point the web UI at the new token
BB=/system/bin/busybox
$BB sed -i "s/var TOKEN = 'ch551';/var TOKEN = 'rbe9k2';/" /data/local/webremote/index.html
$BB grep -n "var TOKEN" /data/local/webremote/index.html
$BB wget -q -O - -T 3 "http://127.0.0.1:8080/cgi-bin/status?p=rbe9k2"
echo
