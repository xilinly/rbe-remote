#!/system/bin/sh
# klbind.sh —— 用 bind mount 让这台电视的 HID 键位表同时满足：power(116) 可用 + 厂商键位不丢。
#
#   sh /data/local/keylayout/klbind.sh install   # 挂上（幂等）
#   sh /data/local/keylayout/klbind.sh status    # 查看
#   sh /data/local/keylayout/klbind.sh remove    # 全部卸掉，回到原厂
#
# 背景：/system 只读且 remount 被拒，但 bind mount 可用。
#   1) Vendor_0416_Product_0300.kl / CH_BT_RC.kl：打开被注释的 key 116/152 POWER；
#   2) Generic.kl：现场合成"Generic 标准键位 + 厂商 CHANGHONGIR 映射 + POWER"，
#      因为实测这台电视给我们的 uhid 设备最终会回落到 Generic.kl（厂商表的内容反而不被采用），
#      只改厂商表会导致换源/设置/数字等厂商键全部变成 keyCode=0。
# 三张表都覆盖后，无论 EventHub 挑哪张，电源键和厂商键都成立。

BB=/system/bin/busybox
SRC_DIR=/system/usr/keylayout
BASE=/data/local/keylayout
WORK=$BASE/work
LOG=$BASE/klbind.log
VENDOR=Vendor_0416_Product_0300.kl
BYNAME=CH_BT_RC.kl
GENERIC=Generic.kl

log() { echo "[$($BB date '+%F %T')] $*" >> "$LOG"; }

list_state() {
    for f in "$VENDOR" "$BYNAME" "$GENERIC"; do
        [ -f "$SRC_DIR/$f" ] || continue
        echo "$f: $($BB grep -n -E '^key +116' "$SRC_DIR/$f" 2>/dev/null || echo '(116 未开启)')"
    done
}

case "$1" in
status)
    echo "-- mount --"
    $BB mount | $BB grep keylayout || echo "(没有 bind mount)"
    echo "-- 生效内容 --"
    list_state
    echo "-- Generic.kl 里的厂商键抽样 --"
    $BB grep -c 'CHANGHONGIR' "$SRC_DIR/$GENERIC" 2>/dev/null | $BB sed 's/^/CHANGHONGIR 行数: /' 2>/dev/null \
        || echo "(grep 不可用)"
    exit 0
    ;;
remove)
    for f in "$VENDOR" "$BYNAME" "$GENERIC"; do
        $BB umount "$SRC_DIR/$f" 2>/dev/null && { echo "已卸载 $f"; log "umount $f"; }
    done
    exit 0
    ;;
esac

$BB mkdir -p "$WORK"

# ---------- 1. 厂商表：打开被注释的 POWER 行 ----------
for f in "$VENDOR" "$BYNAME"; do
    SRC="$SRC_DIR/$f"
    [ -f "$SRC" ] || continue
    $BB umount "$SRC" 2>/dev/null                      # 摘掉自己的旧挂载，避免 cp 读回副本
    $BB cp "$SRC" "$WORK/$f" || { log "cp failed $f"; continue; }
    $BB sed -i 's|^# *key 116  *POWER|key 116   POWER|; s|^# *key 152  *POWER|key 152   POWER|' "$WORK/$f"
done

# ---------- 2. Generic.kl：合成 标准键位 + 厂商映射 + POWER ----------
GEN_SRC="$SRC_DIR/$GENERIC"
if [ -f "$GEN_SRC" ] && [ -f "$WORK/$VENDOR" ]; then
    $BB umount "$GEN_SRC" 2>/dev/null
    $BB cp "$GEN_SRC" "$WORK/$GENERIC.base" || log "cp generic failed"

    # 用 awk 合并：先建立厂商表里"启用状态"的 scancode->整行 映射，
    # 遍历 Generic 时同 scancode 用厂商行覆盖；最后补上 Generic 里没有的厂商 scancode。
    $BB awk '
        function keyof(line,   a) {
            # 返回 "key <scancode>" 里的 scancode；不是 key 行返回 ""
            if (line !~ /^[ \t]*key[ \t]+[0-9]+/) return ""
            split(line, a, /[ \t]+/)
            return a[2]
        }
        BEGIN { vendor_mode = 0 }
        # 第一个文件：厂商表（取启用行，注释行忽略），存 sc->line
        FNR == NR {
            if ($0 ~ /^[ \t]*#/) next
            sc = keyof($0)
            if (sc != "") vline[sc] = $0
            next
        }
        # 第二个文件：Generic，同 scancode 用厂商行替换
        {
            sc = keyof($0)
            if (sc != "" && (sc in vline)) { print vline[sc]; used[sc] = 1; next }
            print
        }
        END {
            print ""
            print "# --- Changhong vendor mappings merged by klbind.sh ---"
            for (sc in vline) if (!(sc in used)) print vline[sc]
        }
    ' "$WORK/$VENDOR" "$WORK/$GENERIC.base" > "$WORK/$GENERIC"

    # 兜底：确保 POWER 一定在
    $BB grep -q -E '^key +116 +POWER' "$WORK/$GENERIC" || \
        $BB sed -i 's|^# *key 116  *POWER.*|key 116   POWER|' "$WORK/$GENERIC"
    $BB chmod 644 "$WORK/$GENERIC"
fi

# ---------- 3. 挂上 ----------
for f in "$VENDOR" "$BYNAME" "$GENERIC"; do
    SRC="$SRC_DIR/$f"
    [ -f "$WORK/$f" ] || continue
    if $BB mount -o bind "$WORK/$f" "$SRC" 2>/dev/null; then log "bind ok $f"; else log "bind FAILED $f"; fi
done

list_state
log "install done"
