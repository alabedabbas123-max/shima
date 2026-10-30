package almlk;
import android.app.Activity;
import java.util.Collections;
import java.util.List;
public class AppSettings {
  public static MavenStore mavenStore(Activity activity) { return new MavenStore(); }
  public static List<String> extraRepositories(Activity activity) { return Collections.emptyList(); }
}
