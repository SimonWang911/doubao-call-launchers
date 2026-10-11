$ErrorActionPreference = 'Stop'

$Root = Resolve-Path (Join-Path $PSScriptRoot '..')

function Assert-FileContains {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing file: $Path"
    }

    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $Path
    if (-not $content.Contains($Pattern)) {
        throw $Message
    }
}

function Assert-FileNotContains {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing file: $Path"
    }

    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $Path
    if ($content.Contains($Pattern)) {
        throw $Message
    }
}

$ruleCache = Join-Path $Root 'common/src/com/simon/doubaolauncher/RuleCache.java'
$buildScript = Join-Path $Root 'build.ps1'
$plan = Join-Path $Root 'docs/refactor/2026-10-11-architecture-maintenance-refactor-plan.md'
$planReview = Join-Path $Root 'docs/refactor/2026-10-11-architecture-maintenance-plan-review.md'
$readme = Join-Path $Root 'README.md'

Assert-FileContains $ruleCache 'private static final Object CACHE_WRITE_LOCK' 'RuleCache must declare a process-wide cache write lock.'
Assert-FileContains $ruleCache 'synchronized (CACHE_WRITE_LOCK)' 'RuleCache must protect reload, version comparison, and commit with the shared lock.'
Assert-FileNotContains $ruleCache 'synchronized boolean saveIfNewer' 'RuleCache must not rely on an instance-level synchronized method for cross-instance safety.'
Assert-FileContains $buildScript 'if (-not (Test-Path -LiteralPath $KeyStore))' 'Build script must check the signing keystore before building.'
Assert-FileContains $buildScript 'throw "Signing keystore is required' 'Build script must fail when the signing keystore is missing.'
Assert-FileNotContains $buildScript 'keytool -genkeypair' 'Build script must not silently generate a replacement signing key.'
Assert-FileContains $plan 'tests/verify_refactor.ps1' 'Refactor plan must include the executable refactor contract gate.'
Assert-FileContains $plan 'CACHE_WRITE_LOCK' 'Refactor plan must document the process-wide cache write lock.'
Assert-FileContains $planReview 'verify_project.ps1' 'Development review must record the aggregate test-entry requirement.'
Assert-FileContains $readme 'remote-rule-url.txt` is an audit record' 'README must distinguish the fixed APK URL from the audit URL file.'

# Deterministic model of the confirmed race: both instances read v1 before either commit.
$oldRaceCacheVersion = 1
$v5ReadVersion = $oldRaceCacheVersion
$v4ReadVersion = $oldRaceCacheVersion
$v5ShouldWrite = 5 -gt $v5ReadVersion
$v4ShouldWrite = 4 -gt $v4ReadVersion
if ($v5ShouldWrite) {
    $oldRaceCacheVersion = 5
}
if ($v4ShouldWrite) {
    $oldRaceCacheVersion = 4
}
if ($oldRaceCacheVersion -ne 4) {
    throw 'The deterministic old-cache race model must end at the stale v4 write.'
}

# With one shared critical section, the second writer reloads v5 and rejects v4.
$serializedCacheVersion = 1
if (5 -gt $serializedCacheVersion) {
    $serializedCacheVersion = 5
}
if (4 -gt $serializedCacheVersion) {
    $serializedCacheVersion = 4
}
if ($serializedCacheVersion -ne 5) {
    throw 'The serialized cache model must preserve the newer v5 rule.'
}

Write-Host 'Refactor contract verification passed.'
