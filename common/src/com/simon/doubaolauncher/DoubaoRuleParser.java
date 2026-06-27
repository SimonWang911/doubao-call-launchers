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
            return new DoubaoRule(
                    schemaVersion,
                    ruleVersion,
                    clean(root.optString("updatedAt", "")),
                    doubaoPackage,
                    doubaoActivity,
                    voice,
                    video,
                    cleanJson);
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
