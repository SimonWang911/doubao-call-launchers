package android.os;
public final class Bundle {
    private final java.util.Map<String, Object> values = new java.util.HashMap<>();
    public void putParcelable(String key, Object value) { values.put(key, value); }
    public void putString(String key, String value) { values.put(key, value); }
    public Object get(String key) { return values.get(key); }
}
