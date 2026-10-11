package com.simon.doubaolauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

final class RuleCache {
    private static final String TAG = "DoubaoRuleCache";
    private static final String PREFS_NAME = "doubao_rule_cache";
    private static final String KEY_RULE_JSON = "rule_json";
    private static final Object CACHE_WRITE_LOCK = new Object();

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

    boolean saveIfNewer(DoubaoRule rule) {
        if (rule == null || rule.sourceJson == null || rule.sourceJson.trim().length() == 0) {
            return false;
        }

        synchronized (CACHE_WRITE_LOCK) {
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
    }

    boolean save(DoubaoRule rule) {
        return saveIfNewer(rule);
    }
}
