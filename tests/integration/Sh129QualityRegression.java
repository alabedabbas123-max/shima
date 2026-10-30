import almlk.AnalyzerBridge;
import almlk.AnalyzerErrorUnit;
import almlk.AnalyzerWorkspacePlan;
import almlk.ErrorBrowserPolicy;
import almlk.JavaDiagnosticCodes;
import almlk.LiveDiagnostics;
import almlk.ProjectPlatform;
import almlk.QuickFix;
import android.content.Context;
import android.os.Looper;
import com.almlkid.kay.ui.CodeLanguageTools;
import com.almlkid.kay.ui.ProjectDiagnostic;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Host/JNI integration tests against the actual sh129 bridge, not Android instrumentation. */
public final class Sh129QualityRegression {
  private static int checks;
  private static void check(boolean ok,String message) { checks++; if (!ok) throw new AssertionError(message); }
  private static void waitFor(BooleanSupplier done,long timeoutMs,String message) throws Exception {
    long end=System.nanoTime()+timeoutMs*1_000_000L;
    while (!done.getAsBoolean() && System.nanoTime()<end) Looper.pump(20);
    check(done.getAsBoolean(),message);
  }
  private static void write(Path file,String text) throws Exception {
    Files.createDirectories(file.getParent()); Files.write(file,text.getBytes(StandardCharsets.UTF_8));
  }
  private static ProjectDiagnostic find(String code,String ref) {
    for (ProjectDiagnostic d : AnalyzerErrorUnit.rows())
      if (d.diagnostic!=null && code.equals(d.diagnostic.code) && (ref==null || ref.equals(d.reference))) return d;
    return null;
  }
  private static int count(String code) {
    int n=0; for (ProjectDiagnostic d:AnalyzerErrorUnit.rows())
      if (d.diagnostic!=null && code.equals(d.diagnostic.code)) n++;
    return n;
  }
  private static AnalyzerBridge.ProjectHost host(File module) {
    return new AnalyzerBridge.ProjectHost() {
      public File moduleDir() { return module; }
      public String namespace() { return "example.app"; }
    };
  }

