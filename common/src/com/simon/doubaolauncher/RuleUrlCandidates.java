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
