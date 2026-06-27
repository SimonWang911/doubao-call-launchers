# Concurrent Rule Refresh Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the existing remote-rule launcher flow so rule validation stays flexible, remote rule fetches run concurrently, cached rules can launch after a 2 second foreground wait, and first-install/no-cache launches wait up to 10 seconds for remote rules.

**Architecture:** Keep the current two-APK launcher architecture, package names, GitHub-hosted rule file, and Android SDK CLI build. Refactor only the rule validation, cache write policy, remote fetch coordination, tests, and documentation. `CallLauncherActivity` remains the Android entry point and still owns volume maximization, mode detection, UI/TTS prompts, and final Doubao dispatch.

**Tech Stack:** Android Java, Android `Uri`, `HttpURLConnection`, Java threads/synchronization, `SharedPreferences`, PowerShell verification scripts, Python snippets inside PowerShell tests, Android SDK CLI build tools.

---

## Confirmed Product Rules

- Keep two independent APKs:
  - `com.simon.doubao.voicecall`
  - `com.simon.doubao.videocall`
- Keep fixed Doubao package validation: `doubaoPackage == com.larus.nova`.
- Keep forced volume maximization before any rule loading or launching.
- Keep the raw GitHub rule URL fixed to this repository. Do not make the repository URL remotely configurable in this refactor.
- Keep the current URL candidate order:
  1. `https://gh-proxy.com/` + raw URL
  2. raw URL
  3. `https://wget.la/` + raw URL
  4. `https://ghfast.top/` + raw URL
- Remote requests must run concurrently.
- If valid cache exists, foreground waits up to 2 seconds for a newer remote rule. If none arrives, launch with cache.
- If no valid cache exists, foreground waits up to 10 seconds for a valid remote rule. If none arrives, show/speak rule-load failure and do not open Doubao.
- Late remote results after cache launch must be allowed to refresh cache, but must never launch Doubao again in the same app invocation.
- Lower or equal remote `ruleVersion` must not overwrite a cached rule.
- Higher remote `ruleVersion` may overwrite cache after validation.
- Cache writes must synchronize the whole read-current-cache-version and commit sequence, so concurrent late remote results cannot write an older rule after a newer rule.
- Version release target for this refactor: `versionCode=3`, `versionName=1.1.1`, tag/release `v1.1.1`.

## Target State Machine

```text
Launch Activity
  -> maximize media and voice-call volume
  -> read launch mode from manifest metadata
  -> read valid local cache
  -> start concurrent remote rule requests

If cache exists:
  -> wait up to 2000 ms for valid remote rule with ruleVersion > cache.ruleVersion
  -> if newer remote arrives: save remote, launch with remote
  -> otherwise: launch with cache
  -> late remote result:
       if valid and ruleVersion > current cache ruleVersion, update cache only
       never launch Doubao again

If cache does not exist:
  -> wait up to 10000 ms for first valid remote rule
  -> if valid remote arrives: save remote, launch with remote
  -> if all requests fail or 10000 ms expires: speak/toast rule-load failure
  -> late remote result after failure: may update cache, but must not launch Doubao
```

## File Structure

- Modify: `common/src/com/simon/doubaolauncher/DoubaoRuleParser.java`
  - Keep schema, rule version, fixed Doubao package, non-empty activity, non-empty mode URIs, and non-empty URI scheme validation.
  - Remove business-route validation such as fixed `sslocal://`.
- Modify: `common/src/com/simon/doubaolauncher/RuleCache.java`
  - Add a synchronized version-aware cache write method that rereads current cache before overwriting.
- Modify: `common/src/com/simon/doubaolauncher/RuleFetchResult.java`
  - Add enough source/status information for cache launch, remote launch, and failure.
- Modify: `common/src/com/simon/doubaolauncher/RuleRepository.java`
  - Replace serial URL loop with concurrent request coordination.
  - Implement 2 second cached foreground wait and 10 second no-cache wait.
  - Keep late remote refresh for cache only.
  - Close the timeout race by making `markForegroundDecisionMade()` also cache any `bestRemote` that arrived just before cache launch/failure.
