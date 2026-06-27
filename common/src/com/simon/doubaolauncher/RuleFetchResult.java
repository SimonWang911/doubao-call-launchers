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
