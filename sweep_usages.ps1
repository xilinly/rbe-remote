param(
    [string]$tv = '192.168.3.17:5555',
    [string]$phone = 'A2NKUT2614007148'
)

# Wait for the TV to (re)open the HID link, then press candidate usages one by one and record what
# the TV does with each: the keycode/scancode it reports plus a screenshot hash so a menu opening is
# visible without looking at the screen.

$ErrorActionPreference = 'Continue'
$dir = 'D:\Documents\工作\rbe_remote\sweep'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$report = Join-Path $dir 'report.txt'
"start $(Get-Date -Format 'o')" | Set-Content -Path $report -Encoding utf8

$connected = $false
for ($i = 0; $i -lt 180; $i++) {
    $tail = adb -s $tv logcat -d -t 200 2>$null
    if ($tail -match 'state: 0->1|hidraw0 open succussfully') { $connected = $true; break }
    Start-Sleep -Seconds 5
}
"connected=$connected at $(Get-Date -Format 'o')" | Add-Content -Path $report -Encoding utf8
if (-not $connected) { exit 0 }

Start-Sleep -Seconds 4
adb -s $tv shell "screencap -p /sdcard/sw_base.png" | Out-Null
adb -s $tv pull /sdcard/sw_base.png "$dir\base.png" | Out-Null
$baseHash = (Get-FileHash "$dir\base.png" -Algorithm MD5).Hash

foreach ($u in @('D5', 'AF', 'B3', 'AE', 'A9', 'AC', 'B4', 'EB', 'BA')) {
    adb -s $phone shell "am broadcast -a org.local.rbe902vc.SEND --es u $u" | Out-Null
    Start-Sleep -Milliseconds 1200
    $keys = (adb -s $tv logcat -d -t 40 2>$null |
        Select-String -Pattern 'interceptKeyTq.*down=true' |
        ForEach-Object { if ($_.Line -match 'keycode=(\d+).*scanCode =(\d+)') { "keycode$($matches[1])/scan$($matches[2])" } }) -join ' '
    adb -s $tv shell "screencap -p /sdcard/sw_$u.png" | Out-Null
    adb -s $tv pull "/sdcard/sw_$u.png" "$dir\$u.png" | Out-Null
    $hash = (Get-FileHash "$dir\$u.png" -Algorithm MD5).Hash
    $screen = if ($hash -eq $baseHash) { 'unchanged' } else { 'CHANGED' }
    "$u  $keys  screen=$screen" | Add-Content -Path $report -Encoding utf8

    # Dismiss whatever the candidate opened before trying the next one.
    adb -s $phone shell "am broadcast -a org.local.rbe902vc.SEND --es u C7" | Out-Null
    Start-Sleep -Milliseconds 900
}
"done $(Get-Date -Format 'o')" | Add-Content -Path $report -Encoding utf8