  private static void editorFindingsRemainPending(Path root) throws Exception {
    AnalyzerErrorUnit.clearAll();
    Path file=root.resolve("X.java");
    String original="class X { void f() { int x = 1 } }";
    write(file,original);
    String ref=file.toFile().getAbsolutePath();
    AtomicReference<String> text=new AtomicReference<>(original);
    AnalyzerBridge bridge=new AnalyzerBridge(new Context(), r -> ref.equals(r) ? text.get() : null, () -> {});
    bridge.focus("X.java",ref);
    waitFor(() -> find("MISSING_TOKEN",ref)!=null,8000,"initial actual JNI result arrives");
    ProjectDiagnostic initial=find("MISSING_TOKEN",ref);
    QuickFix oldFix=AnalyzerErrorUnit.fixAt(ref,initial.diagnostic.line);
    check(oldFix!=null && oldFix.planInsertion(original)!=null,"initial fix applies to its exact snapshot");
    AnalyzerErrorUnit.putUnavailable(ref,"X.java",original,new IllegalStateException("test failure"));
    check(find("PARSER_UNAVAILABLE",ref)!=null && find("MISSING_TOKEN",ref)!=null
        && LiveDiagnostics.isPending(find("MISSING_TOKEN",ref).diagnostic),
        "a parser failure retains the last source finding as pending");
    check(AnalyzerErrorUnit.fixAt(ref,initial.diagnostic.line)==null,"parser failure removes stale fixes");
    try (com.shima.analyzer.core.Analyzer limited=new com.shima.analyzer.core.Analyzer(
        com.shima.analyzer.core.AnalysisConfig.builder().maxDocumentChars(1).build());
         com.shima.analyzer.core.Analyzer regular=new com.shima.analyzer.core.Analyzer()) {
      com.shima.analyzer.core.SourceFile source=new com.shima.analyzer.core.SourceFile(ref,
          com.shima.analyzer.core.Language.JAVA,original);
      AnalyzerErrorUnit.put(ref,"X.java",original,limited.analyze(source));
      check(find("PARSER_UNAVAILABLE",ref)==null && find("DOCUMENT_TOO_LARGE",ref)!=null
          && LiveDiagnostics.isPending(find("MISSING_TOKEN",ref).diagnostic),
          "new partial reason replaces the old status without clearing pending source findings");
      String unicode="cl"+'\\'+"u0061ss X {}";
      com.shima.analyzer.core.SourceFile unparsed=new com.shima.analyzer.core.SourceFile(ref,
          com.shima.analyzer.core.Language.JAVA,unicode);
      AnalyzerErrorUnit.put(ref,"X.java",unicode,regular.analyze(unparsed));
      check(find("DOCUMENT_TOO_LARGE",ref)==null && find("JAVA_UNICODE_ESCAPES_UNSUPPORTED",ref)!=null
          && LiveDiagnostics.isPending(find("MISSING_TOKEN",ref).diagnostic),
          "changing partial reason cannot masquerade as a previous size limit");
      AnalyzerErrorUnit.put(ref,"X.java",original,regular.analyze(source));
      check(find("JAVA_UNICODE_ESCAPES_UNSUPPORTED",ref)==null && find("MISSING_TOKEN",ref)!=null
          && !LiveDiagnostics.isPending(find("MISSING_TOKEN",ref).diagnostic),
          "a complete pass replaces partial status with current findings");
    }
    String edited="// unrelated\n"+original;
    text.set(edited);
    bridge.markEditorEdited("X.java",ref);
    ProjectDiagnostic pending=find("MISSING_TOKEN",ref);
    check(pending!=null && LiveDiagnostics.isPending(pending.diagnostic),"unrelated typing retains the old error as pending");
    check(pending.diagnostic.line==initial.diagnostic.line+1,"pending finding follows an inserted line");
    check(AnalyzerErrorUnit.fixAt(ref,pending.diagnostic.line)==null,"pending finding exposes no old fix");
    check(oldFix.planInsertion(edited)==null,"even a previously obtained zero-length fix rejects a changed document");
    bridge.onEditorSignal("X.java",ref);
    waitFor(() -> {
      ProjectDiagnostic row=find("MISSING_TOKEN",ref);
      return row!=null && !LiveDiagnostics.isPending(row.diagnostic);
    },8000,"the same error is confirmed by a current analysis after an unrelated edit");
    ProjectDiagnostic still=find("MISSING_TOKEN",ref);
    QuickFix current=AnalyzerErrorUnit.fixAt(ref,still.diagnostic.line);
    check(current!=null,"current finding offers a new fix");
    QuickFix.Insertion insertion=current.planInsertion(edited);
    check(insertion!=null,"current fix is applicable");
    String repaired=insertion.applyTo(edited);
    text.set(repaired);
    bridge.markEditorEdited("X.java",ref);
    check(find("MISSING_TOKEN",ref)!=null && LiveDiagnostics.isPending(find("MISSING_TOKEN",ref).diagnostic),
        "even an edit inside the error waits for reanalysis");
    bridge.onEditorSignal("X.java",ref);
    waitFor(() -> find("MISSING_TOKEN",ref)==null,8000,"a current result confirms the real fix");
    bridge.shutdown(); AnalyzerErrorUnit.clearAll();
  }

