package com.simon.doubaolauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;

/** Coordinates one bounded, serialized request to end a Doubao call. */
final class CallEndCoordinator {
    private static final String TAG = "DoubaoCallEndCoordinator";
    private static final String VOICE_PACKAGE = "com.simon.doubao.voicecall";
    private static final String ENABLED_LISTENERS_SETTING = "enabled_notification_listeners";
    private static final Object LOCK = new Object();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static DoubaoNotificationListenerService listener;
    private static EndRequest activeRequest;

    enum Result {
        SUCCESS,
        ALREADY_ENDED,
        UNAVAILABLE,
        NOT_FOUND,
        TIMEOUT,
        FAILED,
        CANCELLED
    }

    interface EndCallback {
        void onResult(Result result);
    }

    private CallEndCoordinator() {
    }

    static void onListenerConnected(DoubaoNotificationListenerService service) {
        synchronized (LOCK) {
            listener = service;
        }
        Log.i(TAG, "Doubao notification listener connected.");
    }

    static void onListenerDisconnected(DoubaoNotificationListenerService service) {
        synchronized (LOCK) {
            if (listener != service) {
                return;
            }
            listener = null;
        }
        Log.i(TAG, "Doubao notification listener disconnected.");
    }

    static boolean isListenerConnected() {
        synchronized (LOCK) {
            return listener != null;
        }
    }

    static DoubaoNotificationListenerService.CallSnapshot snapshot(String key) {
        DoubaoNotificationListenerService current = getListener();
        return current == null ? DoubaoNotificationListenerService.CallSnapshot.unknown() : current.snapshot(key);
    }

    static EndRequest requestEnd(Context context, String token, String key, EndCallback callback) {
        return createRequest(context, token, key, callback, true);
    }

