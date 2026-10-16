param([Parameter(Mandatory)][string]$EvidenceRoot)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sdk = Split-Path -Parent (Split-Path -Parent (Get-Command adb.exe).Source)
$tools = Join-Path $sdk 'build-tools/36.0.0'
New-Item -ItemType Directory -Path $EvidenceRoot -Force | Out-Null
$results = foreach ($mode in @('voice','video')) {
    $apk = Join-Path $root ("dist/doubao-$mode-call.apk")
    $signer = & (Join-Path $tools 'apksigner.bat') verify --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw "$mode signature verification failed." }
    $certificate = [regex]::Match(($signer -join "`n"),'certificate SHA-256 digest: ([0-9a-f]+)').Groups[1].Value
    if ($certificate -ne '878990517f92b7ee41dd14285e7cc81c4c9534dd5814c096e683a04dff6a9d40') { throw 'Original release certificate changed.' }
    $badging = & (Join-Path $tools 'aapt2.exe') dump badging $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK badging unavailable.' }
    $package = "com.simon.doubao.${mode}call"
    if (($badging -join "`n") -notmatch ("package: name='" + [regex]::Escape($package) + "' versionCode='10' versionName='1.4.2'")) { throw 'APK version/package mismatch.' }
    if (($badging -join "`n") -match 'application-debuggable|testOnly') { throw 'Release APK must not be debuggable/test-only.' }
    $manifest = & (Join-Path $tools 'aapt2.exe') dump xmltree $apk --file AndroidManifest.xml
    if ($LASTEXITCODE -ne 0) { throw 'Packaged manifest unavailable.' }
    $other = if ($mode -eq 'voice') { 'com.simon.doubao.videocall' } else { 'com.simon.doubao.voicecall' }
    if (($manifest -join "`n").Contains($other) -or ($manifest -join "`n") -match 'SessionRegistryProvider|CallEndBridgeService|permission.END_CALL') { throw 'Packaged manifest retains companion dependency.' }
    $archive = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $dexEntries = @($archive.Entries | Where-Object { $_.FullName -match '^classes(?:\d+)?\.dex$' })
        if ($dexEntries.Count -eq 0) { throw 'No DEX found.' }
        $dexText = ''
        foreach ($entry in $dexEntries) {
            $stream = $entry.Open(); $memory = [IO.MemoryStream]::new()
            try { $stream.CopyTo($memory); $dexText += [Text.Encoding]::Latin1.GetString($memory.ToArray()) }
            finally { $stream.Dispose(); $memory.Dispose() }
        }
        foreach ($obsolete in @('SessionRegistry','SessionLease','CallEndBridgeService','DoubleTapDetector','onDoubleTap','AuthorizationGraceGate','END_FAILED','FixtureActivity','IntegrationProbe')) {
            if ($dexText.Contains($obsolete)) { throw "Forbidden class/path in actual release DEX: $obsolete" }
        }
        foreach ($required in @('SessionOwnership','KernelCallLock','ForegroundObserver')) {
            if (-not $dexText.Contains($required)) { throw "Missing production class in DEX: $required" }
        }
    } finally { $archive.Dispose() }
    [pscustomobject]@{Package=$package;Version='1.4.2';VersionCode=10;Sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash;Certificate=$certificate;IndependentManifest=$true;NoLegacyOrTestDex=$true;Passed=$true}
}
$results | ConvertTo-Json -Depth 4 | Tee-Object -FilePath (Join-Path $EvidenceRoot 'apk-results.json')
