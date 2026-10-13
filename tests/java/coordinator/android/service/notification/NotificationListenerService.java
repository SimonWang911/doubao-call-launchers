package android.service.notification;
import android.content.ComponentName;
public class NotificationListenerService {
    public static int rebinds;
    public static void requestRebind(ComponentName component) { rebinds++; }
}
