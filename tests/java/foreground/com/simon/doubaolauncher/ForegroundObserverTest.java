package com.simon.doubaolauncher;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStatsManager;
import android.content.Context;

public class ForegroundObserverTest {
    private static int checks;
    private final long start = System.currentTimeMillis() - 10000;
    private final Context context = new Context();
    private final UsageStatsManager manager = new UsageStatsManager();
    private final AppOpsManager ops = new AppOpsManager();
    private final ForegroundObserver observer;
    private ForegroundObserverTest() {
        context.services.put(Context.APP_OPS_SERVICE, ops); context.services.put(Context.USAGE_STATS_SERVICE, manager);
        observer = new ForegroundObserver(context, start);
    }
    private void event(String pkg, String cls, long time, int type) { manager.stored.add(new Event(pkg, cls, time, type)); }
    private void resumed(String pkg, String cls, long offset) { event(pkg, cls, start + offset, Event.MOVE_TO_FOREGROUND); }
    private void eq(CallSession.Foreground expected, String reason) {
        checks++; CallSession.Foreground actual = observer.read();
        if (expected != actual) throw new AssertionError(reason + ": " + actual);
    }
    public static void main(String[] args) {
        ForegroundObserverTest t = new ForegroundObserverTest();
        t.eq(CallSession.Foreground.UNKNOWN, "no old foreground assumed");
        t.event("com.larus.nova", "Old", t.start - 1, Event.MOVE_TO_FOREGROUND);
        t.eq(CallSession.Foreground.UNKNOWN, "old session filtered");
        t.resumed("com.larus.nova", "Main", 9500); t.eq(CallSession.Foreground.DOUBAO, "current foreground");
        t.resumed("com.android.launcher", "Launcher", 9700);
        t.eq(CallSession.Foreground.OTHER, "delayed Home or Recents survives prior cursor");
        t.eq(CallSession.Foreground.OTHER, "duplicate query never rewinds foreground");
        t = new ForegroundObserverTest();
        t.resumed("com.larus.nova", "Main", 9300); t.resumed("com.larus.nova", "Call", 9400);
        t.event("com.larus.nova", "Main", t.start + 9500, Event.ACTIVITY_STOPPED);
        t.eq(CallSession.Foreground.DOUBAO, "old internal Activity stop keeps current Activity");
        t.resumed("com.android.systemui", "Panel", 9600); t.eq(CallSession.Foreground.TRANSIENT, "notification/volume layer");
        t.event("com.android.systemui", "Panel", t.start + 9700, Event.ACTIVITY_STOPPED);
        t.eq(CallSession.Foreground.DOUBAO, "system layer stop restores running target");
        t.event("com.larus.nova", "Call", t.start + 9800, Event.ACTIVITY_STOPPED);
        t.eq(CallSession.Foreground.OTHER, "Back stops last target even without new resume");
        t.ops.mode = 1; t.eq(CallSession.Foreground.UNKNOWN, "permission denied is unknown");
        t.ops.mode = 0; t.manager.unavailable = true; t.eq(CallSession.Foreground.UNKNOWN, "query unavailable is unknown");
        t = new ForegroundObserverTest();
        t.resumed("com.android.settings", "Settings", 9300); t.resumed("com.larus.nova", "Main", 9500);
        t.eq(CallSession.Foreground.DOUBAO, "latest event takes precedence");
        t.resumed("com.android.launcher", "LateOld", 9400); t.eq(CallSession.Foreground.DOUBAO, "late older event cannot rewind latest");
        t = new ForegroundObserverTest();
        t.resumed("com.larus.nova", "Main", 9300);
        t.event("android", "Unrelated", t.start + 9800, 99);
        t.eq(CallSession.Foreground.DOUBAO, "unrelated event keeps foreground");
        t.resumed("com.android.launcher", "Launcher", 9700);
        t.eq(CallSession.Foreground.OTHER, "unrelated event cannot hide delayed foreground exit");
        System.out.println("Foreground observer tests passed (" + checks + " checks).");
    }
}
