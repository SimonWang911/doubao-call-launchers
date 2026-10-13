package com.simon.doubaolauncher;
public final class SessionLeaseTest {
    public static void main(String[] args) {
        SessionLease l = new SessionLease();
        check(l.acquire("voice", "one", 1000), "first owner");
        check(!l.acquire("video", "two", 1001), "cross-APK mutual exclusion");
        check(!l.renew("video", "one", 1002), "other owner cannot renew");
        check(!l.release("voice", "two"), "old or foreign token cannot release");
        check(l.renew("voice", "one", 5000), "heartbeat");
        check(l.isCurrent("one", 10999), "full lease");
        check(!l.isCurrent("one", 11000), "lease deadline");
        check(l.acquire("video", "two", 11000), "expired lease may be reacquired");
        check(!l.isCurrent("one", 11001), "old action rejected");
        check(!l.release("voice", "one"), "old release cannot affect new owner");
        check(l.release("video", "two"), "current release");
        check(l.acquire("voice", "three", 12000), "new session");
        check(!l.isCurrent("three", 0), "reboot invalidates monotonic lease");
        System.out.println("Session lease tests passed (13 checks).");
    }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
