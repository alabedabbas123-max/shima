package almlk;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
public class ProjectLibraries {
  public static class Marker { }
  private boolean complete;
  private String fingerprint="";
  public static final List<String> applied=Collections.synchronizedList(new ArrayList<>());
  public synchronized void apply(MavenResolver.Result result,boolean offline,String value) {
    applied.add(result.label); complete=!result.cancelled && result.failures.isEmpty(); fingerprint=value;
  }
  public synchronized void cancelled() { complete=false; fingerprint=""; }
  public synchronized void invalidate() { clear(); }
  public synchronized void clear() { complete=false; fingerprint=""; }
  public synchronized boolean isComplete() { return complete; }
  public synchronized boolean isUpToDate(String value) { return complete && fingerprint.equals(value); }
  public static String fingerprint(GradleBuildModel model,MavenStore store) { return model.text; }
  public List<Marker> markers(String text,List<GradleBuildModel.Dependency> dependencies) { return Collections.emptyList(); }
}
