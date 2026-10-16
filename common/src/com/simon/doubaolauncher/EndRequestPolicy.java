package com.simon.doubaolauncher;

/** Bounds one asynchronous end-call attempt and makes completion one-shot. */
final class EndRequestPolicy {
    static final long RETRY_INTERVAL_MILLIS = 200L;
    static final long RETRY_WINDOW_MILLIS = 8000L;
    static final long HANGUP_CONFIRM_TIMEOUT_MILLIS = 3000L;

    private final long deadlineMillis;
    private boolean completed;

    EndRequestPolicy(long startTimeMillis) {
        if (startTimeMillis < 0L) {
            throw new IllegalArgumentException("startTimeMillis must not be negative");
        }
        if (startTimeMillis > Long.MAX_VALUE - RETRY_WINDOW_MILLIS) {
            throw new IllegalArgumentException("startTimeMillis is too large");
        }
        deadlineMillis = startTimeMillis + RETRY_WINDOW_MILLIS;
    }

    boolean shouldRetry(long nowMillis) {
        return !completed && nowMillis < deadlineMillis;
    }

    boolean isExpired(long nowMillis) {
        return !completed && nowMillis >= deadlineMillis;
    }

    boolean complete() {
        if (completed) {
            return false;
        }
        completed = true;
        return true;
    }
}
