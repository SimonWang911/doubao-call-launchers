package com.simon.doubaolauncher;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ResultReceiver;

/** Bound, signature-protected companion control. Started-service path is removed. */
public final class CallEndBridgeService extends Service {
    public static final String ACTION_END = "com.simon.doubao.voicecall.action.END_DOUBAO_CALL";
    public static final String EXTRA_RESULT_RECEIVER = "end_result_receiver";
    public static final String EXTRA_CALLER_PACKAGE = "caller_package";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_NOTIFICATION_KEY = "notification_key";
    static final int MESSAGE_END = 1;
    private CallEndCoordinator.EndRequest request;
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if (message.what != MESSAGE_END || !trustedUid(message.sendingUid)) return;
            Bundle data = message.getData();
            data.setClassLoader(ResultReceiver.class.getClassLoader());
            if (!SessionRegistry.VIDEO.equals(data.getString(EXTRA_CALLER_PACKAGE))) return;
            final ResultReceiver receiver = android.os.Build.VERSION.SDK_INT >= 33
                    ? data.getParcelable(EXTRA_RESULT_RECEIVER, ResultReceiver.class)
                    : data.getParcelable(EXTRA_RESULT_RECEIVER);
            String token = data.getString(EXTRA_SESSION_ID);
            String key = data.getString(EXTRA_NOTIFICATION_KEY);
            if (receiver == null || key == null || !SessionRegistry.isCurrent(CallEndBridgeService.this, token)) return;
            if (request != null && !request.completed) { receiver.send(CallEndCoordinator.Result.UNAVAILABLE.ordinal() + 1, null); return; }
            request = CallEndCoordinator.requestLocalEnd(CallEndBridgeService.this, token, key,
                    new CallEndCoordinator.EndCallback() {
                        @Override public void onResult(CallEndCoordinator.Result result) {
                            receiver.send(result.ordinal() + 1, null);
                        }
                    });
        }
    });
    private boolean trustedUid(int uid) {
        String[] packages = getPackageManager().getPackagesForUid(uid);
        if (packages != null) for (String name : packages) {
            if (SessionRegistry.VIDEO.equals(name)
                    && getPackageManager().checkSignatures(getPackageName(), name) == PackageManager.SIGNATURE_MATCH) return true;
        }
        return false;
    }
    @Override public IBinder onBind(Intent intent) {
        if (intent == null || !ACTION_END.equals(intent.getAction())
                || !SessionRegistry.VIDEO.equals(intent.getStringExtra(EXTRA_CALLER_PACKAGE))
                || getPackageManager().checkSignatures(getPackageName(), SessionRegistry.VIDEO) != PackageManager.SIGNATURE_MATCH) return null;
        return messenger.getBinder();
    }
    @Override public boolean onUnbind(Intent intent) { CallEndCoordinator.cancel(request); return false; }
    @Override public void onDestroy() { CallEndCoordinator.cancel(request); super.onDestroy(); }
}
