package android.os;
public final class Message {
    private Bundle data;
    public static Message obtain(Object handler, int what) { return new Message(); }
    public void setData(Bundle data) { this.data = data; }
    public Bundle getData() { return data; }
}