- Modify: `common/src/com/simon/doubaolauncher/CallLauncherActivity.java`
  - Keep one foreground launch decision per Activity instance.
  - Use application context for repository work.
  - Avoid UI/TTS/startActivity after Activity destruction.
- Modify: `tests/verify_rules.ps1`
  - Update rule validation and rule-selection tests for flexible URI schemes and version-aware cache writes.
- Modify: `tests/verify_project.ps1`
  - Update structural checks for concurrent fetching, 2 second/10 second constants, version-aware cache writes, and relaxed parser validation.
- Modify: `tests/smoke_adb.ps1`
  - Update expected version metadata to `1.1.1` if the script checks version output.
- Modify: `apps/voice/AndroidManifest.xml`
  - Bump `versionCode` to `3` and `versionName` to `1.1.1`.
- Modify: `apps/video/AndroidManifest.xml`
  - Bump `versionCode` to `3` and `versionName` to `1.1.1`.
- Modify: `README.md`
  - Document concurrent remote fetching, cache-first fallback after 2 seconds, no-cache 10 second wait, late remote cache refresh, and relaxed URI validation.

---

### Task 1: Update Rule Validation Tests First

**Files:**
- Modify: `tests/verify_rules.ps1`
- Modify: `tests/verify_project.ps1`

- [ ] **Step 1: Change `tests/verify_rules.ps1` validation model**

Replace the Python validation helper in `tests/verify_rules.ps1` with this model:

```python
def validate_rule(value):
    if value.get("schemaVersion") != 1:
        raise AssertionError("schemaVersion must be 1")
    if not isinstance(value.get("ruleVersion"), int) or value["ruleVersion"] < 1:
        raise AssertionError("ruleVersion must be a positive integer")
    if value.get("doubaoPackage") != "com.larus.nova":
        raise AssertionError("doubaoPackage must be com.larus.nova")
    if not str(value.get("doubaoActivity", "")).strip():
        raise AssertionError("doubaoActivity is required")
    for mode in ("voice", "video"):
        entry = value.get(mode)
        if not isinstance(entry, dict):
            raise AssertionError(f"{mode} entry is required")
        uri = str(entry.get("uri", "")).strip()
        if not uri:
            raise AssertionError(f"{mode} uri is required")
        if ":" not in uri or uri.startswith(":"):
            raise AssertionError(f"{mode} uri scheme is required")
    return True
```

Add these test cases after the existing bad package test:

```python
bad_no_scheme = copy.deepcopy(rule)
bad_no_scheme["voice"]["uri"] = "flow/realtime_chat?x=1"
try:
    validate_rule(bad_no_scheme)
    raise AssertionError("missing scheme should fail")
except AssertionError as exc:
    if "scheme" not in str(exc):
        raise

alternate_scheme = copy.deepcopy(rule)
alternate_scheme["voice"]["uri"] = "doubao://voice-entry"
validate_rule(alternate_scheme)
```

Remove any expectation that `voice.uri` must start with `sslocal://`.

- [ ] **Step 2: Add pure decision-model tests in `tests/verify_rules.ps1`**

Add this Python helper after `select_rule` or replace `select_rule` with these helpers:

```python
def should_overwrite_cache(remote, cache):
    validate_rule(remote)
    if cache is None:
        return True
    validate_rule(cache)
    return remote["ruleVersion"] > cache["ruleVersion"]

def foreground_choice(cache, remote_results_in_arrival_order):
    if cache is not None:
        validate_rule(cache)
        for remote in remote_results_in_arrival_order:
            if should_overwrite_cache(remote, cache):
                return "remote", remote
        return "cache", cache

    for remote in remote_results_in_arrival_order:
        validate_rule(remote)
        return "remote", remote
    return "failure", None

def late_remote_refreshes_cache(remote, current_cache):
    return should_overwrite_cache(remote, current_cache)
```

Add these assertions:

