package com.simon.doubaolauncher;
import android.content.Context;
final class SessionRegistry {
    static String current = "session";
    static boolean isCurrent(Context context, String token) { return current.equals(token); }
}
