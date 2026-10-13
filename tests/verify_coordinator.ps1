$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$Classes = Join-Path $Root ('work/coordinator-test/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $Classes -Force | Out-Null
$Sources = @(Get-ChildItem -LiteralPath (Join-Path $Root 'tests/java/coordinator') -Filter '*.java' -Recurse | ForEach-Object FullName)
$Sources += Join-Path $Root 'common/src/com/simon/doubaolauncher/CallEndCoordinator.java'
$Sources += Join-Path $Root 'common/src/com/simon/doubaolauncher/EndRequestPolicy.java'
& javac -encoding UTF-8 -source 8 -target 8 -d $Classes @Sources
if ($LASTEXITCODE -ne 0) { throw 'Real coordinator test compilation failed.' }
& java -cp $Classes com.simon.doubaolauncher.CallEndCoordinatorTest
if ($LASTEXITCODE -ne 0) { throw 'Real coordinator regression failed.' }
Write-Host 'Real coordinator verification passed.'