```python
cache_v3 = copy.deepcopy(rule)
cache_v3["ruleVersion"] = 3
remote_v2 = copy.deepcopy(rule)
remote_v2["ruleVersion"] = 2
remote_v3 = copy.deepcopy(rule)
remote_v3["ruleVersion"] = 3
remote_v4 = copy.deepcopy(rule)
remote_v4["ruleVersion"] = 4
remote_v5 = copy.deepcopy(rule)
remote_v5["ruleVersion"] = 5

assert should_overwrite_cache(remote_v4, cache_v3) is True
assert should_overwrite_cache(remote_v3, cache_v3) is False
assert should_overwrite_cache(remote_v2, cache_v3) is False
assert should_overwrite_cache(remote_v2, None) is True

source, selected = foreground_choice(cache_v3, [remote_v2, remote_v3])
assert source == "cache"
assert selected["ruleVersion"] == 3

source, selected = foreground_choice(cache_v3, [remote_v4])
assert source == "remote"
assert selected["ruleVersion"] == 4

source, selected = foreground_choice(cache_v3, [remote_v4, remote_v5])
assert source == "remote"
assert selected["ruleVersion"] == 4

assert late_remote_refreshes_cache(remote_v5, remote_v4) is True
assert late_remote_refreshes_cache(remote_v3, remote_v4) is False

source, selected = foreground_choice(None, [remote_v2])
assert source == "remote"
assert selected["ruleVersion"] == 2
```

This intentionally models foreground behavior as first valid higher-version remote in arrival order, not highest `ruleVersion` among all in-window responses. A later higher version should refresh cache only.

- [ ] **Step 3: Update structural checks in `tests/verify_project.ps1`**

Change parser-related checks:

```powershell
Assert-FileContains $doubaoRuleParser 'ALLOWED_DOUBAO_PACKAGE = "com.larus.nova"' 'Rule parser must restrict Doubao package.'
Assert-FileContains $doubaoRuleParser 'Uri.parse' 'Rule parser must parse rule URIs with Android Uri.'
Assert-FileContains $doubaoRuleParser 'getScheme()' 'Rule parser must require a non-empty URI scheme.'
```

Remove this old expectation:

```powershell
Assert-FileContains $doubaoRuleParser 'sslocal://' 'Rule parser must restrict call URI scheme.'
```

Add repository/cache checks:

```powershell
Assert-FileContains $ruleCache 'saveIfNewer' 'Rule cache must prevent equal or lower ruleVersion overwrites.'
Assert-FileContains $ruleCache 'synchronized boolean saveIfNewer' 'Rule cache must synchronize version-aware writes.'
Assert-FileContains $ruleCache 'rule.ruleVersion <= cached.ruleVersion' 'Rule cache must reject equal or lower ruleVersion overwrites.'
Assert-FileContains $ruleRepository 'CACHE_FOREGROUND_WAIT_MILLIS = 2000' 'Rule repository must use a 2 second foreground wait when cache exists.'
Assert-FileContains $ruleRepository 'NO_CACHE_WAIT_MILLIS = 10000' 'Rule repository must wait up to 10 seconds when no cache exists.'
Assert-FileContains $ruleRepository 'RuleRequestCoordinator' 'Rule repository must coordinate concurrent remote rule requests.'
Assert-FileContains $ruleRepository 'startRequest' 'Rule repository must start concurrent per-candidate requests.'
Assert-FileContains $ruleRepository 'awaitRemoteLaunchResult' 'Rule repository must await a bounded remote launch decision.'
Assert-FileContains $ruleRepository 'markForegroundDecisionMade' 'Rule repository must mark cache/failure foreground decisions.'
Assert-FileContains $ruleRepository 'launchDecisionMade' 'Rule repository must prevent duplicate foreground decisions.'
Assert-FileContains $ruleRepository 'refreshCacheOnly' 'Late remote rules must refresh cache only after cache launch or failure.'
Assert-FileContains $ruleRepository 'cache.saveIfNewer' 'Rule repository must save remote rules through version-aware cache writes.'
```

Remove these old repository expectations from `tests/verify_project.ps1` because they describe the serial implementation:

