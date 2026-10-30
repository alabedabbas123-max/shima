import almlk.AnalyzerProtocol;
import almlk.JavaCodeAnalyzer;
import almlk.JavaDiagnosticCodes;
import almlk.LiveDiagnostics;
import com.almlkid.analyzer.AnalyzerWire;
import com.almlkid.kay.ui.CodeLanguageTools;
import java.util.Collections;
import java.util.List;

/** Legacy app diagnostics on the JVM, using its actual parsers and attribution code. */
public final class Sh129LegacyRegression {
  private static int checks;
  private static void check(boolean ok,String message) { checks++; if (!ok) throw new AssertionError(message); }
  private static boolean errorAt(List<CodeLanguageTools.Diagnostic> ds,int offset) {
    for (CodeLanguageTools.Diagnostic d:ds) if (d.severity==CodeLanguageTools.Diagnostic.Severity.ERROR
        && d.offset<=offset && offset<d.end) return true;
    return false;
  }
  public static void main(String[] args) {
    String text="package demo; class K { // CREATE TABLE Banner Context KEY\n"
        +" static final String TAG=\"perfectkeboard\"; static final int CPS_BUFFER_SIZEE=16;"
        +" static boolean DEBUG=false, TRACE=false; static final boolean VOICE_INSTALLED=true, ENABLE_VOICE_BUTTON=true;"
        +" void f(){ String sql=\"CREATE TABLE Banner (KEY INTEGER)\"; } }";
    List<CodeLanguageTools.Diagnostic> ds=CodeLanguageTools.inspect("K.java",text);
    for (String valid:new String[]{"CREATE TABLE Banner", "TAG", "CPS_BUFFER_SIZEE", "DEBUG", "TRACE", "VOICE_INSTALLED", "ENABLE_VOICE_BUTTON", "CREATE TABLE Banner (KEY"})
      check(!errorAt(ds,text.indexOf(valid)),"no error over valid comment, constant or string: "+valid+" => "+ds);
    String dup="class A { class Duplicate {} class Duplicate {} void f(){ String s=\"Duplicate\"; } }";
    List<CodeLanguageTools.Diagnostic> duplicates=CodeLanguageTools.inspect("A.java",dup);
    int first=dup.indexOf("Duplicate"), second=dup.indexOf("Duplicate",first+1), string=dup.lastIndexOf("Duplicate");
    int seen=0;
    for (CodeLanguageTools.Diagnostic d:duplicates) if ("ui_diagnostic_duplicate_class".equals(d.messageKey)) {
      if (d.offset==first || d.offset==second) seen++;
      check(d.offset!=string,"duplicate report is not manufactured inside a string");
    }
    check(seen==2,"duplicate class reports both declaration sites");
    AnalyzerProtocol.Finding f=new AnalyzerProtocol.Finding(first,first+9,1,1,AnalyzerProtocol.SEVERITY_ERROR,
        "DUP","duplicate","ui_diagnostic_duplicate_class",new char[]{'s'},new String[]{"Duplicate"},new AnalyzerProtocol.Fix[0]);
    AnalyzerProtocol.Finding[] wire=AnalyzerWire.withOccurrences(dup,new AnalyzerProtocol.Finding[]{f});
    check(!wire[0].hasOccurrences(),"wire does not expand same spelling into unrelated references or strings");
    check(JavaDiagnosticCodes.isUnused("REDUNDANT","ui_diagnostic_unused_import"),"unused imports classified as editor-only hints");
    CodeLanguageTools.Diagnostic old=CodeLanguageTools.Diagnostic.restored(0,1,1,1,CodeLanguageTools.Diagnostic.Severity.ERROR,"ERR","old",null,null);
    List<CodeLanguageTools.Diagnostic> partial=LiveDiagnostics.mergePartial(Collections.singletonList(old),Collections.emptyList());
    check(partial.size()==1 && LiveDiagnostics.isPending(partial.get(0)) && partial.get(0).quickFixes.isEmpty(),"partial result retains previous finding without its fix");
    String huge=new String(new char[JavaCodeAnalyzer.semanticHardStopChars()+1]).replace('\0',' ');
    CodeLanguageTools.inspect("Huge.java",huge);
    check(JavaCodeAnalyzer.assessmentIncomplete(),"semantic hard stop is reported as partial rather than silently complete");
    CodeLanguageTools.inspect("OK.java","class OK {}");
    check(!JavaCodeAnalyzer.assessmentIncomplete(),"partial state resets for the next file");
    check(CodeLanguageTools.inspect("build.gradle","task test { println / text [ / }").isEmpty()
        && JavaCodeAnalyzer.assessmentIncomplete(),
        "Groovy slashy text is not judged by a generic bracket heuristic");
    check(CodeLanguageTools.inspect("README.md","A plain [ character").isEmpty()
        && JavaCodeAnalyzer.assessmentIncomplete(),"unsupported text is explicit partial, not a false syntax error");
    check(CodeLanguageTools.inspect("screen.html","<br><input>").isEmpty()
        && JavaCodeAnalyzer.assessmentIncomplete(),"HTML is not falsely judged as strict XML");
    check(CodeLanguageTools.inspect("X.kt","fun x() = \"[\"").isEmpty()
        && JavaCodeAnalyzer.assessmentIncomplete(),"legacy Kotlin path defers to the SDK parser");
    String platform="C\tjava.lang.Object\tc\t1\t0\t0\t\t\t\t\tjava.lang\n";
    almlk.AndroidApiIndex.install(name -> new java.io.ByteArrayInputStream(
        platform.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    check(almlk.AndroidApiIndex.classByQualifiedName("java.lang.Object")!=null,
        "the attribution test has a loaded platform root");
    String uncertain="package demo; class Child extends UnknownExternal { void existing(){} void f(){ inheritedMethod(); int x=inheritedField; this.otherInherited(); int y=this.otherField; this.existing(1); } }";
    almlk.JavaProjectModel.clear();
    almlk.JavaProjectModel.register("Child.java",uncertain);
    check(almlk.JavaProjectModel.absenceProvablyComplete(),"the attribution test has a completed source index");
    List<CodeLanguageTools.Diagnostic> uncertainRows=CodeLanguageTools.inspect("Child.java",uncertain);
    check(!errorAt(uncertainRows,uncertain.indexOf("inheritedMethod"))
        && !errorAt(uncertainRows,uncertain.indexOf("inheritedField"))
        && !errorAt(uncertainRows,uncertain.indexOf("otherInherited"))
        && !errorAt(uncertainRows,uncertain.indexOf("otherField"))
        && !errorAt(uncertainRows,uncertain.indexOf("existing(1)")),
        "unindexed external ancestry cannot prove an inherited member absent: "+uncertainRows);
    check(!errorAt(uncertainRows,uncertain.indexOf("UnknownExternal")),
        "an unindexed dependency does not prove a simple type absent");
    String certain="package demo; class Stable { void f(){ this.noMethod(); int n=this.noField; } }";
    almlk.JavaProjectModel.clear(); almlk.JavaProjectModel.register("Stable.java",certain);
    List<CodeLanguageTools.Diagnostic> certainRows=CodeLanguageTools.inspect("Stable.java",certain);
    check(errorAt(certainRows,certain.indexOf("noMethod")) && errorAt(certainRows,certain.indexOf("noField")),
        "a reliably closed local hierarchy still reports missing members: "+certainRows);
    almlk.JavaProjectModel.clear();
    System.out.println("PASS: "+checks+" sh129 legacy diagnostic checks (JVM, actual app analysis code)");
  }
}
