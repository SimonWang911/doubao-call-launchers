package com.simon.doubaolauncher;

import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;
import java.io.IOException;

/** App-local ownership; the OS binding excludes concurrent launcher processes. */
final class SessionOwnership {
    enum Acquisition { ACQUIRED, BUSY, UNAVAILABLE }
    private static final String TAG = "DoubaoSessionOwnership";
    private static String currentToken;
    private static KernelCallLock currentLock;
    private static boolean serviceOwned;
    private static boolean releasePending;
    private SessionOwnership() { }

    static synchronized Acquisition acquire(String token) {
        if (token == null || token.length() == 0) return Acquisition.UNAVAILABLE;
        if (currentLock != null) {
            if (!releasePending) return Acquisition.BUSY;
            if (!closeReleasedLock()) return Acquisition.UNAVAILABLE;
        }
        try {
            currentLock = KernelCallLock.acquire();
            currentToken = token;
            serviceOwned = false;
            return Acquisition.ACQUIRED;
        } catch (ErrnoException error) {
            if (error.errno == OsConstants.EADDRINUSE) return Acquisition.BUSY;
            Log.w(TAG, "Could not acquire call control", error);
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Could not acquire call control", error);
        }
        return Acquisition.UNAVAILABLE;
    }
    static synchronized boolean isCurrent(String token) {
        return currentLock != null && !releasePending && token != null && token.equals(currentToken);
    }
    static synchronized boolean adopt(String token) {
        if (!isCurrent(token)) return false;
        serviceOwned = true;
        return true;
    }
    static synchronized boolean releaseUnclaimed(String token) {
        return !serviceOwned && release(token);
    }
    static synchronized boolean release(String token) {
        if (currentLock == null || token == null || !token.equals(currentToken)) return false;
        releasePending = true;
        return closeReleasedLock();
    }
    private static boolean closeReleasedLock() {
        try { currentLock.close(); }
        catch (IOException error) { Log.w(TAG, "Could not release call control", error); return false; }
        currentLock = null;
        currentToken = null;
        serviceOwned = false;
        releasePending = false;
        return true;
    }
}
