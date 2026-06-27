# Remote Rules Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the two Doubao call launcher APKs so Doubao voice/video entry rules are fetched from a GitHub-hosted JSON file with local cache fallback, while preserving the elderly-user one-command launch flow.

**Architecture:** Keep the current two-APK shell, icons, package names, signing strategy, and Android SDK CLI build. Move Doubao package/activity/deep-link data into `rules/doubao-call-rules.json`, publish it before wiring the app, then add focused common Java classes for URL candidates, parsing, validation, cache, remote fetch, and launch orchestration. `CallLauncherActivity` remains the Android entry point and owns mode detection, volume, TTS/toast, and final intent dispatch.

**Tech Stack:** Android Java, Android SDK CLI tools (`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`), Android `org.json`, `HttpURLConnection`, `SharedPreferences`, PowerShell verification scripts, Python for local JSON/state-machine checks, GitHub raw file hosting, optional ADB smoke test.

---

## Non-Negotiable Requirements

- Keep two independent APKs: `com.simon.doubao.voicecall` and `com.simon.doubao.videocall`.
- Do not add accessibility services, settings screens, background services, or manual in-app choices.
- On every launch, maximize media volume and try to maximize voice-call volume before rule loading.
- Every launch waits for remote rule fetch attempts before opening Doubao.
- Remote success and validation uses the remote rule and writes it to local cache.
- Remote failure uses valid local cache.
- Remote failure with no valid cache does not open Doubao and speaks/toasts `规则加载失败，请家人检查网络或规则文件`.
- APK framework Java must not contain the current Doubao voice/video deep-link strings as fallback.
- Restrict remote rules to Doubao package `com.larus.nova` and `sslocal://` URIs.
- A lower remote `ruleVersion` must not overwrite a newer cached rule.
- Keep SDK CLI build; do not migrate to Gradle.
- Increment APK `versionCode` from `1` to `2` for this installable update.

## Target File Structure

- Create: `rules/doubao-call-rules.json`
  - Public GitHub-hosted rule source.
- Create: `rules/remote-rule-url.txt`
  - Stores the real public raw GitHub rule URL after repository creation.
- Create: `tests/verify_rules.ps1`
  - Validates rule JSON, URL ordering, and remote/cache decision behavior without Android.
- Modify: `tests/verify_project.ps1`
  - Adds long-term project, rule, forbidden-string, and release-safety checks.
- Modify: `tests/smoke_adb.ps1`
  - Adds `INTERNET` permission check; still does not launch real calls by default.
- Modify: `apps/voice/AndroidManifest.xml`
  - Adds `INTERNET`; bumps version to `2` / `1.1.0`.
- Modify: `apps/video/AndroidManifest.xml`
  - Adds `INTERNET`; bumps version to `2` / `1.1.0`.
- Modify: `build.ps1`
  - Compiles all Java files under `common/src`.
- Create: `common/src/com/simon/doubaolauncher/CallEntry.java`
- Create: `common/src/com/simon/doubaolauncher/DoubaoRule.java`
- Create: `common/src/com/simon/doubaolauncher/RuleValidationException.java`
- Create: `common/src/com/simon/doubaolauncher/DoubaoRuleParser.java`
- Create: `common/src/com/simon/doubaolauncher/RuleUrlCandidates.java`
- Create: `common/src/com/simon/doubaolauncher/RuleCache.java`
- Create: `common/src/com/simon/doubaolauncher/RuleFetchResult.java`
- Create: `common/src/com/simon/doubaolauncher/RuleRepository.java`
- Modify: `common/src/com/simon/doubaolauncher/CallLauncherActivity.java`
- Modify: `README.md`
  - Documents rule hosting, update procedure, URL order, cache behavior, and release checks.

## Rule URL Order

Use the raw GitHub URL from `rules/remote-rule-url.txt`. Candidate order must be:

1. `https://gh-proxy.com/` + raw URL
2. raw URL
3. `https://wget.la/` + raw URL
4. `https://ghfast.top/` + raw URL

Append a timestamp query parameter to every candidate:

- No existing query: `?t=<epochMillis>`
- Existing query: `&t=<epochMillis>`

---

### Task 1: Add Rule File and Long-Term Rule Verification

**Files:**
- Create: `rules/doubao-call-rules.json`
- Create: `tests/verify_rules.ps1`
- Modify: `tests/verify_project.ps1`

- [ ] **Step 1: Create the initial rule JSON**

Create `rules/doubao-call-rules.json`:

```json
{
  "schemaVersion": 1,
  "ruleVersion": 1,
  "updatedAt": "2026-06-27",
  "doubaoPackage": "com.larus.nova",
  "doubaoActivity": "com.larus.home.impl.alias.AliasActivity1",
  "voice": {
    "uri": "sslocal://flow/realtime_chat?is_from_outer=true&bot_id=7234781073513644036&open_method=shortcuts&sec_scene=shortcuts_call&enter_method=shortcuts"
  },
  "video": {
    "uri": "sslocal://flow/realtime_chat?is_from_outer=true&bot_id=7234781073513644036&open_method=shortcuts&open_vlm=1&sec_scene=shortcuts_video_call&enter_method=shortcuts"
  }
}
```

- [ ] **Step 2: Create `tests/verify_rules.ps1`**

Create `tests/verify_rules.ps1`:

```powershell
$ErrorActionPreference = 'Stop'

$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$RulePath = Join-Path $Root 'rules/doubao-call-rules.json'

if (-not (Test-Path $RulePath)) {
    throw "Missing rule file: $RulePath"
}

@'
import copy
import json
from pathlib import Path

rule_path = Path("rules/doubao-call-rules.json")
rule = json.loads(rule_path.read_text(encoding="utf-8"))

def validate_rule(value):
    if value.get("schemaVersion") != 1:
        raise AssertionError("schemaVersion must be 1")
    if not isinstance(value.get("ruleVersion"), int) or value["ruleVersion"] < 1:
        raise AssertionError("ruleVersion must be a positive integer")
    if value.get("doubaoPackage") != "com.larus.nova":
        raise AssertionError("doubaoPackage must be com.larus.nova")
    if not value.get("doubaoActivity"):
        raise AssertionError("doubaoActivity is required")
    for mode in ("voice", "video"):
        entry = value.get(mode)
        if not isinstance(entry, dict):
            raise AssertionError(f"{mode} entry is required")
        uri = entry.get("uri", "")
        if not uri.startswith("sslocal://"):
            raise AssertionError(f"{mode} uri must start with sslocal://")
    return True

def candidate_urls(raw, now):
    return [
        f"https://gh-proxy.com/{raw}?t={now}",
        f"{raw}?t={now}",
        f"https://wget.la/{raw}?t={now}",
        f"https://ghfast.top/{raw}?t={now}",
    ]

def select_rule(remote, cache):
    if remote is not None:
        validate_rule(remote)
        if cache is not None and remote["ruleVersion"] < cache["ruleVersion"]:
            return "cache", cache
        return "remote", remote
    if cache is not None:
        validate_rule(cache)
        return "cache", cache
    return "failure", None

validate_rule(rule)

bad_package = copy.deepcopy(rule)
bad_package["doubaoPackage"] = "bad.package"
try:
    validate_rule(bad_package)
    raise AssertionError("bad package should fail")
except AssertionError as exc:
    if "doubaoPackage" not in str(exc):
        raise

bad_uri = copy.deepcopy(rule)
bad_uri["voice"]["uri"] = "https://example.com"
try:
    validate_rule(bad_uri)
    raise AssertionError("bad uri should fail")
except AssertionError as exc:
    if "voice uri" not in str(exc):
        raise

newer_cache = copy.deepcopy(rule)
newer_cache["ruleVersion"] = 3
older_remote = copy.deepcopy(rule)
older_remote["ruleVersion"] = 2
source, selected = select_rule(older_remote, newer_cache)
assert source == "cache"
assert selected["ruleVersion"] == 3

source, selected = select_rule(rule, None)
assert source == "remote"

source, selected = select_rule(None, rule)
assert source == "cache"

source, selected = select_rule(None, None)
assert source == "failure"
assert selected is None

urls = candidate_urls("https://raw.githubusercontent.com/example/repo/master/rules/doubao-call-rules.json", 123)
assert urls[0].startswith("https://gh-proxy.com/https://raw.githubusercontent.com/")
assert urls[1].startswith("https://raw.githubusercontent.com/")
assert urls[2].startswith("https://wget.la/https://raw.githubusercontent.com/")
assert urls[3].startswith("https://ghfast.top/https://raw.githubusercontent.com/")
assert all(url.endswith("?t=123") for url in urls)

print("Rule verification passed.")
'@ | python -
```

- [ ] **Step 3: Wire rule verification into project verification**

Add these variables to `tests/verify_project.ps1`:

```powershell
$ruleFile = Join-Path $Root 'rules/doubao-call-rules.json'
$verifyRules = Join-Path $Root 'tests/verify_rules.ps1'
```

Add these assertions:

```powershell
Assert-Exists $ruleFile
Assert-Exists $verifyRules
Assert-FileContains $ruleFile '"schemaVersion": 1' 'Rule JSON must declare schemaVersion 1.'
Assert-FileContains $ruleFile '"ruleVersion": 1' 'Initial rule JSON must declare ruleVersion 1.'
Assert-FileContains $ruleFile '"doubaoPackage": "com.larus.nova"' 'Rule JSON must target Doubao package only.'
Assert-FileContains $ruleFile '"voice"' 'Rule JSON must contain a voice entry.'
Assert-FileContains $ruleFile '"video"' 'Rule JSON must contain a video entry.'
Assert-FileContains $verifyRules 'Rule verification passed.' 'Rule verification script must check rule semantics.'
```

Add this command near the end of `tests/verify_project.ps1` before the success message:

```powershell
powershell -ExecutionPolicy Bypass -File $verifyRules
```

- [ ] **Step 4: Run verification**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: existing checks pass and the output includes `Rule verification passed.`

- [ ] **Step 5: Commit**

Run:

```powershell
git add rules/doubao-call-rules.json tests/verify_rules.ps1 tests/verify_project.ps1
git commit -m "test: add remote rule validation"
```

**Risk:** The rule file contains Doubao deep links by design. Later forbidden-string checks must scan framework Java, not the `rules` directory.

**Rollback:** Revert this commit; no APK behavior has changed.

---

### Task 2: Create GitHub Repository and Confirm Public Rule URL

**Files:**
- Create: `rules/remote-rule-url.txt`
- Modify: `README.md`

- [ ] **Step 1: Create or connect the public GitHub repository**

If `gh` is authenticated, run:

```powershell
gh repo create doubao-call-launchers --public --source . --remote origin --push
```

If the repository already exists or the GitHub UI is used, run after creation:

```powershell
git remote add origin https://github.com/$env:GITHUB_USER/doubao-call-launchers.git
git push -u origin master
```

Expected: `git remote -v` shows `origin`, and GitHub contains `rules/doubao-call-rules.json`.

- [ ] **Step 2: Resolve the real raw URL**

Run:

```powershell
$Remote = git remote get-url origin
if ($Remote -match 'github.com[:/]([^/]+)/([^/.]+)(\.git)?$') {
    $Owner = $Matches[1]
    $Repo = $Matches[2]
} else {
    throw "Could not parse GitHub owner/repo from origin: $Remote"
}
$Raw = "https://raw.githubusercontent.com/$Owner/$Repo/master/rules/doubao-call-rules.json"
Set-Content -Encoding ASCII -Path .\rules\remote-rule-url.txt -Value $Raw
Write-Host $Raw
```

Expected: `rules/remote-rule-url.txt` contains the real public raw URL and does not contain angle-bracket placeholders.

- [ ] **Step 3: Verify raw and accelerated URLs**

Run:

