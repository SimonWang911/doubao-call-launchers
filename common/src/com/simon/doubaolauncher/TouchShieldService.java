package com.simon.doubaolauncher;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Owns one app-local session, its overlay and its bounded call-end operation. */
public final class TouchShieldService extends Service {
    private static final String TAG = "DoubaoTouchShield";
    private static final String CHANNEL_ID = "doubao_call_touch_shield";
    private static final int NOTIFICATION_ID = 2401;
    private static final String EXTRA_TOKEN = "session_id", EXTRA_RECEIVER = "prepare_receiver", EXTRA_DISPATCH = "dispatch_time";
    private static final String ACTION_PREPARE = "com.simon.doubaolauncher.PREPARE_SESSION";
    private static final String ACTION_ARM = "com.simon.doubaolauncher.ARM_SESSION";
    private static final String ACTION_ABORT = "com.simon.doubaolauncher.ABORT_PREPARATION";
    private static final String ACTION_END = "com.simon.doubaolauncher.END_SESSION";
    public static final int RESULT_PREPARED = 3, RESULT_FAILED = 2;
    private final Handler handler = new Handler();
    private String token, callKey;
    private CallSession session;
    private ForegroundObserver observer;
    private ResultReceiver receiver;
    private CallEndCoordinator.EndRequest endRequest;
    private WindowManager windows;
    private FrameLayout shield;
    private boolean foreground, prepared, destroyed, receiverRegistered;
    private long prepareDeadline, nextRebind;
    private String lastInputs;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { tick(); }
    };
    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (destroyed || session == null) return;
            handler.removeCallbacks(this);
            long now = SystemClock.elapsedRealtime();
            if (!SessionOwnership.isCurrent(token)) { finishSession(true); return; }
            if (now >= nextRebind && !CallEndCoordinator.isListenerConnected()) {
                CallEndCoordinator.requestRebind(TouchShieldService.this); nextRebind = now + 2000;
            }
            DoubaoNotificationListenerService.CallSnapshot call = CallEndCoordinator.snapshot(callKey);
            if (session.state() == CallSession.State.PREPARING) {
                if (!screenAvailable() || now >= prepareDeadline) { finishSession(true); return; }
                if (!prepared && call.status == CallSession.CallStatus.CONTROLLABLE) { finishSession(true); return; }
                if (!prepared && call.status == CallSession.CallStatus.ABSENT) {
                    prepared = true; prepareDeadline = now + 3000;
                    sendPrepareResult(RESULT_PREPARED);
                }
            } else {
                if (call.status == CallSession.CallStatus.CONTROLLABLE && callKey == null) callKey = call.key;
                boolean permissions = Settings.canDrawOverlays(TouchShieldService.this)
                        && areNotificationsEnabled(TouchShieldService.this) && ForegroundObserver.hasPermission(TouchShieldService.this)
                        && CallEndCoordinator.isNotificationListenerEnabled(TouchShieldService.this);
                CallSession.Foreground currentForeground = observer.read();
                String inputs = currentForeground + "/" + call.status + "/" + permissions;
                if (!inputs.equals(lastInputs)) { lastInputs = inputs; Log.i(TAG, "Session inputs=" + inputs + " id=" + token); }
                CallSession.State previous = session.state();
                int effects = session.observe(currentForeground, call.status, screenAvailable(), permissions, now);
                if (previous == CallSession.State.WAITING && effects == CallSession.FINISH) {
                    Log.w(TAG, "Session readiness timed out id=" + token);
                    Toast.makeText(TouchShieldService.this, "通话保护未就绪，请在豆包内检查通话", Toast.LENGTH_LONG).show();
                }
                applyEffects(effects);
            }
            if (!destroyed && session != null) handler.postDelayed(this, 200);
        }
    };
    static Intent createPrepareIntent(Context context, ResultReceiver receiver, String token) {
        return command(context, ACTION_PREPARE, token).putExtra(EXTRA_RECEIVER, receiver);
    }
    static Intent createArmIntent(Context context, String token, long dispatchTime) {
        return command(context, ACTION_ARM, token).putExtra(EXTRA_DISPATCH, dispatchTime);
    }
    static Intent createAbortIntent(Context context, String token) { return command(context, ACTION_ABORT, token); }
    private static Intent command(Context context, String action, String token) {
        return new Intent(context, TouchShieldService.class).setAction(action).putExtra(EXTRA_TOKEN, token)
                .setData(Uri.parse("doubao-session:" + token));
    }
    public static boolean areNotificationsEnabled(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }
    @Override public void onCreate() {
        super.onCreate(); windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, filter);
        receiverRegistered = true;
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        String incoming = intent.getStringExtra(EXTRA_TOKEN), action = intent.getAction();
        if (ACTION_PREPARE.equals(action)) {
            if (session != null) return START_NOT_STICKY;
            if (!SessionOwnership.adopt(incoming)) { stopSelf(startId); return START_NOT_STICKY; }
            token = incoming;
            receiver = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver.class)
                    : intent.getParcelableExtra(EXTRA_RECEIVER);
            session = new CallSession(); prepareDeadline = SystemClock.elapsedRealtime() + 10000;
            try { startProtectionForeground(); tick(); }
            catch (RuntimeException error) { Log.e(TAG, "Could not prepare session", error); finishSession(true); }
        } else if (token != null && token.equals(incoming)) {
            if (ACTION_ARM.equals(action) && prepared && session.state() == CallSession.State.PREPARING) {
                long dispatchTime = intent.getLongExtra(EXTRA_DISPATCH, -1);
                if (dispatchTime <= 0) { finishSession(true); return START_NOT_STICKY; }
                observer = new ForegroundObserver(this, dispatchTime);
                session.dispatched(SystemClock.elapsedRealtime());
                Log.i(TAG, "Session dispatched id=" + token); tick();
            } else if (ACTION_ABORT.equals(action) && session.state() == CallSession.State.PREPARING) {
                finishSession(false);
            } else if (ACTION_END.equals(action)) {
                applyEffects(session.endRequested(CallEndCoordinator.snapshot(callKey).status));
            }
        } else if (session == null) stopSelf(startId);
        return START_NOT_STICKY;
    }
    private void tick() { handler.removeCallbacks(monitor); monitor.run(); }
    private boolean screenAvailable() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return power != null && power.isInteractive() && (keyguard == null || !keyguard.isKeyguardLocked());
    }
    private void applyEffects(int effects) {
        if ((effects & CallSession.HIDE) != 0) removeShield();
        if ((effects & CallSession.END) != 0) {
            final CallSession expected = session;
            final String expectedToken = token;
            endRequest = CallEndCoordinator.requestEnd(this, token, callKey, new CallEndCoordinator.EndCallback() {
                @Override public void onResult(CallEndCoordinator.Result result) {
                    if (session != expected || !expectedToken.equals(token)) return;
                    boolean failure = result != CallEndCoordinator.Result.SUCCESS && result != CallEndCoordinator.Result.ALREADY_ENDED;
                    Log.i(TAG, "Session end result=" + result + " id=" + token);
                    if (expected.endCompleted() != 0) finishSession(failure);
                }
            });
            updateNotification();
        }
        if ((effects & CallSession.SHOW) != 0) {
            try { showShield(); updateNotification(); }
            catch (RuntimeException error) { Log.e(TAG, "Could not display protection", error); applyEffects(session.endRequested(CallEndCoordinator.snapshot(callKey).status)); }
        }
        if ((effects & CallSession.FINISH) != 0) finishSession(false);
    }
    private void startProtectionForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "通话保护", NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID, notification());
        foreground = true;
    }
    private Notification notification() {
        PendingIntent end = PendingIntent.getService(this, NOTIFICATION_ID, command(this, ACTION_END, token), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        String text = session != null && session.state() == CallSession.State.ENDING ? "正在结束豆包通话"
                : shield == null ? "等待豆包通话进入前台" : "主页、返回退出或锁屏可结束通话";
        return builder.setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("AI通话保护").setContentText(text)
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).setContentIntent(end)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "结束通话", end).build()).build();
    }
    private void updateNotification() { if (foreground) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification()); }
    private void showShield() {
        if (shield != null) return;
        shield = new FrameLayout(this) {
            @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }
            @Override public boolean onTouchEvent(MotionEvent event) { return true; }
        };
        shield.setBackgroundColor(Color.argb(64, 255, 255, 255));
        TextView hint = new TextView(this); hint.setText("按主页键或锁屏结束通话"); hint.setTextSize(22); hint.setTextColor(Color.DKGRAY); hint.setGravity(Gravity.CENTER);
        shield.addView(hint, new FrameLayout.LayoutParams(-1, -1));
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(-1, -1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        layout.setTitle("DoubaoCallShield:" + getPackageName() + ":" + token);
        windows.addView(shield, layout);
        Log.i(TAG, "Shield shown id=" + token + " elapsed=" + SystemClock.elapsedRealtime());
    }
    private void removeShield() {
        if (shield != null) {
            try { windows.removeViewImmediate(shield); } catch (RuntimeException error) { Log.w(TAG, "Window already removed", error); }
            shield = null; Log.i(TAG, "Shield removed id=" + token);
        }
    }
    private void sendPrepareResult(int code) { if (receiver != null) { ResultReceiver pending = receiver; receiver = null; pending.send(code, null); } }
    private void finishSession(boolean failure) {
        if (session == null) return;
        boolean dispatched = observer != null;
        sendPrepareResult(RESULT_FAILED);
        cleanup(); stopSelf();
        if (failure) Toast.makeText(this, dispatched ? "保护已关闭，自动挂断未确认，请在豆包内检查" : "通话保护未启动，请检查权限与当前通话", Toast.LENGTH_LONG).show();
    }
    private void cleanup() {
        handler.removeCallbacksAndMessages(null); removeShield();
        CallEndCoordinator.cancel(endRequest); endRequest = null;
        SessionOwnership.release(token); token = null; session = null; observer = null;
        if (foreground) { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true); foreground = false; }
    }
    @Override public void onDestroy() {
        destroyed = true; sendPrepareResult(RESULT_FAILED); cleanup();
        if (receiverRegistered) unregisterReceiver(screenReceiver);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
