$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$SdkCandidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)
if ($env:LOCALAPPDATA) {
    $SdkCandidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$Sdk = $SdkCandidates |
    Where-Object { $_ -and (Test-Path -LiteralPath $_) } |
    Select-Object -First 1
if (-not $Sdk) {
    throw 'Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME, or install it under LOCALAPPDATA\Android\Sdk.'
}
$Sdk = (Resolve-Path -LiteralPath $Sdk).Path
$BuildTools = (Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$Platform = (Get-ChildItem (Join-Path $Sdk 'platforms') -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$AndroidJar = Join-Path $Platform 'android.jar'
$Aapt2 = Join-Path $BuildTools 'aapt2.exe'
$D8 = Join-Path $BuildTools 'd8.bat'
$ZipAlign = Join-Path $BuildTools 'zipalign.exe'
$ApkSigner = Join-Path $BuildTools 'apksigner.bat'
$DefaultKeyStore = Join-Path $Root 'build\doubao-launchers-debug.keystore'
$KeyStore = if ($env:DOUBAO_KEYSTORE) { $env:DOUBAO_KEYSTORE } else { $DefaultKeyStore }
$KeyAlias = if ($env:DOUBAO_KEY_ALIAS) { $env:DOUBAO_KEY_ALIAS } else { 'doubao' }
$KeyStorePass = if ($env:DOUBAO_KEYSTORE_PASS) { $env:DOUBAO_KEYSTORE_PASS } else { 'android' }
$KeyPass = if ($env:DOUBAO_KEY_PASS) { $env:DOUBAO_KEY_PASS } else { 'android' }
$OutDir = Join-Path $Root 'dist'

New-Item -ItemType Directory -Force (Join-Path $Root 'build') | Out-Null
New-Item -ItemType Directory -Force $OutDir | Out-Null

if (-not (Test-Path -LiteralPath $KeyStore)) {
    throw "Signing keystore is required but was not found: $KeyStore. Restore the original keystore before building."
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

    $ResolvedWork = [IO.Path]::GetFullPath($Work)
    $BuildRoot = [IO.Path]::GetFullPath((Join-Path $Root 'build')) + [IO.Path]::DirectorySeparatorChar
    if ($Name -notin @('voice', 'video') -or -not $ResolvedWork.StartsWith($BuildRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to clean a path outside the app build directory.'
    }
    if (Test-Path -LiteralPath $ResolvedWork) {
        Remove-Item -LiteralPath $ResolvedWork -Recurse -Force
    }
    New-Item -ItemType Directory -Force $Compiled, $Classes, $Dex | Out-Null

    & $Aapt2 compile --dir (Join-Path $AppRoot 'res') -o $Compiled
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed for $Name" }

    $FlatFiles = Get-ChildItem $Compiled -Filter '*.flat' | ForEach-Object { $_.FullName }
    $LinkArguments = @('link', '-o', $LinkedApk, '-I', $AndroidJar,
        '--manifest', (Join-Path $AppRoot 'AndroidManifest.xml'),
        '--java', (Join-Path $Work 'gen'), '--min-sdk-version', '23', '--target-sdk-version', '36')
    $LinkArguments += $FlatFiles
    & $Aapt2 @LinkArguments
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed for $Name" }

    $Sources = Get-ChildItem (Join-Path $Root 'common\src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
    javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $AndroidJar -d $Classes $Sources
    if ($LASTEXITCODE -ne 0) { throw "javac failed for $Name" }

    $ClassFiles = Get-ChildItem $Classes -Recurse -Filter '*.class' | ForEach-Object { $_.FullName }
    & $D8 --min-api 23 --lib $AndroidJar --output $Dex $ClassFiles
    if ($LASTEXITCODE -ne 0) { throw "d8 failed for $Name" }

    Copy-Item $LinkedApk $UnsignedApk
    Push-Location $Dex
    try {
        jar uf $UnsignedApk classes.dex
        if ($LASTEXITCODE -ne 0) { throw "jar update failed for $Name" }
    } finally {
        Pop-Location
    }

    & $ZipAlign -p -f 4 $UnsignedApk $AlignedApk
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed for $Name" }

    $SignArguments = @('sign', '--ks', $KeyStore, '--ks-key-alias', $KeyAlias,
        '--ks-pass', "pass:$KeyStorePass", '--key-pass', "pass:$KeyPass", '--out', $FinalApk, $AlignedApk)
    & $ApkSigner @SignArguments
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed for $Name" }

    & $ApkSigner verify --verbose $FinalApk
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed for $Name" }

    Write-Host "Built $PackageName -> $FinalApk"
}

Build-App -Name 'voice' -PackageName 'com.simon.doubao.voicecall'
Build-App -Name 'video' -PackageName 'com.simon.doubao.videocall'
