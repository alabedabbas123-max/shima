package com.shima.analyzer.editor;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import java.util.Objects;

/** Default adapter; no editor widget or UI implementation is required by the core engine. */
public final class EditTextAdapter implements EditorAdapter {
    private final EditText editor;
    public EditTextAdapter(EditText editor) { this.editor = Objects.requireNonNull(editor, "editor"); }
    @Override public String getText() { return editor.getText().toString(); }
    @Override public void replace(int startOffset, int endOffset, String replacement) {
        editor.getText().replace(startOffset, endOffset, replacement);
    }
    @Override public Subscription subscribe(final Runnable onChange) {
        Objects.requireNonNull(onChange, "onChange");
        final TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { onChange.run(); }
        };
        editor.addTextChangedListener(watcher);
        return new Subscription() {
            private boolean closed;
            @Override public void close() {
                if (!closed) { closed=true; editor.removeTextChangedListener(watcher); }
            }
        };
    }
}
