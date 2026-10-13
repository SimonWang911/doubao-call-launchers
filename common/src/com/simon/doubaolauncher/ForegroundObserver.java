package com.simon.doubaolauncher;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;
import java.util.LinkedHashMap;
import java.util.Map;

/** Uses public usage events only, for the lifetime of one dispatched session. */
final class ForegroundObserver {
    private final Context context;
    private long cursor;
    private final long dispatchWallTime;
    private long lastEventTime;
    private String latestPackage, latestClass;
    private final Map<String, String> resumed = new LinkedHashMap<String, String>();
    private final Map<String, Long> seen = new LinkedHashMap<String, Long>();
    private boolean targetStopped;
    ForegroundObserver(Context context, long dispatchWallTime) {
        this.context = context; this.dispatchWallTime = dispatchWallTime; cursor = dispatchWallTime;
        lastEventTime = dispatchWallTime;
    }
    static boolean hasPermission(Context context) {
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }
    CallSession.Foreground read() {
        if (!hasPermission(context)) return CallSession.Foreground.UNKNOWN;
        try {
            long until = System.currentTimeMillis();
            UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = manager.queryEvents(cursor, until + 1);
            if (events == null) return CallSession.Foreground.UNKNOWN;
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                if (type != UsageEvents.Event.MOVE_TO_FOREGROUND && type != UsageEvents.Event.ACTIVITY_STOPPED) continue;
                String pkg = event.getPackageName(), cls = event.getClassName();
                String key = pkg + "/" + cls;
                long time = event.getTimeStamp();
                String identity = time + ":" + event.getEventType() + ":" + key;
                if (seen.containsKey(identity) || time < lastEventTime) continue;
                seen.put(identity, time); lastEventTime = time;
                while (seen.size() > 256) seen.remove(seen.keySet().iterator().next());
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    resumed.remove(key); resumed.put(key, pkg);
                    latestPackage = pkg; latestClass = cls;
                    if ("com.larus.nova".equals(pkg)) targetStopped = false;
                } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_STOPPED) {
                    resumed.remove(key);
                    if (equal(pkg, latestPackage) && equal(cls, latestClass)) {
                        targetStopped = "com.larus.nova".equals(pkg);
                        latestPackage = null; latestClass = null;
                        for (Map.Entry<String, String> entry : resumed.entrySet()) {
                            latestPackage = entry.getValue(); latestClass = entry.getKey().substring(entry.getKey().indexOf('/') + 1);
                        }
                        if ("com.larus.nova".equals(latestPackage)) targetStopped = false;
                    }
                }
            }
            cursor = Math.max(dispatchWallTime, until - 2000);
            if ("com.larus.nova".equals(latestPackage) && !targetStopped) return CallSession.Foreground.DOUBAO;
            if ("com.android.systemui".equals(latestPackage)) return CallSession.Foreground.TRANSIENT;
            if (latestPackage != null || targetStopped) return CallSession.Foreground.OTHER;
            return CallSession.Foreground.UNKNOWN;
        } catch (RuntimeException error) { return CallSession.Foreground.UNKNOWN; }
    }
    private static boolean equal(String left, String right) { return left == null ? right == null : left.equals(right); }
}
