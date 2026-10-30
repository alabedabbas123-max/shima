import almlk.GradleBuildModel;
import almlk.MavenResolver;
import almlk.ProjectLibraries;
import almlk.ProjectPlatform;
import android.app.Activity;
import android.os.Looper;
import com.almlkid.ui.DependencyCycle;
import com.almlkid.ui.MavenDownloadDialog;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Real DependencyCycle with controlled resolver/dialog/Android doubles; not a device or Gradle test. */
public final class Sh129DependencyCycleRegression {
  private static int checks;
  private static void check(boolean ok,String message) { checks++; if (!ok) throw new AssertionError(message); }
  private static void waitFor(BooleanSupplier condition,String message) throws Exception {
    long end=System.nanoTime()+6_000_000_000L;
    while (!condition.getAsBoolean() && System.nanoTime()<end) Looper.pump(10);
    check(condition.getAsBoolean(),message);
  }
  private static final class Host implements DependencyCycle.Host {
    int invalidations,completions;
    public String liveText(File file) { return null; }
    public void publishDependencyMarkers(File file,String text,List<ProjectLibraries.Marker> markers) { }
    public void onLibrariesInvalidated() { invalidations++; }
    public void onLibrariesResolved() { completions++; }
  }
  public static void main(String[] args) throws Exception {
    Looper.getMainLooper();
    Host host=new Host();
    DependencyCycle cycle=new DependencyCycle(new Activity(),host);
    File first=new File("first-project"), second=new File("second-project");
    cycle.start(first,DependencyCycle.Mode.OPEN);
    check(!cycle.isAnalysisClasspathReady() && host.invalidations==1,"starting resolution invalidates the analysis snapshot");
    waitFor(() -> host.completions==1 && cycle.isAnalysisClasspathReady(),"completed current generation becomes ready");
    check("first-project".equals(ProjectPlatform.current().label),"current project platform is published");

    GradleBuildModel.blockName="first-project";
    GradleBuildModel.entered=new CountDownLatch(1); GradleBuildModel.release=new CountDownLatch(1);
    cycle.start(first,DependencyCycle.Mode.SAVE);
    check(!cycle.isAnalysisClasspathReady(),"a saved build immediately invalidates readiness");
    check(GradleBuildModel.entered.await(5,TimeUnit.SECONDS),"old generation reached the controlled worker");
    cycle.start(second,DependencyCycle.Mode.OPEN);
    check("unknown".equals(ProjectPlatform.current().label) && !cycle.isAnalysisClasspathReady(),
        "switching project clears the previous platform before a new result arrives");
    GradleBuildModel.blockName=null; GradleBuildModel.release.countDown();
    waitFor(() -> host.completions==2 && cycle.isAnalysisClasspathReady(),"new project resolves after stale worker finishes");
    check("second-project".equals(ProjectPlatform.current().label) && cycle.model().text.equals("second-project"),
        "stale generation cannot publish its model or platform");

    cycle.start(second,DependencyCycle.Mode.REFRESH);
    waitFor(() -> MavenDownloadDialog.callback!=null,"interactive refresh has an active download callback");
    check(!cycle.isAnalysisClasspathReady(),"download in progress is not considered ready");
    int before=host.completions, invalidations=host.invalidations;
    cycle.start(second,DependencyCycle.Mode.SAVE);
    check(host.invalidations==invalidations+1 && !cycle.isAnalysisClasspathReady(),
        "a change during download invalidates the old generation immediately");
    MavenResolver.Result stale=new MavenResolver.Result(); stale.label="stale-download";
    MavenDownloadDialog.finish(stale);
    waitFor(() -> host.completions==before+1 && cycle.isAnalysisClasspathReady(),
        "queued new generation resolves after the stale download finishes");
    check(!ProjectLibraries.applied.contains("stale-download"),"the stale download cannot publish dependency results");
    check("second-project".equals(ProjectPlatform.current().label),"queued generation keeps the correct project platform");
    cycle.reset();
    check(!cycle.isAnalysisClasspathReady() && "unknown".equals(ProjectPlatform.current().label),
        "reset clears generation readiness and project platform");
    System.out.println("PASS: "+checks+" dependency-generation checks (real cycle, resolver/Android doubles; not on device)");
  }
}