  private static void planUtf8AndVersions(Path root) throws Exception {
    Path module=root.resolve("planner");
    String text=new String(new char[8191]).replace('\0','a')+"عربي 😀 end";
    Path f=module.resolve("src/main/java/p/Utf8.java"); write(f,text);
    AnalyzerWorkspacePlan.Entry entry=new AnalyzerWorkspacePlan.Entry("src/main/java/p/Utf8.java",
        AnalyzerWorkspacePlan.Kind.JAVA,Files.size(f));
    check(text.equals(AnalyzerWorkspacePlan.read(module.toFile(),entry).text),"UTF-8 split at 8 KiB is decoded without corruption");
    Path bad=module.resolve("src/main/java/p/Bad.java"); Files.write(bad,new byte[]{(byte)0xc3,(byte)0x28});
    AnalyzerWorkspacePlan.Entry invalid=new AnalyzerWorkspacePlan.Entry("src/main/java/p/Bad.java",AnalyzerWorkspacePlan.Kind.JAVA,2);
    check(!AnalyzerWorkspacePlan.read(module.toFile(),invalid).usable(),"invalid UTF-8 is reported instead of silently replaced");
    Path image=module.resolve("src/main/res/drawable/icon.png"); Files.createDirectories(image.getParent()); Files.write(image,new byte[]{1,2,3});
    check(AnalyzerWorkspacePlan.kindOf("src/main/res/drawable/icon.png")==AnalyzerWorkspacePlan.Kind.RESOURCE,
        "binary resources participate by name without decoding bytes as text");
    ProjectPlatform unknown=ProjectPlatform.unknown();
    check(unknown.analyzerMinApi()==0 && unknown.analyzerCompileApi(37)==0,
        "unknown project APIs never inherit the phone API");
    ProjectPlatform old=ProjectPlatform.of(19,25,25,"example");
    check(old.analyzerMinApi()==19 && old.analyzerCompileApi(37)==25,"minSdk below host min and selected compileSdk are preserved");
    check(!unknown.removedForThisProject(25),"unknown compileSdk does not invent removed API errors");
    ProjectPlatform flavor=ProjectPlatform.fromGradle("android { defaultConfig { minSdk rootProject.min } productFlavors { old { minSdk 19 } } }");
    check(flavor.minSdk==0,"an unresolved default does not guess a flavor's minSdk");
    ProjectPlatform expression=ProjectPlatform.fromGradle("android { defaultConfig { minSdk 19 + 2 } }");
    check(expression.minSdk==0,"a numeric expression is not mistaken for a literal SDK level");
    check(JavaDiagnosticCodes.severityFor("ui_diagnostic_method_never_used",CodeLanguageTools.Diagnostic.Severity.ERROR)
        ==CodeLanguageTools.Diagnostic.Severity.WARNING,"unused app finding is a warning, never an error");
    check(!ErrorBrowserPolicy.listsInPanel(1,"UNUSED_FIELD",null)
        && !ErrorBrowserPolicy.listsInPanel(1,"REDUNDANT","ui_diagnostic_method_never_used"),
        "unused warnings are excluded from the critical panel");
    check(ErrorBrowserPolicy.listsInPanel(1,"ANDROID_API_LEVEL",null),"other warnings remain visible in the panel");
    String before="a\nbad\nz", after="a\ngood\nz";
    CodeLanguageTools.Diagnostic oldDiagnostic=CodeLanguageTools.Diagnostic.restored(2,5,2,1,
        CodeLanguageTools.Diagnostic.Severity.ERROR,"TEST","broken",null,null)
        .withQuickFixes(java.util.Collections.singletonList(QuickFix.insertText(before,2,"x","", "")));
    almlk.LineDocument document=new almlk.LineDocument(); document.replace(0,0,after);
    almlk.LineOffsetTable offsets=almlk.LineOffsetTable.build(document);
    LiveDiagnostics.LineLocator locator=new LiveDiagnostics.LineLocator() {
      public int lineForOffset(int offset) { return offsets.lineFor(offset); }
      public int lineStartOffset(int line) { return offsets.startOf(line); }
    };
    List<CodeLanguageTools.Diagnostic> remapped=LiveDiagnostics.remapEdit(
        java.util.Collections.singletonList(oldDiagnostic),before,after,2,3,4,locator);
    check(remapped.size()==1 && remapped.get(0).offset==2 && remapped.get(0).end==6
        && LiveDiagnostics.isPending(remapped.get(0)),"an overlapping edit keeps an approximate pending anchor");
    check(remapped.get(0).quickFixes.isEmpty(),"remapping removes stale app-engine quick fixes");
  }

