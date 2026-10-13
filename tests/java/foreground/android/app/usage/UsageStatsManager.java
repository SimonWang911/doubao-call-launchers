package android.app.usage;
import java.util.ArrayList;
import java.util.List;
public class UsageStatsManager {
    public final List<UsageEvents.Event> stored = new ArrayList<UsageEvents.Event>();
    public boolean unavailable;
    public UsageEvents queryEvents(long start, long end) {
        if (unavailable) return null;
        List<UsageEvents.Event> result = new ArrayList<UsageEvents.Event>();
        for (UsageEvents.Event event : stored) if (event.getTimeStamp() >= start && event.getTimeStamp() < end) result.add(event);
        return new UsageEvents(result);
    }
}
