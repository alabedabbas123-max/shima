import almlk.AnalyzerBridge;
import android.content.Context;
import android.os.Looper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Uses deterministic Looper/Handler test doubles, not Android instrumentation. */
public final class Sh129MainThreadDispatch {
  private static int checks;
  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    Looper.getMainLooper(); // Establish the main test thread before the worker starts.
    Thread mainThread = Thread.currentThread();
    final int[] publishes = {0};
    final Thread[] publishedOn = {null};
    AnalyzerBridge bridge = new AnalyzerBridge(new Context(), ref -> "", () -> {
      publishes[0]++;
      publishedOn[0] = Thread.currentThread();
    });
    Method notify = AnalyzerBridge.class.getDeclaredMethod("notifyPublisher");
    notify.setAccessible(true);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker = new Thread(() -> {
      try {
        notify.invoke(bridge);
      } catch (InvocationTargetException error) {
        failure.set(error.getCause());
      } catch (Exception error) {
        failure.set(error);
      }
    }, "project-pass-test");
    worker.start();
    worker.join(5000);
    check(!worker.isAlive() && failure.get() == null, "worker notification should return safely");
    check(publishes[0] == 0, "worker must not update the UI inline");
    Looper.pump(100);
    check(publishes[0] == 1 && publishedOn[0] == mainThread,
        "publisher must run once on the main loop");
    bridge.release();
    System.out.println("PASS: " + checks + " app publisher main-thread checks");
  }
}
