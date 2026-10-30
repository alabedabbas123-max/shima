package almlk;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
public class GradleBuildModel {
  public static volatile String blockName;
  public static volatile CountDownLatch entered, release;
  public final File buildFile;
  public final String text;
  public Object projectModules;
  public final List<Dependency> dependencies=Collections.emptyList();
  public static class Dependency { }
  private GradleBuildModel(File root) { buildFile=new File(root,"build.gradle"); text=root.getName(); }
  public static GradleBuildModel load(File root) {
    if (root.getName().equals(blockName)) {
      CountDownLatch start=entered, go=release;
      if (start!=null) start.countDown();
      if (go!=null) try { go.await(); } catch (InterruptedException x) { Thread.currentThread().interrupt(); return null; }
    }
    return new GradleBuildModel(root);
  }
  public List<Dependency> reparse(String text) { return dependencies; }
}