    static EndRequest requestLocalEnd(Context context, String token, String key, EndCallback callback) {
        return createRequest(context, token, key, callback, false);
    }
    private static EndRequest createRequest(Context context, String token, String key,
            EndCallback callback, boolean allowBridge) {
        if (callback == null || context == null) throw new IllegalArgumentException("Context and callback required");
        final EndRequest request = new EndRequest(
                context.getApplicationContext(), token, key, callback, allowBridge);
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                if (request.completed) return;
                if (activeRequest != null && !activeRequest.completed) {
                    complete(request, Result.UNAVAILABLE);
                    return;
                }
                activeRequest = request;
                attemptLocalEnd(request);
            }
        });
        return request;
    }
    static void cancel(final EndRequest request) {
        if (request != null) MAIN_HANDLER.post(new Runnable() {
            @Override public void run() { complete(request, Result.CANCELLED); }
        });
    }

    private static void attemptLocalEnd(final EndRequest request) {
        if (request.completed || request.localAttemptInFlight) {
            return;
        }
        if (request.key == null || !SessionRegistry.isCurrent(request.context, request.token)) {
            complete(request, Result.CANCELLED);
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (!request.policy.shouldRetry(now)) {
            startBridgeOrFinish(request);
            return;
        }

        DoubaoNotificationListenerService current = getListener();
        if (current == null) {
            requestRebind(request.context);
            scheduleLocalRetry(request);
            return;
        }

        request.localAttemptInFlight = true;
        final int attemptNumber = ++request.localAttemptNumber;
        Log.i(TAG, "Notification listener available; requesting Doubao hangup.");
        current.requestHangup(request.token, request.key, new EndCallback() {
            @Override
            public void onResult(final Result result) {
                MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        handleLocalResult(request, attemptNumber, result);
                    }
                });
            }
        });
    }

    private static void handleLocalResult(EndRequest request, int attemptNumber, Result result) {
        if (request.completed || !request.localAttemptInFlight
                || attemptNumber != request.localAttemptNumber) {
            return;
        }
        request.localAttemptInFlight = false;
        if (result == Result.SUCCESS || result == Result.ALREADY_ENDED
                || result == Result.TIMEOUT) {
            complete(request, result);
            return;
        }

        Log.w(TAG, "Local Doubao hangup attempt returned " + result + "; will retry if possible.");
        if (request.policy.shouldRetry(SystemClock.elapsedRealtime())) {
            scheduleLocalRetry(request);
        } else {
            startBridgeOrFinish(request);
        }
    }

    private static void scheduleLocalRetry(final EndRequest request) {
        if (request.completed || request.retryRunnable != null) {
            return;
        }
        request.retryRunnable = new Runnable() {
            @Override
            public void run() {
                request.retryRunnable = null;
                if (!request.completed) {
                    attemptLocalEnd(request);
                }
            }
        };
        Log.d(TAG, "Waiting for Doubao notification listener; retrying end request.");
        MAIN_HANDLER.postDelayed(request.retryRunnable, EndRequestPolicy.RETRY_INTERVAL_MILLIS);
    }

    private static void startBridgeOrFinish(final EndRequest request) {
        if (request.completed || request.bridgeStarted) {
            return;
        }
        if (!SessionRegistry.isCurrent(request.context, request.token)) {
            complete(request, Result.CANCELLED);
            return;
        }
        if (request.allowBridge && request.context != null
                && !VOICE_PACKAGE.equals(request.context.getPackageName())) {
            request.bridgeStarted = true;
            Log.i(TAG, "Local listener retry window ended; requesting the voice bridge.");
            requestBridge(request);
            return;
        }
        complete(request, Result.UNAVAILABLE);
    }

    private static void requestBridge(final EndRequest request) {
        final ResultReceiver receiver = new ResultReceiver(MAIN_HANDLER) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                complete(request, resultFromCode(resultCode));
            }
        };
        Intent bridgeIntent = new Intent(CallEndBridgeService.ACTION_END);
        bridgeIntent.setComponent(new ComponentName(
                VOICE_PACKAGE, CallEndBridgeService.class.getName()));
        bridgeIntent.putExtra(
                CallEndBridgeService.EXTRA_CALLER_PACKAGE, request.context.getPackageName());

        request.bridgeConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (request.completed || request.bridgeMessageSent) {
                    return;
                }
                if (!SessionRegistry.isCurrent(request.context, request.token)) {
                    complete(request, Result.CANCELLED);
                    return;
                }
                request.bridgeMessageSent = true;
                Bundle data = new Bundle();
                data.putParcelable(CallEndBridgeService.EXTRA_RESULT_RECEIVER, receiver);
                data.putString(CallEndBridgeService.EXTRA_CALLER_PACKAGE, request.context.getPackageName());
                data.putString(CallEndBridgeService.EXTRA_SESSION_ID, request.token);
                data.putString(CallEndBridgeService.EXTRA_NOTIFICATION_KEY, request.key);
                Message message = Message.obtain(null, CallEndBridgeService.MESSAGE_END);
                message.setData(data);
                try {
                    new Messenger(binder).send(message);
                } catch (RemoteException | RuntimeException ex) {
                    Log.w(TAG, "Could not send the bound voice bridge request.", ex);
                    complete(request, Result.UNAVAILABLE);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                complete(request, Result.UNAVAILABLE);
            }

            @Override
            public void onNullBinding(ComponentName name) {
                complete(request, Result.UNAVAILABLE);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                complete(request, Result.UNAVAILABLE);
            }
        };

        request.bridgeTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                request.bridgeTimeoutRunnable = null;
                if (!request.completed) {
                    Log.w(TAG, "Voice bridge did not return an end result in time.");
                    complete(request, Result.UNAVAILABLE);
                }
            }
        };
        MAIN_HANDLER.postDelayed(request.bridgeTimeoutRunnable, EndRequestPolicy.BRIDGE_TIMEOUT_MILLIS);

        try {
            request.bridgeBound = request.context.bindService(
                    bridgeIntent, request.bridgeConnection, Context.BIND_AUTO_CREATE);
            if (!request.bridgeBound) {
                complete(request, Result.UNAVAILABLE);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not start the voice control bridge.", ex);
            complete(request, Result.UNAVAILABLE);
        }
    }

    private static void complete(EndRequest request, Result result) {
        if (request.completed || !request.policy.complete()) {
            return;
        }
        request.completed = true;
        if (activeRequest == request) activeRequest = null;
        DoubaoNotificationListenerService current = getListener();
        if (current != null && request.localAttemptInFlight) current.cancelHangup(request.token);
        if (request.retryRunnable != null) {
            MAIN_HANDLER.removeCallbacks(request.retryRunnable);
            request.retryRunnable = null;
        }
        if (request.bridgeTimeoutRunnable != null) {
            MAIN_HANDLER.removeCallbacks(request.bridgeTimeoutRunnable);
            request.bridgeTimeoutRunnable = null;
        }
        if (request.bridgeBound && request.bridgeConnection != null) {
            request.bridgeBound = false;
            try {
                request.context.unbindService(request.bridgeConnection);
            } catch (RuntimeException ex) {
                Log.w(TAG, "Could not unbind the voice control bridge.", ex);
            }
        }
        Log.i(TAG, "Doubao call-end result=" + result);
        try {
            request.callback.onResult(result);
        } catch (RuntimeException ex) {
            Log.w(TAG, "End result callback failed.", ex);
        }
    }

    private static DoubaoNotificationListenerService getListener() {
        synchronized (LOCK) {
            return listener;
        }
    }

    private static Result resultFromCode(int code) {
        Result[] results = Result.values();
        int index = code - 1;
        if (index < 0 || index >= results.length) {
            return Result.FAILED;
        }
        return results[index];
    }

    static boolean isNotificationListenerEnabled(Context context) {
        if (context == null) {
            return false;
        }
        String enabledListeners = Settings.Secure.getString(
                context.getContentResolver(), ENABLED_LISTENERS_SETTING);
        if (enabledListeners == null || enabledListeners.trim().length() == 0) {
            return false;
        }
        ComponentName expected = new ComponentName(
                context, DoubaoNotificationListenerService.class);
        String[] flattenedComponents = enabledListeners.split(":");
        for (String flattened : flattenedComponents) {
            ComponentName actual = ComponentName.unflattenFromString(flattened);
            if (expected.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    static void requestRebind(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        try {
            NotificationListenerService.requestRebind(new ComponentName(
                    context, DoubaoNotificationListenerService.class));
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not request notification listener rebind.", ex);
        }
    }

    static final class EndRequest {
        final Context context;
        final String token;
        final String key;
        final EndCallback callback;
        final EndRequestPolicy policy;
        final boolean allowBridge;
        boolean completed;
        boolean localAttemptInFlight;
        int localAttemptNumber;
        boolean bridgeStarted;
        boolean bridgeBound;
        boolean bridgeMessageSent;
        ServiceConnection bridgeConnection;
        Runnable retryRunnable;
        Runnable bridgeTimeoutRunnable;

        EndRequest(Context context, String token, String key, EndCallback callback, boolean allowBridge) {
            this.context = context;
            this.token = token;
            this.key = key;
            this.callback = callback;
            this.policy = new EndRequestPolicy(SystemClock.elapsedRealtime());
            this.allowBridge = allowBridge;
        }
    }
}
