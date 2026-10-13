$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$source = Get-Content -LiteralPath (Join-Path $root 'common/src/com/simon/doubaolauncher/TouchShieldService.java') -Raw -Encoding utf8
if ($source -match 'permissions\s*\?\s*session\.observe') { throw 'E1: permission failure still bypasses continuous session observation.' }
if (-not $source.Contains('session.observe(currentForeground, call.status, screenAvailable(), permissions, now)')) { throw 'E1: all continuous session inputs must reach the state machine.' }
if ($source.IndexOf('(effects & CallSession.HIDE)') -gt $source.IndexOf('(effects & CallSession.END)')) { throw 'Session effects must hide before requesting hangup.' }
Write-Host 'Continuous session input contract passed.'
