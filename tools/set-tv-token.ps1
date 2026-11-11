# 改电视上 webremote 的访问令牌，并自检。
#   pwsh -File tools/set-tv-token.ps1 -Token rbe7x4q
#   pwsh -File tools/set-tv-token.ps1 -Token rbe7x4q -Serial 192.168.3.17:5555
# 令牌只能用小写字母和数字：cgi-bin 里用的是 busybox 字符串比较，别带 & 或 =。
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Serial = '192.168.3.17:5555',
    [string]$Dir = '/data/local/webremote'
)

$ErrorActionPreference = 'Stop'
if ($Token -notmatch '^[a-z0-9]{4,24}$') {
    throw "令牌只允许 4-24 位小写字母/数字：$Token"
}

Write-Host "[1/4] 检查 adb 连接 $Serial"
& adb -s $Serial shell "echo ok" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "adb 连不上 $Serial（先 adb connect $Serial）" }

Write-Host "[2/4] 找出现有令牌（取第一个 cgi-bin/power 里的 p=…）"
$old = (& adb -s $Serial shell "busybox grep -o 'p=[a-z0-9]*' $Dir/cgi-bin/power | busybox head -1").Trim()
if ($old -match '^p=(.+)$') { $oldToken = $Matches[1] } else { $oldToken = '' }
Write-Host "      旧令牌: $(if ($oldToken) { $oldToken } else { '(没找到)' })"

Write-Host "[3/4] 改写 cgi-bin/* 与 index.html"
# 逐个文件 sed -i，避免 busybox 的版本差异；没有旧令牌时按 p=ch551 兜底不动。
$files = @('cgi-bin/power', 'cgi-bin/key', 'cgi-bin/app', 'cgi-bin/source', 'cgi-bin/text', 'cgi-bin/screen', 'cgi-bin/status', 'index.html')
foreach ($f in $files) {
    $path = "$Dir/$f"
    $exists = (& adb -s $Serial shell "[ -f $path ] && echo yes").Trim()
    if ($exists -ne 'yes') { Write-Host "      跳过（不存在）: $f"; continue }
    if ($oldToken) {
        & adb -s $Serial shell "busybox sed -i 's/p=$oldToken/p=$Token/g' $path"
    }
    Write-Host "      已处理: $f"
}

Write-Host "[4/4] 自检"
Start-Sleep -Milliseconds 500
$newOk = (& adb -s $Serial shell "busybox wget -q -O - -T 3 'http://127.0.0.1:8080/cgi-bin/status?p=$Token'").Trim()
$oldOk = if ($oldToken) { (& adb -s $Serial shell "busybox wget -q -O - -T 3 'http://127.0.0.1:8080/cgi-bin/status?p=$oldToken'").Trim() } else { '' }
Write-Host "      新令牌: $newOk"
if ($oldToken) { Write-Host "      旧令牌: $oldOk" }
if ($newOk -notmatch '"ok":true') { throw '新令牌自检失败，检查 httpd 是否在跑（start.sh）' }
if ($oldToken -and $oldOk -match '"ok":true') { throw '旧令牌仍然有效，sed 没改全' }
Write-Host "完成：电视上的令牌已换成 $Token，记得在 APP 的「网络」区填同一个。"
