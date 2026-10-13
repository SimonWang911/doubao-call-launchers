package com.simon.doubaolauncher;

/** Pure session lifecycle. Effects are executed in HIDE, END, SHOW, FINISH order. */
final class CallSession {
    static final int SHOW = 1, HIDE = 2, END = 4, FINISH = 8;
    static final long START_TIMEOUT_MILLIS = 40000L;
    static final long EXIT_CONFIRM_MILLIS = 800L;
    static final long ABSENCE_CONFIRM_MILLIS = 600L;
    enum State { PREPARING, WAITING, ACTIVE, ENDING, FINISHED }
    enum Foreground { DOUBAO, OTHER, TRANSIENT, UNKNOWN }
    enum CallStatus { UNKNOWN, ABSENT, CONTROLLABLE }
    private State state = State.PREPARING;
    private long deadline, foreignSince = -1, absentSince = -1;
    private boolean endIssued;
    State state() { return state; }
    void dispatched(long now) {
        if (state != State.PREPARING) throw new IllegalStateException("Already dispatched");
        deadline = now + START_TIMEOUT_MILLIS;
        state = State.WAITING;
    }
    int observe(Foreground foreground, CallStatus call, boolean screenAvailable, boolean permissionsAvailable, long now) {
        if (state == State.PREPARING || state == State.FINISHED) return 0;
        if (state == State.ENDING) {
            if (!endIssued && call == CallStatus.CONTROLLABLE) { endIssued = true; return END; }
            if (!endIssued && now >= deadline) { state = State.FINISHED; return FINISH; }
            return 0;
        }
        if (!screenAvailable || !permissionsAvailable) return endRequested(call);
        if (foreground == Foreground.OTHER) {
            if (foreignSince < 0) foreignSince = now;
            if (now - foreignSince >= EXIT_CONFIRM_MILLIS) return endRequested(call);
        } else if (foreground == Foreground.DOUBAO) {
            foreignSince = -1;
        }
        if (state == State.WAITING) {
            if (now >= deadline) {
                if (call == CallStatus.CONTROLLABLE) return endRequested(call);
                state = State.FINISHED; return FINISH;
            }
            if (foreground == Foreground.DOUBAO && foreignSince < 0 && call == CallStatus.CONTROLLABLE) {
                state = State.ACTIVE; return SHOW;
            }
        } else if (call == CallStatus.ABSENT) {
            if (absentSince < 0) absentSince = now;
            if (now - absentSince >= ABSENCE_CONFIRM_MILLIS) { state = State.FINISHED; return HIDE | FINISH; }
        } else { absentSince = -1; }
        return 0;
    }
    int endRequested(CallStatus call) {
        if (state == State.ENDING || state == State.FINISHED) return 0;
        boolean wasActive = state == State.ACTIVE;
        int effects = wasActive ? HIDE : 0;
        if (state == State.PREPARING) { state = State.FINISHED; return FINISH; }
        state = State.ENDING;
        if (wasActive || call == CallStatus.CONTROLLABLE) { endIssued = true; effects |= END; }
        return effects;
    }
    int endCompleted() {
        if (state != State.ENDING) return 0;
        state = State.FINISHED; return FINISH;
    }
}
