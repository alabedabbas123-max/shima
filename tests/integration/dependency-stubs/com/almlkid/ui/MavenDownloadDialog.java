package com.almlkid.ui;
import almlk.MavenResolver;
import android.app.Activity;
import java.util.List;
public class MavenDownloadDialog {
  public interface Callback { void onFinished(MavenResolver.Result result); }
  public static volatile Callback callback;
  public static volatile boolean running;
  public static MavenDownloadDialog start(Activity activity,MavenResolver resolver,List<MavenResolver.Request> requests,
      boolean refresh,Callback cb) { running=true; callback=cb; return new MavenDownloadDialog(); }
  public static boolean isRunning() { return running; }
  public static void showActive() { }
  public static void cancelActive() { }
  public static void showOutcome(Activity activity,MavenResolver.Result result,boolean interactive) { }
  public static void finish(MavenResolver.Result result) {
    Callback cb=callback; callback=null; running=false; if (cb!=null) cb.onFinished(result);
  }
}