```powershell
Assert-FileContains $ruleRepository 'remoteRule.ruleVersion < cached.ruleVersion' 'Rule repository must reject older remote rules.'
Assert-FileContains $ruleRepository 'cache.save(remoteRule)' 'Rule repository must save valid remote rules.'
```

Update manifest version checks:

```powershell
Assert-FileContains $voiceManifest 'android:versionCode="3"' 'Voice APK must declare versionCode 3.'
Assert-FileContains $voiceManifest 'android:versionName="1.1.1"' 'Voice APK must declare versionName 1.1.1.'
Assert-FileContains $videoManifest 'android:versionCode="3"' 'Video APK must declare versionCode 3.'
Assert-FileContains $videoManifest 'android:versionName="1.1.1"' 'Video APK must declare versionName 1.1.1.'
```

- [ ] **Step 4: Run tests and verify they fail**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: FAIL, because production code still requires `sslocal://`, lacks `Uri.parse`, lacks concurrent coordinator markers, and manifests still declare version `1.1.0`.

- [ ] **Step 5: Commit failing tests**

```powershell
git add tests/verify_rules.ps1 tests/verify_project.ps1
git commit -m "test: define concurrent rule refresh behavior"
```

---

### Task 2: Relax Parser to Structural URI Validation

**Files:**
- Modify: `common/src/com/simon/doubaolauncher/DoubaoRuleParser.java`

- [ ] **Step 1: Update imports**

Add:

```java
import android.net.Uri;
```

- [ ] **Step 2: Replace URI prefix validation**

Replace:

```java
private static final String REQUIRED_URI_PREFIX = "sslocal://";
```

with no replacement constant. Keep:

```java
private static final int SUPPORTED_SCHEMA_VERSION = 1;
private static final String ALLOWED_DOUBAO_PACKAGE = "com.larus.nova";
```

Replace `parseEntry` with:

```java
private CallEntry parseEntry(JSONObject object, String label) throws RuleValidationException {
    if (object == null) {
        throw new RuleValidationException(label + " entry is missing.");
    }

    String uriString = clean(object.optString("uri", ""));
    if (uriString.length() == 0) {
        throw new RuleValidationException(label + " uri is missing.");
    }

    Uri uri = Uri.parse(uriString);
    String scheme = clean(uri.getScheme());
    if (scheme.length() == 0) {
        throw new RuleValidationException(label + " uri scheme is missing.");
    }

    return new CallEntry(uriString);
}
```

- [ ] **Step 3: Run tests**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: still FAIL on concurrent repository/version checks, but parser checks pass.

- [ ] **Step 4: Commit parser change**

```powershell
git add common/src/com/simon/doubaolauncher/DoubaoRuleParser.java
git commit -m "refactor: relax rule URI validation"
```

---

### Task 3: Add Version-Aware Cache Writes

**Files:**
- Modify: `common/src/com/simon/doubaolauncher/RuleCache.java`

- [ ] **Step 1: Keep current `save` compatibility briefly**

Add a synchronized version-aware method while keeping `save` available until the repository is refactored. The `synchronized` keyword is required because concurrent late remote results can otherwise read the same old cache and commit out of order.

```java
synchronized boolean saveIfNewer(DoubaoRule rule) {
    if (rule == null || rule.sourceJson == null || rule.sourceJson.trim().length() == 0) {
        return false;
    }

    DoubaoRule cached = load();
    if (cached != null && rule.ruleVersion <= cached.ruleVersion) {
        Log.i(TAG, "Skip cache write because remote ruleVersion is not newer: remote="
                + rule.ruleVersion + ", cache=" + cached.ruleVersion);
        return false;
    }

    boolean saved = preferences.edit().putString(KEY_RULE_JSON, rule.sourceJson).commit();
    if (!saved) {
        Log.w(TAG, "Failed to commit rule cache.");
    }
    return saved;
}
```

Change existing `save` to delegate to the safer method:

```java
boolean save(DoubaoRule rule) {
    return saveIfNewer(rule);
}
```

