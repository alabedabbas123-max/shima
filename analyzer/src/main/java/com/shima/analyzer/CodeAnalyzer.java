package com.shima.analyzer;

import android.content.Context;
import android.os.Looper;
import android.widget.EditText;
import com.shima.analyzer.core.AnalysisConfig;
import com.shima.analyzer.core.AnalysisResult;
import com.shima.analyzer.core.Language;
import com.shima.analyzer.core.WorkspaceSnapshot;
import com.shima.analyzer.editor.EditTextAdapter;
import com.shima.analyzer.editor.EditorAdapter;
import java.util.Objects;

/** Fluent entry point. The Context is checked but is never retained. */
public final class CodeAnalyzer {
    private CodeAnalyzer() { }
    public static Builder with(Context context) {
        Objects.requireNonNull(context, "context");
        return new Builder();
    }
    public interface ResultListener { void onResult(AnalysisResult result); }
    public interface ErrorListener { void onError(Throwable error); }

    public static final class Builder {
        private EditorAdapter editor;
        private Language language=Language.JAVA;
        private String sourceId="editor.java";
        private AnalysisConfig config=AnalysisConfig.builder().build();
        private WorkspaceSnapshot workspace;
        private int debounceMillis=250;
        private ResultListener resultListener;
        private ErrorListener errorListener;
        private Builder() { }
        public Builder attachTo(EditText editText) { editor=new EditTextAdapter(editText); return this; }
        public Builder attachTo(EditorAdapter adapter) { editor=Objects.requireNonNull(adapter, "adapter"); return this; }
        public Builder language(Language value) { language=Objects.requireNonNull(value, "language"); return this; }
        public Builder sourceId(String value) {
            if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("sourceId must not be empty");
            sourceId=value; return this;
        }
        public Builder config(AnalysisConfig value) { config=Objects.requireNonNull(value, "config"); return this; }
        /** Optional immutable project snapshot for resource and structural inspections. */
        public Builder workspace(WorkspaceSnapshot value) { workspace=Objects.requireNonNull(value, "workspace"); return this; }
        public Builder debounceMillis(int value) {
            if (value < 0 || value > 30_000) throw new IllegalArgumentException("debounceMillis must be 0..30000");
            debounceMillis=value; return this;
        }
        public Builder onResult(ResultListener value) { resultListener=Objects.requireNonNull(value, "listener"); return this; }
        public Builder onError(ErrorListener value) { errorListener=Objects.requireNonNull(value, "listener"); return this; }
        /** Attach and request initial analysis. Call start, applyFix and close on the main thread. */
        public AnalysisHandle start() {
            if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("start() must run on the main thread");
            if (editor == null) throw new IllegalStateException("attachTo must be called before start");
            AnalysisHandle handle = new AnalysisHandle(editor, sourceId, language, config, workspace, debounceMillis, resultListener, errorListener);
            handle.attach();
            return handle;
        }
    }
}
