package com.simon.doubaolauncher;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;

/** Coordinates one bounded, serialized request to end a Doubao call. */
final class CallEndCoordinator {
    private static final String TAG = "DoubaoCallEndCoordinator";
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
        if (callback == null || context == null) throw new IllegalArgumentException("Context and callback required");
        final EndRequest request = new EndRequest(context.getApplicationContext(), token, key, callback);
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
        if (request.key == null || !SessionOwnership.isCurrent(request.token)) {
            complete(request, Result.CANCELLED);
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (!request.policy.shouldRetry(now)) {
            complete(request, Result.UNAVAILABLE);
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
            complete(request, Result.UNAVAILABLE);
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
        boolean completed;
        boolean localAttemptInFlight;
        int localAttemptNumber;
        Runnable retryRunnable;

        EndRequest(Context context, String token, String key, EndCallback callback) {
            this.context = context;
            this.token = token;
            this.key = key;
            this.callback = callback;
            this.policy = new EndRequestPolicy(SystemClock.elapsedRealtime());
        }
    }
}
