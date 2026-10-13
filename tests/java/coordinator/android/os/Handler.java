package android.os;
import java.util.PriorityQueue;
public final class Handler {
    private static long now;
    private static long order;
    private static final PriorityQueue<Task> TASKS = new PriorityQueue<Task>();
    public Handler(Looper looper) { }
    public boolean post(Runnable runnable) { return postDelayed(runnable, 0L); }
    public boolean postDelayed(Runnable runnable, long delay) {
        TASKS.add(new Task(runnable, now + delay, order++));
        return true;
    }
    public void removeCallbacks(Runnable runnable) {
        TASKS.removeIf(task -> task.runnable == runnable);
    }
    public static long now() { return now; }
    public static void reset() { TASKS.clear(); now = 1000L; order = 0L; }
    public static void runCurrent() { advanceTo(now); }
    public static void advanceTo(long time) {
        if (time < now) { throw new IllegalArgumentException("time cannot go backwards"); }
        int executions = 0;
        while (!TASKS.isEmpty() && TASKS.peek().when <= time) {
            Task task = TASKS.poll();
            now = task.when;
            task.runnable.run();
            if (++executions > 10000) { throw new AssertionError("unbounded scheduling"); }
        }
        now = time;
    }
    public static int pendingTasks() { return TASKS.size(); }
    private static final class Task implements Comparable<Task> {
        final Runnable runnable;
        final long when;
        final long sequence;
        Task(Runnable runnable, long when, long sequence) {
            this.runnable = runnable; this.when = when; this.sequence = sequence;
        }
        public int compareTo(Task other) {
            int time = Long.compare(when, other.when);
            return time == 0 ? Long.compare(sequence, other.sequence) : time;
        }
    }
}
