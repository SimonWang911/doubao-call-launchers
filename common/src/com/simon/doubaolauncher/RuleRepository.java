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
    private static final int CONNECT_TIMEOUT_MILLIS = 2000;
    private static final int READ_TIMEOUT_MILLIS = 2000;
    private static final long CACHE_FOREGROUND_WAIT_MILLIS = 2000L;
    private static final long NO_CACHE_WAIT_MILLIS = 10000L;
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
        RuleRequestCoordinator coordinator = new RuleRequestCoordinator(cached, candidates.size());

        if (candidates.size() == 0) {
            coordinator.markForegroundDecisionMade();
            if (cached != null) {
                return RuleFetchResult.fromCache(cached);
            }
            return RuleFetchResult.failure(RULE_LOAD_FAILED_MESSAGE);
        }

        for (String candidate : candidates) {
            startRequest(candidate, coordinator);
        }

        if (cached != null) {
            RuleFetchResult remoteWithinCacheWindow =
                    coordinator.awaitRemoteLaunchResult(CACHE_FOREGROUND_WAIT_MILLIS);
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

    private void startRequest(final String candidate, final RuleRequestCoordinator coordinator) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                fetchCandidate(candidate, coordinator);
            }
        }, "doubao-rule-fetcher").start();
    }

    private void fetchCandidate(String candidate, RuleRequestCoordinator coordinator) {
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

    private final class RuleRequestCoordinator {
        private final DoubaoRule initialCache;
        private final int totalRequests;
        private int completedRequests;
        private DoubaoRule foregroundRemote;
        private DoubaoRule bestRemote;
        private boolean launchDecisionMade;

        RuleRequestCoordinator(DoubaoRule initialCache, int totalRequests) {
            this.initialCache = initialCache;
            this.totalRequests = totalRequests;
        }

        synchronized void acceptRemote(DoubaoRule remoteRule) {
            completedRequests++;
            if (remoteRule != null && isUsableForForeground(remoteRule)) {
                if (foregroundRemote == null) {
                    foregroundRemote = remoteRule;
                }
                if (bestRemote == null || remoteRule.ruleVersion > bestRemote.ruleVersion) {
                    bestRemote = remoteRule;
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
            while (foregroundRemote == null && completedRequests < totalRequests) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                if (!waitQuietly(remaining)) {
                    break;
                }
            }

            if (foregroundRemote != null) {
                DoubaoRule selected = foregroundRemote;
                launchDecisionMade = true;
                cache.saveIfNewer(selected);
                if (bestRemote != null && bestRemote != selected) {
                    cache.saveIfNewer(bestRemote);
                }
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

        private boolean isUsableForForeground(DoubaoRule remoteRule) {
            return initialCache == null || remoteRule.ruleVersion > initialCache.ruleVersion;
        }

        private void refreshCacheOnly(DoubaoRule remoteRule) {
            cache.saveIfNewer(remoteRule);
        }

        private boolean waitQuietly(long millis) {
            try {
                wait(millis);
                return true;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
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