  private static void focusedEditorDependencyContext(Path root) throws Exception {
    AnalyzerErrorUnit.clearAll();
    Path module=root.resolve("editor-context"), file=module.resolve("src/main/java/demo/C.java");
    String text="package demo; import android.app.NotificationChannel; class C { NotificationChannel channel; }";
    write(file,text); String ref=file.toFile().getAbsolutePath();
    java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean(true);
    ProjectPlatform.publish(ProjectPlatform.of(21,35,35,"demo"));
    AnalyzerBridge bridge=new AnalyzerBridge(new Context(),r -> ref.equals(r) ? text : null,() -> {});
    bridge.setProjectHost(new AnalyzerBridge.ProjectHost() {
      public File moduleDir() { return module.toFile(); }
      public String namespace() { return "demo"; }
      public boolean classpathComplete() { return ready.get(); }
    });
    bridge.focus("C.java",ref);
    waitFor(() -> find("ANDROID_API_LEVEL",ref)!=null,8000,"focused editor uses the resolved project API");
    ready.set(false); bridge.projectContextChanged();
    ProjectDiagnostic pending=find("ANDROID_API_LEVEL",ref);
    check(pending!=null && LiveDiagnostics.isPending(pending.diagnostic),
        "dependency invalidation immediately marks a focused API finding pending");
    Looper.pump(1200);
    pending=find("ANDROID_API_LEVEL",ref);
    check(pending!=null && LiveDiagnostics.isPending(pending.diagnostic),
        "a complete syntax pass with unknown API does not falsely clear that finding");
    ProjectPlatform.publish(ProjectPlatform.of(28,35,35,"demo"));
    ready.set(true); bridge.projectContextChanged();
    waitFor(() -> find("ANDROID_API_LEVEL",ref)==null,8000,
        "a newer resolved API context confirms that the old finding no longer applies");
    bridge.shutdown(); AnalyzerErrorUnit.clearAll();
  }

  private static void projectPass(Path root,int files,boolean testStale) throws Exception {
    AnalyzerErrorUnit.clearAll();
    Path module=root.resolve("project"+files);
    String xml="<TextView xmlns:android=\"http://schemas.android.com/apk/res/android\" "
        +"android:layout_width=\"invalid\" android:layout_height=\"wrap_content\" android:text=\"@string/missing\"/>";
    for (int i=0;i<files;i++) write(module.resolve("src/main/res/layout/f"+i+".xml"),xml);
    write(module.resolve("src/main/AndroidManifest.xml"),"<manifest><application></manifest>");
    Path image=module.resolve("src/main/res/drawable/icon.png"); Files.createDirectories(image.getParent()); Files.write(image,new byte[]{1,2,3});
    AnalyzerWorkspacePlan.BatchPlan plan=AnalyzerWorkspacePlan.batches(module.toFile());
    int planned=0; boolean sawBinary=false;
    for (AnalyzerWorkspacePlan.Plan batch:plan.batches) {
      planned+=batch.includedCount();
      for (AnalyzerWorkspacePlan.Entry entry:batch.resources) if (entry.kind==AnalyzerWorkspacePlan.Kind.RESOURCE) sawBinary=true;
      check(batch.includedCount()<=AnalyzerWorkspacePlan.MAX_FILES,"each analysis batch fits its memory/file budget");
    }
    check(planned==files+2 && plan.skippedTooLarge==0 && sawBinary,"all XML, manifest and binary names are planned without a project cutoff");
    if (files>400) check(plan.batches.size()>1,"projects over 400 files are split into additional batches");
    ProjectPlatform.publish(ProjectPlatform.of(21,35,35,"example.app"));
    final Thread main=Thread.currentThread();
    final int[] wrongThread={0};
    AnalyzerBridge bridge=new AnalyzerBridge(new Context(),ref -> null,() -> { if (Thread.currentThread()!=main) wrongThread[0]++; });
    bridge.setProjectHost(host(module.toFile()));
    bridge.requestProjectPass();
    if (testStale) {
      bridge.projectFilesChanged();
      Looper.pump(500);
      check(count("XML_INVALID_DIMENSION")==0,"superseded project pass cannot publish stale rows");
    }
    waitFor(() -> count("XML_INVALID_DIMENSION")==files,20000,"actual project pass reaches every XML including beyond 64/400 files");
    check(count("XML_SYNTAX_ERROR")==1,"manifest is analyzed as an XML document");
    check(count("WORKSPACE_INCOMPLETE")==1,"incomplete resource coverage is one aggregated project message");
    check(count("ANALYSIS_LIMIT")==0,"resource incompleteness is not mislabeled as a time/size limit");
    check(AnalyzerErrorUnit.storedFileCount()>=files+1,"diagnostics are not silently evicted after 64 files");
    check(wrongThread[0]==0,"project results are committed and published on the main loop");
    bridge.shutdown(); AnalyzerErrorUnit.clearAll();
  }

