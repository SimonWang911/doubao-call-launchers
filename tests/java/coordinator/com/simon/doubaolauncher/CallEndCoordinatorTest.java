package com.simon.doubaolauncher;
import android.content.Context;
import android.os.Handler;
import java.util.ArrayList;
import java.util.List;

/** Exercises the real coordinator with deterministic platform and notification gateways. */
public final class CallEndCoordinatorTest {
    private static DoubaoNotificationListenerService connected;
    private static int failures;
    public static void main(String[] args) {
        scenario("delayed connection", CallEndCoordinatorTest::testDelayedConnectionIsOneShot);
        scenario("deadline", CallEndCoordinatorTest::testDeadlineStopsRebinding);
        scenario("stale attempt", CallEndCoordinatorTest::testStaleAttemptCallbackIsIgnored);
        scenario("video local recovery", CallEndCoordinatorTest::testVideoUsesOwnRecoveredListener);
        scenario("video terminal deadline", CallEndCoordinatorTest::testVideoDeadlineNeverCallsCompanion);
        scenario("parallel requests", CallEndCoordinatorTest::testParallelRequestsCannotOperateTwice);
        scenario("cancel before connection", CallEndCoordinatorTest::testCancelledRequestNeverOperates);
        scenario("ownership changed", CallEndCoordinatorTest::testOwnershipChangeStopsRetry);
        scenario("old callback after new session", CallEndCoordinatorTest::testOldCallbackCannotCompleteNewRequest);
        if (failures != 0) { throw new AssertionError(failures + " coordinator scenarios failed"); }
        System.out.println("Real call-end coordinator tests passed (9 scenarios).");
    }
    private static void scenario(String name, Runnable test) {
        try { test.run(); System.out.println("PASS: " + name); }
        catch (AssertionError error) { failures++; System.out.println("FAIL: " + name + ": " + error.getMessage()); }
    }
    private static void reset() {
        if (connected != null) { CallEndCoordinator.onListenerDisconnected(connected); }
        connected = null;
        Handler.reset();
        SessionOwnership.current = "session";
    }
    private static void testParallelRequestsCannotOperateTwice() {
        reset(); connect();
        List<CallEndCoordinator.Result> first = new ArrayList<>();
        List<CallEndCoordinator.Result> second = new ArrayList<>();
        CallEndCoordinator.requestEnd(new Context(), "session", "call", first::add);
        CallEndCoordinator.requestEnd(new Context(), "session", "call", second::add);
        Handler.runCurrent();
        check(connected.attempts.size() == 1, "parallel end requests must not execute two hangup operations");
        check(connected.cancellations == 0, "rejected duplicate cannot cancel the first operation");
        connected.attempts.get(0).onResult(CallEndCoordinator.Result.SUCCESS);
        Handler.advanceTo(30000L);
    }
    private static void connect() {
        connected = new DoubaoNotificationListenerService();
        CallEndCoordinator.onListenerConnected(connected);
    }
    private static void testDelayedConnectionIsOneShot() {
        reset();
        List<CallEndCoordinator.Result> results = new ArrayList<>();
        CallEndCoordinator.requestEnd(new Context(), "session", "call", results::add);
        Handler.advanceTo(1599L);
        check(results.isEmpty(), "disconnected request must wait");
        connect();
        Handler.advanceTo(1600L);
        check(connected.attempts.size() == 1, "connected listener must get one attempt");
        connected.attempts.get(0).onResult(CallEndCoordinator.Result.SUCCESS);
        connected.attempts.get(0).onResult(CallEndCoordinator.Result.FAILED);
        Handler.advanceTo(20000L);
        check(results.size() == 1 && results.get(0) == CallEndCoordinator.Result.SUCCESS, "completion must be once");
        check(Handler.pendingTasks() == 0, "completed request must cancel timers");
    }
    private static void testDeadlineStopsRebinding() {
        reset();
        List<CallEndCoordinator.Result> results = new ArrayList<>();
        CallEndCoordinator.requestEnd(new Context(), "session", "call", results::add);
        Handler.advanceTo(8999L);
        check(results.isEmpty(), "must wait until retry deadline");
        Handler.advanceTo(9000L);
        connect();
        Handler.advanceTo(10000L);
        check(results.size() == 1 && results.get(0) == CallEndCoordinator.Result.UNAVAILABLE, "deadline returns unavailable once");
        check(connected.attempts.isEmpty(), "late connection must not execute expired request");
    }
    private static void testStaleAttemptCallbackIsIgnored() {
        reset(); connect();
        List<CallEndCoordinator.Result> results = new ArrayList<>();
        CallEndCoordinator.requestEnd(new Context(), "session", "call", results::add);
        Handler.runCurrent();
        CallEndCoordinator.EndCallback first = connected.attempts.get(0);
        first.onResult(CallEndCoordinator.Result.NOT_FOUND);
        Handler.advanceTo(1200L);
        check(connected.attempts.size() == 2, "recoverable result must retry");
        first.onResult(CallEndCoordinator.Result.TIMEOUT);
        Handler.runCurrent();
        check(results.isEmpty(), "stale previous-attempt callback must not complete current attempt");
        connected.attempts.get(1).onResult(CallEndCoordinator.Result.SUCCESS);
        Handler.runCurrent();
        check(results.size() == 1 && results.get(0) == CallEndCoordinator.Result.SUCCESS, "current attempt determines result");
    }
    private static final class VideoContext extends Context {
        @Override public String getPackageName() { return "com.simon.doubao.videocall"; }
    }
    private static void testVideoUsesOwnRecoveredListener() {
        reset(); List<CallEndCoordinator.Result> results = new ArrayList<>();
        CallEndCoordinator.requestEnd(new VideoContext(), "session", "call", results::add);
        Handler.advanceTo(1599L); connect(); Handler.advanceTo(1600L);
        check(connected.attempts.size() == 1, "video uses its own recovered listener");
        connected.attempts.get(0).onResult(CallEndCoordinator.Result.SUCCESS); Handler.runCurrent();
        check(results.size() == 1 && results.get(0) == CallEndCoordinator.Result.SUCCESS, "video local success");
    }
    private static void testVideoDeadlineNeverCallsCompanion() {
        reset(); List<CallEndCoordinator.Result> results = new ArrayList<>();
        CallEndCoordinator.requestEnd(new VideoContext(), "session", "call", results::add);
        Handler.advanceTo(9000L);
        check(results.size() == 1 && results.get(0) == CallEndCoordinator.Result.UNAVAILABLE, "video local deadline is terminal");
        connect(); Handler.advanceTo(30000L);
        check(connected.attempts.isEmpty() && Handler.pendingTasks() == 0, "no bridge or late hangup after deadline");
    }
    private static void testCancelledRequestNeverOperates() {
        reset(); List<CallEndCoordinator.Result> results = new ArrayList<>();
        CallEndCoordinator.EndRequest request = CallEndCoordinator.requestEnd(new Context(), "session", "call", results::add);
        Handler.runCurrent(); CallEndCoordinator.cancel(request); Handler.runCurrent();
        connect(); Handler.advanceTo(30000);
        check(connected.attempts.isEmpty(), "cancelled retry must never invoke hangup");
        check(results.size() == 1 && results.get(0) == CallEndCoordinator.Result.CANCELLED, "cancel completes once");
    }
    private static void testOwnershipChangeStopsRetry() {
        reset(); List<CallEndCoordinator.Result> results = new ArrayList<>();
        CallEndCoordinator.requestEnd(new Context(), "session", "call", results::add);
        Handler.runCurrent(); SessionOwnership.current = "new"; connect(); Handler.advanceTo(1200);
        check(connected.attempts.isEmpty(), "old token cannot invoke current call");
        check(results.size() == 1 && results.get(0) == CallEndCoordinator.Result.CANCELLED, "invalid ownership cancels");
    }
    private static void testOldCallbackCannotCompleteNewRequest() {
        reset(); connect(); List<CallEndCoordinator.Result> old = new ArrayList<>(), next = new ArrayList<>();
        CallEndCoordinator.EndRequest request = CallEndCoordinator.requestEnd(new Context(), "session", "call", old::add);
        Handler.runCurrent(); CallEndCoordinator.EndCallback late = connected.attempts.get(0);
        CallEndCoordinator.cancel(request); Handler.runCurrent(); SessionOwnership.current = "new";
        CallEndCoordinator.requestEnd(new Context(), "new", "call", next::add); Handler.runCurrent();
        late.onResult(CallEndCoordinator.Result.SUCCESS); Handler.runCurrent();
        check(next.isEmpty(), "old callback cannot complete new request");
        connected.attempts.get(1).onResult(CallEndCoordinator.Result.SUCCESS); Handler.runCurrent();
        check(next.size() == 1 && old.size() == 1, "each session completes once");
    }
    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
