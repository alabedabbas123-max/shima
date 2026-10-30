package android.content;
public class Context {
    public static final int BIND_AUTO_CREATE=1;
    public Context getApplicationContext() { return this; }
    public java.io.File getFilesDir() { return new java.io.File(System.getProperty("java.io.tmpdir"),"shima-qa-files"); }
    public java.io.File getNoBackupFilesDir() { return getFilesDir(); }
    public SharedPreferences getSharedPreferences(String name,int mode) { return null; }
    public boolean bindService(Intent intent,ServiceConnection connection,int flags) { return false; }
    public void unbindService(ServiceConnection connection) { }
}
