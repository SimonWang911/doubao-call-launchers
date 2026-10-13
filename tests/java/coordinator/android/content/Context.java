package android.content;
public class Context {
    public static final int BIND_AUTO_CREATE = 1;
    public Context getApplicationContext() { return this; }
    public String getPackageName() { return "com.simon.doubao.voicecall"; }
    public Object getContentResolver() { return this; }
    public ComponentName startService(Intent intent) { return null; }
    public boolean bindService(Intent intent, ServiceConnection connection, int flags) { return false; }
    public void unbindService(ServiceConnection connection) { }
}
