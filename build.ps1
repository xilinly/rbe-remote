# Build, align and sign the BLE remote APK with the portable toolchain in this folder.
# Uses only toolchain/jdk + sdk/ ; nothing system-wide is modified.
#   pwsh -File build.ps1            build build_apk/ and RBE902VC_BLE_autopair_v0.4.apk
#   pwsh -File build.ps1 -Version 0.5-name
param(
    [string]$Version = "0.8-clone",
    [string]$Output = "RBE902VC_BLE_v0.8.apk"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk = Join-Path $root 'toolchain\jdk\jdk-17.0.21.0.2+1'
$buildTools = Join-Path $root 'sdk\build-tools\34.0.0'
$androidJar = Join-Path $root 'sdk\platforms\android-34\android.jar'
$source = Join-Path $root 'app\src\main\java\org\local\rbe902vc\MainActivity.java'
$sourceRoot = Join-Path $root 'app\src\main\java'
$manifest = Join-Path $root 'app\src\main\AndroidManifest.xml'
$build = Join-Path $root 'build_apk'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
$keystore = Join-Path $env:USERPROFILE '.android\debug.keystore'

foreach ($required in @($jdk, $buildTools, $androidJar, $source, $manifest, $keystore)) {
    if (-not (Test-Path $required)) { throw "missing: $required" }
}

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$buildTools;$env:PATH"

New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null
# Remove only previous compilation output (single directory, explicit pattern).
Get-ChildItem -Path $classes -Filter *.class -File -Recurse -ErrorAction SilentlyContinue | Remove-Item -Force
Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $dex 'classes.dex')
Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $build 'base.apk'), (Join-Path $build 'unsigned.apk'), (Join-Path $build 'aligned.apk')

Write-Host '[1/6] javac'
# Every .java under app/src/main/java, so helper classes such as SendReceiver are compiled too.
# Passed as direct arguments: an @argfile would be read with the platform charset and mangle the
# non-ASCII path of this project.
$sources = @(Get-ChildItem -Path $sourceRoot -Filter *.java -File -Recurse | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 --release 17 -classpath $androidJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '[2/6] d8'
$classFiles = Get-ChildItem -Path $classes -Filter *.class -File -Recurse | ForEach-Object { $_.FullName }
& d8 --release --min-api 34 --lib $androidJar --output $dex @classFiles
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '[3/6] aapt2 link'
& aapt2 link -o (Join-Path $build 'base.apk') -I $androidJar --manifest $manifest `
    --min-sdk-version 34 --target-sdk-version 34 --version-code 4 --version-name $Version
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '[4/6] merge dex'
Copy-Item (Join-Path $build 'base.apk') (Join-Path $build 'unsigned.apk') -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open((Join-Path $build 'unsigned.apk'), 'Update')
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, (Join-Path $dex 'classes.dex'), 'classes.dex',
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally { $zip.Dispose() }

Write-Host '[5/6] zipalign'
& zipalign -f -p 4 (Join-Path $build 'unsigned.apk') (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '[6/6] apksigner'
$final = Join-Path $root $Output
& apksigner sign --ks $keystore --ks-key-alias androiddebugkey `
    --ks-pass pass:android --key-pass pass:android --out $final (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& apksigner verify --print-certs $final | Select-Object -First 4
& aapt2 dump badging $final | Select-String 'package:|sdkVersion|targetSdkVersion' | ForEach-Object { $_.Line }
Write-Host "APK: $final ($((Get-Item $final).Length) bytes)"
