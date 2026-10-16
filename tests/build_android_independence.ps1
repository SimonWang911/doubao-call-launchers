param([Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sdk = Split-Path -Parent (Split-Path -Parent (Get-Command adb).Source)
$tools = Join-Path $sdk 'build-tools/36.0.0'
$androidJar = Join-Path $sdk 'platforms/android-36/android.jar'
$key = Join-Path $root 'build/doubao-launchers-debug.keystore'
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$OutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path
foreach ($name in @('fixture','probe')) {
    $work = Join-Path $OutputRoot $name
    $source = Join-Path $PSScriptRoot ('android/' + $name)
    New-Item -ItemType Directory -Path $work,(Join-Path $work 'classes'),(Join-Path $work 'dex'),(Join-Path $work 'assets') -Force | Out-Null
    $arguments = @('link','-o',(Join-Path $work 'unsigned.apk'),'-I',$androidJar,'--manifest',(Join-Path $source 'AndroidManifest.xml'))
    if ($name -eq 'probe') {
        Copy-Item -LiteralPath (Join-Path $root 'rules/doubao-call-rules.json') -Destination (Join-Path $work 'assets/doubao-call-rules.json')
        $arguments += @('-A',(Join-Path $work 'assets'))
    }
    & (Join-Path $tools 'aapt2.exe') @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Android test resource link failed.' }
    $sources = @(Get-ChildItem -LiteralPath $source -Filter '*.java' | ForEach-Object FullName)
    & javac '-J-Duser.language=en' '-Xlint:-options' -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar -classpath (Join-Path $root 'build/voice/classes') -d (Join-Path $work 'classes') @sources
    if ($LASTEXITCODE -ne 0) { throw 'Android test compilation failed.' }
    $classes = @(Get-ChildItem -LiteralPath (Join-Path $work 'classes') -Recurse -Filter '*.class' | ForEach-Object FullName)
    & (Join-Path $tools 'd8.bat') --min-api 23 --lib $androidJar --output (Join-Path $work 'dex') @classes
    if ($LASTEXITCODE -ne 0) { throw 'Android test DEX failed.' }
    Push-Location (Join-Path $work 'dex')
    try { & jar uf (Join-Path $work 'unsigned.apk') classes.dex; if ($LASTEXITCODE -ne 0) { throw 'Android test archive failed.' } } finally { Pop-Location }
    & (Join-Path $tools 'zipalign.exe') -f 4 (Join-Path $work 'unsigned.apk') (Join-Path $work 'aligned.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Android test alignment failed.' }
    & (Join-Path $tools 'apksigner.bat') sign --ks $key --ks-key-alias doubao --ks-pass pass:android --key-pass pass:android --out (Join-Path $work 'test.apk') (Join-Path $work 'aligned.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Android test signing failed.' }
    Write-Output (Join-Path $work 'test.apk')
}
