package android.app;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
public abstract class Service extends Context {
    public abstract IBinder onBind(Intent intent);
    public void onDestroy() { }
    public void onTrimMemory(int level) { }
}
