package android.app.usage;
import java.util.List;
public class UsageEvents {
    private final List<Event> events;
    private int position;
    public UsageEvents(List<Event> events) { this.events = events; }
    public boolean hasNextEvent() { return position < events.size(); }
    public boolean getNextEvent(Event target) {
        if (!hasNextEvent()) return false;
        Event source = events.get(position++);
        target.pkg = source.pkg; target.cls = source.cls; target.time = source.time; target.type = source.type;
        return true;
    }
    public static class Event {
        public static final int MOVE_TO_FOREGROUND = 1, ACTIVITY_STOPPED = 23;
        String pkg, cls; long time; int type;
        public Event() { }
        public Event(String pkg, String cls, long time, int type) { this.pkg = pkg; this.cls = cls; this.time = time; this.type = type; }
        public String getPackageName() { return pkg; }
        public String getClassName() { return cls; }
        public long getTimeStamp() { return time; }
        public int getEventType() { return type; }
    }
}
