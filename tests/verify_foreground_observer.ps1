$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$Classes = Join-Path $Root ('work/observer-test/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $Classes -Force | Out-Null
$Sources = @(Get-ChildItem -LiteralPath (Join-Path $Root 'tests/java/foreground') -Filter '*.java' -Recurse | ForEach-Object FullName)
$Sources += Join-Path $Root 'common/src/com/simon/doubaolauncher/ForegroundObserver.java'
$Sources += Join-Path $Root 'common/src/com/simon/doubaolauncher/CallSession.java'
& javac -encoding UTF-8 -source 8 -target 8 -d $Classes @Sources
if ($LASTEXITCODE -ne 0) { throw 'Foreground observer test compilation failed.' }
& java -cp $Classes com.simon.doubaolauncher.ForegroundObserverTest
if ($LASTEXITCODE -ne 0) { throw 'Foreground observer regression failed.' }
