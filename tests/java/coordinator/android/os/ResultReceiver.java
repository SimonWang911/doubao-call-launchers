package android.os;
public class ResultReceiver {
    private final Handler handler;
    public ResultReceiver(Handler handler) { this.handler = handler; }
    protected void onReceiveResult(int code, Bundle data) { }
    public void send(final int code, final Bundle data) {
        handler.post(() -> onReceiveResult(code, data));
    }
}