```powershell
$Raw = (Get-Content -Raw .\rules\remote-rule-url.txt).Trim()
$Candidates = @(
  "https://gh-proxy.com/$Raw",
  $Raw,
  "https://wget.la/$Raw",
  "https://ghfast.top/$Raw"
)
$Success = 0
foreach ($Url in $Candidates) {
  Write-Host "Testing $Url"
  try {
    $Response = Invoke-WebRequest $Url -UseBasicParsing -TimeoutSec 10
    if ($Response.Content -match '"schemaVersion"\s*:\s*1') {
      $Success += 1
      Write-Host "OK $($Response.StatusCode)"
    } else {
      Write-Warning "Response did not look like the rule JSON."
    }
  } catch {
    Write-Warning $_.Exception.Message
  }
}
if ($Success -lt 1) {
  throw 'No rule URL candidate returned valid JSON.'
}
```

Expected: at least one candidate returns JSON. Preferably the first candidate succeeds.

- [ ] **Step 4: Add release-safety checks**

Add to `tests/verify_project.ps1`:

```powershell
$remoteRuleUrl = Join-Path $Root 'rules/remote-rule-url.txt'
Assert-Exists $remoteRuleUrl
$remoteRuleUrlContent = (Get-Content -Raw -Encoding ASCII $remoteRuleUrl).Trim()
if ($remoteRuleUrlContent -notmatch '^https://raw\.githubusercontent\.com/.+/doubao-call-launchers/master/rules/doubao-call-rules\.json$') {
    throw 'remote-rule-url.txt must contain the real public raw GitHub rule URL.'
}
if ($remoteRuleUrlContent.Contains('<') -or $remoteRuleUrlContent.Contains('>')) {
    throw 'remote-rule-url.txt must not contain placeholders.'
}
```

- [ ] **Step 5: Add README remote hosting text**

Add a `Remote Rule Hosting` section to `README.md`. Use plain text and short command blocks, not a nested Markdown fence:

```markdown
## Remote Rule Hosting

The installed APKs load Doubao entry data from the public raw GitHub URL recorded in `rules/remote-rule-url.txt`.

Rule file:

`rules/doubao-call-rules.json`

Every rule update must increment `ruleVersion`. A lower `ruleVersion` must not overwrite a newer cached rule on phones.

The app tries rule URLs in this order:

1. `https://gh-proxy.com/` + raw URL
2. raw URL
3. `https://wget.la/` + raw URL
4. `https://ghfast.top/` + raw URL

Remote success updates the local cache. Remote failure uses the last valid cache. Remote failure with no valid cache does not open Doubao.
```

- [ ] **Step 6: Run verification and commit**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
git add rules/remote-rule-url.txt README.md tests/verify_project.ps1
git commit -m "docs: publish remote rule URL"
git push
```

Expected: verification passes, commit is pushed, and the raw URL is public.

**Risk:** A private repository will fail on phones because unauthenticated raw fetches are not available.

**Rollback:** Fix the repository visibility or raw URL and make a forward commit before implementing `RuleRepository`.

---

### Task 3: Prepare Android Permissions and CLI Build for Multiple Java Files

**Files:**
- Modify: `apps/voice/AndroidManifest.xml`
- Modify: `apps/video/AndroidManifest.xml`
- Modify: `build.ps1`
- Modify: `tests/verify_project.ps1`

- [ ] **Step 1: Add failing static checks**

Add these assertions to `tests/verify_project.ps1`:

```powershell
Assert-FileContains $voiceManifest 'android.permission.INTERNET' 'Voice manifest must allow network rule fetches.'
Assert-FileContains $videoManifest 'android.permission.INTERNET' 'Video manifest must allow network rule fetches.'
Assert-FileContains $buildScript "Get-ChildItem (Join-Path `$Root 'common\src') -Recurse -Filter '*.java'" 'Build script must compile all common Java sources.'
```

- [ ] **Step 2: Run verification and confirm failure**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: fail with `Voice manifest must allow network rule fetches.`

- [ ] **Step 3: Add `INTERNET` permission and bump version**

In both manifests, add:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Change both manifests from:

```xml
android:versionCode="1"
android:versionName="1.0.0"
```

to:

```xml
android:versionCode="2"
android:versionName="1.1.0"
```

Update the version assertions in `tests/verify_project.ps1` to versionCode `2` and versionName `1.1.0`.

- [ ] **Step 4: Compile all common Java files**

Replace this block in `build.ps1`:

```powershell
$Sources = @(
    (Join-Path $Root 'common\src\com\simon\doubaolauncher\CallLauncherActivity.java')
)
```

with:

```powershell
$Sources = Get-ChildItem (Join-Path $Root 'common\src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
```

- [ ] **Step 5: Run verification and build**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Expected: static verification passes; both APKs build and verify.

- [ ] **Step 6: Commit**

Run:

```powershell
git add apps/voice/AndroidManifest.xml apps/video/AndroidManifest.xml build.ps1 tests/verify_project.ps1
git commit -m "chore: prepare launchers for remote rules"
```

**Risk:** Installed phones cannot be downgraded below versionCode `2` without uninstalling.

**Rollback:** Make a forward fix with versionCode `3` if a distributed build needs correction.

---

### Task 4: Add URL Candidate Builder and Reliable Order Checks

**Files:**
- Create: `common/src/com/simon/doubaolauncher/RuleUrlCandidates.java`
- Modify: `tests/verify_project.ps1`

- [ ] **Step 1: Add static checks**

Add to `tests/verify_project.ps1`:

```powershell
$ruleUrlCandidates = Join-Path $Root 'common/src/com/simon/doubaolauncher/RuleUrlCandidates.java'
Assert-Exists $ruleUrlCandidates
Assert-FileContains $ruleUrlCandidates 'https://gh-proxy.com/' 'First rule URL candidate must use gh-proxy.com.'
Assert-FileContains $ruleUrlCandidates 'https://wget.la/' 'Rule URL candidates must include wget.la.'
Assert-FileContains $ruleUrlCandidates 'https://ghfast.top/' 'Rule URL candidates must include ghfast.top.'
Assert-FileContains $ruleUrlCandidates 'appendTimestamp' 'Rule URL candidates must append a timestamp cache buster.'
```

- [ ] **Step 2: Create `RuleUrlCandidates.java`**

```java
package com.simon.doubaolauncher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class RuleUrlCandidates {
    private static final String GH_PROXY_PREFIX = "https://gh-proxy.com/";
    private static final String WGET_LA_PREFIX = "https://wget.la/";
    private static final String GHFAST_TOP_PREFIX = "https://ghfast.top/";

    private RuleUrlCandidates() {
    }

    static List<String> build(String rawUrl, long nowMillis) {
        Set<String> urls = new LinkedHashSet<>();
        String cleanRawUrl = clean(rawUrl);
        if (!isHttp(cleanRawUrl)) {
            return new ArrayList<>();
        }

        add(urls, GH_PROXY_PREFIX + cleanRawUrl, nowMillis);
        add(urls, cleanRawUrl, nowMillis);
        add(urls, WGET_LA_PREFIX + cleanRawUrl, nowMillis);
        add(urls, GHFAST_TOP_PREFIX + cleanRawUrl, nowMillis);
        return new ArrayList<>(urls);
    }

    private static void add(Set<String> urls, String url, long nowMillis) {
        urls.add(appendTimestamp(url, nowMillis));
    }

    static String appendTimestamp(String url, long nowMillis) {
        String separator = url.indexOf('?') >= 0 ? "&" : "?";
        return url + separator + "t=" + nowMillis;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isHttp(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }
}
```

- [ ] **Step 3: Add reliable order check**

Add this to `tests/verify_project.ps1`:

```powershell
$ruleUrlContent = Get-Content -Raw -Encoding UTF8 $ruleUrlCandidates
$ghProxyIndex = $ruleUrlContent.IndexOf('add(urls, GH_PROXY_PREFIX + cleanRawUrl, nowMillis)')
$rawIndex = $ruleUrlContent.IndexOf('add(urls, cleanRawUrl, nowMillis)')
$wgetIndex = $ruleUrlContent.IndexOf('add(urls, WGET_LA_PREFIX + cleanRawUrl, nowMillis)')
$ghfastIndex = $ruleUrlContent.IndexOf('add(urls, GHFAST_TOP_PREFIX + cleanRawUrl, nowMillis)')
if (-not ($ghProxyIndex -ge 0 -and $rawIndex -gt $ghProxyIndex -and $wgetIndex -gt $rawIndex -and $ghfastIndex -gt $wgetIndex)) {
    throw 'Rule URL candidates must be ordered as gh-proxy, raw, wget.la, ghfast.top.'
}
```

- [ ] **Step 4: Run verification and build**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Expected: passes.

- [ ] **Step 5: Commit**

Run:

```powershell
git add common/src/com/simon/doubaolauncher/RuleUrlCandidates.java tests/verify_project.ps1
git commit -m "feat: add remote rule URL candidates"
```

**Risk:** Proxy services can change behavior. The order is isolated in one file.

**Rollback:** Revert this commit; no launch path depends on it until Task 7.

---

### Task 5: Add Rule Models and Parser Validation

**Files:**
- Create: `common/src/com/simon/doubaolauncher/CallEntry.java`
- Create: `common/src/com/simon/doubaolauncher/DoubaoRule.java`
- Create: `common/src/com/simon/doubaolauncher/RuleValidationException.java`
- Create: `common/src/com/simon/doubaolauncher/DoubaoRuleParser.java`
- Modify: `tests/verify_project.ps1`

- [ ] **Step 1: Add parser checks**

Add to `tests/verify_project.ps1`:

```powershell
$callEntry = Join-Path $Root 'common/src/com/simon/doubaolauncher/CallEntry.java'
$doubaoRule = Join-Path $Root 'common/src/com/simon/doubaolauncher/DoubaoRule.java'
$ruleValidationException = Join-Path $Root 'common/src/com/simon/doubaolauncher/RuleValidationException.java'
$doubaoRuleParser = Join-Path $Root 'common/src/com/simon/doubaolauncher/DoubaoRuleParser.java'
Assert-Exists $callEntry
Assert-Exists $doubaoRule
Assert-Exists $ruleValidationException
Assert-Exists $doubaoRuleParser
Assert-FileContains $doubaoRuleParser 'SUPPORTED_SCHEMA_VERSION = 1' 'Rule parser must declare supported schema version.'
Assert-FileContains $doubaoRuleParser 'ALLOWED_DOUBAO_PACKAGE = "com.larus.nova"' 'Rule parser must restrict Doubao package.'
Assert-FileContains $doubaoRuleParser 'sslocal://' 'Rule parser must restrict call URI scheme.'
Assert-FileContains $doubaoRuleParser 'ruleVersion' 'Rule parser must validate ruleVersion.'
```

- [ ] **Step 2: Create model and exception files**

Create `CallEntry.java`:

```java
package com.simon.doubaolauncher;

final class CallEntry {
    final String uri;

    CallEntry(String uri) {
        this.uri = uri;
    }
}
```

Create `DoubaoRule.java`:

```java
package com.simon.doubaolauncher;

final class DoubaoRule {
    final int schemaVersion;
    final int ruleVersion;
    final String updatedAt;
    final String doubaoPackage;
    final String doubaoActivity;
    final CallEntry voice;
    final CallEntry video;
    final String sourceJson;

    DoubaoRule(int schemaVersion, int ruleVersion, String updatedAt, String doubaoPackage,
            String doubaoActivity, CallEntry voice, CallEntry video, String sourceJson) {
        this.schemaVersion = schemaVersion;
        this.ruleVersion = ruleVersion;
        this.updatedAt = updatedAt;
        this.doubaoPackage = doubaoPackage;
        this.doubaoActivity = doubaoActivity;
        this.voice = voice;
        this.video = video;
        this.sourceJson = sourceJson;
    }

    CallEntry entryForMode(String mode) {
        return "video".equals(mode) ? video : voice;
    }
}
```

Create `RuleValidationException.java`:

```java
package com.simon.doubaolauncher;

final class RuleValidationException extends Exception {
    RuleValidationException(String message) {
        super(message);
    }

    RuleValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: Create `DoubaoRuleParser.java`**

```java
package com.simon.doubaolauncher;

import org.json.JSONException;
import org.json.JSONObject;

final class DoubaoRuleParser {
    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final String ALLOWED_DOUBAO_PACKAGE = "com.larus.nova";
    private static final String REQUIRED_URI_PREFIX = "sslocal://";

    DoubaoRule parse(String json) throws RuleValidationException {
        String cleanJson = clean(json);
        if (cleanJson.length() == 0) {
            throw new RuleValidationException("Rule JSON is empty.");
        }

        try {
            JSONObject root = new JSONObject(cleanJson);
            int schemaVersion = root.optInt("schemaVersion", -1);
            if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                throw new RuleValidationException("Unsupported schemaVersion: " + schemaVersion);
            }

            int ruleVersion = root.optInt("ruleVersion", -1);
            if (ruleVersion < 1) {
                throw new RuleValidationException("Invalid ruleVersion: " + ruleVersion);
            }

            String doubaoPackage = clean(root.optString("doubaoPackage", ""));
            if (!ALLOWED_DOUBAO_PACKAGE.equals(doubaoPackage)) {
                throw new RuleValidationException("Unexpected doubaoPackage: " + doubaoPackage);
            }

            String doubaoActivity = clean(root.optString("doubaoActivity", ""));
            if (doubaoActivity.length() == 0) {
                throw new RuleValidationException("doubaoActivity is missing.");
            }

            CallEntry voice = parseEntry(root.optJSONObject("voice"), "voice");
            CallEntry video = parseEntry(root.optJSONObject("video"), "video");
            return new DoubaoRule(schemaVersion, ruleVersion, clean(root.optString("updatedAt", "")),
                    doubaoPackage, doubaoActivity, voice, video, cleanJson);
        } catch (JSONException ex) {
            throw new RuleValidationException("Rule JSON is malformed.", ex);
        }
    }

    private CallEntry parseEntry(JSONObject object, String label) throws RuleValidationException {
        if (object == null) {
            throw new RuleValidationException(label + " entry is missing.");
        }
        String uri = clean(object.optString("uri", ""));
        if (!uri.startsWith(REQUIRED_URI_PREFIX)) {
            throw new RuleValidationException(label + " uri must start with sslocal://");
        }
        return new CallEntry(uri);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
```

- [ ] **Step 4: Run verification and build**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Expected: passes.

- [ ] **Step 5: Commit**

Run:

```powershell
git add common/src/com/simon/doubaolauncher/CallEntry.java common/src/com/simon/doubaolauncher/DoubaoRule.java common/src/com/simon/doubaolauncher/RuleValidationException.java common/src/com/simon/doubaolauncher/DoubaoRuleParser.java tests/verify_project.ps1
git commit -m "feat: add Doubao rule parser"
```

**Risk:** Android parser behavior is covered by build; detailed edge cases are covered by `tests/verify_rules.ps1`.

**Rollback:** Revert parser files; no Activity code depends on them until Task 8.

---

### Task 6: Add Deterministic Local Rule Cache

**Files:**
- Create: `common/src/com/simon/doubaolauncher/RuleCache.java`
- Modify: `tests/verify_project.ps1`

- [ ] **Step 1: Add cache checks**

Add to `tests/verify_project.ps1`:

```powershell
$ruleCache = Join-Path $Root 'common/src/com/simon/doubaolauncher/RuleCache.java'
Assert-Exists $ruleCache
Assert-FileContains $ruleCache 'SharedPreferences' 'Rule cache must use SharedPreferences.'
Assert-FileContains $ruleCache 'KEY_RULE_JSON' 'Rule cache must store the last valid JSON.'
Assert-FileContains $ruleCache 'commit()' 'Rule cache must use deterministic commit writes.'
Assert-FileContains $ruleCache 'save(DoubaoRule rule)' 'Rule cache must save validated rules only.'
Assert-FileContains $ruleCache 'load()' 'Rule cache must load cached rules.'
```

- [ ] **Step 2: Create `RuleCache.java`**

```java
package com.simon.doubaolauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

final class RuleCache {
    private static final String TAG = "DoubaoRuleCache";
    private static final String PREFS_NAME = "doubao_rule_cache";
    private static final String KEY_RULE_JSON = "rule_json";

    private final SharedPreferences preferences;
    private final DoubaoRuleParser parser;

    RuleCache(Context context, DoubaoRuleParser parser) {
        this.preferences = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.parser = parser;
    }

    DoubaoRule load() {
        String json = preferences.getString(KEY_RULE_JSON, "");
        if (json == null || json.trim().length() == 0) {
            return null;
        }
        try {
            return parser.parse(json);
        } catch (RuleValidationException ex) {
            Log.w(TAG, "Cached rule is invalid.", ex);
            return null;
        }
    }

    boolean save(DoubaoRule rule) {
        if (rule == null || rule.sourceJson == null || rule.sourceJson.trim().length() == 0) {
            return false;
        }
        boolean saved = preferences.edit().putString(KEY_RULE_JSON, rule.sourceJson).commit();
        if (!saved) {
            Log.w(TAG, "Failed to commit rule cache.");
        }
        return saved;
    }
}
```

- [ ] **Step 3: Run verification and build**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Expected: passes.

- [ ] **Step 4: Commit**

Run:

```powershell
git add common/src/com/simon/doubaolauncher/RuleCache.java tests/verify_project.ps1
git commit -m "feat: add deterministic rule cache"
```

**Risk:** `commit()` blocks briefly, but rule JSON is tiny and this runs on the repository background thread.

**Rollback:** Revert this commit; remote-only behavior must not be released without cache fallback.

---

### Task 7: Add Remote-First Rule Repository with Real Raw URL

**Files:**
- Create: `common/src/com/simon/doubaolauncher/RuleFetchResult.java`
- Create: `common/src/com/simon/doubaolauncher/RuleRepository.java`
- Modify: `tests/verify_project.ps1`

- [ ] **Step 1: Verify precondition**

Run:

```powershell
$Raw = (Get-Content -Raw .\rules\remote-rule-url.txt).Trim()
if ($Raw -notmatch '^https://raw\.githubusercontent\.com/.+/doubao-call-launchers/master/rules/doubao-call-rules\.json$') {
  throw "Real raw rule URL is missing. Complete Task 2 before RuleRepository."
}
Write-Host $Raw
```

Expected: prints the real raw URL. Stop here if it fails.

- [ ] **Step 2: Add repository checks**

Add to `tests/verify_project.ps1`:

```powershell
$ruleFetchResult = Join-Path $Root 'common/src/com/simon/doubaolauncher/RuleFetchResult.java'
$ruleRepository = Join-Path $Root 'common/src/com/simon/doubaolauncher/RuleRepository.java'
Assert-Exists $ruleFetchResult
Assert-Exists $ruleRepository
Assert-FileContains $ruleRepository 'HttpURLConnection' 'Rule repository must fetch remote rules with HttpURLConnection.'
Assert-FileContains $ruleRepository 'setConnectTimeout' 'Rule repository must use a connect timeout.'
Assert-FileContains $ruleRepository 'setReadTimeout' 'Rule repository must use a read timeout.'
Assert-FileContains $ruleRepository 'RuleUrlCandidates.build' 'Rule repository must use ordered URL candidates.'
Assert-FileContains $ruleRepository 'remoteRule.ruleVersion < cached.ruleVersion' 'Rule repository must reject older remote rules.'
Assert-FileContains $ruleRepository 'cache.save(remoteRule)' 'Rule repository must save valid remote rules.'
Assert-FileContains $ruleRepository 'RuleFetchResult.fromCache' 'Rule repository must fall back to valid cache.'
Assert-FileContains $ruleRepository 'raw.githubusercontent.com' 'Rule repository must contain the public raw GitHub rule URL.'
$ruleRepositoryContent = Get-Content -Raw -Encoding UTF8 $ruleRepository
if ($ruleRepositoryContent.Contains('raw-url-placeholder')) {
    throw 'RuleRepository must not contain placeholder URL text.'
}
```

- [ ] **Step 3: Create `RuleFetchResult.java`**

```java
package com.simon.doubaolauncher;

final class RuleFetchResult {
    enum Source {
        REMOTE,
        CACHE,
        FAILURE
    }

    final Source source;
    final DoubaoRule rule;
    final String message;

    private RuleFetchResult(Source source, DoubaoRule rule, String message) {
        this.source = source;
        this.rule = rule;
        this.message = message;
    }

    static RuleFetchResult fromRemote(DoubaoRule rule) {
        return new RuleFetchResult(Source.REMOTE, rule, "");
    }

    static RuleFetchResult fromCache(DoubaoRule rule) {
        return new RuleFetchResult(Source.CACHE, rule, "");
    }

    static RuleFetchResult failure(String message) {
        return new RuleFetchResult(Source.FAILURE, null, message);
    }

    boolean hasRule() {
        return rule != null;
    }
}
```

- [ ] **Step 4: Generate `RuleRepository.java` with the real raw URL**

Run this command to generate `RuleRepository.java` with the exact value from `rules/remote-rule-url.txt`. The command stops if the URL is not a real public raw GitHub URL.

```powershell
$Raw = (Get-Content -Raw .\rules\remote-rule-url.txt).Trim()
if ($Raw -notmatch '^https://raw\.githubusercontent\.com/.+/doubao-call-launchers/master/rules/doubao-call-rules\.json$') {
    throw "Real raw rule URL is missing. Complete Task 2 before RuleRepository."
}
$EscapedRaw = $Raw.Replace('\', '\\').Replace('"', '\"')
$RepoFile = '.\common\src\com\simon\doubaolauncher\RuleRepository.java'
@"
package com.simon.doubaolauncher;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class RuleRepository {
    private static final String TAG = "DoubaoRuleRepo";
    private static final String RAW_RULE_URL = "$EscapedRaw";
    private static final int CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int READ_TIMEOUT_MILLIS = 3000;
    private static final String RULE_LOAD_FAILED_MESSAGE = "规则加载失败，请家人检查网络或规则文件";

    private final DoubaoRuleParser parser;
    private final RuleCache cache;

    RuleRepository(Context context) {
        this.parser = new DoubaoRuleParser();
        this.cache = new RuleCache(context, parser);
    }

    RuleFetchResult loadRule() {
        DoubaoRule cached = cache.load();
        List<String> candidates = RuleUrlCandidates.build(RAW_RULE_URL, System.currentTimeMillis());
        for (String candidate : candidates) {
            try {
                String json = fetch(candidate);
                DoubaoRule remoteRule = parser.parse(json);
                if (cached != null && remoteRule.ruleVersion < cached.ruleVersion) {
                    Log.w(TAG, "Remote ruleVersion is older than cache: remote="
                            + remoteRule.ruleVersion + ", cache=" + cached.ruleVersion);
                    continue;
                }
                boolean saved = cache.save(remoteRule);
                if (!saved) {
                    Log.w(TAG, "Remote rule is valid but cache update failed.");
                }
                return RuleFetchResult.fromRemote(remoteRule);
            } catch (IOException ex) {
                Log.w(TAG, "Failed to fetch rule from " + candidate, ex);
            } catch (RuleValidationException ex) {
                Log.w(TAG, "Remote rule invalid from " + candidate, ex);
            } catch (RuntimeException ex) {
                Log.w(TAG, "Unexpected rule fetch failure from " + candidate, ex);
            }
        }

        if (cached != null) {
            return RuleFetchResult.fromCache(cached);
        }
        return RuleFetchResult.failure(RULE_LOAD_FAILED_MESSAGE);
    }

    private String fetch(String urlString) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Unexpected HTTP status: " + code);
            }
            return readFully(connection.getInputStream());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readFully(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } finally {
            reader.close();
        }
        return builder.toString();
    }
}
"@ | Set-Content -Encoding UTF8 $RepoFile
```

- [ ] **Step 5: Verify the raw URL line**

Run:

```powershell
$Raw = (Get-Content -Raw .\rules\remote-rule-url.txt).Trim()
$RepoFile = '.\common\src\com\simon\doubaolauncher\RuleRepository.java'
if (-not ((Get-Content -Raw -Encoding UTF8 $RepoFile).Contains($Raw))) {
    throw 'RuleRepository does not contain the real raw URL.'
}
```

Expected: no error.

- [ ] **Step 6: Run verification and build**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Expected: passes.

- [ ] **Step 7: Commit**

Run:

```powershell
git add common/src/com/simon/doubaolauncher/RuleFetchResult.java common/src/com/simon/doubaolauncher/RuleRepository.java tests/verify_project.ps1
git commit -m "feat: add remote-first rule repository"
```

**Risk:** If the raw URL changes, installed APKs need a framework rebuild. Rule content changes only require updating `rules/doubao-call-rules.json`.

**Rollback:** Make a forward fix with the corrected raw URL and rebuild APKs.

---

### Task 8: Refactor Activity to Use Loaded Rules

**Files:**
- Modify: `common/src/com/simon/doubaolauncher/CallLauncherActivity.java`
- Modify: `tests/verify_project.ps1`

- [ ] **Step 1: Replace Activity hardcoded-rule checks**

Remove old assertions that require `shortcuts_call`, `shortcuts_video_call`, and `open_vlm=1` inside `CallLauncherActivity.java`.

Add:

```powershell
Assert-FileContains $launcherActivity 'new Thread' 'Launcher must load remote rules off the main thread.'
Assert-FileContains $launcherActivity 'RuleRepository' 'Launcher must use RuleRepository.'
Assert-FileContains $launcherActivity 'runOnUiThread' 'Launcher must return to main thread before UI/Activity work.'
Assert-FileContains $launcherActivity 'RULE_LOAD_FAILED_MESSAGE' 'Launcher must speak a rule-load failure message.'
Assert-FileContains $launcherActivity 'entryForMode' 'Launcher must select voice/video entry from the loaded rule.'

$javaSourceRoot = Join-Path $Root 'common/src'
$forbiddenPatterns = @('shortcuts_call', 'shortcuts_video_call', 'open_vlm=1')
foreach ($pattern in $forbiddenPatterns) {
    $match = Get-ChildItem $javaSourceRoot -Recurse -Filter '*.java' | Select-String -Pattern $pattern -SimpleMatch
    if ($match) {
        throw "Framework Java must not contain hardcoded Doubao deep-link fragment: $pattern"
    }
}
```

- [ ] **Step 2: Refactor `launchDoubaoCall()`**

Remove hardcoded `DOUBAO_PACKAGE`, `DOUBAO_ALIAS_ACTIVITY`, `VOICE_CALL_URI`, and `VIDEO_CALL_URI`.

Add:

```java
private static final String RULE_LOAD_FAILED_MESSAGE =
        "\u89c4\u5219\u52a0\u8f7d\u5931\u8d25\uff0c\u8bf7\u5bb6\u4eba\u68c0\u67e5\u7f51\u7edc\u6216\u89c4\u5219\u6587\u4ef6\u3002";
```

Replace `launchDoubaoCall()` with:

```java
private void launchDoubaoCall() {
    maximizeAudibleVolume();

    final String mode = readLaunchMode();
    Log.i(TAG, "launch mode=" + mode);

    final RuleRepository repository = new RuleRepository(this);
    new Thread(new Runnable() {
        @Override
        public void run() {
            final RuleFetchResult result = repository.loadRule();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (activityDestroyed) {
                        return;
                    }
                    handleRuleResult(mode, result);
                }
            });
        }
    }, "doubao-rule-loader").start();
}
```

Add:

```java
private void handleRuleResult(String mode, RuleFetchResult result) {
    if (result == null || !result.hasRule()) {
        speakAndToast(RULE_LOAD_FAILED_MESSAGE);
        return;
    }
    openDoubaoCall(mode, result.rule);
}

private void openDoubaoCall(String mode, DoubaoRule rule) {
    boolean video = MODE_VIDEO.equals(mode);
    CallEntry entry = rule.entryForMode(mode);

    if (!isPackageInstalled(rule.doubaoPackage)) {
        Log.w(TAG, "Doubao package is not installed or not visible.");
        speakAndToast(DOUBAO_NOT_INSTALLED_MESSAGE);
        return;
    }

    speakAndToast(video ? OPENING_VIDEO_MESSAGE : OPENING_VOICE_MESSAGE);

    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(entry.uri));
    intent.addCategory(Intent.CATEGORY_LAUNCHER);
    intent.setComponent(new ComponentName(rule.doubaoPackage, rule.doubaoActivity));
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    Log.i(TAG, "starting Doubao intent=" + intent.toUri(0));

    try {
        startActivity(intent);
        Log.i(TAG, "Doubao startActivity dispatched.");
    } catch (ActivityNotFoundException ex) {
        Log.e(TAG, "Doubao activity not found.", ex);
        speakAndToast(ENTRY_UNAVAILABLE_MESSAGE);
    } catch (RuntimeException ex) {
        Log.e(TAG, "Failed to open Doubao.", ex);
        speakAndToast(OPEN_FAILED_MESSAGE);
    }
}
```

- [ ] **Step 3: Run verification and build**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Expected: passes, and framework Java contains no `shortcuts_call`, `shortcuts_video_call`, or `open_vlm=1`.

- [ ] **Step 4: Commit**

Run:

```powershell
git add common/src/com/simon/doubaolauncher/CallLauncherActivity.java tests/verify_project.ps1
git commit -m "refactor: launch Doubao from loaded rules"
```

**Risk:** Remote loading can add delay on poor networks. This is required because remote-first is non-negotiable.

**Rollback:** Revert only during development; do not ship a fallback hardcoded deep link.

---

### Task 9: Update Smoke Test and Operations Documentation

**Files:**
- Modify: `tests/smoke_adb.ps1`
- Modify: `README.md`
- Modify: `tests/verify_project.ps1`

- [ ] **Step 1: Add smoke test internet permission checks**

In `tests/smoke_adb.ps1`, after `MODIFY_AUDIO_SETTINGS` assertions, add:

```powershell
Assert-Contains $voiceDump 'android.permission.INTERNET: granted=true' 'Voice package internet permission is not granted.'
Assert-Contains $videoDump 'android.permission.INTERNET: granted=true' 'Video package internet permission is not granted.'
```

Add to `tests/verify_project.ps1`:

```powershell
Assert-FileContains $adbSmokeTest 'android.permission.INTERNET' 'ADB smoke test must verify internet permission.'
Assert-FileContains $adbSmokeTest 'LaunchVoice' 'ADB smoke test must require an explicit opt-in to launch voice.'
Assert-FileContains $adbSmokeTest 'LaunchVideo' 'ADB smoke test must require an explicit opt-in to launch video.'
```

- [ ] **Step 2: Document remote rule operations**

Add a `Remote Rule Update Procedure` section to `README.md`:

```markdown
## Remote Rule Update Procedure

When Doubao changes shortcut or deep-link behavior:

1. Re-check the working Doubao package, Activity, voice URI, and video URI.
2. Edit `rules/doubao-call-rules.json`.
3. Increment `ruleVersion`.
4. Commit and push to GitHub.
5. Confirm the raw GitHub URL in `rules/remote-rule-url.txt` returns the updated JSON.
6. Confirm at least one accelerated URL returns the updated JSON.

The installed APK does not need to be rebuilt for normal Doubao rule updates. Rebuild APKs only when framework code, permissions, package names, app icons, signing, or rule URL hosting changes.
```

- [ ] **Step 3: Run verification and commit**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
git add tests/smoke_adb.ps1 README.md tests/verify_project.ps1
git commit -m "docs: document remote rule operations"
```

Expected: passes.

**Risk:** README must not imply cached old rules are preferred over remote success.

**Rollback:** Documentation-only rollback is safe.

---

### Task 10: Final Build, Install, and Device Verification

**Files:**
- Build outputs: `dist/doubao-voice-call.apk`
- Build outputs: `dist/doubao-video-call.apk`

- [ ] **Step 1: Run final static verification**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: `Project verification passed.` and `Rule verification passed.`

- [ ] **Step 2: Build both APKs**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Expected: both APKs build and pass apksigner verification.

- [ ] **Step 3: Inspect APK badging**

Run:

```powershell
$Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$BuildTools = (Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$Aapt = Join-Path $BuildTools 'aapt.exe'
& $Aapt dump badging .\dist\doubao-voice-call.apk | Select-String -Pattern '^package|application-label'
& $Aapt dump badging .\dist\doubao-video-call.apk | Select-String -Pattern '^package|application-label'
```

Expected: package names are unchanged, versionCode is `2`, and versionName is `1.1.0`.

- [ ] **Step 4: Install to connected phone**

Run:

```powershell
adb devices
adb install -r .\dist\doubao-voice-call.apk
adb install -r .\dist\doubao-video-call.apk
```

Expected: both installs return `Success`.

- [ ] **Step 5: Run ADB smoke test**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\smoke_adb.ps1 -DeviceId 304b77ee
```

Expected: `ADB smoke test passed.` It must not launch voice or video unless `-LaunchVoice` or `-LaunchVideo` is passed.

- [ ] **Step 6: Optional real launch test**

Only run if the user approves opening the apps:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\smoke_adb.ps1 -DeviceId 304b77ee -LaunchVoice
powershell -ExecutionPolicy Bypass -File .\tests\smoke_adb.ps1 -DeviceId 304b77ee -LaunchVideo
```

Expected:

- Volume is maximized before rule loading.
- Remote rule fetch succeeds, or valid cache is used after remote failure.
- Doubao opens the matching voice/video entry.
- If remote and cache are unavailable, Doubao does not open and the rule failure prompt is spoken/shown.

- [ ] **Step 7: Check final git state**

Run:

```powershell
git status --short --ignored
```

Expected: only ignored build outputs such as `build/` and `dist/` appear.

**Risk:** Device behavior can still depend on Doubao-side changes, proxy availability, ROM behavior, and first-run Android authorization prompts.

**Rollback:** If framework behavior is correct, fix Doubao changes by updating `rules/doubao-call-rules.json` and incrementing `ruleVersion`; rebuild APKs only for framework URL, permission, package, signing, or code changes.

---

## Final Acceptance Criteria

- `rules/doubao-call-rules.json` exists, passes `tests/verify_rules.ps1`, and is pushed to GitHub.
- `rules/remote-rule-url.txt` contains the real public raw GitHub rule URL.
- At least one remote URL candidate returns valid JSON; candidate order is `gh-proxy`, raw, `wget.la`, `ghfast.top`.
- `RuleRepository.java` contains the real raw URL and no placeholder text.
- APK framework Java contains no `shortcuts_call`, `shortcuts_video_call`, or `open_vlm=1`.
- The rule file is the only repository location that contains current Doubao voice/video deep-link fragments.
- Both APKs declare `INTERNET` and `MODIFY_AUDIO_SETTINGS`.
- Both APKs use versionCode `2` and versionName `1.1.0`.
- Remote success updates cache and launches with remote rule.
- Remote failure uses valid cache.
- Remote failure without valid cache speaks/toasts `规则加载失败，请家人检查网络或规则文件` and does not open Doubao.
- Invalid remote JSON does not overwrite cache.
- Lower remote `ruleVersion` does not overwrite newer cache.
- Static verification, rule verification, build, APK badging check, install, and ADB smoke test pass.
