package almlk;
public class ProjectPlatform {
  private static volatile ProjectPlatform current=unknown();
  public final String label;
  private ProjectPlatform(String label) { this.label=label; }
  public static ProjectPlatform unknown() { return new ProjectPlatform("unknown"); }
  public static ProjectPlatform fromGradle(String text) { return new ProjectPlatform(text); }
  public static ProjectPlatform fromManifest(String text) { return unknown(); }
  public ProjectPlatform mergedWith(ProjectPlatform other) { return this; }
  public static void publish(ProjectPlatform p) { current=p; }
  public static ProjectPlatform current() { return current; }
}
