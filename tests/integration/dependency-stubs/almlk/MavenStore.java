package almlk;
import java.util.Collections;
import java.util.List;
import java.util.Map;
public class MavenStore {
  public static void publishInstalled(Map<String,List<String>> versions) { }
  public static Map<String,List<String>> installedSnapshot() { return Collections.emptyMap(); }
  public Map<String,List<String>> installedVersions() { return Collections.emptyMap(); }
}
