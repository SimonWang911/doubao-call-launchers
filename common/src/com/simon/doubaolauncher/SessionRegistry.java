package com.simon.doubaolauncher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/** Both installations always select the same authority; failed IPC never falls back. */
final class SessionRegistry {
    static final String VOICE = "com.simon.doubao.voicecall";
    static final String VIDEO = "com.simon.doubao.videocall";
    static boolean isLauncherPackage(String name) { return VOICE.equals(name) || VIDEO.equals(name); }
    static boolean acquire(Context context, String token) { return invoke(context, "acquire", token); }
    static boolean renew(Context context, String token) { return invoke(context, "renew", token); }
    static boolean isCurrent(Context context, String token) { return invoke(context, "check", token); }
    static void release(Context context, String token) { invoke(context, "release", token); }
    private static boolean invoke(Context context, String method, String token) {
        if (context == null || token == null) return false;
        try {
            String host = context.getPackageName();
            try {
                context.getPackageManager().getPackageInfo(VOICE, 0);
                host = VOICE;
            } catch (PackageManager.NameNotFoundException absent) { }
            if (context.getPackageManager().checkSignatures(context.getPackageName(), host) != PackageManager.SIGNATURE_MATCH) return false;
            Uri authority = Uri.parse("content://" + host + ".sessions");
            Bundle result = context.getContentResolver().call(authority, method, token, null);
            return result != null && result.getInt("protocol") == 1 && result.getBoolean("accepted");
        } catch (RuntimeException error) {
            Log.w("DoubaoSessionRegistry", "Session registry unavailable; refusing operation.", error);
            return false;
        }
    }
}
