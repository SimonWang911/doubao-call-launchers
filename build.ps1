$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$BuildTools = (Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$Platform = (Get-ChildItem (Join-Path $Sdk 'platforms') -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$AndroidJar = Join-Path $Platform 'android.jar'
$Aapt2 = Join-Path $BuildTools 'aapt2.exe'
$D8 = Join-Path $BuildTools 'd8.bat'
$ZipAlign = Join-Path $BuildTools 'zipalign.exe'
$ApkSigner = Join-Path $BuildTools 'apksigner.bat'
$KeyStore = Join-Path $Root 'build\doubao-launchers-debug.keystore'
$OutDir = Join-Path $Root 'dist'

New-Item -ItemType Directory -Force (Join-Path $Root 'build') | Out-Null
New-Item -ItemType Directory -Force $OutDir | Out-Null

if (-not (Test-Path $KeyStore)) {
    keytool -genkeypair `
        -keystore $KeyStore `
        -storepass android `
        -keypass android `
        -alias doubao `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -dname "CN=Doubao Call Launcher,O=Local,C=CN" | Out-Null
}

function Build-App {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$PackageName
    )

    $AppRoot = Join-Path $Root "apps\$Name"
    $Work = Join-Path $Root "build\$Name"
    $Compiled = Join-Path $Work 'compiled'
    $Classes = Join-Path $Work 'classes'
    $Dex = Join-Path $Work 'dex'
    $LinkedApk = Join-Path $Work "$Name-linked.apk"
    $UnsignedApk = Join-Path $Work "$Name-unsigned.apk"
    $AlignedApk = Join-Path $Work "$Name-aligned.apk"
    $FinalApk = Join-Path $OutDir "doubao-$Name-call.apk"

    Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $Compiled, $Classes, $Dex | Out-Null

    & $Aapt2 compile --dir (Join-Path $AppRoot 'res') -o $Compiled
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed for $Name" }

    $FlatFiles = Get-ChildItem $Compiled -Filter '*.flat' | ForEach-Object { $_.FullName }
    & $Aapt2 link `
        -o $LinkedApk `
        -I $AndroidJar `
        --manifest (Join-Path $AppRoot 'AndroidManifest.xml') `
        --java (Join-Path $Work 'gen') `
        --min-sdk-version 23 `
        --target-sdk-version 36 `
        $FlatFiles
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed for $Name" }

    $Sources = @(
        (Join-Path $Root 'common\src\com\simon\doubaolauncher\CallLauncherActivity.java')
    )
    javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $AndroidJar -d $Classes $Sources
    if ($LASTEXITCODE -ne 0) { throw "javac failed for $Name" }

    $ClassFiles = Get-ChildItem $Classes -Recurse -Filter '*.class' | ForEach-Object { $_.FullName }
    & $D8 --min-api 23 --lib $AndroidJar --output $Dex $ClassFiles
    if ($LASTEXITCODE -ne 0) { throw "d8 failed for $Name" }

    Copy-Item $LinkedApk $UnsignedApk
    Push-Location $Dex
    try {
        jar uf $UnsignedApk classes.dex
    } finally {
        Pop-Location
    }

    & $ZipAlign -p -f 4 $UnsignedApk $AlignedApk
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed for $Name" }

    & $ApkSigner sign `
        --ks $KeyStore `
        --ks-key-alias doubao `
        --ks-pass pass:android `
        --key-pass pass:android `
        --out $FinalApk `
        $AlignedApk
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed for $Name" }

    & $ApkSigner verify --verbose $FinalApk
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed for $Name" }

    Write-Host "Built $PackageName -> $FinalApk"
}

Build-App -Name 'voice' -PackageName 'com.simon.doubao.voicecall'
Build-App -Name 'video' -PackageName 'com.simon.doubao.videocall'
