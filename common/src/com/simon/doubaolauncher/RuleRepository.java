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
    private static final String RAW_RULE_URL =
            "https://raw.githubusercontent.com/SimonWang911/doubao-call-launchers/master/rules/doubao-call-rules.json";
    private static final int CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int READ_TIMEOUT_MILLIS = 3000;
    private static final String RULE_LOAD_FAILED_MESSAGE =
            "\u89c4\u5219\u52a0\u8f7d\u5931\u8d25\uff0c\u8bf7\u5bb6\u4eba\u68c0\u67e5\u7f51\u7edc\u6216\u89c4\u5219\u6587\u4ef6\u3002";

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
