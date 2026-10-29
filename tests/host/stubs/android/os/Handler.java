package android.os;
public final class Handler {
    private final Looper looper;
    public Handler(Looper looper) { this.looper=looper; }
    public boolean post(Runnable runnable) { looper.post(this,runnable,0); return true; }
    public boolean postDelayed(Runnable runnable, long delay) { looper.post(this,runnable,delay); return true; }
    public void removeCallbacks(Runnable runnable) { looper.remove(this,runnable); }
    public void removeCallbacksAndMessages(Object token) { looper.remove(this,null); }
}