- [ ] **Step 2: Run tests**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: still FAIL on concurrent repository/version checks, but cache marker checks pass.

- [ ] **Step 3: Commit cache change**

```powershell
git add common/src/com/simon/doubaolauncher/RuleCache.java
git commit -m "feat: prevent stale rule cache overwrites"
```

---

### Task 4: Refactor Rule Repository to Concurrent Requests

**Files:**
- Modify: `common/src/com/simon/doubaolauncher/RuleRepository.java`
- Modify: `common/src/com/simon/doubaolauncher/RuleFetchResult.java`

- [ ] **Step 1: Extend `RuleFetchResult`**

Ensure `RuleFetchResult` can distinguish foreground source and failure:

```java
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

- [ ] **Step 2: Add repository constants**

In `RuleRepository.java`, set:

```java
private static final int CONNECT_TIMEOUT_MILLIS = 2000;
private static final int READ_TIMEOUT_MILLIS = 2000;
private static final long CACHE_FOREGROUND_WAIT_MILLIS = 2000L;
private static final long NO_CACHE_WAIT_MILLIS = 10000L;
```

- [ ] **Step 3: Replace `loadRule` with coordinator-backed logic**

Replace `loadRule()` with:

```java
RuleFetchResult loadRule() {
    DoubaoRule cached = cache.load();
    List<String> candidates = RuleUrlCandidates.build(RAW_RULE_URL, System.currentTimeMillis());
    RuleRequestCoordinator coordinator = new RuleRequestCoordinator(cached, candidates.size());

    if (candidates.size() == 0) {
        if (cached != null) {
            coordinator.markForegroundDecisionMade();
            return RuleFetchResult.fromCache(cached);
        }
        coordinator.markForegroundDecisionMade();
        return RuleFetchResult.failure(RULE_LOAD_FAILED_MESSAGE);
    }

    for (String candidate : candidates) {
        startRequest(candidate, coordinator);
    }

    if (cached != null) {
        RuleFetchResult remoteWithinCacheWindow = coordinator.awaitRemoteLaunchResult(CACHE_FOREGROUND_WAIT_MILLIS);
        if (remoteWithinCacheWindow != null) {
            return remoteWithinCacheWindow;
        }
        coordinator.markForegroundDecisionMade();
        return RuleFetchResult.fromCache(cached);
    }

    RuleFetchResult noCacheResult = coordinator.awaitRemoteLaunchResult(NO_CACHE_WAIT_MILLIS);
    if (noCacheResult != null) {
        return noCacheResult;
    }

    coordinator.markForegroundDecisionMade();
    return RuleFetchResult.failure(RULE_LOAD_FAILED_MESSAGE);
}
```

This shape must not wait twice in the no-cache path. `awaitRemoteLaunchResult(NO_CACHE_WAIT_MILLIS)` is the entire no-cache foreground wait.

- [ ] **Step 4: Add request starter**

Add:

```java
private void startRequest(final String candidate, final RuleRequestCoordinator coordinator) {
    new Thread(new Runnable() {
        @Override
        public void run() {
            try {
                String json = fetch(candidate);
                DoubaoRule remoteRule = parser.parse(json);
                coordinator.acceptRemote(remoteRule);
            } catch (IOException ex) {
                Log.w(TAG, "Failed to fetch rule from " + candidate, ex);
                coordinator.recordFailure();
            } catch (RuleValidationException ex) {
                Log.w(TAG, "Remote rule invalid from " + candidate, ex);
                coordinator.recordFailure();
            } catch (RuntimeException ex) {
                Log.w(TAG, "Unexpected rule fetch failure from " + candidate, ex);
                coordinator.recordFailure();
            }
        }
    }, "doubao-rule-fetcher").start();
}
```

- [ ] **Step 5: Add coordinator inner class**

Add this inner class inside `RuleRepository`:

```java
private final class RuleRequestCoordinator {
    private final DoubaoRule initialCache;
    private final int totalRequests;
    private int completedRequests;
    private DoubaoRule bestRemote;
    private boolean launchDecisionMade;

