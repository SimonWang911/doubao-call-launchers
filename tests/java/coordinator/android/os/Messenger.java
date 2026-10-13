package android.os;
public final class Messenger {
    private final IBinder binder;
    public Messenger(IBinder binder) { this.binder = binder; }
    public void send(Message message) throws RemoteException { binder.send(message); }
}
