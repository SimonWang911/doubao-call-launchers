$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'foreground_adb.ps1'
$tokens = $null; $parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($runner,[ref]$tokens,[ref]$parseErrors)
if ($parseErrors.Count) { throw 'Device runner could not be parsed.' }
$definition = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-Adb' },$true)
if (-not $definition) { throw 'Real ADB adapter missing.' }
. ([scriptblock]::Create($definition.Extent.Text))
$Serial = 'test-transport'
function adb {
    $script:calls.Add(($args -join ' '))
    if ($script:responses.Count -eq 0) { throw 'Unexpected additional ADB action.' }
    $response = $script:responses.Dequeue()
    $global:LASTEXITCODE = $response.Code
    if ($response.Text) { Write-Output $response.Text }
}
function Reset-Responses([object[]]$items) {
    $script:responses = [Collections.Generic.Queue[object]]::new()
    foreach ($item in $items) { $script:responses.Enqueue($item) }
    $script:calls = [Collections.Generic.List[string]]::new()
}
$checks = 0
foreach ($partial in @('private-partial-window-dump','')) {
    Reset-Responses @(@{Code=1;Text=$partial},@{Code=0;Text='disconnected'},@{Code=0;Text='connected'},@{Code=0;Text='complete-window'})
    $result = Invoke-Adb @('shell','dumpsys','window','windows')
    if ($result -ne 'complete-window' -or $calls.Count -ne 4 -or $responses.Count -ne 0) { throw 'E3: incomplete read must recover once and return only the complete second result.' }
    $checks++
}
Reset-Responses @(@{Code=1;Text='private-one'},@{Code=0;Text='disconnected'},@{Code=0;Text='connected'},@{Code=1;Text='private-two'})
$message = $null
try { Invoke-Adb @('shell','dumpsys','notification') | Out-Null } catch { $message = $_.Exception.Message }
if (-not $message -or $message -match 'private-' -or $calls.Count -ne 4) { throw 'E3: failed second read must stop without exposing raw output.' }
$checks++
Reset-Responses @(@{Code=1;Text='private-launch-output'})
$message = $null
try { Invoke-Adb @('shell','am','start') | Out-Null } catch { $message = $_.Exception.Message }
if (-not $message -or $message -match 'private-' -or $calls.Count -ne 1) { throw 'Mutating ADB actions must not be retried or print raw output.' }
$checks++
Reset-Responses @(@{Code=1;Text='private-dump'},@{Code=1;Text='private-reconnect-error'})
$message = $null
try { Invoke-Adb @('shell','getprop','ro.product.model') | Out-Null } catch { $message = $_.Exception.Message }
if (-not $message -or $message -match 'private-' -or $calls.Count -ne 2) { throw 'Read recovery must stop when target reconnect fails.' }
$checks++
Write-Host "Real ADB read adapter verification passed ($checks scenarios)."
