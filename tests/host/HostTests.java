import android.content.Context;
import android.os.Looper;
import android.widget.EditText;
import com.shima.analyzer.AnalysisHandle;
import com.shima.analyzer.CodeAnalyzer;
import com.shima.analyzer.core.*;
import com.shima.analyzer.editor.EditorAdapter;
import com.shima.analyzer.dependencies.*;
import java.io.*;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the actual JNI backend on Linux and Android-adapter logic against small test doubles. */
public final class HostTests {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static SourceFile javaFile(String id, String text) { return new SourceFile(id, Language.JAVA, text); }
    private static SourceFile kotlinFile(String id, String text) { return new SourceFile(id, Language.KOTLIN, text); }
    private static Diagnostic find(AnalysisResult result, String code) {
        for (Diagnostic diagnostic : result.getDiagnostics()) if (diagnostic.getCode().equals(code)) return diagnostic;
        return null;
    }
    private static String signature(AnalysisResult result) {
        StringBuilder s=new StringBuilder(result.getStatus().toString());
        for (Diagnostic d : result.getDiagnostics()) {
            s.append('|').append(d.getCode()).append('@').append(d.getRange().getStartOffset()).append(':').append(d.getRange().getEndOffset());
            for (QuickFix f : d.getQuickFixes()) s.append('/').append(f.getTitle());
        }
        return s.toString();
    }
    private static void syntaxAndFixes() {
        try (Analyzer engine=new Analyzer()) {
            String src="package demo;\r\nclass Main {\r\n  List<String> list;\r\n}";
            AnalysisResult r=engine.analyze(javaFile("Main.java",src));
            check(r.getStatus()==AnalysisResult.Status.COMPLETE, "normal parse completes");
            check(r.getParseMode()==AnalysisResult.ParseMode.FULL, "first parse is full");
            Diagnostic missing=find(r,"POSSIBLE_MISSING_IMPORT");
            check(missing!=null, "known missing import suggested");
            check(missing.getRange().getStartLine()==3 && missing.getRange().getStartColumn()==3, "1-based line and column");
            check(missing.getQuickFixes().size()==1, "List has one import candidate");
            QuickFix fix=missing.getQuickFixes().get(0);
            String fixed=fix.applyTo(src);
            check(fixed.contains("import java.util.List;\r\n"), "import fix respects CRLF");
            check(!fix.isApplicableTo(src+" "), "stale import fix rejected");
            try { fix.applyTo(src+" "); throw new AssertionError("stale fix applied"); }
            catch (IllegalStateException expected) { checks++; }
            AnalysisResult after=engine.analyze(javaFile("Main.java",fixed));
            check(after.getParseMode()==AnalysisResult.ParseMode.INCREMENTAL, "changed file uses incremental tree");
            check(find(after,"POSSIBLE_MISSING_IMPORT")==null, "import resolves after applying fix");
            check(engine.analyze(javaFile("Main.java",fixed)).getParseMode()==AnalysisResult.ParseMode.CACHED, "identical source reuses cache");
            String bad="class X { void f() { int x = 1 } }";
            Diagnostic token=find(engine.analyze(javaFile("X.java",bad)),"MISSING_TOKEN");
            check(token!=null && !token.getQuickFixes().isEmpty(), "missing token has fix");
            String corrected=token.getQuickFixes().get(0).applyTo(bad);
            check(find(engine.analyze(javaFile("X.java",corrected)),"MISSING_TOKEN")==null, "token fix reparses");
            check(find(engine.analyze(javaFile("X.java","class X { int n = \"bad\"; }")),"INCOMPATIBLE_LITERAL")!=null, "Java literal mismatch");
            check(find(engine.analyze(kotlinFile("X.kt","class X { val n: Int = \"bad\" }")),"INCOMPATIBLE_LITERAL")!=null, "Kotlin literal mismatch");
            check(find(engine.analyze(kotlinFile("X.kt","class X { val xs: List<String> = emptyList() }")),"POSSIBLE_MISSING_IMPORT")==null, "Kotlin default imports respected");
            check(find(engine.analyze(javaFile("generic.java","class A<List> { List value; }")),"POSSIBLE_MISSING_IMPORT")==null, "type parameters are not imports");
            check(find(engine.analyze(javaFile("generic-annotated.java","class A<@Ann List> { List value; }")),"POSSIBLE_MISSING_IMPORT")==null, "annotated Java type parameters are not imports");
            check(find(engine.analyze(javaFile("wild.java","import java.util.*; class A { List<String> a; }")),"POSSIBLE_MISSING_IMPORT")==null, "wildcard import avoids false hint");
            String emoji="// 😀\nclass A { List<String> x; }";
            Diagnostic unicode=find(engine.analyze(javaFile("unicode.java",emoji)),"POSSIBLE_MISSING_IMPORT");
            check(unicode.getRange().getStartOffset()==emoji.indexOf("List"), "offset uses UTF-16 code units");
            String unpaired="// \uD800\nclass A { List<String> x; }";
            QuickFix utf= find(engine.analyze(javaFile("unpaired.java",unpaired)),"POSSIBLE_MISSING_IMPORT").getQuickFixes().get(0);
            check(!utf.isApplicableTo(unpaired.replace('\uD800','\uD801')), "fingerprint preserves unpaired surrogates");
        }
    }
    private static void androidCompatibility() {
        String src="import android.app.NotificationChannel; import android.app.ProgressDialog; import android.os.Trace; " +
                "class C { NotificationChannel c; ProgressDialog p; void f() { Trace.beginAsyncSection(\"x\", 1); } }";
        try (Analyzer engine=new Analyzer(AnalysisConfig.builder().minApi(21).compileApi(35).build())) {
            AnalysisResult r=engine.analyze(javaFile("C.java",src));
            int api=0, deprecated=0;
            for (Diagnostic d : r.getDiagnostics()) { if(d.getCode().equals("ANDROID_API_LEVEL")) api++; if(d.getCode().equals("ANDROID_DEPRECATED")) deprecated++; }
            check(api==2, "class and static method API checks");
            check(deprecated==1, "deprecation check");
            String fully="class C { android.app.NotificationChannel c; }";
            check(find(engine.analyze(javaFile("full.java",fully)),"ANDROID_API_LEVEL")!=null,"qualified Android type");
            String kotlin="import android.os.Trace\nfun go() { Trace.beginAsyncSection(\"x\", 1) }";
            check(find(engine.analyze(kotlinFile("trace.kt",kotlin)),"ANDROID_API_LEVEL")!=null, "Kotlin static method API check");
            String kotlinType="import android.app.NotificationChannel\nclass C { val channel: NotificationChannel? = null }";
            check(find(engine.analyze(kotlinFile("channel.kt",kotlinType)),"ANDROID_API_LEVEL")!=null, "Kotlin type API check");
        }
        try (Analyzer engine=new Analyzer()) {
            AnalysisResult unknown=engine.analyze(javaFile("unknown-levels.java",src));
            check(find(unknown,"ANDROID_API_LEVEL")==null && find(unknown,"ANDROID_DEPRECATED")==null,
                    "unknown project APIs do not invent device/default levels");
            check(find(engine.analyze(javaFile("unknown-import.java","class C { NotificationChannel c; }")),"POSSIBLE_MISSING_IMPORT")==null,
                    "unknown compile SDK does not suggest a hard-coded platform import");
        }
        try (Analyzer engine=new Analyzer(AnalysisConfig.builder().minApi(19).compileApi(25).build())) {
            check(find(engine.analyze(javaFile("old-import.java","class C { NotificationChannel c; }")),"POSSIBLE_MISSING_IMPORT")==null,
                    "do not suggest an Android class introduced after compileSdk");
            check(find(engine.analyze(javaFile("old-api.java","import android.app.NotificationChannel; class C { NotificationChannel c; }")),"ANDROID_API_LEVEL")!=null,
                    "project minSdk below the host app's minSdk is supported");
        }
        try (Analyzer engine=new Analyzer(AnalysisConfig.builder().compileApi(26).build())) {
            check(find(engine.analyze(javaFile("new-import.java","class C { NotificationChannel c; }")),"POSSIBLE_MISSING_IMPORT")!=null,
                    "a known compile SDK can suggest an available platform type");
        }
        AnalysisConfig config=AnalysisConfig.builder().minApi(30).compileApi(35).build();
        try (Analyzer engine=new Analyzer(config)) {
            AnalysisResult r=engine.analyze(javaFile("C.java",src));
            check(find(r,"ANDROID_API_LEVEL")==null, "minApi suppresses older API warning");
            check(find(r,"ANDROID_DEPRECATED")!=null, "deprecation is independent of minApi");
        }
        // The method name below is deliberately synthetic: parser rules do not verify a classpath.
        String extended="import android.window.SplashScreen; import android.os.Trace; " +
                "class Extended { SplashScreen s; void f() { Trace.futureWork(); } }";
        AnalysisConfig.Builder catalogBuilder=AnalysisConfig.builder().minApi(21).compileApi(35)
                .addAndroidApiType("android.window.SplashScreen",31,0)
                .addAndroidApiStaticMethod("android.os.Trace","futureWork",33,35);
        AnalysisConfig extendedConfig=catalogBuilder.build();
        catalogBuilder.addAndroidApiType("android.window.SplashScreen",21,0); // must not mutate the built config
        try (Analyzer engine=new Analyzer()) {
            AnalysisResult unknown=engine.analyze(javaFile("extended.java",extended));
            check(find(unknown,"ANDROID_API_LEVEL")==null && find(unknown,"ANDROID_DEPRECATED")==null,
                    "unknown platform APIs make no compatibility claim");
        }
        try (Analyzer engine=new Analyzer(extendedConfig)) {
            AnalysisResult known=engine.analyze(javaFile("extended.java",extended));
            int api=0, deprecated=0;
            for (Diagnostic d : known.getDiagnostics()) { if (d.getCode().equals("ANDROID_API_LEVEL")) api++; if (d.getCode().equals("ANDROID_DEPRECATED")) deprecated++; }
            check(api==2 && deprecated==1, "host metadata enables class and static method checks");
            Diagnostic importHint=find(engine.analyze(javaFile("import.java","class C { SplashScreen s; }")),"POSSIBLE_MISSING_IMPORT");
            check(importHint!=null && importHint.getQuickFixes().get(0).getTitle().contains("android.window.SplashScreen"),
                    "host platform types are also import candidates");
        }
        try (Analyzer engine=new Analyzer(AnalysisConfig.builder().compileApi(35).addAndroidApiType("android.app.ProgressDialog",1,0).build())) {
            check(find(engine.analyze(javaFile("override.java","import android.app.ProgressDialog; class C { ProgressDialog p; }")),"ANDROID_DEPRECATED")==null,
                    "explicit host metadata overrides built-in entries");
        }
        try { AnalysisConfig.builder().addAndroidApiType("not.android.Type",1,0); throw new AssertionError("non-platform owner accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { AnalysisConfig.builder().addAndroidApiType("android.a.Type",0,0); throw new AssertionError("invalid since API accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { AnalysisConfig.builder().addAndroidApiType("android.a.Type",31,29); throw new AssertionError("invalid deprecated API accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { AnalysisConfig.builder().addAndroidApiStaticMethod("android.a.Type","bad-name",21,0); throw new AssertionError("invalid method accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void projectAndBudgets() {
        SourceFile widget=javaFile("a/Widget.java","package a; public class Widget {}"),
                   consumer=javaFile("b/Main.java","package b; class Main { Widget widget; }");
        try (Analyzer engine=new Analyzer()) {
            ProjectResult initial=engine.analyzeProject(Arrays.asList(widget,consumer));
            Diagnostic suggestion=find(initial.getFiles().get("b/Main.java"),"POSSIBLE_MISSING_IMPORT");
            check(suggestion!=null && suggestion.getQuickFixes().get(0).getTitle().contains("a.Widget"), "project symbol indexed");
            SourceFile samePackage=javaFile("b/Widget.java","package b; class Widget {}" );
            ProjectResult changed=engine.analyzeProject(Arrays.asList(widget,consumer,samePackage));
            check(changed.getFiles().get("b/Main.java").getParseMode()==AnalysisResult.ParseMode.CACHED, "unchanged file tree cached");
            check(find(changed.getFiles().get("b/Main.java"),"POSSIBLE_MISSING_IMPORT")==null, "changed index invalidates semantic results");
            ProjectResult removed=engine.analyzeProject(Arrays.asList(widget,consumer));
            check(find(removed.getFiles().get("b/Main.java"),"POSSIBLE_MISSING_IMPORT")!=null, "deleted declaration updates suggestions");
            ProjectResult incomplete=engine.analyzeProject(Arrays.asList(consumer,javaFile("Broken.java", "class Broken { void f( }")));
            check(!incomplete.isComplete(), "syntax failure makes project index incomplete");
            check(find(incomplete.getFiles().get("b/Main.java"),"PROJECT_INDEX_INCOMPLETE")!=null, "incomplete project index explained");
            check(find(incomplete.getFiles().get("b/Main.java"),"POSSIBLE_MISSING_IMPORT")==null, "incomplete index cannot make import claims");
            try { engine.analyzeProject(Arrays.asList(widget,widget)); throw new AssertionError("duplicate IDs accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
            engine.clearCache();
            check(engine.analyze(consumer).getParseMode()==AnalysisResult.ParseMode.FULL, "clearCache releases native tree");
        }
        try (Analyzer engine=new Analyzer(AnalysisConfig.builder().maxCachedFiles(1).build())) {
            engine.analyze(javaFile("first.java", "class First {}"));
            engine.analyze(javaFile("second.java", "class Second {}"));
            check(engine.analyze(javaFile("first.java", "class First {}" )).getParseMode()==AnalysisResult.ParseMode.FULL, "LRU eviction releases the old tree");
        }
        try (Analyzer engine=new Analyzer(AnalysisConfig.builder().maxDiagnostics(1).build())) {
            AnalysisResult truncated=engine.analyze(javaFile("Limit.java", "class X { int x = \"bad\"; boolean y = 4; }"));
            check(truncated.getStatus()==AnalysisResult.Status.PARTIAL && truncated.getDiagnostics().size()==1, "diagnostic truncation is partial");
            check(truncated.getIncompleteReasons().contains("DIAGNOSTIC_LIMIT"), "diagnostic truncation has its actual cause");
        }
        try (Analyzer engine=new Analyzer(AnalysisConfig.builder().addSymbol("other.Widget").build())) {
            String kotlin="class Box<out Widget>(val value: Widget)";
            check(find(engine.analyze(kotlinFile("variant.kt",kotlin)),"POSSIBLE_MISSING_IMPORT")==null,
                    "variant Kotlin type parameters are not imports");
        }
        try (Analyzer engine=new Analyzer()) {
            SourceFile fromKotlin=kotlinFile("a/Widget.kt", "package a\nclass Widget");
            SourceFile javaUse=javaFile("b/JavaUse.java", "package b; class JavaUse { Widget value; }");
            SourceFile fromJava=javaFile("c/Thing.java", "package c; public class Thing {}");
            SourceFile kotlinUse=kotlinFile("d/KotlinUse.kt", "package d\nclass KotlinUse { val thing: Thing? = null }");
            ProjectResult project=engine.analyzeProject(Arrays.asList(fromKotlin, javaUse, fromJava, kotlinUse));
            Diagnostic javaSuggestion=find(project.getFiles().get("b/JavaUse.java"),"POSSIBLE_MISSING_IMPORT");
            Diagnostic kotlinSuggestion=find(project.getFiles().get("d/KotlinUse.kt"),"POSSIBLE_MISSING_IMPORT");
            check(javaSuggestion!=null && javaSuggestion.getQuickFixes().get(0).getTitle().contains("a.Widget"),
                    "Kotlin top-level class indexed for Java");
            check(kotlinSuggestion!=null && kotlinSuggestion.getQuickFixes().get(0).getTitle().contains("c.Thing"),
                    "Java top-level class indexed for Kotlin");
        }
        AnalysisConfig.Builder manySymbols=AnalysisConfig.builder();
        for (int i=0; i<20; i++) manySymbols.addSymbol("p"+i+".Widget");
        try (Analyzer engine=new Analyzer(manySymbols.build())) {
            Diagnostic many=find(engine.analyze(javaFile("Many.java","class Many { Widget w; }")), "POSSIBLE_MISSING_IMPORT");
            check(many!=null && many.getQuickFixes().size()==8, "ambiguous import fixes are bounded");
        }
        AnalysisConfig limits=AnalysisConfig.builder().maxDocumentChars(30).maxEvents(3).maxProjectChars(60).maxDiagnostics(2).build();
        try (Analyzer engine=new Analyzer(limits)) {
            AnalysisResult large=engine.analyze(javaFile("large",new String(new char[1000]).replace('\0','\n')));
            check(large.getStatus()==AnalysisResult.Status.PARTIAL && find(large,"DOCUMENT_TOO_LARGE")!=null, "oversize is explicit, not clean");
            String largeEscaped="class "+'\\'+"u0041 "+new String(new char[40]).replace('\0',' ');
            AnalysisResult rejected=engine.analyze(javaFile("large-escaped",largeEscaped));
            check(rejected.getIncompleteReasons().contains("DOCUMENT_TOO_LARGE")
                    && !rejected.getIncompleteReasons().contains("JAVA_UNICODE_ESCAPES_UNSUPPORTED"),
                    "document budget is checked before Unicode scanning");
            AnalysisResult capped=engine.analyze(javaFile("small","class A{List a;Map b;Set c;}"));
            check(capped.getStatus()==AnalysisResult.Status.PARTIAL && find(capped,"ANALYSIS_LIMIT")!=null, "event budget is explicit");
            try { engine.analyzeProject(Arrays.asList(javaFile("a",new String(new char[40])),javaFile("b",new String(new char[40])))); throw new AssertionError("project budget accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
    }
    private static void incrementalMatchesFresh() {
        Random random=new Random(2026);
        String[] inserts={"a", " ", "\n", ";", "{", "}", "😀", "/*x*/", "\r\n", "\uD800"};
        for (Language language : new Language[]{Language.JAVA, Language.KOTLIN}) {
            String text=language==Language.JAVA ? "package p;\nclass X { int x = 1; List<String> xs; }\n" : "package p\nclass X { val x: Int = 1\n fun f() = x\n}\n";
            try (Analyzer incremental=new Analyzer()) {
                for (int round=0; round<100; round++) {
                    int start=random.nextInt(text.length()+1), end=Math.min(text.length(),start+random.nextInt(Math.min(3,text.length()-start)+1));
                    text=text.substring(0,start)+inserts[random.nextInt(inserts.length)]+text.substring(end);
                    SourceFile file=new SourceFile("mutation",language,text);
                    AnalysisResult inc=incremental.analyze(file);
                    try (Analyzer fresh=new Analyzer()) {
                        AnalysisResult full=fresh.analyze(file);
                        check(signature(inc).equals(signature(full)), language+" incremental differs from full at edit "+round+"\n"+signature(inc)+"\n"+signature(full));
                    }
                }
            }
        }
    }
    private static boolean splitsSurrogatePair(String text, int offset) {
        return offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset-1)) && Character.isLowSurrogate(text.charAt(offset));
    }
    private static void incrementalUnicodeBoundaries() throws Exception {
        // An edit can create a pair on only one side of the old/new diff.
        String[][] changes = {
            {"// a\uD83Dx\nclass X {}", "// a\uD83D\uDE00x\nclass X {}"},
            {"// a\uDE00x\nclass X {}", "// a\uD83D\uDE00x\nclass X {}"},
            {"// a\uD83D\uDE00x\nclass X {}", "// a\uD83Dx\nclass X {}"},
            {"// a\uD83D\uDE00x\nclass X {}", "// a\uDE00x\nclass X {}"}
        };
        Class<?> editClass=Class.forName("com.shima.analyzer.core.Analyzer$Edit");
        java.lang.reflect.Method between=editClass.getDeclaredMethod("between", String.class, String.class);
        between.setAccessible(true);
        java.lang.reflect.Field start=editClass.getDeclaredField("start"), oldEnd=editClass.getDeclaredField("oldEnd"), newEnd=editClass.getDeclaredField("newEnd");
        start.setAccessible(true); oldEnd.setAccessible(true); newEnd.setAccessible(true);
        for (String[] change : changes) {
            Object edit=between.invoke(null, change[0], change[1]);
            int first=start.getInt(edit), lastOld=oldEnd.getInt(edit), lastNew=newEnd.getInt(edit);
            check(!splitsSurrogatePair(change[0], first) && !splitsSurrogatePair(change[1], first), "incremental start never splits UTF-16 pair");
            check(!splitsSurrogatePair(change[0], lastOld) && !splitsSurrogatePair(change[1], lastNew), "incremental end never splits UTF-16 pair");
            try (Analyzer inc=new Analyzer(); Analyzer fresh=new Analyzer()) {
                inc.analyze(javaFile("unicode", change[0]));
                AnalysisResult updated=inc.analyze(javaFile("unicode", change[1]));
                check(signature(updated).equals(signature(fresh.analyze(javaFile("unicode", change[1])))), "Unicode edit matches full parse");
            }
        }
    }
    private static int countCode(AnalysisResult result, String code) {
        int count=0; for (Diagnostic diagnostic : result.getDiagnostics()) if (code.equals(diagnostic.getCode())) count++; return count;
    }
    private static void workspaceResourcesAndXml() {
        String java="package demo; class Main { int good=R.id.present; int bad=R.id.key; }";
        String layout="<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:layout_width=\"match_parent\" android:layout_height=\"wrap_content\"><View android:id=\"@+id/present\" /></LinearLayout>";
        WorkspaceSnapshot ws=WorkspaceSnapshot.builder().namespace("demo").resourceIndexComplete(true)
                .addSource(javaFile("Main.java",java)).addResource(new ResourceFile("res/layout/main.xml",layout)).build();
        try (Analyzer engine=new Analyzer()) {
            WorkspaceResult result=engine.analyzeWorkspace(ws);
            Diagnostic missing=find(result.getSources().get("Main.java"),"MISSING_RESOURCE");
            check(result.isResourceIndexComplete() && missing!=null,"missing resource detected against explicit complete index");
            check(java.substring(missing.getRange().getStartOffset(),missing.getRange().getEndOffset()).equals("R.id.key"),"resource diagnostic spans R.id.key");
            check(missing.getWorkspaceFixes().size()==1,"missing id offers cross-file fix");
            WorkspaceFix fix=missing.getWorkspaceFixes().get(0);
            Map<String,String> current=new HashMap<>(); current.put("Main.java",java); current.put("res/layout/main.xml",layout);
            String ids=fix.preview(current).get("res/values/ids.xml");
            check(ids.contains("<item type=\"id\" name=\"key\" />"),"id fix creates values resource");
            current.put("Main.java",java+" ");
            try { fix.preview(current); throw new AssertionError("stale workspace fix applied"); }
            catch (IllegalStateException expected) { checks++; }
            WorkspaceSnapshot fixed=WorkspaceSnapshot.builder().namespace("demo").resourceIndexComplete(true).addSource(javaFile("Main.java",java))
                    .addResource(new ResourceFile("res/layout/main.xml",layout)).addResource(new ResourceFile("res/values/ids.xml",ids)).build();
            check(find(engine.analyzeWorkspace(fixed).getSources().get("Main.java"),"MISSING_RESOURCE")==null,"added id resolves reference");
            WorkspaceSnapshot incomplete=WorkspaceSnapshot.builder().addSource(javaFile("Main.java",java)).build();
            AnalysisResult incompleteResult=engine.analyzeWorkspace(incomplete).getSources().get("Main.java");
            check(find(incompleteResult,"MISSING_RESOURCE")==null,"incomplete index never claims missing resource");
            check(incompleteResult.getStatus()==AnalysisResult.Status.PARTIAL && find(incompleteResult,"RESOURCE_INDEX_INCOMPLETE")!=null,"editor can see incomplete resource index");
            check(incompleteResult.getIncompleteReasons().contains("RESOURCE_INDEX_INCOMPLETE")
                    && !incompleteResult.getIncompleteReasons().contains("ANALYSIS_TIMEOUT"),
                    "an incomplete resource index is not falsely reported as a timeout");
            String shadow="class C { Object R; int x=R.id.key; }";
            WorkspaceSnapshot shadowed=WorkspaceSnapshot.builder().resourceIndexComplete(true).addSource(javaFile("C.java",shadow)).build();
            check(find(engine.analyzeWorkspace(shadowed).getSources().get("C.java"),"MISSING_RESOURCE")==null,"local R shadow suppresses resource claims");
            String comment="class C { String s=\"R.id.fake\"; /* R.id.fake */ }";
            WorkspaceSnapshot commented=WorkspaceSnapshot.builder().resourceIndexComplete(true).addSource(javaFile("C.java",comment)).build();
            check(find(engine.analyzeWorkspace(commented).getSources().get("C.java"),"MISSING_RESOURCE")==null,"resource-like text and comments are ignored");
            String kt="package demo\nclass C { val x = R.id.missing }";
            WorkspaceSnapshot kotlin=WorkspaceSnapshot.builder().namespace("demo").resourceIndexComplete(true).addSource(kotlinFile("C.kt",kt)).build();
            check(find(engine.analyzeWorkspace(kotlin).getSources().get("C.kt"),"MISSING_RESOURCE")!=null,"Kotlin R reference detected");
            AnalysisResult bad=engine.analyze(new SourceFile("res/layout/bad.xml",Language.XML,"<a><b></a>"));
            check(find(bad,"XML_SYNTAX_ERROR")!=null,"malformed XML detected");
            String invalid="<View xmlns:android=\"http://schemas.android.com/apk/res/android\" android:layout_width=\"not-a-size\" />";
            AnalysisResult dimension=engine.analyze(new SourceFile("res/layout/a.xml",Language.XML,invalid));
            Diagnostic dim=find(dimension,"XML_INVALID_DIMENSION");
            check(dim!=null && invalid.substring(dim.getRange().getStartOffset(),dim.getRange().getEndOffset()).equals("not-a-size"),"invalid dimension has exact attribute range");
            check(find(engine.analyze(new SourceFile("res/layout/evil.xml",Language.XML,"<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>")),"XML_DTD_FORBIDDEN")!=null,"DTD rejected");
        }
        try (Analyzer limited=new Analyzer(AnalysisConfig.builder().maxDiagnostics(1).build())) {
            WorkspaceSnapshot many=WorkspaceSnapshot.builder().resourceIndexComplete(true).addSource(javaFile("C.java","class C { int a=R.id.a; int b=R.id.b; }")).build();
            AnalysisResult truncated=limited.analyzeWorkspace(many).getSources().get("C.java");
            check(truncated.getStatus()==AnalysisResult.Status.PARTIAL && truncated.getDiagnostics().size()==1,"workspace diagnostic cap marks truncation partial");
        }
    }
    private static void workspaceJavaInspections() throws Exception {
        try (Analyzer engine=new Analyzer()) {
            String source="class C { private int count; private int count; private void unused(){} void f(){ int x=1; } public void calc(int a){} public void calc(int b){} public void calc(String c){} }";
            WorkspaceSnapshot ws=WorkspaceSnapshot.builder().addSource(javaFile("C.java",source)).build();
            AnalysisResult result=engine.analyzeWorkspace(ws).getSources().get("C.java");
            check(countCode(result,"DUPLICATE_FIELD")==2,"duplicate field reported at both declarations");
            check(countCode(result,"DUPLICATE_METHOD")==2,"duplicate method signature reported at both declarations, overload excluded");
            String returns="class D { int f(){return 1;} String f(){return null;} }";
            AnalysisResult duplicateReturns=engine.analyzeWorkspace(WorkspaceSnapshot.builder().addSource(javaFile("D.java",returns)).build()).getSources().get("D.java");
            check(countCode(duplicateReturns,"DUPLICATE_METHOD")==2,"return type does not distinguish Java method signatures");
            String anonymous="class A { void f(){ Object x=new Object(){ void run(){ String s=getStart(); } }; } }";
            AnalysisResult anonymousResult=engine.analyzeWorkspace(WorkspaceSnapshot.builder().addSource(javaFile("A.java",anonymous)).build()).getSources().get("A.java");
            check(find(anonymousResult,"UNRESOLVED_METHOD")==null,"anonymous class bodies are not attributed to outer class");
            Diagnostic unused=find(result,"UNUSED_METHOD");
            check(unused!=null && unused.getSeverity()==Diagnostic.Severity.WARNING,"unused method is warning, not error");
            check(find(result,"UNUSED_LOCAL")!=null,"unused local is warning");
            String a="class A { void f(){ String str=getStart(); } }";
            result=engine.analyzeWorkspace(WorkspaceSnapshot.builder().addSource(javaFile("A.java",a)).build()).getSources().get("A.java");
            Diagnostic missing=find(result,"UNRESOLVED_METHOD");
            check(missing!=null && !missing.getQuickFixes().isEmpty(),"missing local call offers fix");
            String generated=missing.getQuickFixes().get(0).applyTo(a);
            check(generated.contains("private String getStart() { return null; }"),"return type inferred from local declaration");
            writeFixture("local/A.java",generated);
            String voidSource="class A { void f(){ getStart(); } }";
            result=engine.analyzeWorkspace(WorkspaceSnapshot.builder().addSource(javaFile("A.java",voidSource)).build()).getSources().get("A.java");
            check(find(result,"UNRESOLVED_METHOD").getQuickFixes().get(0).applyTo(voidSource).contains("private void getStart() {}"),"standalone call produces void stub");
            String receiver="class Abed { private String name; } class Use { void f(){ Abed abd = new Abed(); String n = abd.getName(); } }";
            result=engine.analyzeWorkspace(WorkspaceSnapshot.builder().addSource(javaFile("Use.java",receiver)).build()).getSources().get("Use.java");
            Diagnostic getName=find(result,"UNRESOLVED_METHOD");
            check(getName!=null && getName.getQuickFixes().get(0).applyTo(receiver).contains("public String getName() { return name; }"),"receiver stub uses matching field");
            String target="package demo; public class Abed { private String name; public Abed(String s, int n) {} }";
            String caller="package demo; class Use { void f(){ Abed abd=new Abed(\"\",0); String value=abd.getName(); } }";
            WorkspaceSnapshot cross=WorkspaceSnapshot.builder().addSource(javaFile("Abed.java",target)).addSource(javaFile("Use.java",caller)).build();
            Diagnostic crossDiagnostic=find(engine.analyzeWorkspace(cross).getSources().get("Use.java"),"UNRESOLVED_METHOD");
            check(crossDiagnostic!=null && crossDiagnostic.getWorkspaceFixes().size()==1,"cross-file receiver offers guarded workspace edit");
            Map<String,String> snapshots=new HashMap<>(); snapshots.put("Abed.java",target); snapshots.put("Use.java",caller);
            String crossFixed=crossDiagnostic.getWorkspaceFixes().get(0).preview(snapshots).get("Abed.java");
            check(crossFixed.contains("public String getName() { return name; }"),"cross-file stub targets receiver class");
            writeFixture("cross/Abed.java",crossFixed); writeFixture("cross/Use.java",caller);
            String base="import android.widget.BaseAdapter;\npublic class Abed extends BaseAdapter { }";
            result=engine.analyzeWorkspace(WorkspaceSnapshot.builder().addSource(javaFile("Abed.java",base)).build()).getSources().get("Abed.java");
            Diagnostic required=find(result,"MISSING_ABSTRACT_METHODS");
            check(required!=null && !required.getQuickFixes().isEmpty(),"BaseAdapter missing overrides detected");
            String fixed=required.getQuickFixes().get(0).applyTo(base);
            check(fixed.contains("import android.view.View;") && fixed.contains("import android.view.ViewGroup;") && fixed.contains("getView(int position"),"BaseAdapter fix adds view imports and stubs");
            writeFixture("base/Abed.java",fixed);
            result=engine.analyzeWorkspace(WorkspaceSnapshot.builder().addSource(javaFile("Abed.java",fixed)).build()).getSources().get("Abed.java");
            check(find(result,"MISSING_ABSTRACT_METHODS")==null,"generated BaseAdapter overrides resolve inspection");
            String wildcard="import android.widget.BaseAdapter; import android.view.*; class Wild extends BaseAdapter { public int getCount(){return 0;} public Object getItem(int i){return null;} public long getItemId(int i){return 0;} public View getView(int i,View v,ViewGroup g){return null;} }";
            AnalysisResult wildResult=engine.analyzeWorkspace(WorkspaceSnapshot.builder().addSource(javaFile("Wild.java",wildcard)).build()).getSources().get("Wild.java");
            check(find(wildResult,"MISSING_ABSTRACT_METHODS")==null,"ambiguous wildcard override is not duplicated by quick fix");
            String annotated="import android.widget.BaseAdapter; abstract class X extends BaseAdapter { public int getCount(){return 0;} }";
            result=engine.analyzeWorkspace(WorkspaceSnapshot.builder().addSource(javaFile("X.java",annotated)).build()).getSources().get("X.java");
            check(find(result,"MISSING_OVERRIDE_ANNOTATION").getSeverity()==Diagnostic.Severity.WARNING,"missing Override defaults to style warning");
            writeFixture("override/X.java",find(result,"MISSING_OVERRIDE_ANNOTATION").getQuickFixes().get(0).applyTo(annotated));
            result=engine.analyzeWorkspace(WorkspaceSnapshot.builder().requireOverride(true).addSource(javaFile("X.java",annotated)).build()).getSources().get("X.java");
            check(find(result,"MISSING_OVERRIDE_ANNOTATION").getSeverity()==Diagnostic.Severity.ERROR,"Override policy can be enforced");
        }
    }
    private static void sourceMemberResolution() {
        String parent="package demo; class Base { public String inherited(){return \"ok\";} public int count; }";
        String helper="package demo; class ClipboardHelper extends Base { public String getKey(){return \"yes\";} }";
        String editor="package demo; class Dictionary extends Base { ClipboardHelper clibs; "
            +"private static final String TAG=\"perfectkeboard\"; private static final int CPS_BUFFER_SIZEE=16; "
            +"public static boolean DEBUG=false, TRACE=false; static final boolean VOICE_INSTALLED=true, ENABLE_VOICE_BUTTON=true; "
            +"void f(){ // this.clibs.fakeComment();\n"
            +"String sql=\"CREATE TABLE ghost (getKay)\"; String text=\"this.clibs.fakeString()\"; "
            +"String good=this.clibs.getKey(); String inherited=this.clibs.inherited(); "
            +"int count=this.clibs.count; String s=this.clibs.getKay(); this.clibs.setKay(\"kay\"); "
            +"int absent=this.clibs.noSuchField; this.inherited(); this.noSuchLocal(); } }";
        try (Analyzer engine=new Analyzer()) {
            WorkspaceSnapshot complete=WorkspaceSnapshot.builder().addSource(javaFile("Base.java",parent))
                .addSource(javaFile("ClipboardHelper.java",helper)).addSource(javaFile("Dictionary.java",editor)).build();
            AnalysisResult result=engine.analyzeWorkspace(complete).getSources().get("Dictionary.java");
            check(hasAt(result,"UNRESOLVED_METHOD",editor.indexOf("getKay()")),"field receiver missing no-argument method detected");
            check(hasAt(result,"UNRESOLVED_METHOD",editor.indexOf("setKay(\"kay\")")),"field receiver missing method with arguments detected");
            check(hasAt(result,"UNRESOLVED_METHOD",editor.indexOf("noSuchLocal()")),"missing method on this with known superclass detected");
            check(hasAt(result,"UNRESOLVED_FIELD",editor.indexOf("noSuchField")),"missing field on a known hierarchy detected");
            check(!hasAt(result,"UNRESOLVED_METHOD",editor.indexOf("getKey()"))
                && !hasAt(result,"UNRESOLVED_METHOD",editor.indexOf("inherited()"))
                && !hasAt(result,"UNRESOLVED_FIELD",editor.indexOf(".count")+1),"existing and inherited members are not flagged");
            for (Diagnostic d : result.getDiagnostics()) if (d.getSeverity()==Diagnostic.Severity.ERROR) {
                int offset=d.getRange().getStartOffset();
                check(offset!=editor.indexOf("TAG") && offset!=editor.indexOf("CPS_BUFFER_SIZEE")
                    && offset!=editor.indexOf("DEBUG") && offset!=editor.indexOf("TRACE")
                    && offset!=editor.indexOf("VOICE_INSTALLED") && offset!=editor.indexOf("ENABLE_VOICE_BUTTON"),
                    "valid static constants/fields are not errors");
                check(offset!=editor.indexOf("fakeComment") && offset!=editor.indexOf("fakeString")
                    && offset!=editor.indexOf("CREATE TABLE"),"comments and string contents never become member diagnostics");
            }
            WorkspaceSnapshot incomplete=WorkspaceSnapshot.builder().projectIndexComplete(false)
                .addSource(javaFile("ClipboardHelper.java",helper)).addSource(javaFile("Dictionary.java",editor)).build();
            AnalysisResult partial=engine.analyzeWorkspace(incomplete).getSources().get("Dictionary.java");
            check(!hasAt(partial,"UNRESOLVED_METHOD",editor.indexOf("getKay()"))
                && partial.getIncompleteReasons().contains("PROJECT_INDEX_INCOMPLETE"),"incomplete project never claims a member is absent");
            String external="package demo; class ClipboardHelper extends android.database.sqlite.SQLiteOpenHelper {}";
            WorkspaceSnapshot unknown=WorkspaceSnapshot.builder().addSource(javaFile("ClipboardHelper.java",external))
                .addSource(javaFile("Base.java",parent)).addSource(javaFile("Dictionary.java",editor)).build();
            AnalysisResult uncertain=engine.analyzeWorkspace(unknown).getSources().get("Dictionary.java");
            check(!hasAt(uncertain,"UNRESOLVED_METHOD",editor.indexOf("getKay()"))
                && !hasAt(uncertain,"UNRESOLVED_FIELD",editor.indexOf("noSuchField")),"unknown Android superclass suppresses absence-based errors");
            String shadows="class A { Missing value; void f(Local value){ value.exists(); this.value.call(); } } "
                +"class Local { void exists(){} } class Missing { void call(){} }";
            AnalysisResult shadow=engine.analyzeWorkspace(WorkspaceSnapshot.builder().addSource(javaFile("A.java",shadows)).build()).getSources().get("A.java");
            check(find(shadow,"UNRESOLVED_METHOD")==null,"parameters shadow fields and this.field resolves separately");
            String scoped="class Right { void exists(){} } class Wrong {} class Scope { Right x; void f(){ { Wrong x=null; } x.exists(); } }";
            AnalysisResult scopeResult=engine.analyzeWorkspace(WorkspaceSnapshot.builder().addSource(javaFile("Scope.java",scoped)).build()).getSources().get("Scope.java");
            check(find(scopeResult,"UNRESOLVED_METHOD")==null,"out-of-scope local does not shadow a field after its block ends");
            String nested="class Outer { void present(){} class Inner { void f(){ present(); } } "
                +"void g(){ class Local { void known(){} } Local x=null; x.known(); } }";
            AnalysisResult nestedResult=engine.analyzeWorkspace(WorkspaceSnapshot.builder().addSource(javaFile("Outer.java",nested)).build()).getSources().get("Outer.java");
            check(find(nestedResult,"UNRESOLVED_METHOD")==null,"nested/local types and outer-instance calls are not misresolved");
            String objectShadow="class Object { void inherited(){} } class Child extends Object { void f(){ inherited(); } }";
            AnalysisResult objectResult=engine.analyzeWorkspace(WorkspaceSnapshot.builder().addSource(javaFile("Child.java",objectShadow)).build()).getSources().get("Child.java");
            check(find(objectResult,"UNRESOLVED_METHOD")==null,"a project class named Object is not mistaken for java.lang.Object");
            String arrays="class Item {} class C { Item elements[]; void f(Item input[]){ int n=this.elements.length; int m=input.length; } }";
            AnalysisResult arraysResult=engine.analyzeWorkspace(WorkspaceSnapshot.builder().addSource(javaFile("C.java",arrays)).build()).getSources().get("C.java");
            check(find(arraysResult,"UNRESOLVED_FIELD")==null,"array declarator dimensions are not mistaken for scalar project objects");
            String escaped="cl"+'\\'+"u0061ss Escaped {}";
            WorkspaceSnapshot unicode=WorkspaceSnapshot.builder().addSource(javaFile("Base.java",parent))
                .addSource(javaFile("ClipboardHelper.java",helper)).addSource(javaFile("Dictionary.java",editor))
                .addSource(javaFile("Escaped.java",escaped)).build();
            AnalysisResult unicodeResult=engine.analyzeWorkspace(unicode).getSources().get("Dictionary.java");
            check(!hasAt(unicodeResult,"UNRESOLVED_METHOD",editor.indexOf("getKay()"))
                && unicodeResult.getIncompleteReasons().contains("PROJECT_INDEX_INCOMPLETE"),
                "an excluded Unicode-escaped source makes the project absence index visibly incomplete");
            String escapedKeyword="cl"+'\\'+"u0061ss E {}";
            AnalysisResult unicodeSingle=engine.analyze(javaFile("E.java",escapedKeyword));
            check(unicodeSingle.getIncompleteReasons().contains("JAVA_UNICODE_ESCAPES_UNSUPPORTED")
                && find(unicodeSingle,"SYNTAX_ERROR")==null && find(unicodeSingle,"MISSING_TOKEN")==null,
                "translated Java Unicode escapes are not falsely diagnosed on the raw token stream");
            String literalEscape="class Safe { char c='"+'\\'+"u0001'; }";
            AnalysisResult safeEscape=engine.analyze(javaFile("Safe.java",literalEscape));
            check(safeEscape.getStatus()==AnalysisResult.Status.COMPLETE
                && find(safeEscape,"SYNTAX_ERROR")==null,"safe Unicode character escapes in literals do not disable project inspection");
            String esc=""+'\\'+"u";
            String safeSource="// "+esc+"0041\n/* "+esc+"0062 */ class Safe { String s=\""+esc+"0063\"; }";
            AnalysisResult safeComments=engine.analyze(javaFile("SafeComments.java",safeSource));
            check(safeComments.getStatus()==AnalysisResult.Status.COMPLETE && find(safeComments,"SYNTAX_ERROR")==null,
                "ordinary Unicode escapes in comments and string contents do not suppress analysis");
            String[] unsafe={"// "+esc+"000A class X {}", "/* "+esc+"002a/ class X {}",
                "class X { String s=\""+esc+"0022; }", "class X { String s=\""+esc+"005c\"; }",
                "class Na"+esc+"006de {}", "class X { String s=\"\"\"\n"+esc+"0022\"\"\"; }"};
            for (int i=0;i<unsafe.length;i++) {
                AnalysisResult u=engine.analyze(javaFile("Esc"+i+".java",unsafe[i]));
                check(u.getStatus()==AnalysisResult.Status.PARTIAL
                    && u.getIncompleteReasons().contains("JAVA_UNICODE_ESCAPES_UNSUPPORTED")
                    && find(u,"SYNTAX_ERROR")==null && find(u,"MISSING_TOKEN")==null,
                    "structurally unsafe Unicode escape "+i+" is partial without raw-parser syntax errors");
            }
        }
    }
    private static boolean hasAt(AnalysisResult result,String code,int offset) {
        for (Diagnostic d : result.getDiagnostics()) if (d.getCode().equals(code) && d.getRange().getStartOffset()==offset) return true;
        return false;
    }

    private static void writeFixture(String relative,String text) throws Exception {
        String directory=System.getenv("SHIMA_FIXTURE_DIR");
        if (directory==null || directory.isEmpty()) return;
        File file=new File(directory,relative); file.getParentFile().mkdirs();
        try (FileOutputStream out=new FileOutputStream(file)) { out.write(text.getBytes("UTF-8")); }
    }
    private static String sha256(byte[] data) throws Exception {
        StringBuilder value=new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) value.append(String.format("%02x",b&255));
        return value.toString();
    }
    private static void dependencyResolution() throws Exception {
        String build="// implementation 'fake:comment:1'\nimplementation(\"com.example:parent:1.0\")\napi 'org.example:child:2.0'\nimplementation(project(\":local\"))\nruntimeOnly(\"a:b:$version\")";
        BuildDependencyScanner.Result scan=new BuildDependencyScanner().scan(build);
        check(scan.getCoordinates().size()==2 && !scan.isComplete(),"Gradle scanner extracts literal GAVs and reports dynamic/project expressions");
        check(!new BuildDependencyScanner().scan("implementation('a:b:1') { exclude group: 'x' }").isComplete(),"dependency exclusions are not silently ignored");
        try { MavenCoordinate.parse("a:b:../bad"); throw new AssertionError("unsafe version accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        final MavenCoordinate parent=MavenCoordinate.parse("com.example:parent:1.0"), child=MavenCoordinate.parse("org.example:child:2.0");
        String parentPom="<project><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version><packaging>aar</packaging><dependencies><dependency><groupId>org.example</groupId><artifactId>child</artifactId><version>2.0</version></dependency></dependencies></project>";
        String childPom="<project><groupId>org.example</groupId><artifactId>child</artifactId><version>2.0</version></project>";
        final Map<String,byte[]> remote=new HashMap<>();
        remote.put(parent.relativePath("pom"),parentPom.getBytes("UTF-8")); remote.put(parent.relativePath("aar"),new byte[]{1,2,3,4});
        remote.put(child.relativePath("pom"),childPom.getBytes("UTF-8")); remote.put(child.relativePath("jar"),new byte[]{5,6,7});
        final int[] requests={0};
        MavenInstaller.Transport transport=new MavenInstaller.Transport() {
            public MavenInstaller.Download open(URL url) throws IOException {
                requests[0]++; String path=url.getPath().substring("/maven/".length()); byte[] data=remote.get(path);
                if (data==null) throw new IOException("not found");
                return new MavenInstaller.Download(new ByteArrayInputStream(data),data.length,null);
            }
        };
        Map<String,String> locks=new HashMap<>();
        locks.put(parent.lockKey("pom"),sha256(remote.get(parent.relativePath("pom")))); locks.put(parent.lockKey("aar"),sha256(remote.get(parent.relativePath("aar"))));
        locks.put(child.lockKey("pom"),sha256(remote.get(child.relativePath("pom")))); locks.put(child.lockKey("jar"),sha256(remote.get(child.relativePath("jar"))));
        File cache=java.nio.file.Files.createTempDirectory("shima-maven-test").toFile();
        try {
            MavenInstaller installer=new MavenInstaller(cache,Arrays.asList("https://repo.example/maven/"),locks,new MavenInstaller.Options(),transport);
            final int[] progress={0};
            MavenInstaller.Report report=installer.install(Arrays.asList(parent),new MavenInstaller.Cancellation(),new MavenInstaller.Listener() {
                public void onProgress(MavenInstaller.Progress event) { progress[0]++; }
            });
            check(report.isComplete() && report.getFiles().size()==4 && progress[0]>=4,"Maven resolver downloads locked parent and literal transitive dependency");
            check(installer.findCached(child,"jar")!=null,"cache detects verified artifact");
            DependencyAudit.Result present=new DependencyAudit().inspect("implementation 'com.example:parent:1.0'",installer);
            check(present.getMissing().isEmpty() && present.getUnknown().isEmpty(),"offline audit sees cached direct declaration");
            int before=requests[0];
            check(installer.install(Arrays.asList(parent),new MavenInstaller.Cancellation(),null).isComplete() && requests[0]==before,"cache avoids repeated downloads");
            try (FileOutputStream out=new FileOutputStream(installer.findCached(child,"jar"))) { out.write(99); }
            check(installer.findCached(child,"jar")==null,"corrupt cache entry is rejected");
            check(new DependencyAudit().inspect("api 'org.example:child:2.0'",installer).getMissing().contains(child),"offline audit reports missing direct binary");
            check(installer.install(Arrays.asList(parent),new MavenInstaller.Cancellation(),null).isComplete() && requests[0]>before,"corrupt cache entry is redownloaded");
            MavenInstaller locked=new MavenInstaller(new File(cache,"locked"),Arrays.asList("https://repo.example/maven/"),Collections.<String,String>emptyMap(),new MavenInstaller.Options(),transport);
            before=requests[0];
            check(!locked.install(Arrays.asList(parent),new MavenInstaller.Cancellation(),null).isComplete() && requests[0]==before,"missing trusted hash blocks network download");
            MavenInstaller.Cancellation cancel=new MavenInstaller.Cancellation(); cancel.cancel();
            check(installer.install(Arrays.asList(parent),cancel,null).isCancelled(),"installer cancellation reported");
            try { new MavenInstaller(new File(cache,"unsafe"),Arrays.asList("http://repo.example/"),locks,new MavenInstaller.Options(),transport); throw new AssertionError("HTTP repository accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
            Map<String,String> wrong=new HashMap<>(locks); wrong.put(parent.lockKey("pom"),String.join("",Collections.nCopies(64,"0")));
            MavenInstaller badHash=new MavenInstaller(new File(cache,"bad-hash"),Arrays.asList("https://repo.example/maven/"),wrong,new MavenInstaller.Options(),transport);
            MavenInstaller.Report mismatch=badHash.install(Arrays.asList(parent),new MavenInstaller.Cancellation(),null);
            check(!mismatch.isComplete() && badHash.findCached(parent,"pom")==null,"mismatched SHA-256 never publishes cache entry");
            final MavenInstaller.Cancellation during=new MavenInstaller.Cancellation();
            MavenInstaller interrupted=new MavenInstaller(new File(cache,"cancel"),Arrays.asList("https://repo.example/maven/"),locks,new MavenInstaller.Options(),transport);
            MavenInstaller.Report interruptedReport=interrupted.install(Arrays.asList(parent),during,new MavenInstaller.Listener() {
                public void onProgress(MavenInstaller.Progress event) { during.cancel(); }
            });
            check(interruptedReport.isCancelled() && interrupted.findCached(parent,"pom")==null,"mid-stream cancellation removes temporary download");
            MavenInstaller limited=new MavenInstaller(new File(cache,"limit"),Arrays.asList("https://repo.example/maven/"),locks,new MavenInstaller.Options().maxPomBytes(10),transport);
            check(!limited.install(Arrays.asList(parent),new MavenInstaller.Cancellation(),null).isComplete(),"advertised response size limit enforced");
            MavenInstaller.Transport truncated=new MavenInstaller.Transport() {
                public MavenInstaller.Download open(URL url) {
                    byte[] data=remote.get(parent.relativePath("pom"));
                    return new MavenInstaller.Download(new ByteArrayInputStream(data),data.length+1,null);
                }
            };
            MavenInstaller incompleteDownload=new MavenInstaller(new File(cache,"truncated"),Arrays.asList("https://repo.example/maven/"),locks,new MavenInstaller.Options(),truncated);
            check(!incompleteDownload.install(Arrays.asList(parent),new MavenInstaller.Cancellation(),null).isComplete(),"truncated HTTP response rejected");
            String dtd="<?xml version=\"1.0\"?><!DOCTYPE project [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><project><artifactId>parent</artifactId></project>";
            for (String encoding : Arrays.asList("UTF-8","UTF-16")) {
                byte[] malicious=dtd.getBytes(encoding); remote.put(parent.relativePath("pom"),malicious);
                Map<String,String> maliciousLock=new HashMap<>(locks); maliciousLock.put(parent.lockKey("pom"),sha256(malicious));
                MavenInstaller hardened=new MavenInstaller(new File(cache,"dtd-"+encoding),Arrays.asList("https://repo.example/maven/"),maliciousLock,new MavenInstaller.Options(),transport);
                MavenInstaller.Report denied=hardened.install(Arrays.asList(parent),new MavenInstaller.Cancellation(),null);
                check(!denied.isComplete() && hasIssue(denied,"INVALID_POM"),"DTD rejected in "+encoding);
            }
            String conflict=parentPom.replace("</dependencies>","<dependency><groupId>org.example</groupId><artifactId>child</artifactId><version>3.0</version></dependency></dependencies>");
            byte[] conflictBytes=conflict.getBytes("UTF-8"); remote.put(parent.relativePath("pom"),conflictBytes);
            Map<String,String> conflictLocks=new HashMap<>(locks); conflictLocks.put(parent.lockKey("pom"),sha256(conflictBytes));
            MavenInstaller conflictInstaller=new MavenInstaller(new File(cache,"conflict"),Arrays.asList("https://repo.example/maven/"),conflictLocks,new MavenInstaller.Options(),transport);
            check(hasIssue(conflictInstaller.install(Arrays.asList(parent),new MavenInstaller.Cancellation(),null),"VERSION_CONFLICT"),"conflicting versions reported instead of silently choosing a winner");
            String unsupported=parentPom.replace("</dependency>","<exclusions><exclusion><groupId>x</groupId><artifactId>y</artifactId></exclusion></exclusions></dependency>");
            byte[] unsupportedBytes=unsupported.getBytes("UTF-8"); remote.put(parent.relativePath("pom"),unsupportedBytes);
            Map<String,String> unsupportedLocks=new HashMap<>(locks); unsupportedLocks.put(parent.lockKey("pom"),sha256(unsupportedBytes));
            MavenInstaller unsupportedInstaller=new MavenInstaller(new File(cache,"unsupported"),Arrays.asList("https://repo.example/maven/"),unsupportedLocks,new MavenInstaller.Options(),transport);
            check(hasIssue(unsupportedInstaller.install(Arrays.asList(parent),new MavenInstaller.Cancellation(),null),"UNSUPPORTED_DEPENDENCY"),"POM exclusions are reported, never ignored");
        } finally { deleteTree(cache); }
    }
    private static boolean hasIssue(MavenInstaller.Report report,String prefix) {
        for (String issue : report.getIssues()) if (issue.startsWith(prefix)) return true;
        return false;
    }
    private static void deleteTree(File file) {
        File[] children=file.listFiles(); if (children!=null) for (File child : children) deleteTree(child); file.delete();
    }
    private static void cancelAndClose() throws Exception {
        final AnalysisConfig config=AnalysisConfig.builder().maxDocumentChars(1_000_000).timeoutMillis(5000).build();
        final Analyzer engine=new Analyzer(config);
        StringBuilder huge=new StringBuilder("class Big {\n");
        for(int i=0;i<18000;i++) huge.append("int field").append(i).append(" = 1;\n");
        huge.append('}');
        final String text=huge.toString();
        final AtomicReference<AnalysisResult> result=new AtomicReference<>();
        final CountDownLatch entered=new CountDownLatch(1);
        Thread thread=new Thread(new Runnable() { public void run() { entered.countDown(); result.set(engine.analyze(javaFile("Big.java",text))); }});
        thread.start(); entered.await(1,TimeUnit.SECONDS); Thread.sleep(10); engine.cancel(); thread.join(8000);
        check(!thread.isAlive(), "cooperative cancellation terminates");
        check(result.get()!=null, "cancellation returns an explicit result");
        AnalysisResult next=engine.analyze(javaFile("Big.java","class Big { int ok; }"));
        check(next.getStatus()==AnalysisResult.Status.COMPLETE && find(next,"SYNTAX_ERROR")==null, "parser usable after cancellation");
        engine.close(); engine.close();
        try { engine.analyze(javaFile("x","class X {}")); throw new AssertionError("closed analyzer accepted work"); }
        catch (IllegalStateException expected) { checks++; }
    }
    private static void waitForResults(List<AnalysisResult> results, int count, long timeoutMillis) throws InterruptedException {
        long deadline=System.currentTimeMillis()+timeoutMillis;
        while (results.size()<count && System.currentTimeMillis()<deadline) Looper.pump(20);
        Looper.pump(100);
    }
    private static void editorLifecycle() throws Exception {
        final EditText editor=new EditText();
        editor.getText().replace(0,0,"class X { int old; }");
        final List<AnalysisResult> delivered=new ArrayList<>();
        final List<Throwable> failures=new ArrayList<>();
        AnalysisHandle handle=CodeAnalyzer.with(new Context()).attachTo(editor).sourceId("X.java").debounceMillis(45)
                .onResult(new CodeAnalyzer.ResultListener(){ public void onResult(AnalysisResult r) { check(Looper.myLooper()==Looper.getMainLooper(),"callback on main"); delivered.add(r); }})
                .onError(new CodeAnalyzer.ErrorListener(){ public void onError(Throwable t) { failures.add(t); }}).start();
        check(editor.watcherCount()==1,"EditText watcher attached");
        editor.getText().replace(0,editor.getText().length(),"class X { List<String> xs; }");
        editor.getText().replace(0,editor.getText().length(),"class X { List<String> values; }");
        waitForResults(delivered, 1, 5000);
        check(failures.isEmpty(),"no editor analysis failure: "+failures);
        check(delivered.size()==1,"debounce delivers only final snapshot");
        Diagnostic missing=find(delivered.get(0),"POSSIBLE_MISSING_IMPORT");
        check(missing!=null,"editor delivers diagnostics");
        QuickFix fix=missing.getQuickFixes().get(0);
        handle.applyFix(fix);
        check(editor.getText().toString().contains("import java.util.List;"),"editor applies fix");
        try { handle.applyFix(fix); throw new AssertionError("editor accepted stale fix"); }
        catch (IllegalStateException expected) { checks++; }
        handle.close(); handle.close();
        check(editor.watcherCount()==0,"close removes TextWatcher");
        int count=delivered.size(); editor.getText().replace(0,0,"//"); Looper.pump(200);
        check(delivered.size()==count,"no callbacks after close");
        // The one-line entry point is also valid when the host does not subscribe to results.
        AnalysisHandle noListener=CodeAnalyzer.with(new Context()).attachTo(editor).start();
        noListener.close();
        check(editor.watcherCount()==0,"listener is optional and session still detaches");
        final List<Throwable> errors=new ArrayList<>();
        EditorAdapter broken=new EditorAdapter() {
            public String getText(){ throw new IllegalStateException("snapshot failed"); }
            public void replace(int a,int b,String c) { }
            public Subscription subscribe(Runnable r){ return new Subscription(){public void close(){}}; }
        };
        AnalysisHandle brokenHandle=CodeAnalyzer.with(new Context()).attachTo(broken).debounceMillis(0)
                .onError(new CodeAnalyzer.ErrorListener(){public void onError(Throwable t){ errors.add(t); }}).start();
        Looper.pump(40); brokenHandle.close();
        check(errors.size()==1,"editor snapshot failures reach onError");
    }
    private static void reentrantEditorClose() throws Exception {
        final int[] unsubscribeCalls={0};
        final int[] snapshotsRead={0};
        final AnalysisHandle[] handle={null};
        final List<AnalysisResult> results=new ArrayList<>();
        final List<Throwable> errors=new ArrayList<>();
        EditorAdapter adapter=new EditorAdapter() {
            public String getText() { snapshotsRead[0]++; return "class X {}"; }
            public void replace(int a,int b,String text) { }
            public Subscription subscribe(final Runnable onChange) {
                return new Subscription() {
                    public void close() {
                        if (++unsubscribeCalls[0]>1) throw new AssertionError("reentrant unsubscribe");
                        // A host-provided unsubscribe can synchronously report another change
                        // and reenter the session's close method.
                        onChange.run();
                        handle[0].close();
                    }
                };
            }
        };
        handle[0]=CodeAnalyzer.with(new Context()).attachTo(adapter).sourceId("X.java").debounceMillis(0)
                .onResult(new CodeAnalyzer.ResultListener() { public void onResult(AnalysisResult value) { results.add(value); } })
                .onError(new CodeAnalyzer.ErrorListener() { public void onError(Throwable value) { errors.add(value); } }).start();
        handle[0].close();
        handle[0].close();
        Looper.pump(100);
        check(unsubscribeCalls[0]==1,"reentrant and repeated close unsubscribes once");
        check(snapshotsRead[0]==0,"change during unsubscribe does not restart analysis");
        check(results.isEmpty() && errors.isEmpty(),"closing cancels pending delivery and does not publish diagnostics");
    }
    private static void closeFromResultCallback() throws Exception {
        final EditText editor=new EditText();
        editor.getText().replace(0,0,"class X {}");
        final AnalysisHandle[] handle={null};
        final List<AnalysisResult> results=new ArrayList<>();
        final List<Throwable> errors=new ArrayList<>();
        handle[0]=CodeAnalyzer.with(new Context()).attachTo(editor).sourceId("X.java").debounceMillis(0)
                .onResult(new CodeAnalyzer.ResultListener() { public void onResult(AnalysisResult value) {
                    results.add(value);
                    handle[0].close();
                } })
                .onError(new CodeAnalyzer.ErrorListener() { public void onError(Throwable value) { errors.add(value); } }).start();
        waitForResults(results,1,5000);
        check(results.size()==1 && errors.isEmpty(),"close from onResult completes without another callback");
        check(editor.watcherCount()==0,"close from onResult detaches editor");
        editor.getText().replace(0,0,"// changed\n");
        Looper.pump(100);
        check(results.size()==1 && errors.isEmpty(),"no delivery follows close from onResult");
    }
    private static void workspaceEditorLifecycle() throws Exception {
        final EditText editor=new EditText(); editor.getText().replace(0,0,"class X { int x=R.id.key; }");
        final List<AnalysisResult> results=new ArrayList<>(); final List<Throwable> errors=new ArrayList<>();
        WorkspaceSnapshot initial=WorkspaceSnapshot.builder().resourceIndexComplete(true).build();
        AnalysisHandle handle=CodeAnalyzer.with(new Context()).attachTo(editor).sourceId("X.java").workspace(initial).debounceMillis(0)
                .onResult(new CodeAnalyzer.ResultListener() { public void onResult(AnalysisResult value) { results.add(value); } })
                .onError(new CodeAnalyzer.ErrorListener() { public void onError(Throwable value) { errors.add(value); } }).start();
        waitForResults(results,1,5000);
        check(errors.isEmpty() && !results.isEmpty() && find(results.get(0),"MISSING_RESOURCE")!=null,"editor workspace publishes resource diagnostics");
        WorkspaceSnapshot updated=WorkspaceSnapshot.builder().resourceIndexComplete(true)
                .addResource(new ResourceFile("res/values/ids.xml","<resources><item type=\"id\" name=\"key\" /></resources>")).build();
        handle.updateWorkspace(updated); waitForResults(results,2,5000);
        check(results.size()>=2 && find(results.get(results.size()-1),"MISSING_RESOURCE")==null,"workspace updates invalidate editor diagnostics");
        handle.close(); check(editor.watcherCount()==0,"workspace editor unsubscribes on close");
    }
    private static void xmlWorkspaceEditorLifecycle() throws Exception {
        final EditText editor=new EditText();
        editor.getText().replace(0,0,"<TextView xmlns:android=\"http://schemas.android.com/apk/res/android\" android:text=\"@string/name\" />");
        final List<AnalysisResult> results=new ArrayList<>();
        WorkspaceSnapshot initial=WorkspaceSnapshot.builder().resourceIndexComplete(true).build();
        AnalysisHandle handle=CodeAnalyzer.with(new Context()).attachTo(editor).sourceId("res/layout/main.xml").language(Language.XML).workspace(initial).debounceMillis(0)
                .onResult(new CodeAnalyzer.ResultListener() { public void onResult(AnalysisResult value) { results.add(value); } }).start();
        waitForResults(results,1,5000);
        check(!results.isEmpty() && find(results.get(0),"MISSING_RESOURCE")!=null,"XML editor checks references against workspace");
        WorkspaceSnapshot updated=WorkspaceSnapshot.builder().resourceIndexComplete(true)
                .addResource(new ResourceFile("res/values/strings.xml","<resources><string name=\"name\">Hello</string></resources>")).build();
        handle.updateWorkspace(updated); waitForResults(results,2,5000);
        check(results.size()>=2 && find(results.get(results.size()-1),"MISSING_RESOURCE")==null,"XML editor sees updated resources");
        handle.close(); check(editor.watcherCount()==0,"XML editor unsubscribes on close");
    }
    private static void latestEditorResultWins() throws Exception {
        EditText editor=new EditText();
        StringBuilder slow=new StringBuilder("class X { List<String> xs; ");
        for (int i=0; i<20000; i++) slow.append("int x").append(i).append("=1;");
        slow.append('}');
        editor.getText().replace(0, 0, slow.toString());
        final List<AnalysisResult> results=new ArrayList<>();
        final List<Throwable> errors=new ArrayList<>();
        AnalysisHandle handle=CodeAnalyzer.with(new Context()).attachTo(editor).sourceId("X.java")
            .config(AnalysisConfig.builder().maxDocumentChars(1_000_000).timeoutMillis(5000).build())
            .debounceMillis(0).onResult(new CodeAnalyzer.ResultListener() { public void onResult(AnalysisResult r) { results.add(r); } })
            .onError(new CodeAnalyzer.ErrorListener() { public void onError(Throwable t) { errors.add(t); } }).start();
        Looper.pump(5);
        int alreadyDelivered=results.size();
        editor.getText().replace(0, editor.getText().length(), "class X { int n = \"bad\"; }");
        waitForResults(results, alreadyDelivered+1, 5000);
        check(errors.isEmpty(), "no errors during rapid replacement");
        check(results.size()>alreadyDelivered, "newest edit delivered");
        for (int i=alreadyDelivered; i<results.size(); i++)
            check(find(results.get(i), "INCOMPATIBLE_LITERAL")!=null && find(results.get(i), "POSSIBLE_MISSING_IMPORT")==null, "no stale result after edit");
        handle.close();
    }
    private static byte[] classHeader(String internal, boolean isPublic) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0xCAFEBABE); out.writeShort(0); out.writeShort(52); out.writeShort(5);
        out.writeByte(1); out.writeUTF(internal); out.writeByte(7); out.writeShort(1);
        out.writeByte(1); out.writeUTF("java/lang/Object"); out.writeByte(7); out.writeShort(3);
        out.writeShort(isPublic ? 0x0021 : 0x0020); out.writeShort(2); out.writeShort(4);
        out.writeShort(0); out.writeShort(0); out.writeShort(0); out.writeShort(0); out.flush();
        return bytes.toByteArray();
    }
    private static byte[] jarFor(String name, boolean isPublic) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(name+".class")); zip.write(classHeader(name,isPublic)); zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    private static void dependencyVersionSymbols() throws Exception {
        File dir=new File(System.getProperty("java.io.tmpdir"),"shima-version-"+System.nanoTime());
        check(dir.mkdirs(),"temporary dependency test directory created");
        File oldJar=new File(dir,"library-1.0.jar"), newJar=new File(dir,"library-2.0.jar"), aar=new File(dir,"library-2.0.aar"), bad=new File(dir,"bad.jar");
        try {
            try (FileOutputStream out=new FileOutputStream(oldJar)) { out.write(jarFor("example/lib/OldApi",true)); }
            byte[] newer=jarFor("example/lib/NewApi",true);
            try (FileOutputStream out=new FileOutputStream(newJar)) { out.write(newer); }
            try (java.util.zip.ZipOutputStream out=new java.util.zip.ZipOutputStream(new FileOutputStream(aar))) {
                out.putNextEntry(new java.util.zip.ZipEntry("classes.jar")); out.write(newer); out.closeEntry();
            }
            try (java.util.zip.ZipOutputStream out=new java.util.zip.ZipOutputStream(new FileOutputStream(bad))) {
                out.putNextEntry(new java.util.zip.ZipEntry("example/lib/Fake.class")); out.write(new byte[]{1,2,3}); out.closeEntry();
            }
            ClasspathSymbols.Result old=ClasspathSymbols.scan(Collections.singletonList(oldJar));
            ClasspathSymbols.Result updated=ClasspathSymbols.scan(Collections.singletonList(aar));
            check(old.isComplete() && old.getSymbols().equals(Collections.singletonList("example.lib.OldApi")),"selected old JAR supplies only its public type");
            check(updated.isComplete() && updated.getSymbols().equals(Collections.singletonList("example.lib.NewApi")),"selected new AAR supplies its own public type");
            check(!ClasspathSymbols.scan(Collections.singletonList(bad)).isComplete(),"corrupt class headers are reported, not trusted");
            check(!ClasspathSymbols.scan(Arrays.asList(aar,newJar)).isComplete(),"duplicate classes are explicitly reported");
            AnalysisConfig.Builder oldConfig=AnalysisConfig.builder(), newConfig=AnalysisConfig.builder();
            old.addTo(oldConfig); updated.addTo(newConfig);
            String source="class Use { NewApi value; }";
            try (Analyzer before=new Analyzer(oldConfig.build()); Analyzer after=new Analyzer(newConfig.build())) {
                check(find(before.analyze(javaFile("Use.java",source)),"POSSIBLE_MISSING_IMPORT")==null,
                        "an unavailable class from another library version is not suggested");
                Diagnostic hint=find(after.analyze(javaFile("Use.java",source)),"POSSIBLE_MISSING_IMPORT");
                check(hint!=null && hint.getQuickFixes().get(0).getTitle().contains("example.lib.NewApi"),
                        "selected library version changes the available import candidate");
            }
        } finally {
            oldJar.delete(); newJar.delete(); aar.delete(); bad.delete(); dir.delete();
        }
    }
    private static void cancelWorkspaceAndReuse() throws Exception {
        AnalysisConfig config=AnalysisConfig.builder().maxProjectFiles(400).maxProjectChars(20_000_000)
                .maxEvents(100_000).timeoutMillis(30_000).build();
        Analyzer analyzer=new Analyzer(config);
        StringBuilder text=new StringBuilder("<resources>");
        for (int i=0;i<4000;i++) text.append("<string name=\"x").append(i).append("\">v</string>");
        text.append("</resources>");
        WorkspaceSnapshot.Builder builder=WorkspaceSnapshot.builder().resourceIndexComplete(true);
        for (int i=0;i<120;i++) builder.addResource(new ResourceFile("res/values/f"+i+".xml",text.toString()));
        WorkspaceSnapshot large=builder.build();
        AtomicReference<WorkspaceResult> result=new AtomicReference<>();
        CountDownLatch entered=new CountDownLatch(1);
        Thread worker=new Thread(() -> { entered.countDown(); result.set(analyzer.analyzeWorkspace(large)); });
        worker.start(); entered.await(1,TimeUnit.SECONDS); Thread.sleep(15); analyzer.cancel(); worker.join(10_000);
        check(!worker.isAlive() && result.get()!=null,"cancelling a workspace pass terminates cooperatively");
        int cancelled=0; for (AnalysisResult r:result.get().getResources().values())
            if (r.getStatus()==AnalysisResult.Status.CANCELLED) cancelled++;
        check(cancelled==120,"cancelled workspace reports cancellation for every resource rather than publishing partial results");
        WorkspaceSnapshot next=WorkspaceSnapshot.builder().resourceIndexComplete(true)
                .addResource(new ResourceFile("res/values/strings.xml","<resources><string name=\"after\">ok</string></resources>"))
                .build();
        WorkspaceResult fresh=analyzer.analyzeWorkspace(next);
        check(fresh.getResources().get("res/values/strings.xml").getStatus()==AnalysisResult.Status.COMPLETE
                && fresh.isResourceIndexComplete(),"resource index remains reusable after cancellation");
        analyzer.close();
    }
    private static void diagnosticStabilityAndXmlReuse() {
        String original="class X { void f() { int x = 1 } }";
        try (Analyzer analyzer=new Analyzer()) {
            AnalysisResult initial=analyzer.analyze(javaFile("X.java",original));
            Diagnostic error=find(initial,"MISSING_TOKEN");
            check(error!=null,"syntax error exists before an unrelated edit");
            DiagnosticTracker tracker=new DiagnosticTracker();
            tracker.setCurrent(Collections.singletonList(error));
            tracker.applyEdit(0,0,3,original.length()+3);
            check(tracker.isPending() && tracker.getMarks().size()==1 && tracker.getMarks().get(0).isPending(),
                    "unrelated edit preserves a visibly pending finding");
            check(tracker.getMarks().get(0).getStartOffset()==error.getRange().getStartOffset()+3,
                    "pending range shifts after an insertion before it");
            String edited="//\n"+original;
            AnalysisResult stillWrong=analyzer.analyze(javaFile("X.java",edited));
            check(find(stillWrong,"MISSING_TOKEN")!=null,"unrelated edit did not actually fix the error");
            tracker.setCurrent(stillWrong.getDiagnostics());
            check(!tracker.isPending() && find(stillWrong,"MISSING_TOKEN")!=null,"fresh result replaces pending findings");
            Diagnostic fresh=find(stillWrong,"MISSING_TOKEN");
            String repaired=fresh.getQuickFixes().get(0).applyTo(edited);
            tracker.applyEdit(fresh.getRange().getStartOffset(),0,1,repaired.length());
            check(tracker.isPending() && !tracker.getMarks().isEmpty(),"editing the error also waits for reanalysis");
            AnalysisResult fixed=analyzer.analyze(javaFile("X.java",repaired));
            check(find(fixed,"MISSING_TOKEN")==null,"only a fresh analysis confirms the error was fixed");
            tracker.setCurrent(fixed.getDiagnostics());
            check(!tracker.isPending(),"current result ends the pending state");
        }
        String xml="<TextView xmlns:android=\"http://schemas.android.com/apk/res/android\" android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\" android:text=\"@string/title\"/>";
        WorkspaceSnapshot.Builder builder=WorkspaceSnapshot.builder().resourceIndexComplete(false);
        for (int i=0;i<70;i++) builder.addResource(new ResourceFile("res/layout/f"+i+".xml",xml));
        WorkspaceSnapshot snapshot=builder.build();
        try (Analyzer analyzer=new Analyzer()) {
            WorkspaceResult first=analyzer.analyzeWorkspace(snapshot);
            check(first.getParsedXmlCount()==70 && first.getReusedXmlCount()==0,"initial workspace parses all XML files");
            int partial=0;
            for (AnalysisResult r:first.getResources().values()) {
                if (r.getStatus()==AnalysisResult.Status.PARTIAL) partial++;
                check(r.getIncompleteReasons().equals(Collections.singletonList("RESOURCE_INDEX_INCOMPLETE")),
                        "resource coverage is not reported as a parser timeout");
            }
            check(partial==70,"all XML resource-reference checks report incomplete coverage truthfully");
            WorkspaceResult same=analyzer.analyzeWorkspace(snapshot);
            check(same.getParsedXmlCount()==0 && same.getReusedXmlCount()==70,"unchanged resources reuse their index");
            WorkspaceSnapshot changed=snapshot.withResource(new ResourceFile("res/layout/f13.xml",xml.replace("@string/title","@string/other")));
            WorkspaceResult next=analyzer.analyzeWorkspace(changed);
            check(next.getParsedXmlCount()==1 && next.getReusedXmlCount()==69,"one XML edit reparses only that XML document");
            try (Analyzer freshAnalyzer=new Analyzer()) {
                WorkspaceResult fresh=freshAnalyzer.analyzeWorkspace(changed);
                for (String key:next.getResources().keySet())
                    check(signature(next.getResources().get(key)).equals(signature(fresh.getResources().get(key))),
                            "reused XML result matches fresh analysis for "+key);
            }
            WorkspaceSnapshot subset=WorkspaceSnapshot.builder().projectIndexComplete(false)
                    .addSource(javaFile("Partial.java","class Partial { void f() { missing(); } }")).build();
            AnalysisResult part=analyzer.analyzeWorkspace(subset).getSources().get("Partial.java");
            check(part.getIncompleteReasons().contains("PROJECT_INDEX_INCOMPLETE") && find(part,"UNRESOLVED_METHOD")==null,
                    "a source batch does not assert missing methods using an incomplete project index");
            check(!subset.withSource(javaFile("Partial.java","class Partial {}" )).isProjectIndexComplete(),
                    "replacing the live source retains the batch coverage flag");
            WorkspaceSnapshot syntaxIncomplete=WorkspaceSnapshot.builder().projectIndexComplete(true)
                    .addSource(javaFile("Broken.java","class Broken { void f( }"))
                    .addSource(javaFile("Caller.java","class Caller { void f() { missing(); } }")).build();
            AnalysisResult caller=analyzer.analyzeWorkspace(syntaxIncomplete).getSources().get("Caller.java");
            check(caller.getIncompleteReasons().contains("PROJECT_INDEX_INCOMPLETE") && find(caller,"UNRESOLVED_METHOD")==null,
                    "a different syntactically broken source also suppresses absence-based method claims");
            WorkspaceSnapshot validIndex=WorkspaceSnapshot.builder().resourceIndexComplete(true)
                    .addResource(new ResourceFile("res/layout/bad_dimension.xml",
                            "<View xmlns:android=\"http://schemas.android.com/apk/res/android\" android:layout_width=\"banana\" android:layout_height=\"wrap_content\"/>"))
                    .addResource(new ResourceFile("res/values/strings.xml","<resources><string name=\"title\">Hi</string></resources>"))
                    .build();
            WorkspaceResult dimension=analyzer.analyzeWorkspace(validIndex);
            check(dimension.isResourceIndexComplete(),"a dimension finding does not invalidate otherwise valid resource declarations");
            check(find(dimension.getResources().get("res/layout/bad_dimension.xml"),"XML_INVALID_DIMENSION")!=null,
                    "the actual invalid dimension remains visible");
            String layout="<TextView xmlns:android=\"http://schemas.android.com/apk/res/android\" android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\" android:text=\"@string/title\"/>";
            WorkspaceSnapshot withValue=WorkspaceSnapshot.builder().resourceIndexComplete(true)
                    .addResource(new ResourceFile("res/layout/main.xml",layout))
                    .addResource(new ResourceFile("res/values/strings.xml","<resources><string name=\"title\">Hi</string></resources>"))
                    .build();
            check(find(analyzer.analyzeWorkspace(withValue).getResources().get("res/layout/main.xml"),"MISSING_RESOURCE")==null,
                    "existing value resolves before deletion");
            WorkspaceSnapshot removed=WorkspaceSnapshot.builder().resourceIndexComplete(true)
                    .addResource(new ResourceFile("res/layout/main.xml",layout)).build();
            WorkspaceResult afterRemoval=analyzer.analyzeWorkspace(removed);
            check(afterRemoval.getParsedXmlCount()==0 && afterRemoval.getReusedXmlCount()==1
                    && find(afterRemoval.getResources().get("res/layout/main.xml"),"MISSING_RESOURCE")!=null,
                    "removing a resource drops its declarations while reusing unchanged XML");
        }
        StringBuilder pressure=new StringBuilder("class Pressure {");
        for (int i=0;i<20;i++) pressure.append(" private int unused").append(i).append(";");
        pressure.append(" void f() { missing(); } }");
        try (Analyzer bounded=new Analyzer(AnalysisConfig.builder().maxDiagnostics(10).build())) {
            WorkspaceSnapshot input=WorkspaceSnapshot.builder().addSource(javaFile("Pressure.java",pressure.toString())).build();
            AnalysisResult result=bounded.analyzeWorkspace(input).getSources().get("Pressure.java");
            check(result.getStatus()==AnalysisResult.Status.PARTIAL && result.getIncompleteReasons().contains("DIAGNOSTIC_LIMIT")
                    && find(result,"UNRESOLVED_METHOD")!=null,
                    "bounded workspace results prioritize a real error over a flood of unused warnings");
            String manyWithResource=pressure.toString().replace("void f()", "int use = R.string.title; void f()");
            WorkspaceSnapshot multiple=WorkspaceSnapshot.builder().resourceIndexComplete(false)
                    .addSource(javaFile("Pressure.java",manyWithResource)).build();
            AnalysisResult partial=bounded.analyzeWorkspace(multiple).getSources().get("Pressure.java");
            check(partial.getIncompleteReasons().contains("DIAGNOSTIC_LIMIT")
                    && partial.getIncompleteReasons().contains("RESOURCE_INDEX_INCOMPLETE"),
                    "independent resource coverage and diagnostic-limit causes are both preserved");
        }
        AnalysisConfig a=AnalysisConfig.builder().minApi(23).compileApi(34).addAndroidApiType("android.example.Thing",23,0).build();
        AnalysisConfig b=AnalysisConfig.builder().minApi(23).compileApi(34).addAndroidApiType("android.example.Thing",23,0).build();
        AnalysisConfig c=AnalysisConfig.builder().minApi(23).compileApi(35).addAndroidApiType("android.example.Thing",23,0).build();
        check(a.equals(b) && a.hashCode()==b.hashCode(),"equivalent configurations permit engine reuse");
        check(!a.equals(c),"changing the project's compile SDK invalidates the analysis configuration");
    }
    public static void main(String[] args) throws Exception {
        syntaxAndFixes(); androidCompatibility(); projectAndBudgets(); incrementalMatchesFresh(); incrementalUnicodeBoundaries(); workspaceResourcesAndXml(); workspaceJavaInspections(); sourceMemberResolution(); dependencyResolution(); cancelAndClose(); editorLifecycle(); reentrantEditorClose(); closeFromResultCallback(); workspaceEditorLifecycle(); xmlWorkspaceEditorLifecycle(); latestEditorResultWins(); diagnosticStabilityAndXmlReuse(); dependencyVersionSymbols(); cancelWorkspaceAndReuse();
        System.out.println("PASS: " + checks + " host checks (real JNI parsers + editor test doubles)");
    }
}
