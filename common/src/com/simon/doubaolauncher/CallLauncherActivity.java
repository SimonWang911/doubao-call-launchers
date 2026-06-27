package com.simon.doubaolauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import java.util.Locale;

public class CallLauncherActivity extends Activity {
    private static final String TAG = "DoubaoCallLauncher";
    private static final String EXTRA_MODE = "com.simon.doubaolauncher.MODE";
    private static final String MODE_VIDEO = "video";
    private static final String DOUBAO_PACKAGE = "com.larus.nova";
    private static final String DOUBAO_ALIAS_ACTIVITY = "com.larus.home.impl.alias.AliasActivity1";
    private static final long FINISH_DELAY_MILLIS = 2500L;

    private static final String VOLUME_READY_MESSAGE =
            "\u97f3\u91cf\u5df2\u8c03\u5230\u6700\u5927\u3002";
    private static final String AUTHORIZATION_HELP_MESSAGE =
            "\u5982\u679c\u770b\u5230\u5141\u8bb8\u6253\u5f00\u8c46\u5305\uff0c\u8bf7\u9009\u62e9\u5141\u8bb8\u3002";
    private static final String OPENING_VOICE_MESSAGE =
            VOLUME_READY_MESSAGE + "\u6b63\u5728\u6253\u5f00\u8c46\u5305\u8bed\u97f3\u901a\u8bdd\u3002"
                    + AUTHORIZATION_HELP_MESSAGE;
    private static final String OPENING_VIDEO_MESSAGE =
            VOLUME_READY_MESSAGE + "\u6b63\u5728\u6253\u5f00\u8c46\u5305\u89c6\u9891\u901a\u8bdd\u3002"
                    + AUTHORIZATION_HELP_MESSAGE;
    private static final String DOUBAO_NOT_INSTALLED_MESSAGE =
            "\u672a\u5b89\u88c5\u8c46\u5305\uff0c\u8bf7\u5bb6\u4eba\u534f\u52a9\u5b89\u88c5\u3002";
    private static final String ENTRY_UNAVAILABLE_MESSAGE =
            "\u8c46\u5305\u901a\u8bdd\u5165\u53e3\u5931\u6548\uff0c\u8bf7\u5bb6\u4eba\u534f\u52a9\u66f4\u65b0\u3002";
    private static final String OPEN_FAILED_MESSAGE =
            "\u6253\u5f00\u8c46\u5305\u5931\u8d25\uff0c\u8bf7\u5bb6\u4eba\u534f\u52a9\u68c0\u67e5\u3002";

    private static final String VOICE_CALL_URI =
            "sslocal://flow/realtime_chat?is_from_outer=true"
                    + "&bot_id=7234781073513644036"
                    + "&open_method=shortcuts"
                    + "&sec_scene=shortcuts_call"
                    + "&enter_method=shortcuts";

    private static final String VIDEO_CALL_URI =
            "sslocal://flow/realtime_chat?is_from_outer=true"
                    + "&bot_id=7234781073513644036"
                    + "&open_method=shortcuts"
                    + "&open_vlm=1"
                    + "&sec_scene=shortcuts_video_call"
                    + "&enter_method=shortcuts";

    private TextToSpeech tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launchDoubaoCall();
    }

    private void launchDoubaoCall() {
        maximizeAudibleVolume();

        String mode = readLaunchMode();
        boolean video = MODE_VIDEO.equals(mode);
        Log.i(TAG, "launch mode=" + mode + ", video=" + video);

        if (!isPackageInstalled(DOUBAO_PACKAGE)) {
            Log.w(TAG, "Doubao package is not installed or not visible.");
            speakAndToast(DOUBAO_NOT_INSTALLED_MESSAGE);
            finishAfterDelay();
            return;
        }

        speakAndToast(video ? OPENING_VIDEO_MESSAGE : OPENING_VOICE_MESSAGE);

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(video ? VIDEO_CALL_URI : VOICE_CALL_URI));
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(DOUBAO_PACKAGE, DOUBAO_ALIAS_ACTIVITY));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.i(TAG, "starting Doubao intent=" + intent.toUri(0));

        try {
            startActivity(intent);
            Log.i(TAG, "Doubao startActivity dispatched.");
            finishAfterDelay();
        } catch (ActivityNotFoundException ex) {
            Log.e(TAG, "Doubao activity not found.", ex);
            speakAndToast(ENTRY_UNAVAILABLE_MESSAGE);
            finishAfterDelay();
        } catch (RuntimeException ex) {
            Log.e(TAG, "Failed to open Doubao.", ex);
            speakAndToast(OPEN_FAILED_MESSAGE);
            finishAfterDelay();
        }
    }

    private void maximizeAudibleVolume() {
        Object audioService = getSystemService(AUDIO_SERVICE);
        if (!(audioService instanceof AudioManager)) {
            Log.w(TAG, "AudioManager is unavailable.");
            return;
        }

        AudioManager audioManager = (AudioManager) audioService;
        maximizeStream(audioManager, AudioManager.STREAM_MUSIC, "music");
        maximizeStream(audioManager, AudioManager.STREAM_VOICE_CALL, "voice_call");
    }

    private void maximizeStream(AudioManager audioManager, int streamType, String label) {
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
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(Locale.CHINA);
                    tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "doubao_call_launcher_status");
                }
            }
        });
    }

    private void finishAfterDelay() {
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tts != null) {
                    tts.shutdown();
                    tts = null;
                }
                finish();
            }
        }, FINISH_DELAY_MILLIS);
    }
}
