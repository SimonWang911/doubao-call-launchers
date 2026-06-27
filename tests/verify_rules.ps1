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
