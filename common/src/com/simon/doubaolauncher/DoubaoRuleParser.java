package com.simon.doubaolauncher;

import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

final class DoubaoRuleParser {
    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final String ALLOWED_DOUBAO_PACKAGE = "com.larus.nova";

    DoubaoRule parse(String json) throws RuleValidationException {
        String cleanJson = clean(json);
        if (cleanJson.length() == 0) {
            throw new RuleValidationException("Rule JSON is empty.");
        }

        try {
            JSONObject root = new JSONObject(cleanJson);
            int schemaVersion = parseRequiredInteger(root, "schemaVersion");
            if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                throw new RuleValidationException("Unsupported schemaVersion: " + schemaVersion);
            }

            int ruleVersion = parseRequiredInteger(root, "ruleVersion");
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

    private int parseRequiredInteger(JSONObject object, String key) throws RuleValidationException {
        Object value = object.opt(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new RuleValidationException(key + " must be an integer.");
        }

        long parsed = ((Number) value).longValue();
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new RuleValidationException(key + " is out of integer range.");
        }
        return (int) parsed;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
