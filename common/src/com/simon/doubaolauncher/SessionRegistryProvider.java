package com.simon.doubaolauncher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;

/** Signature-protected atomic registry. No call operation is executed here. */
public final class SessionRegistryProvider extends ContentProvider {
    private SharedPreferences store;
    @Override public boolean onCreate() { store = getContext().getSharedPreferences("call_session_owner", 0); return true; }
    @Override public synchronized Bundle call(String method, String token, Bundle extras) {
        String caller = trustedCaller();
        SessionLease lease = new SessionLease();
        lease.owner = store.getString("owner", null);
        lease.token = store.getString("token", null);
        lease.heartbeat = store.getLong("heartbeat", 0);
        long now = SystemClock.elapsedRealtime();
        boolean accepted;
        if ("acquire".equals(method)) accepted = lease.acquire(caller, token, now);
        else if ("renew".equals(method)) accepted = lease.renew(caller, token, now);
        else if ("release".equals(method)) accepted = lease.release(caller, token);
        else if ("check".equals(method)) accepted = lease.isCurrent(token, now);
        else throw new IllegalArgumentException("Unknown registry operation");
        if (accepted && !"check".equals(method)) {
            accepted = store.edit().putString("owner", lease.owner).putString("token", lease.token)
                    .putLong("heartbeat", lease.heartbeat).commit();
        }
        Bundle result = new Bundle();
        result.putInt("protocol", 1);
        result.putBoolean("accepted", accepted);
        return result;
    }
    private String trustedCaller() {
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages != null) for (String name : packages) {
            if (SessionRegistry.isLauncherPackage(name)
                    && getContext().getPackageManager().checkSignatures(getContext().getPackageName(), name)
                    == PackageManager.SIGNATURE_MATCH) return name;
        }
        throw new SecurityException("Untrusted session registry caller");
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
