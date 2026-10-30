package almlk;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
public class MavenResolver {
  public static final Object OFFLINE=new Object();
  public static class Repo { }
  public static class Request { }
  public static class Failure { public static final int NOT_FOUND=1; public int type; }
  public static class Result {
    public boolean cancelled;
    public String label="offline";
    public final List<Failure> failures=new ArrayList<>();
  }
  public MavenResolver(MavenStore store,List<Repo> repos,Object downloader) { }
  public MavenResolver refreshMetadata(boolean value) { return this; }
  public Result resolve(List<Request> requests,boolean refresh,Object progress,Object cancel) { return new Result(); }
  public static List<Repo> repositories(GradleBuildModel model,File local,List<String> extra) { return Collections.emptyList(); }
  public static List<Request> requests(GradleBuildModel model) { return Collections.emptyList(); }
}
