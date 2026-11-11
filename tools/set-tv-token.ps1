# 改电视上 webremote 的访问令牌，并自检。
#   pwsh -File tools/set-tv-token.ps1 -Token rbe9k2
#   pwsh -File tools/set-tv-token.ps1 -Token rbe9k2 -Serial 192.168.3.17:5555
#   pwsh -File tools/set-tv-token.ps1 -Token rbe9k2 -OldToken ch551      # 自动探测失败时手工指定
# 令牌只能用小写字母和数字：cgi-bin 里用的是 busybox 字符串比较，别带 & 或 =。
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Serial = '192.168.3.17:5555',
    [string]$Dir = '/data/local/webremote',
    [string]$OldToken = ''
)

$ErrorActionPreference = 'Stop'
if ($Token -notmatch '^[a-z0-9]{4,24}$') {
    throw "令牌只允许 4-24 位小写字母/数字：$Token"
}

function Tv([string]$command) {
    # 电视上的 adbd 有时会先吐两行 "--socket created" 之类的噪声，只取真正关心的输出。
    (& adb -s $Serial shell $command 2>&1) | ForEach-Object { "$_" } | Where-Object { $_ -notmatch '^(--|$)' }
}

Write-Host "[1/4] 检查 adb 连接 $Serial"
$ping = Tv 'echo ok'
if ($ping -notcontains 'ok') { throw "adb 连不上 $Serial（先 adb connect $Serial）" }

Write-Host "[2/4] 找出现有令牌"
if (-not $OldToken) {
    $lines = Tv "busybox grep -o 'p=[a-z0-9]*' $Dir/cgi-bin/power"
    foreach ($line in $lines) {
        if ($line -match '^\s*p=([a-z0-9]+)\s*$') { $OldToken = $Matches[1]; break }
    }
}
if (-not $OldToken) { throw "没找到现有令牌，用 -OldToken 手工指定（电视上：busybox grep -n 'p=' $Dir/cgi-bin/power）" }
Write-Host "      旧令牌: $OldToken"

Write-Host "[3/4] 改写 cgi-bin/* 与 index.html（p=$OldToken → p=$Token）"
$files = @('cgi-bin/power', 'cgi-bin/key', 'cgi-bin/app', 'cgi-bin/source', 'cgi-bin/text', 'cgi-bin/screen', 'cgi-bin/status', 'index.html')
$changed = 0
foreach ($f in $files) {
    $path = "$Dir/$f"
    $exists = Tv "[ -f $path ] && echo yes"
    if ($exists -notcontains 'yes') { Write-Host "      跳过（不存在）: $f"; continue }
    $hits = Tv "busybox grep -c 'p=$OldToken' $path"
    if ($hits -notcontains '1' -and $hits -notcontains '2' -and $hits -notcontains '3' -and $hits -notcontains '4' -and $hits -notcontains '5') {
        Write-Host "      跳过（该文件没有旧令牌）: $f"; continue
    }
    Tv "busybox sed -i 's/p=$OldToken/p=$Token/g' $path" | Out-Null
    $left = Tv "busybox grep -c 'p=$OldToken' $path"
    Write-Host "      已处理: $f （残留旧令牌行数: $($left -join ',')）"
    $changed++
}
if ($changed -eq 0) { throw '没有文件被改写，检查 $Dir 与 -OldToken' }

Write-Host "[4/4] 自检"
Start-Sleep -Milliseconds 600
$newOk = (Tv "busybox wget -q -O - -T 3 'http://127.0.0.1:8080/cgi-bin/status?p=$Token'") -join ''
$oldOk = (Tv "busybox wget -q -O - -T 3 'http://127.0.0.1:8080/cgi-bin/status?p=$OldToken'") -join ''
Write-Host "      新令牌: $newOk"
Write-Host "      旧令牌: $oldOk"
if ($newOk -notmatch '"ok":true') { throw '新令牌自检失败：检查 httpd 是否在跑（sh start.sh）以及 $Dir 是否正确' }
if ($oldOk -match '"ok":true') { throw '旧令牌仍然有效，sed 没改全' }
Write-Host "完成：电视上的令牌已换成 $Token，请在 APP 的「网络」区填同一个。"
