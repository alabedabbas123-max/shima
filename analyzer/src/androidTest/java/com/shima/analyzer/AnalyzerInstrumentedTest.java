package com.shima.analyzer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.shima.analyzer.core.AnalysisResult;
import com.shima.analyzer.core.Analyzer;
import com.shima.analyzer.core.Diagnostic;
import com.shima.analyzer.core.Language;
import com.shima.analyzer.core.QuickFix;
import com.shima.analyzer.core.SourceFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs against the actual Android JNI library; execute on API 21 and current emulators/devices. */
@RunWith(AndroidJUnit4.class)
public final class AnalyzerInstrumentedTest {
    private static Diagnostic find(AnalysisResult result, String code) {
        for (Diagnostic d : result.getDiagnostics()) if (code.equals(d.getCode())) return d;
        return null;
    }

    @Test public void nativeJavaAndKotlinParsersAndIncrementalEdits() {
        try (Analyzer engine = new Analyzer()) {
            AnalysisResult java = engine.analyze(new SourceFile("X.java", Language.JAVA, "class X { int n = \"bad\"; }"));
            assertEquals(AnalysisResult.Status.COMPLETE, java.getStatus());
            assertNotNull(find(java, "INCOMPATIBLE_LITERAL"));
            AnalysisResult updated = engine.analyze(new SourceFile("X.java", Language.JAVA, "class X { int n = 1; }"));
            assertEquals(AnalysisResult.ParseMode.INCREMENTAL, updated.getParseMode());
            AnalysisResult kotlin = engine.analyze(new SourceFile("X.kt", Language.KOTLIN, "class X { val n: Int = \"bad\" }"));
            assertNotNull(find(kotlin, "INCOMPATIBLE_LITERAL"));
        }
    }

    @Test public void editTextCallbackAndFixAreMainThreadSafe() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<AnalysisResult> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<AnalysisHandle> handle = new AtomicReference<>();
        final AtomicReference<EditText> editor = new AtomicReference<>();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
                @Override public void run() {
                    EditText view = new EditText(InstrumentationRegistry.getInstrumentation().getTargetContext());
                    view.setText("class X { List<String> values; }");
                    editor.set(view);
                    handle.set(CodeAnalyzer.with(view.getContext()).attachTo(view).sourceId("X.java").debounceMillis(0)
                        .onResult(new CodeAnalyzer.ResultListener() {
                            @Override public void onResult(AnalysisResult value) {
                                if (Looper.myLooper() != Looper.getMainLooper()) failure.set(new AssertionError("Callback was not on main thread"));
                                result.set(value); latch.countDown();
                            }
                        }).onError(new CodeAnalyzer.ErrorListener() {
                            @Override public void onError(Throwable error) { failure.set(error); latch.countDown(); }
                        }).start());
                }
            });
            assertTrue("Timed out waiting for editor analysis", latch.await(10, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertNotNull(result.get());
            Diagnostic diagnostic = find(result.get(), "POSSIBLE_MISSING_IMPORT");
            assertNotNull(diagnostic);
            final QuickFix fix = diagnostic.getQuickFixes().get(0);
            final AtomicReference<String> updatedText = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
                @Override public void run() {
                    handle.get().applyFix(fix);
                    updatedText.set(editor.get().getText().toString());
                }
            });
            assertTrue(updatedText.get().contains("import java.util.List;"));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
                @Override public void run() { if (handle.get() != null) handle.get().close(); }
            });
        }
    }
}
