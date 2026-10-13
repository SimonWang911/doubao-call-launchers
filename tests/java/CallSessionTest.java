package com.simon.doubaolauncher;

public final class CallSessionTest {
    private static int checks;
    public static void main(String[] args) {
        readyHasNoFixedDelay(); waitingDoesNotCoverOrphanCall(); exitHidesBeforeEnd();
        screenOffIsImmediate(); earlyExitNeverShowsLateWindow(); naturalEndIsStable();
        unknownIsNotEnded(); transientAndRotationStayActive(); terminalNeverRestores();
        duplicateEndIsOneShot(); waitingDeadlineIsBounded(); lateStartupCallIsEnded();
        timeoutEndsDispatchedCall();
        revokedPermissionsStillEndLateCall(); revokedPermissionsStillReachDeadline();
        permissionLossHidesActiveCall(); invalidReadyNeverShows(); permissionRecoveryCannotRestore();
        System.out.println("Call session tests passed (" + checks + " checks).");
    }
    private static CallSession waiting() { CallSession s = new CallSession(); s.dispatched(1000); return s; }
    private static CallSession active() { CallSession s = waiting(); eq(CallSession.SHOW, observe(s, CallSession.Foreground.DOUBAO, CallSession.CallStatus.CONTROLLABLE, true, 1000), "ready shows immediately"); return s; }
    private static int observe(CallSession s, CallSession.Foreground f, CallSession.CallStatus c, boolean screen, long now) { return s.observe(f, c, screen, true, now); }
    private static void readyHasNoFixedDelay() { CallSession s = waiting(); eq(CallSession.SHOW, observe(s, CallSession.Foreground.DOUBAO, CallSession.CallStatus.CONTROLLABLE, true, 1001), "no old grace"); }
    private static void waitingDoesNotCoverOrphanCall() { CallSession s = waiting(); eq(0, observe(s, CallSession.Foreground.UNKNOWN, CallSession.CallStatus.CONTROLLABLE, true, 1000), "notification alone is insufficient"); eq(0, observe(s, CallSession.Foreground.DOUBAO, CallSession.CallStatus.ABSENT, true, 1001), "process alone is insufficient"); }
    private static void exitHidesBeforeEnd() { CallSession s = active(); eq(0, observe(s, CallSession.Foreground.OTHER, CallSession.CallStatus.CONTROLLABLE, true, 1100), "debounce exit"); eq(CallSession.HIDE | CallSession.END, observe(s, CallSession.Foreground.OTHER, CallSession.CallStatus.CONTROLLABLE, true, 1900), "hide and request end together"); eq(CallSession.FINISH, s.endCompleted(), "finish separately"); }
    private static void screenOffIsImmediate() { CallSession s = active(); eq(CallSession.HIDE | CallSession.END, observe(s, CallSession.Foreground.DOUBAO, CallSession.CallStatus.CONTROLLABLE, false, 1001), "screen off has no debounce"); }
    private static void earlyExitNeverShowsLateWindow() { CallSession s = waiting(); observe(s, CallSession.Foreground.OTHER, CallSession.CallStatus.ABSENT, true, 1001); eq(0, observe(s, CallSession.Foreground.OTHER, CallSession.CallStatus.ABSENT, true, 1801), "early withdrawal waits for late dispatch"); eq(CallSession.END, observe(s, CallSession.Foreground.DOUBAO, CallSession.CallStatus.CONTROLLABLE, true, 2200), "late call is ended without cover"); }
    private static void naturalEndIsStable() { CallSession s = active(); eq(0, observe(s, CallSession.Foreground.DOUBAO, CallSession.CallStatus.ABSENT, true, 1100), "notification removal may be transient"); eq(CallSession.HIDE | CallSession.FINISH, observe(s, CallSession.Foreground.DOUBAO, CallSession.CallStatus.ABSENT, true, 1700), "stable natural end removes cover"); }
    private static void unknownIsNotEnded() { CallSession s = active(); eq(0, observe(s, CallSession.Foreground.DOUBAO, CallSession.CallStatus.UNKNOWN, true, 2000), "query failure does not imply ended"); eq(0, observe(s, CallSession.Foreground.DOUBAO, CallSession.CallStatus.ABSENT, true, 2100), "start absence"); observe(s, CallSession.Foreground.DOUBAO, CallSession.CallStatus.UNKNOWN, true, 2200); eq(0, observe(s, CallSession.Foreground.DOUBAO, CallSession.CallStatus.ABSENT, true, 2700), "unknown resets absence confirmation"); }
    private static void transientAndRotationStayActive() { CallSession s = active(); eq(0, observe(s, CallSession.Foreground.TRANSIENT, CallSession.CallStatus.CONTROLLABLE, true, 9000), "system layer not exit"); eq(0, observe(s, CallSession.Foreground.UNKNOWN, CallSession.CallStatus.CONTROLLABLE, true, 9100), "internal activity replacement not exit"); }
    private static void terminalNeverRestores() { CallSession s = active(); s.endRequested(CallSession.CallStatus.CONTROLLABLE); s.endCompleted(); eq(0, observe(s, CallSession.Foreground.DOUBAO, CallSession.CallStatus.CONTROLLABLE, true, 5000), "return/wake/late callback cannot restore"); eq(0, s.endCompleted(), "late callback does not finish twice"); }
    private static void duplicateEndIsOneShot() { CallSession s = active(); eq(CallSession.HIDE | CallSession.END, s.endRequested(CallSession.CallStatus.CONTROLLABLE), "notification action ends both"); eq(0, s.endRequested(CallSession.CallStatus.CONTROLLABLE), "duplicate end has no second action"); eq(CallSession.FINISH, s.endCompleted(), "failure also finishes without cover"); }
    private static void waitingDeadlineIsBounded() { CallSession s = waiting(); eq(CallSession.FINISH, observe(s, CallSession.Foreground.DOUBAO, CallSession.CallStatus.UNKNOWN, true, 41000), "startup timeout bounded"); }
    private static void lateStartupCallIsEnded() { CallSession s = waiting(); eq(0, s.endRequested(CallSession.CallStatus.ABSENT), "wait for dispatched call"); eq(CallSession.END, observe(s, CallSession.Foreground.OTHER, CallSession.CallStatus.CONTROLLABLE, true, 1002), "late call belongs to withdrawn startup"); }
    private static void timeoutEndsDispatchedCall() { CallSession s = waiting(); eq(CallSession.END, observe(s, CallSession.Foreground.UNKNOWN, CallSession.CallStatus.CONTROLLABLE, true, 41000), "startup timeout cannot leave an owned controllable call"); }
    private static void revokedPermissionsStillEndLateCall() {
        for (CallSession.CallStatus initial : new CallSession.CallStatus[] { CallSession.CallStatus.ABSENT, CallSession.CallStatus.UNKNOWN }) {
            CallSession s = waiting();
            eq(0, s.observe(CallSession.Foreground.UNKNOWN, initial, true, false, 1001), "revoked waiting session does not show");
            state(CallSession.State.ENDING, s, "permission failure withdraws startup");
            eq(CallSession.END, s.observe(CallSession.Foreground.DOUBAO, CallSession.CallStatus.CONTROLLABLE, true, false, 1002), "late call ends even while permission remains revoked");
            eq(0, s.observe(CallSession.Foreground.DOUBAO, CallSession.CallStatus.CONTROLLABLE, false, false, 1003), "combined failures do not issue duplicate end");
            eq(CallSession.FINISH, s.endCompleted(), "end result finishes withdrawn session");
            eq(0, s.observe(CallSession.Foreground.DOUBAO, CallSession.CallStatus.CONTROLLABLE, true, true, 1004), "restoration cannot revive finished session");
        }
    }
    private static void revokedPermissionsStillReachDeadline() {
        for (CallSession.CallStatus missing : new CallSession.CallStatus[] { CallSession.CallStatus.ABSENT, CallSession.CallStatus.UNKNOWN }) {
            CallSession s = waiting();
            eq(0, s.observe(CallSession.Foreground.UNKNOWN, missing, true, false, 1001), "withdraw without call");
            eq(0, s.observe(CallSession.Foreground.UNKNOWN, missing, false, false, 40999), "withdrawn startup remains bounded until original deadline");
            eq(CallSession.FINISH, s.observe(CallSession.Foreground.UNKNOWN, missing, false, false, 41000), "revoked session must finish at startup deadline");
            state(CallSession.State.FINISHED, s, "deadline reaches terminal state");
            eq(0, s.observe(CallSession.Foreground.DOUBAO, CallSession.CallStatus.CONTROLLABLE, true, true, 41001), "late input cannot restore timed out session");
        }
    }
    private static void permissionLossHidesActiveCall() {
        CallSession s = active();
        eq(CallSession.HIDE | CallSession.END, s.observe(CallSession.Foreground.DOUBAO, CallSession.CallStatus.UNKNOWN, true, false, 1001), "active permission failure hides before bounded hangup");
        eq(0, s.observe(CallSession.Foreground.DOUBAO, CallSession.CallStatus.CONTROLLABLE, true, true, 1002), "permission restoration during ending cannot restore cover");
        eq(CallSession.FINISH, s.endCompleted(), "failed or successful hangup still finishes without cover");
    }
    private static void invalidReadyNeverShows() {
        CallSession s = waiting();
        eq(CallSession.END, s.observe(CallSession.Foreground.DOUBAO, CallSession.CallStatus.CONTROLLABLE, true, false, 1001), "controllable call with revoked permission must end, never show");
        eq(0, s.observe(CallSession.Foreground.DOUBAO, CallSession.CallStatus.CONTROLLABLE, false, false, 1002), "screen and permission failure stay one shot");
    }
    private static void permissionRecoveryCannotRestore() {
        CallSession s = waiting();
        eq(0, s.observe(CallSession.Foreground.UNKNOWN, CallSession.CallStatus.ABSENT, true, false, 1001), "first revoke withdraws startup");
        eq(CallSession.END, s.observe(CallSession.Foreground.DOUBAO, CallSession.CallStatus.CONTROLLABLE, true, true, 1002), "restored permission cannot turn withdrawn late call into active call");
    }
    private static void state(CallSession.State expected, CallSession actual, String reason) { checks++; if (actual.state() != expected) throw new AssertionError(reason + ": " + actual.state()); }
    private static void eq(int expected, int actual, String reason) { checks++; if (expected != actual) throw new AssertionError(reason + ": " + actual); }
}
