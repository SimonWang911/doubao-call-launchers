param([switch]$ContractOnly)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$errors = @()
$javaRoot = Join-Path $root 'common/src/com/simon/doubaolauncher'
foreach ($obsolete in @('SessionRegistry.java','SessionRegistryProvider.java','SessionLease.java','CallEndBridgeService.java')) {
    if (Test-Path -LiteralPath (Join-Path $javaRoot $obsolete)) { $errors += "Cross-APK registry/lease/bridge remains: $obsolete" }
}
$sources = Get-ChildItem -LiteralPath $javaRoot -Filter '*.java'
foreach ($file in $sources) {
    $source = Get-Content -LiteralPath $file.FullName -Raw -Encoding utf8
    foreach ($obsolete in @('SessionRegistry.','CallEndBridgeService','VOICE_PACKAGE','DoubleTapDetector','onDoubleTap','AuthorizationGraceGate','END_FAILED')) {
        if ($source.Contains($obsolete)) { $errors += "$($file.Name) retains $obsolete" }
    }
}
foreach ($mode in @('voice','video')) {
    [xml]$manifest = Get-Content -LiteralPath (Join-Path $root "apps/$mode/AndroidManifest.xml") -Raw -Encoding utf8
    if (@($manifest.manifest.application.provider).Where({$null -ne $_}).Count) { $errors += "$mode still exports session storage." }
    $text = $manifest.OuterXml
    if ($text.Contains('permission.END_CALL') -or $text.Contains('CallEndBridgeService')) { $errors += "$mode retains companion permission/bridge." }
    $other = if ($mode -eq 'voice') { 'com.simon.doubao.videocall' } else { 'com.simon.doubao.voicecall' }
    if ($text.Contains($other)) { $errors += "$mode still references companion package." }
}
if ($errors.Count) { throw ($errors -join [Environment]::NewLine) }
if ($ContractOnly) { 'Independent launcher structure passed.'; exit 0 }
$classes = Join-Path $root ('work/ownership-test/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$testSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'tests/java/ownership') -Recurse -Filter '*.java' | ForEach-Object FullName)
$testSources += Join-Path $javaRoot 'SessionOwnership.java'
$testSources += Join-Path $javaRoot 'KernelCallLock.java'
& javac '-J-Duser.language=en' '-Xlint:-options' -encoding UTF-8 -source 8 -target 8 -d $classes @testSources
if ($LASTEXITCODE -ne 0) { throw 'Ownership test compilation failed.' }
& java -cp $classes com.simon.doubaolauncher.SessionOwnershipTest
if ($LASTEXITCODE -ne 0) { throw 'Ownership regression failed.' }
'Independent launcher verification passed.'
