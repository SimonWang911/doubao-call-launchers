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
        uri = str(entry.get("uri", "")).strip()
        if not uri:
            raise AssertionError(f"{mode} uri is required")
        if ":" not in uri or uri.startswith(":"):
            raise AssertionError(f"{mode} uri scheme is required")
    return True

def candidate_urls(raw, now):
    return [
        f"https://gh-proxy.com/{raw}?t={now}",
        f"{raw}?t={now}",
        f"https://wget.la/{raw}?t={now}",
        f"https://ghfast.top/{raw}?t={now}",
    ]

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

validate_rule(rule)

bad_package = copy.deepcopy(rule)
bad_package["doubaoPackage"] = "bad.package"
try:
    validate_rule(bad_package)
    raise AssertionError("bad package should fail")
except AssertionError as exc:
    if "doubaoPackage" not in str(exc):
        raise

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

source, selected = foreground_choice(cache_v3, [remote_v4, remote_v5])
assert source == "remote"
assert selected["ruleVersion"] == 4

assert late_remote_refreshes_cache(remote_v5, remote_v4) is True
assert late_remote_refreshes_cache(remote_v3, remote_v4) is False

source, selected = foreground_choice(None, [remote_v2])
assert source == "remote"
assert selected["ruleVersion"] == 2

source, selected = foreground_choice(None, [])
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
