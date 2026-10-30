package android.os;
public final class Messenger {
    public interface Observer { void sent(Message msg,Thread sender); }
    public static volatile Observer observer;
    private final Target target;
    private static final class Target implements IBinder {
        final Handler handler;
        Target(Handler handler) { this.handler=handler; }
    }
    public Messenger(Handler handler) { target=new Target(handler); }
    public Messenger(IBinder binder) { target=(Target) binder; }
    public IBinder getBinder() { return target; }
    public void send(Message msg) throws RemoteException {
        Observer current=observer; if (current!=null) current.sent(msg,Thread.currentThread());
        if (target==null) throw new RemoteException("missing target");
        target.handler.post(() -> target.handler.handleMessage(msg));
    }
}
