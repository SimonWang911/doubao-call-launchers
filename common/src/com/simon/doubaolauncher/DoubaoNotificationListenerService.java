package com.simon.doubaolauncher;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import java.util.Locale;

/** Uses the user-authorized Doubao call notification to request a hangup. */
public final class DoubaoNotificationListenerService extends NotificationListenerService {
    private static final String TAG = "DoubaoNotificationListener";
    private static final String DOUBAO_PACKAGE = "com.larus.nova";
    private static final String HANGUP_RESOURCE_NAME = "hangup_btn";
    private static final long HANGUP_CONFIRM_POLL_MILLIS = 200L;
    static final long HANGUP_CONFIRM_TIMEOUT_MILLIS = EndRequestPolicy.HANGUP_CONFIRM_TIMEOUT_MILLIS;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PendingHangup pendingHangup;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        CallEndCoordinator.onListenerConnected(this);
    }

    @Override
    public void onListenerDisconnected() {
        CallEndCoordinator.onListenerDisconnected(this);
        finishPending(CallEndCoordinator.Result.UNAVAILABLE);
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification statusBarNotification) {
        super.onNotificationRemoved(statusBarNotification);
        PendingHangup request = pendingHangup;
        if (request != null && statusBarNotification != null
                && request.notificationKey.equals(statusBarNotification.getKey())) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    confirmHangup();
                }
            });
        }
    }

    @Override
    public void onDestroy() {
        CallEndCoordinator.onListenerDisconnected(this);
        finishPending(CallEndCoordinator.Result.UNAVAILABLE);
        super.onDestroy();
    }

    static final class CallSnapshot {
        final CallSession.CallStatus status;
        final String key;
        CallSnapshot(CallSession.CallStatus status, String key) { this.status = status; this.key = key; }
        static CallSnapshot unknown() { return new CallSnapshot(CallSession.CallStatus.UNKNOWN, null); }
    }
    CallSnapshot snapshot(String expectedKey) {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return CallSnapshot.unknown();
            StatusBarNotification target = findHangupNotification(active);
            if (target != null) {
                if (expectedKey != null && !expectedKey.equals(target.getKey())) return CallSnapshot.unknown();
                return new CallSnapshot(CallSession.CallStatus.CONTROLLABLE, target.getKey());
            }
            for (StatusBarNotification notification : active) {
                if (notification != null && DOUBAO_PACKAGE.equals(notification.getPackageName())
                        && (expectedKey != null ? expectedKey.equals(notification.getKey()) : notification.getId() == 101)) return CallSnapshot.unknown();
            }
            return new CallSnapshot(CallSession.CallStatus.ABSENT, null);
        } catch (RuntimeException error) { return CallSnapshot.unknown(); }
    }
    void cancelHangup(String token) {
        if (pendingHangup != null && pendingHangup.token.equals(token)) pendingHangup = null;
    }
    void requestHangup(final String token, final String key, final CallEndCoordinator.EndCallback callback) {
        if (callback == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                beginHangup(token, key, callback);
            }
        });
    }

    private void beginHangup(String token, String key, CallEndCoordinator.EndCallback callback) {
        if (key == null || !SessionOwnership.isCurrent(token)) {
            notifyResult(callback, CallEndCoordinator.Result.CANCELLED);
            return;
        }
        if (pendingHangup != null) {
            notifyResult(callback, CallEndCoordinator.Result.UNAVAILABLE);
            return;
        }

        StatusBarNotification[] activeNotifications;
        try {
            activeNotifications = getActiveNotifications();
        } catch (RuntimeException ex) {
            android.util.Log.w(TAG, "Could not read active notifications.", ex);
            notifyResult(callback, CallEndCoordinator.Result.UNAVAILABLE);
            return;
        }

        if (activeNotifications == null) {
            notifyResult(callback, CallEndCoordinator.Result.UNAVAILABLE);
            return;
        }

        StatusBarNotification target = findHangupNotification(activeNotifications);
        if (target == null) {
            notifyResult(callback, snapshot(key).status == CallSession.CallStatus.ABSENT
                    ? CallEndCoordinator.Result.ALREADY_ENDED : CallEndCoordinator.Result.UNAVAILABLE);
            return;
        }
        if (!key.equals(target.getKey())) {
            notifyResult(callback, CallEndCoordinator.Result.CANCELLED);
            return;
        }

        try {
            if (!performHangup(token, target.getNotification())) {
                notifyResult(callback, CallEndCoordinator.Result.FAILED);
                return;
            }
        } catch (RuntimeException ex) {
            android.util.Log.w(TAG, "Could not invoke Doubao hangup control.", ex);
            notifyResult(callback, CallEndCoordinator.Result.FAILED);
            return;
        }

        pendingHangup = new PendingHangup(
                token, target.getKey(), callback,
                SystemClock.elapsedRealtime() + HANGUP_CONFIRM_TIMEOUT_MILLIS);
        scheduleConfirmation();
    }

    private StatusBarNotification findHangupNotification(StatusBarNotification[] activeNotifications) {
        StatusBarNotification fallback = null;
        for (StatusBarNotification notification : activeNotifications) {
            if (notification == null || !DOUBAO_PACKAGE.equals(notification.getPackageName())) {
                continue;
            }
            if (!hasHangupControl(notification.getNotification())) {
                continue;
            }
            if (notification.getId() == 101) {
                return notification;
            }
            if (fallback == null) {
                fallback = notification;
            }
        }
        return fallback;
    }

    private boolean hasHangupControl(Notification notification) {
        if (notification == null) {
            return false;
        }
        if (hasHangupAction(notification)) {
            return true;
        }
        return findHangupView(notification.contentView) != null
                || findHangupView(notification.bigContentView) != null
                || findHangupView(notification.headsUpContentView) != null;
    }

    private boolean performHangup(String token, Notification notification) {
        if (notification == null) {
            return false;
        }
        if (performHangupAction(token, notification)) {
            return true;
        }
        return performRemoteViewClick(token, notification.contentView)
                || performRemoteViewClick(token, notification.bigContentView)
                || performRemoteViewClick(token, notification.headsUpContentView);
    }

    private boolean hasHangupAction(Notification notification) {
        Notification.Action[] actions = notification.actions;
        if (actions == null) {
            return false;
        }
        for (Notification.Action action : actions) {
            if (action != null && isHangupTitle(action.title) && action.actionIntent != null) {
                return true;
            }
        }
        return false;
    }

    private boolean performHangupAction(String token, Notification notification) {
        Notification.Action[] actions = notification.actions;
        if (actions == null) {
            return false;
        }
        for (Notification.Action action : actions) {
            if (action == null || !isHangupTitle(action.title) || action.actionIntent == null) {
                continue;
            }
            try {
                if (!SessionOwnership.isCurrent(token)) return false;
                action.actionIntent.send();
                return true;
            } catch (PendingIntent.CanceledException ex) {
                android.util.Log.w(TAG, "Doubao hangup action was canceled.", ex);
                return false;
            }
        }
        return false;
    }

    private boolean isHangupTitle(CharSequence title) {
        if (title == null) {
            return false;
        }
        String normalized = title.toString().trim().toLowerCase(Locale.ROOT);
        return "\u6302\u65ad".equals(normalized)
                || "\u7ed3\u675f\u901a\u8bdd".equals(normalized)
                || "hang up".equals(normalized)
                || "end call".equals(normalized);
    }

    private boolean performRemoteViewClick(String token, RemoteViews remoteViews) {
        View hangupView = findHangupView(remoteViews);
        return hangupView != null && SessionOwnership.isCurrent(token) && hangupView.performClick();
    }

    private View findHangupView(RemoteViews remoteViews) {
        if (remoteViews == null) {
            return null;
        }
        try {
            Context doubaoContext = createPackageContext(DOUBAO_PACKAGE, 0);
            int hangupId = doubaoContext.getResources().getIdentifier(
                    HANGUP_RESOURCE_NAME, "id", DOUBAO_PACKAGE);
            if (hangupId == 0) {
                return null;
            }
            FrameLayout parent = new FrameLayout(this);
            View root = remoteViews.apply(doubaoContext, parent);
            View hangupView = root.findViewById(hangupId);
            return hangupView != null && hangupView.isEnabled() ? hangupView : null;
        } catch (Throwable ex) {
            android.util.Log.w(TAG, "Could not apply Doubao notification layout.", ex);
            return null;
        }
    }

    private void scheduleConfirmation() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                confirmHangup();
            }
        }, HANGUP_CONFIRM_POLL_MILLIS);
    }

    private void confirmHangup() {
        PendingHangup request = pendingHangup;
        if (request == null) {
            return;
        }
        if (!SessionOwnership.isCurrent(request.token)) {
            finishPending(CallEndCoordinator.Result.CANCELLED);
            return;
        }

        if (isHangupConfirmed(request.notificationKey)) {
            finishPending(CallEndCoordinator.Result.SUCCESS);
            return;
        }
        if (SystemClock.elapsedRealtime() >= request.deadlineMillis) {
            finishPending(CallEndCoordinator.Result.TIMEOUT);
            return;
        }
        scheduleConfirmation();
    }

    private boolean isHangupConfirmed(String notificationKey) {
        StatusBarNotification[] activeNotifications;
        try {
            activeNotifications = getActiveNotifications();
        } catch (RuntimeException ex) {
            return false;
        }
        if (activeNotifications == null) {
            return false;
        }
        for (StatusBarNotification notification : activeNotifications) {
            if (notification == null || !DOUBAO_PACKAGE.equals(notification.getPackageName())) {
                continue;
            }
            if (notificationKey.equals(notification.getKey())) {
                return false;
            }
            if (hasHangupControl(notification.getNotification())) {
                return false;
            }
        }
        return true;
    }

    private void finishPending(CallEndCoordinator.Result result) {
        PendingHangup request = pendingHangup;
        pendingHangup = null;
        if (request != null) {
            notifyResult(request.callback, result);
        }
    }

    private void notifyResult(CallEndCoordinator.EndCallback callback,
            CallEndCoordinator.Result result) {
        try {
            callback.onResult(result);
        } catch (RuntimeException ex) {
            android.util.Log.w(TAG, "End result callback failed.", ex);
        }
    }

    private static final class PendingHangup {
        final String token;
        final String notificationKey;
        final CallEndCoordinator.EndCallback callback;
        final long deadlineMillis;

        PendingHangup(String token, String notificationKey,
                CallEndCoordinator.EndCallback callback,
                long deadlineMillis) {
            this.token = token;
            this.notificationKey = notificationKey;
            this.callback = callback;
            this.deadlineMillis = deadlineMillis;
        }
    }
}
