package android.content;
import java.util.HashMap;
import java.util.Map;
public class Context {
    public static final String APP_OPS_SERVICE = "appops", USAGE_STATS_SERVICE = "usagestats";
    public final Map<String, Object> services = new HashMap<String, Object>();
    public Object getSystemService(String name) { return services.get(name); }
    public String getPackageName() { return "com.simon.doubao.voicecall"; }
}
