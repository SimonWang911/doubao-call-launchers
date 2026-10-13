package android.app;
public class AppOpsManager {
    public static final String OPSTR_GET_USAGE_STATS = "usage";
    public static final int MODE_ALLOWED = 0;
    public int mode;
    public int checkOpNoThrow(String operation, int uid, String pkg) { return mode; }
}
