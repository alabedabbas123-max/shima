package com.shima.analyzer.editor;

/**
 * Minimal bridge for a custom editor. All methods and notifications must run on
 * Android's main thread; getText returns an immutable snapshot.
 */
public interface EditorAdapter {
    String getText();
    void replace(int startOffset, int endOffset, String replacement);
    Subscription subscribe(Runnable onChange);

    interface Subscription extends AutoCloseable {
        @Override void close();
    }
}