    RuleRequestCoordinator(DoubaoRule initialCache, int totalRequests) {
        this.initialCache = initialCache;
        this.totalRequests = totalRequests;
    }

    synchronized void acceptRemote(DoubaoRule remoteRule) {
        completedRequests++;
        if (remoteRule != null) {
            if (initialCache == null) {
                if (bestRemote == null || remoteRule.ruleVersion > bestRemote.ruleVersion) {
                    bestRemote = remoteRule;
                }
            } else if (remoteRule.ruleVersion > initialCache.ruleVersion) {
                if (bestRemote == null || remoteRule.ruleVersion > bestRemote.ruleVersion) {
                    bestRemote = remoteRule;
                }
            }

            if (launchDecisionMade) {
                refreshCacheOnly(remoteRule);
            }
        }
        notifyAll();
    }

    synchronized void recordFailure() {
        completedRequests++;
        notifyAll();
    }

    synchronized RuleFetchResult awaitRemoteLaunchResult(long waitMillis) {
        long deadline = System.currentTimeMillis() + waitMillis;
        while (bestRemote == null && completedRequests < totalRequests) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                break;
            }
            waitQuietly(remaining);
        }

        if (bestRemote != null) {
            DoubaoRule selected = bestRemote;
            launchDecisionMade = true;
            cache.saveIfNewer(selected);
            return RuleFetchResult.fromRemote(selected);
        }
        return null;
    }

    synchronized void markForegroundDecisionMade() {
        launchDecisionMade = true;
        if (bestRemote != null) {
            refreshCacheOnly(bestRemote);
        }
    }

    private void refreshCacheOnly(DoubaoRule remoteRule) {
        cache.saveIfNewer(remoteRule);
    }

    private void waitQuietly(long millis) {
        try {
            wait(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
```

Do not access `launchDecisionMade` directly outside synchronized methods. Use `markForegroundDecisionMade()` whenever the foreground decision becomes cache launch or failure. `markForegroundDecisionMade()` must refresh cache with any `bestRemote` that arrived between the foreground wait timeout and the cache/failure decision. Late remote rules may call `refreshCacheOnly(remoteRule)` through `acceptRemote`, but must never trigger a second `RuleFetchResult.fromRemote` after cache launch/failure.

- [ ] **Step 6: Recheck no-cache and cache wait math**

After coding, inspect `loadRule()`. The no-cache path must not accidentally wait 10 seconds twice. The cache path must not wait longer than 2 seconds before returning cache. Acceptable behavior:

```text
With cache:
  awaitRemoteLaunchResult(2000)
  if newer remote arrives: return remote
  if none arrives: markForegroundDecisionMade and return cache

No cache:
  awaitRemoteLaunchResult(10000)
  if remote arrives: return remote
  if none arrives: failure
```

If `awaitRemoteLaunchResult(10000)` already waited the full no-cache period, do not call another 10 second wait after it. Adjust `loadRule()` so the total no-cache foreground wait is at most 10 seconds.

- [ ] **Step 7: Run tests**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: repository markers pass except any manifest/README version documentation still pending.

- [ ] **Step 8: Commit repository refactor**

```powershell
git add common/src/com/simon/doubaolauncher/RuleRepository.java common/src/com/simon/doubaolauncher/RuleFetchResult.java
git commit -m "feat: fetch remote rules concurrently"
```

---

### Task 5: Preserve Activity One-Shot Launch Semantics

**Files:**
- Modify: `common/src/com/simon/doubaolauncher/CallLauncherActivity.java`

- [ ] **Step 1: Construct repository with application context**

In `launchDoubaoCall`, replace:

```java
final RuleRepository repository = new RuleRepository(this);
```

with:

```java
final RuleRepository repository = new RuleRepository(getApplicationContext());
```

- [ ] **Step 2: Keep Activity-destroy guard before foreground handling**

Verify the existing `runOnUiThread` callback still contains:

```java
if (activityDestroyed) {
    return;
}
handleRuleResult(mode, result);
```

If missing, add it exactly before `handleRuleResult`.

- [ ] **Step 3: Run tests**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: no Activity lifecycle structural regression.

- [ ] **Step 4: Commit Activity adjustment**

```powershell
git add common/src/com/simon/doubaolauncher/CallLauncherActivity.java
git commit -m "refactor: isolate rule loading from activity context"
```

---

### Task 6: Version Bump and Documentation

**Files:**
- Modify: `apps/voice/AndroidManifest.xml`
- Modify: `apps/video/AndroidManifest.xml`
- Modify: `README.md`
- Modify: `tests/smoke_adb.ps1` if it checks version strings

- [ ] **Step 1: Bump both manifests**

Set both manifests to:

```xml
android:versionCode="3"
android:versionName="1.1.1"
```

- [ ] **Step 2: Update README remote rule behavior**

Replace or extend the `Remote Rule Hosting` section with these points:

```markdown
The app fetches rule URL candidates concurrently.

When a valid local cache exists, startup waits up to 2 seconds for a newer valid remote rule. If no newer remote rule arrives in that window, the app launches with the cached rule. Late remote results may update the cache for the next launch, but they never trigger a second Doubao launch in the current run.

When no valid local cache exists, startup waits up to 10 seconds for a valid remote rule. If all remote candidates fail or no valid rule arrives by then, the app shows/speaks the rule-load failure message and does not open Doubao.

Rule validation intentionally checks structure and the fixed Doubao package, not Doubao's internal URI parameters. The APK requires `schemaVersion`, a positive `ruleVersion`, `doubaoPackage` equal to `com.larus.nova`, a non-empty `doubaoActivity`, and non-empty voice/video URIs with a URI scheme.
```

- [ ] **Step 3: Run tests**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected:

```text
Rule verification passed.
Project verification passed.
```

- [ ] **Step 4: Commit docs and version bump**

```powershell
git add apps/voice/AndroidManifest.xml apps/video/AndroidManifest.xml README.md tests/smoke_adb.ps1
git commit -m "docs: document concurrent rule refresh"
```

---

### Task 7: Build, Inspect, and Optional Device Smoke Test

**Files:**
- No source edits expected.
- Generated: `dist/doubao-voice-call.apk`
- Generated: `dist/doubao-video-call.apk`

- [ ] **Step 1: Build both APKs**

Run:

```powershell
$env:DOUBAO_KEYSTORE='C:\Users\Simon\Desktop\GitHub\doubao-call-launchers\build\doubao-launchers-debug.keystore'
$env:DOUBAO_KEY_ALIAS='doubao'
$env:DOUBAO_KEYSTORE_PASS='android'
$env:DOUBAO_KEY_PASS='android'
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Expected:

```text
Built com.simon.doubao.voicecall -> ...\dist\doubao-voice-call.apk
Built com.simon.doubao.videocall -> ...\dist\doubao-video-call.apk
```

`apksigner verify` output must show v1/v2/v3 verification as true.

- [ ] **Step 2: Inspect APK metadata**

Run:

```powershell
$BuildTools = (Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$Aapt = Join-Path $BuildTools 'aapt.exe'
& $Aapt dump badging .\dist\doubao-voice-call.apk | Select-String -Pattern "package:|application-label:"
& $Aapt dump badging .\dist\doubao-video-call.apk | Select-String -Pattern "package:|application-label:"
```

Expected:

```text
com.simon.doubao.voicecall versionCode='3' versionName='1.1.1'
com.simon.doubao.videocall versionCode='3' versionName='1.1.1'
```

- [ ] **Step 3: Optional no-launch ADB smoke test**

If a device is connected and the user asks for install/smoke verification:

```powershell
adb devices -l
powershell -ExecutionPolicy Bypass -File .\tests\smoke_adb.ps1 -DeviceId 304b77ee
```

Expected: smoke test passes without launching either app.

- [ ] **Step 4: Commit any test-script adjustments**

Only if `tests/smoke_adb.ps1` or other source files were changed after Task 6:

```powershell
git status --short
git add tests/smoke_adb.ps1
git commit -m "test: update adb smoke expectations"
```

---

### Task 8: Release Checklist

**Files:**
- No source edits expected unless verification finds an issue.

- [ ] **Step 1: Confirm clean source state**

Run:

```powershell
git status --short --branch
```

Expected: source tree clean except ignored `build/` and `dist/` outputs.

- [ ] **Step 2: Push main branch**

Run:

```powershell
git push origin master
```

- [ ] **Step 3: Tag release**

Run:

```powershell
git tag -a v1.1.1 -m "Release v1.1.1"
git push origin v1.1.1
```

- [ ] **Step 4: Upload release APKs**

Run:

```powershell
Copy-Item .\dist\doubao-voice-call.apk .\dist\doubao-voice-call-v1.1.1.apk -Force
Copy-Item .\dist\doubao-video-call.apk .\dist\doubao-video-call-v1.1.1.apk -Force
gh release create v1.1.1 `
  .\dist\doubao-voice-call-v1.1.1.apk `
  .\dist\doubao-video-call-v1.1.1.apk `
  --title "v1.1.1" `
  --notes "Concurrent remote rule refresh, relaxed structural rule validation, 2 second cached startup fallback, and 10 second no-cache remote wait."
```

- [ ] **Step 5: Verify release assets**

Run:

```powershell
gh release view v1.1.1 --json tagName,name,url,isDraft,isPrerelease,assets --jq '{tagName,name,url,isDraft,isPrerelease,assets:[.assets[].name]}'
Get-FileHash -Algorithm SHA256 .\dist\doubao-voice-call-v1.1.1.apk, .\dist\doubao-video-call-v1.1.1.apk
```

Expected assets:

```text
doubao-voice-call-v1.1.1.apk
doubao-video-call-v1.1.1.apk
```

---

## Review Checklist Before Implementation

Use this checklist before starting code changes:

- The parser is intentionally structural and does not re-hardcode Doubao route details.
- `doubaoPackage == com.larus.nova` remains fixed.
- Concurrent remote requests cannot overwrite a newer cache with an equal or lower version.
- `saveIfNewer` synchronizes read-current-cache and commit as one critical section.
- Late remote results can refresh cache after cache launch, but cannot cause another foreground launch.
- A remote rule arriving between the 2 second timeout and cache launch is still saved by `markForegroundDecisionMade()`.
- Cached foreground wait is 2 seconds.
- No-cache foreground wait is 10 seconds total, not 10 seconds per URL and not two sequential 10 second waits.
- Activity destruction blocks UI/TTS/startActivity, while application-context cache refresh remains safe.
- Tests prove the new expectations before implementation.
- Version bump to `1.1.1` is included if APKs are released.

## Execution Prompt

Use the following prompt to execute this plan in a fresh development session:

```text
You are working in C:\Users\Simon\Desktop\GitHub\doubao-call-launchers on the Doubao call launcher project.

Implement docs/superpowers/plans/2026-06-28-concurrent-rule-refresh-plan.md exactly as a refactor, preserving the existing two-APK architecture and Android SDK CLI build.

Non-negotiable requirements:
- Keep fixed Doubao package validation: com.larus.nova.
- Relax URI validation to structure only: non-empty URI with non-empty scheme.
- Fetch all remote rule URL candidates concurrently.
- With valid cache, wait up to 2 seconds for a higher-version remote rule, otherwise launch with cache.
- Without valid cache, wait up to 10 seconds total for a valid remote rule before failure.
- Late remote results may update cache only and must never trigger a second Doubao launch.
- If a higher-version remote arrives after the 2 second foreground wait but before cache launch is marked, save it to cache only.
- Equal or lower ruleVersion must not overwrite cache.
- Bump APK version to versionCode 3 / versionName 1.1.1.

Follow TDD by updating tests before implementation. Run:
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
powershell -ExecutionPolicy Bypass -File .\build.ps1

Do not release until verification passes and APK metadata confirms versionCode 3 / versionName 1.1.1.
```
