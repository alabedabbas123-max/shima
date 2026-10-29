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
        try (Analyzer engine=new Analyzer()) {
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
        AnalysisConfig config=AnalysisConfig.builder().minApi(30).compileApi(35).build();
        try (Analyzer engine=new Analyzer(config)) {
            AnalysisResult r=engine.analyze(javaFile("C.java",src));
            check(find(r,"ANDROID_API_LEVEL")==null, "minApi suppresses older API warning");
            check(find(r,"ANDROID_DEPRECATED")!=null, "deprecation is independent of minApi");
        }
        // The method name below is deliberately synthetic: parser rules do not verify a classpath.
        String extended="import android.window.SplashScreen; import android.os.Trace; " +
                "class Extended { SplashScreen s; void f() { Trace.futureWork(); } }";
        AnalysisConfig.Builder catalogBuilder=AnalysisConfig.builder()
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
        try (Analyzer engine=new Analyzer(AnalysisConfig.builder().addAndroidApiType("android.app.ProgressDialog",1,0).build())) {
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
    public static void main(String[] args) throws Exception {
        syntaxAndFixes(); androidCompatibility(); projectAndBudgets(); incrementalMatchesFresh(); incrementalUnicodeBoundaries(); workspaceResourcesAndXml(); workspaceJavaInspections(); dependencyResolution(); cancelAndClose(); editorLifecycle(); workspaceEditorLifecycle(); xmlWorkspaceEditorLifecycle(); latestEditorResultWins();
        System.out.println("PASS: " + checks + " host checks (real JNI parsers + editor test doubles)");
    }
}