  private static void projectMembersAndIncompleteIndex(Path root) throws Exception {
    AnalyzerErrorUnit.clearAll();
    Path module=root.resolve("member-project"), base=module.resolve("src/main/java/demo/Base.java");
    Path helper=module.resolve("src/main/java/demo/Helper.java"), editor=module.resolve("src/main/java/demo/Dictionary.java");
    write(base,"package demo; class Base { public String inherited(){return \"ok\";} }");
    write(helper,"package demo; class Helper extends Base { }");
    String source="package demo; class Dictionary { Helper clibs; void f(){ "
        +"this.clibs.inherited(); this.clibs.getKay(); this.clibs.setKay(\"x\"); int x=this.clibs.absent; } }";
    write(editor,source);
    String ref=editor.toFile().getAbsolutePath();
    java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean(true);
    AnalyzerBridge bridge=new AnalyzerBridge(new Context(),r -> null,() -> {});
    bridge.setProjectHost(new AnalyzerBridge.ProjectHost() {
      public File moduleDir() { return module.toFile(); }
      public String namespace() { return "demo"; }
      public boolean classpathComplete() { return ready.get(); }
    });
    bridge.requestProjectPass();
    waitFor(() -> count("UNRESOLVED_METHOD")==2 && count("UNRESOLVED_FIELD")==1,8000,
        "sh129 workspace bridge reports missing members of known source classes");
    ProjectDiagnostic get=find("UNRESOLVED_METHOD",ref);
    check(get!=null && !LiveDiagnostics.isPending(get.diagnostic),"first known member finding is current");
    ready.set(false);
    bridge.projectContextChanged();
    waitFor(() -> {
      ProjectDiagnostic row=find("UNRESOLVED_METHOD",ref);
      return row!=null && LiveDiagnostics.isPending(row.diagnostic);
    },9000,"when dependency resolution becomes incomplete the old finding stays pending");
    check(AnalyzerErrorUnit.fixAt(ref,get.diagnostic.line)==null,"partial member finding exposes no stale fix");
    write(helper,"package demo; class Helper extends Base { public void getKay(){} public void setKay(String s){} public int absent; }");
    ready.set(true);
    bridge.projectContextChanged();
    waitFor(() -> count("UNRESOLVED_METHOD")==0 && count("UNRESOLVED_FIELD")==0,9000,
        "completed project pass confirms actual member repairs and clears pending rows");
    Path directory=module.resolve("src/main/java/temporarily-unreadable");
    Path tracked=directory.resolve("Tracked.java");
    write(tracked,"class Tracked { void f(){ int value=1 } }");
    String trackedRef=tracked.toFile().getAbsolutePath();
    bridge.projectFilesChanged();
    waitFor(() -> find("MISSING_TOKEN",trackedRef)!=null,9000,"new project source gets a syntax finding");
    Files.delete(tracked); Files.delete(directory);
    Path external=root.resolve("outside-project"); Files.createDirectories(external);
    Files.createSymbolicLink(directory,external);
    bridge.projectFilesChanged();
    waitFor(() -> {
      ProjectDiagnostic old=find("MISSING_TOKEN",trackedRef);
      return old!=null && LiveDiagnostics.isPending(old.diagnostic);
    },9000,"an incomplete directory scan retains unseen rows as pending rather than asserting deletion");
    Files.delete(directory);
    bridge.projectFilesChanged();
    waitFor(() -> find("MISSING_TOKEN",trackedRef)==null,9000,
        "a subsequent complete scan confirms deletion and removes the obsolete row");
    bridge.shutdown(); AnalyzerErrorUnit.clearAll();
  }

  public static void main(String[] args) throws Exception {
    Looper.getMainLooper();
    Path root=Files.createTempDirectory("shima-quality-integration-");
    try {
      planUtf8AndVersions(root);
      editorFindingsRemainPending(root);
      focusedEditorDependencyContext(root);
      projectMembersAndIncompleteIndex(root);
      projectPass(root,70,true);
      projectPass(root,405,false);
      System.out.println("PASS: "+checks+" sh129 quality checks (real JNI + host main-loop doubles; not an Android build)");
    } finally {
      try (java.util.stream.Stream<Path> paths=Files.walk(root)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
      }
    }
  }
}
