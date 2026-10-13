package android.content;
import java.util.HashMap;
public final class Intent {
    private final HashMap<String, Object> extras = new HashMap<String, Object>();
    public Intent(String action) { }
    public Intent setComponent(ComponentName component) { return this; }
    public Intent putExtra(String key, Object value) { extras.put(key, value); return this; }
    public Object getExtra(String key) { return extras.get(key); }
}
