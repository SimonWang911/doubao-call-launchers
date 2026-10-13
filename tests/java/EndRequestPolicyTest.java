package com.simon.doubaolauncher;

public final class EndRequestPolicyTest {
    public static void main(String[] args) {
        testRetryWindowAndBoundary();
        testCompletionIsOneShot();
        testNegativeStartRejected();
        System.out.println("End request policy tests passed.");
    }

    private static void testRetryWindowAndBoundary() {
        EndRequestPolicy policy = new EndRequestPolicy(1000L);
        assertTrue(policy.shouldRetry(1000L), "request must retry at its start");
        assertTrue(policy.shouldRetry(8999L), "request must retry before the deadline");
        assertFalse(policy.shouldRetry(9000L), "request must stop retrying at the deadline");
        assertTrue(policy.isExpired(9000L), "request must be expired at the deadline");
    }

    private static void testCompletionIsOneShot() {
        EndRequestPolicy policy = new EndRequestPolicy(1000L);
        assertTrue(policy.complete(), "first completion must succeed");
        assertFalse(policy.complete(), "second completion must be ignored");
        assertFalse(policy.shouldRetry(1001L), "completed request must not retry");
    }

    private static void testNegativeStartRejected() {
        try {
            new EndRequestPolicy(-1L);
            throw new AssertionError("negative start time must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }
}
