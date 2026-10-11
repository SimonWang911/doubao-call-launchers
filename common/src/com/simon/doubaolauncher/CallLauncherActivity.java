package com.simon.doubaolauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.Toast;

import java.util.Locale;

public class CallLauncherActivity extends Activity {
    private static final String TAG = "DoubaoCallLauncher";
    private static final String EXTRA_MODE = "com.simon.doubaolauncher.MODE";
    private static final String MODE_VIDEO = "video";
    private static final long SHORT_FINISH_DELAY_MILLIS = 1200L;
    private static final long MAX_FINISH_DELAY_MILLIS = 6000L;
    private static final long SHIELD_READY_TIMEOUT_MILLIS = 3000L;
    private static final String STATUS_UTTERANCE_ID = "doubao_call_launcher_status";

    private static final String OPENING_VOICE_MESSAGE = "\u6b63\u5728\u542f\u52a8\u8c46\u5305\u8bed\u97f3";
    private static final String OPENING_VIDEO_MESSAGE = "\u6b63\u5728\u542f\u52a8\u8c46\u5305\u89c6\u9891";
    private static final String DOUBAO_NOT_INSTALLED_MESSAGE =
            "\u672a\u5b89\u88c5\u8c46\u5305\uff0c\u8bf7\u5bb6\u4eba\u534f\u52a9\u5b89\u88c5\u3002";
    private static final String ENTRY_UNAVAILABLE_MESSAGE =
            "\u8c46\u5305\u901a\u8bdd\u5165\u53e3\u5931\u6548\uff0c\u8bf7\u5bb6\u4eba\u534f\u52a9\u66f4\u65b0\u3002";
    private static final String OPEN_FAILED_MESSAGE =
            "\u6253\u5f00\u8c46\u5305\u5931\u8d25\uff0c\u8bf7\u5bb6\u4eba\u534f\u52a9\u68c0\u67e5\u3002";
    private static final String RULE_LOAD_FAILED_MESSAGE =
            "\u89c4\u5219\u52a0\u8f7d\u5931\u8d25\uff0c\u8bf7\u5bb6\u4eba\u68c0\u67e5\u7f51\u7edc\u6216\u89c4\u5219\u6587\u4ef6\u3002";
    private static final String VIDEO_SHIELD_PERMISSION_MESSAGE =
            "\u8bf7\u5bb6\u4eba\u5148\u5f00\u542f\u60ac\u6d6e\u7a97\u548c\u901a\u77e5\u6743\u9650\u3002";
    private static final String VIDEO_SHIELD_FAILED_MESSAGE =
            "\u89c6\u9891\u901a\u8bdd\u4fdd\u62a4\u542f\u52a8\u5931\u8d25\uff0c\u8bf7\u5bb6\u4eba\u68c0\u67e5\u8bbe\u7f6e\u3002";

