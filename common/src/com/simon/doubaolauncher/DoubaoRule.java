package com.simon.doubaolauncher;

final class DoubaoRule {
    final int schemaVersion;
    final int ruleVersion;
    final String updatedAt;
    final String doubaoPackage;
    final String doubaoActivity;
    final CallEntry voice;
    final CallEntry video;
    final String sourceJson;

    DoubaoRule(
            int schemaVersion,
            int ruleVersion,
            String updatedAt,
            String doubaoPackage,
            String doubaoActivity,
            CallEntry voice,
            CallEntry video,
            String sourceJson) {
        this.schemaVersion = schemaVersion;
        this.ruleVersion = ruleVersion;
        this.updatedAt = updatedAt;
        this.doubaoPackage = doubaoPackage;
        this.doubaoActivity = doubaoActivity;
        this.voice = voice;
        this.video = video;
        this.sourceJson = sourceJson;
    }

    CallEntry entryForMode(String mode) {
        return "video".equals(mode) ? video : voice;
    }
}
