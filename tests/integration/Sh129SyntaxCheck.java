import com.shima.analyzer.core.AnalysisConfig;
import com.shima.analyzer.core.AnalysisResult;
import com.shima.analyzer.core.Analyzer;
import com.shima.analyzer.core.Diagnostic;
import com.shima.analyzer.core.Language;
import com.shima.analyzer.core.SourceFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Parser smoke test only: this does not replace javac/Android compilation. */
public final class Sh129SyntaxCheck {
  public static void main(String[] args) throws Exception {
    AnalysisConfig config = AnalysisConfig.builder().maxDocumentChars(1_000_000)
        .maxEvents(100_000).maxDiagnostics(2_000).timeoutMillis(20_000).build();
    try (Analyzer analyzer = new Analyzer(config)) {
      for (String path : args) {
        String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        AnalysisResult result = analyzer.analyze(new SourceFile(path, Language.JAVA, text));
        if (result.getStatus() != AnalysisResult.Status.COMPLETE)
          throw new AssertionError("Parser did not finish for " + path + ": " + result.getStatus());
        for (Diagnostic diagnostic : result.getDiagnostics()) {
          if ("SYNTAX_ERROR".equals(diagnostic.getCode()) || "MISSING_TOKEN".equals(diagnostic.getCode()))
            throw new AssertionError(path + ":" + diagnostic.getRange().getStartLine() + " " + diagnostic.getMessage());
        }
      }
    }
    System.out.println("PASS: " + args.length + " Java parser smoke checks (not an Android build)");
  }
}