    private TextToSpeech tts;
    private boolean activityDestroyed;
    private boolean finishRequested;
    private Runnable timeoutFinishRunnable;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Intent pendingVideoIntent;
    private String pendingVideoMode;
    private boolean shieldStartPending;
    private boolean shieldSessionStarted;
    private Runnable shieldReadyTimeoutRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launchDoubaoCall();
    }

    private void launchDoubaoCall() {
        maximizeAudibleVolume();

        final String mode = readLaunchMode();
        Log.i(TAG, "launch mode=" + mode);

        final RuleRepository repository = new RuleRepository(getApplicationContext());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final RuleFetchResult result = repository.loadRule();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (activityDestroyed) {
                            return;
                        }
                        handleRuleResult(mode, result);
                    }
                });
            }
        }, "doubao-rule-loader").start();
    }

    private void handleRuleResult(String mode, RuleFetchResult result) {
        if (result == null || !result.hasRule()) {
            speakAndToast(RULE_LOAD_FAILED_MESSAGE);
            return;
        }
        openDoubaoCall(mode, result.rule);
    }

    private void openDoubaoCall(String mode, DoubaoRule rule) {
        boolean video = MODE_VIDEO.equals(mode);
        CallEntry entry = rule.entryForMode(mode);

        if (!isPackageInstalled(rule.doubaoPackage)) {
            Log.w(TAG, "Doubao package is not installed or not visible.");
            speakAndToast(DOUBAO_NOT_INSTALLED_MESSAGE);
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(entry.uri));
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(rule.doubaoPackage, rule.doubaoActivity));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.i(TAG, "starting Doubao intent=" + intent.toUri(0));

        if (video) {
            startVideoShieldAndOpen(mode, intent);
            return;
        }

        dispatchDoubaoIntent(mode, intent, false);
    }

    private void startVideoShieldAndOpen(final String mode, final Intent intent) {
        if (!canDrawOverlays() || !areNotificationsEnabled()) {
            speakAndToast(VIDEO_SHIELD_PERMISSION_MESSAGE);
            return;
        }

        pendingVideoMode = mode;
        pendingVideoIntent = intent;
        shieldStartPending = true;
        shieldSessionStarted = false;

        ResultReceiver resultReceiver = new ResultReceiver(mainHandler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (!shieldStartPending || activityDestroyed) {
                    if (activityDestroyed) {
                        stopVideoShield();
                    }
                    return;
                }

                shieldStartPending = false;
                cancelShieldReadyTimeout();
                if (resultCode == TouchShieldService.RESULT_READY) {
                    shieldSessionStarted = true;
                    Intent readyIntent = pendingVideoIntent;
                    String readyMode = pendingVideoMode;
                    pendingVideoIntent = null;
                    pendingVideoMode = null;
                    dispatchDoubaoIntent(readyMode, readyIntent, true);
                    return;
                }

                pendingVideoIntent = null;
                pendingVideoMode = null;
                stopVideoShield();
                speakAndToast(VIDEO_SHIELD_FAILED_MESSAGE);
            }
        };

        armShieldReadyTimeout();
        Intent serviceIntent = TouchShieldService.createStartIntent(this, resultReceiver);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (RuntimeException ex) {
            Log.e(TAG, "Could not start the touch-shield service.", ex);
            shieldStartPending = false;
            pendingVideoIntent = null;
            pendingVideoMode = null;
            cancelShieldReadyTimeout();
            stopVideoShield();
            speakAndToast(VIDEO_SHIELD_FAILED_MESSAGE);
        }
    }

    private void dispatchDoubaoIntent(String mode, Intent intent, boolean shieldStarted) {
        try {
            startActivity(intent);
            Log.i(TAG, "Doubao startActivity dispatched.");
            speakAndToast(MODE_VIDEO.equals(mode) ? OPENING_VIDEO_MESSAGE : OPENING_VOICE_MESSAGE);
        } catch (ActivityNotFoundException ex) {
            Log.e(TAG, "Doubao activity not found.", ex);
            if (shieldStarted) {
                shieldSessionStarted = false;
                stopVideoShield();
            }
            speakAndToast(ENTRY_UNAVAILABLE_MESSAGE);
        } catch (RuntimeException ex) {
            Log.e(TAG, "Failed to open Doubao.", ex);
            if (shieldStarted) {
                shieldSessionStarted = false;
                stopVideoShield();
            }
            speakAndToast(OPEN_FAILED_MESSAGE);
        }
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private boolean areNotificationsEnabled() {
        return TouchShieldService.areNotificationsEnabled(this);
    }

    private void armShieldReadyTimeout() {
        cancelShieldReadyTimeout();
        shieldReadyTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                shieldReadyTimeoutRunnable = null;
                if (!activityDestroyed && shieldStartPending) {
                    shieldStartPending = false;
                    pendingVideoIntent = null;
                    pendingVideoMode = null;
                    stopVideoShield();
                    speakAndToast(VIDEO_SHIELD_FAILED_MESSAGE);
                }
            }
        };
        mainHandler.postDelayed(shieldReadyTimeoutRunnable, SHIELD_READY_TIMEOUT_MILLIS);
    }

    private void cancelShieldReadyTimeout() {
        if (shieldReadyTimeoutRunnable != null) {
            mainHandler.removeCallbacks(shieldReadyTimeoutRunnable);
            shieldReadyTimeoutRunnable = null;
        }
    }

    private void stopVideoShield() {
        Intent stopIntent = TouchShieldService.createStopIntent(getApplicationContext());
        stopIntent.setAction(TouchShieldService.ACTION_STOP);
        try {
            startService(stopIntent);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not dispatch shield stop action; stopping service directly.", ex);
            stopService(stopIntent);
        }
    }

    private void maximizeAudibleVolume() {
        Object audioService = getSystemService(AUDIO_SERVICE);
        if (!(audioService instanceof AudioManager)) {
            Log.w(TAG, "AudioManager is unavailable.");
            return;
        }

        AudioManager audioManager = (AudioManager) audioService;
        maximizeMusicVolume(audioManager);
        maximizeVoiceCallVolume(audioManager);
    }

    private void maximizeMusicVolume(AudioManager audioManager) {
        unmuteAndMaximizeStream(audioManager, AudioManager.STREAM_MUSIC, "music");
    }

    private void maximizeVoiceCallVolume(AudioManager audioManager) {
        unmuteAndMaximizeStream(audioManager, AudioManager.STREAM_VOICE_CALL, "voice_call");
    }

    private void unmuteAndMaximizeStream(AudioManager audioManager, int streamType, String label) {
        try {
            audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0);
        } catch (SecurityException ex) {
            Log.w(TAG, "No permission to unmute stream " + label + ".", ex);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not unmute stream " + label + ".", ex);
        }

        try {
            int maxVolume = audioManager.getStreamMaxVolume(streamType);
            audioManager.setStreamVolume(streamType, maxVolume, 0);
            Log.i(TAG, "Set stream " + label + " volume to " + maxVolume + ".");
        } catch (SecurityException ex) {
            Log.w(TAG, "No permission to set stream " + label + " volume.", ex);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not set stream " + label + " volume.", ex);
        }
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ex) {
            return false;
        }
    }

    private String readLaunchMode() {
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(getComponentName(), PackageManager.GET_META_DATA);
            if (info.metaData != null) {
                Log.i(TAG, "activity metadata=" + info.metaData);
                return info.metaData.getString(EXTRA_MODE, "voice");
            }
            Log.w(TAG, "activity metadata is null.");
        } catch (PackageManager.NameNotFoundException ignored) {
            Log.w(TAG, "activity info not found.");
            return "voice";
        }
        return "voice";
    }

    private void speakAndToast(final String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        final TextToSpeech[] engineHolder = new TextToSpeech[1];
        TextToSpeech engine = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                TextToSpeech activeEngine = engineHolder[0];
                if (activityDestroyed || activeEngine == null || activeEngine != tts) {
                    if (activeEngine != null) {
                        activeEngine.shutdown();
                    }
                    return;
                }

                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "TextToSpeech init failed with status=" + status);
                    finishSoon();
                    return;
                }

                activeEngine.setLanguage(Locale.CHINA);
                activeEngine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        Log.i(TAG, "TTS started.");
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        finishSoon();
                    }

                    @Override
                    public void onError(String utteranceId) {
                        finishSoon();
                    }
                });

                int result = activeEngine.speak(message, TextToSpeech.QUEUE_FLUSH, null, STATUS_UTTERANCE_ID);
                if (result == TextToSpeech.ERROR) {
                    Log.w(TAG, "TextToSpeech speak failed.");
                    finishSoon();
                }
            }
        });
        engineHolder[0] = engine;
        tts = engine;
        armTtsTimeout();
    }

    private void armTtsTimeout() {
        cancelTimeoutFinish();
        timeoutFinishRunnable = new Runnable() {
            @Override
            public void run() {
                timeoutFinishRunnable = null;
                if (!activityDestroyed && !finishRequested) {
                    finishNow();
                }
            }
        };
        getWindow().getDecorView().postDelayed(timeoutFinishRunnable, MAX_FINISH_DELAY_MILLIS);
    }

    private void finishSoon() {
        if (activityDestroyed || finishRequested) {
            return;
        }
        finishRequested = true;
        cancelTimeoutFinish();
        finishAfterDelay(SHORT_FINISH_DELAY_MILLIS);
    }

    private void finishAfterDelay(long delayMillis) {
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                finishNow();
            }
        }, delayMillis);
    }

    private void finishNow() {
        if (activityDestroyed) {
            return;
        }
        finishRequested = true;
        cancelTimeoutFinish();
        shutdownTts();
        finish();
    }

    private void cancelTimeoutFinish() {
        if (timeoutFinishRunnable != null) {
            getWindow().getDecorView().removeCallbacks(timeoutFinishRunnable);
            timeoutFinishRunnable = null;
        }
    }

    private void shutdownTts() {
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        cancelTimeoutFinish();
        cancelShieldReadyTimeout();
        if (shieldStartPending && !shieldSessionStarted) {
            shieldStartPending = false;
            pendingVideoIntent = null;
            pendingVideoMode = null;
            stopVideoShield();
        }
        shutdownTts();
        super.onDestroy();
    }
}
