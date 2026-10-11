package com.simon.doubaolauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Keeps a transparent, touch-consuming window above the video call. */
public class TouchShieldService extends Service {
    private static final String TAG = "DoubaoTouchShield";
    private static final String EXTRA_RESULT_RECEIVER =
            "com.simon.doubaolauncher.TOUCH_SHIELD_RESULT_RECEIVER";
    private static final String CHANNEL_ID = "doubao_video_touch_shield";
    private static final int NOTIFICATION_ID = 2401;

    public static final String ACTION_START = "com.simon.doubaolauncher.TOUCH_SHIELD_START";
    public static final String ACTION_STOP = "com.simon.doubaolauncher.TOUCH_SHIELD_STOP";
    public static final int RESULT_READY = 1;
    public static final int RESULT_FAILED = 2;

    private WindowManager windowManager;
    private ShieldView shieldView;
    private boolean foregroundStarted;

    public static Intent createStartIntent(Context context, ResultReceiver resultReceiver) {
        Intent intent = new Intent(context, TouchShieldService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_RESULT_RECEIVER, resultReceiver);
        return intent;
    }

    public static Intent createStopIntent(Context context) {
        Intent intent = new Intent(context, TouchShieldService.class);
        intent.setAction(ACTION_STOP);
        return intent;
    }

    public static boolean areNotificationsEnabled(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true;
        }
        Object notificationService = context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (!(notificationService instanceof NotificationManager)) {
            return false;
        }
        NotificationManager manager = (NotificationManager) notificationService;
        if (!manager.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
            return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopShield();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        ResultReceiver resultReceiver = readResultReceiver(intent);
        try {
            ensureForeground();
            ensureTouchWindow();
            sendResult(resultReceiver, RESULT_READY);
        } catch (RuntimeException ex) {
            Log.e(TAG, "Could not start the touch shield.", ex);
            sendResult(resultReceiver, RESULT_FAILED);
            stopShield();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopShield();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureForeground() {
        if (foregroundStarted) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            throw new IllegalStateException("Overlay permission is not granted.");
        }
        if (!areNotificationsEnabled(this)) {
            throw new IllegalStateException("Notification permission is not granted.");
        }

        createNotificationChannel();
        PendingIntent stopIntent = PendingIntent.getService(
                this,
                NOTIFICATION_ID,
                createStopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("AI\u89c6\u9891\u906e\u7f69")
                .setContentText("\u89c6\u9891\u901a\u8bdd\u89e6\u6478\u906e\u7f69\u5df2\u5f00\u542f")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(stopIntent)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "\u89e3\u9664\u906e\u7f69",
                        stopIntent).build())
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("Notification manager is unavailable.");
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "AI\u89c6\u9891\u906e\u7f69",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("\u89c6\u9891\u901a\u8bdd\u89e6\u6478\u906e\u7f69\u63a7\u5236");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void ensureTouchWindow() {
        if (shieldView != null) {
            return;
        }
        if (windowManager == null) {
            throw new IllegalStateException("Window manager is unavailable.");
        }

        int windowType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                windowFlags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        ShieldView view = new ShieldView(this);
        windowManager.addView(view, params);
        shieldView = view;
    }

    private void stopShield() {
        if (shieldView != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(shieldView);
            } catch (RuntimeException ex) {
                Log.w(TAG, "Touch shield window was already removed.", ex);
            }
            shieldView = null;
        }

        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            foregroundStarted = false;
        }
    }

    private ResultReceiver readResultReceiver(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class);
        }
        return intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);
    }

    private void sendResult(ResultReceiver resultReceiver, int resultCode) {
        if (resultReceiver == null) {
            return;
        }
        try {
            resultReceiver.send(resultCode, null);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not send shield result.", ex);
        }
    }

    private static final class ShieldView extends View {
        ShieldView(Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
            setClickable(true);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return true;
        }
    }
}
