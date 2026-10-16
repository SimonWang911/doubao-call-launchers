package com.simon.doubaolauncher;
/** Coordinator boundary only; the real owner and kernel adapter have a separate executable suite. */
final class SessionOwnership {
    static String current = "session";
    static boolean isCurrent(String token) { return current.equals(token); }
}
