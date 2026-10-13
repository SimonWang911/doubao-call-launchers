package android.content;
public final class ComponentName {
    private final String value;
    public ComponentName(String packageName, String className) { value = packageName + "/" + className; }
    public ComponentName(Context context, Class<?> type) { this(context.getPackageName(), type.getName()); }
    public static ComponentName unflattenFromString(String text) {
        String[] parts = text.split("/", 2);
        return parts.length == 2 ? new ComponentName(parts[0], parts[1]) : null;
    }
    public boolean equals(Object other) { return other instanceof ComponentName && value.equals(((ComponentName) other).value); }
    public int hashCode() { return value.hashCode(); }
}
