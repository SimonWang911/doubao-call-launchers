package com.simon.doubaolauncher;

/** One shared owner; the provider serializes and persists these transitions. */
final class SessionLease {
    static final long LEASE_MILLIS = 6000;
    String owner, token;
    long heartbeat;
    boolean acquire(String nextOwner, String nextToken, long now) {
        if (nextOwner == null || nextToken == null || nextToken.length() == 0) return false;
        if (token != null && isCurrent(token, now)) return false;
        owner = nextOwner; token = nextToken; heartbeat = now; return true;
    }
    boolean isCurrent(String expected, long now) {
        return expected != null && expected.equals(token) && now >= heartbeat && now - heartbeat < LEASE_MILLIS;
    }
    boolean renew(String caller, String expected, long now) {
        if (!caller.equals(owner) || !isCurrent(expected, now)) return false;
        heartbeat = now; return true;
    }
    boolean release(String caller, String expected) {
        if (expected == null || !expected.equals(token) || !caller.equals(owner)) return false;
        owner = null; token = null; heartbeat = 0; return true;
    }
}
