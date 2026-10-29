package com.shima.analyzer;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.shima.analyzer.core.AnalysisConfig;
import com.shima.analyzer.core.AnalysisResult;
import com.shima.analyzer.core.Analyzer;
import com.shima.analyzer.core.Language;
import com.shima.analyzer.core.QuickFix;
import com.shima.analyzer.core.SourceFile;
import com.shima.analyzer.core.ResourceFile;
import com.shima.analyzer.core.WorkspaceResult;
import com.shima.analyzer.core.WorkspaceSnapshot;
import com.shima.analyzer.core.TextEdit;
import com.shima.analyzer.editor.EditorAdapter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns one editor subscription and one worker. Close it when the screen is destroyed. */
public final class AnalysisHandle implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker;
    private final Analyzer analyzer;
    private final String sourceId;
    private final Language language;
    private final int debounceMillis;
    private EditorAdapter editor;
    private WorkspaceSnapshot workspace;
    private EditorAdapter.Subscription subscription;
    private CodeAnalyzer.ResultListener listener;
    private CodeAnalyzer.ErrorListener errorListener;
    private Runnable delayed;
    private Future<?> queued;
    private volatile long generation;
    private volatile boolean closed;

    AnalysisHandle(EditorAdapter editor, String sourceId, Language language, AnalysisConfig config, WorkspaceSnapshot workspace, int debounceMillis,
                   CodeAnalyzer.ResultListener listener, CodeAnalyzer.ErrorListener errorListener) {
        this.editor=editor; this.sourceId=sourceId; this.language=language; this.workspace=workspace; this.debounceMillis=debounceMillis;
        this.listener=listener; this.errorListener=errorListener; analyzer=new Analyzer(config);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(1), new ThreadFactory() {
            @Override public Thread newThread(Runnable task) {
                Thread thread=new Thread(task, "shima-analyzer");
                thread.setDaemon(true); thread.setPriority(Thread.NORM_PRIORITY-1);
                return thread;
            }
        });
    }
    void attach() {
        requireMain();
        try {
            subscription = editor.subscribe(new Runnable() { @Override public void run() { requestAnalysis(); } });
            if (subscription == null) throw new IllegalStateException("EditorAdapter returned a null subscription");
            requestAnalysis();
        } catch (RuntimeException | Error e) {
            close();
            throw e;
        }
    }
    /** Debounced; invalidates/cancels previous work immediately. */
    public void requestAnalysis() {
        requireMain();
        if (closed) return;
        final long requested = ++generation;
        analyzer.cancel();
        if (delayed != null) main.removeCallbacks(delayed);
        cancelQueued();
        delayed = new Runnable() {
            @Override public void run() {
                delayed=null;
                if (closed || requested != generation) return;
                final String snapshot;
                try { snapshot=editor.getText(); }
                catch (RuntimeException e) { reportError(requested, e); return; }
                if (snapshot == null) { reportError(requested, new IllegalStateException("EditorAdapter.getText() returned null")); return; }
                final WorkspaceSnapshot requestedWorkspace=workspace;
                queued = worker.submit(new Runnable() {
                    @Override public void run() {
                        if (closed || requested != generation) return;
                        try {
                            SourceFile current=new SourceFile(sourceId, language, snapshot);
                            final AnalysisResult result;
                            if (requestedWorkspace == null) result=analyzer.analyze(current);
                            else if (language == Language.XML) {
                                WorkspaceResult project=analyzer.analyzeWorkspace(requestedWorkspace.withResource(new ResourceFile(sourceId,snapshot)));
                                AnalysisResult xml=project.getResources().get(sourceId);
                                result=xml != null ? xml : analyzer.analyze(current);
                            } else {
                                WorkspaceResult project=analyzer.analyzeWorkspace(requestedWorkspace.withSource(current));
                                result=project.getSources().get(sourceId);
                            }
                            if (result.getStatus() == AnalysisResult.Status.CANCELLED) return;
                            main.post(new Runnable() {
                                @Override public void run() {
                                    if (!closed && requested == generation && listener != null) listener.onResult(result);
                                }
                            });
                        } catch (final RuntimeException | LinkageError error) {
                            main.post(new Runnable() { @Override public void run() { reportError(requested, error); } });
                        }
                    }
                });
            }
        };
        main.postDelayed(delayed, debounceMillis);
    }
    /** Replace project/resource snapshots and schedule a fresh analysis on the main thread. */
    public void updateWorkspace(WorkspaceSnapshot value) {
        requireMain();
        if (closed) throw new IllegalStateException("Session is closed");
        if (value == null) throw new NullPointerException("workspace");
        workspace=value;
        requestAnalysis();
    }
    /** Applies a current fix from this editor, in descending offset order. */
    public void applyFix(QuickFix fix) {
        requireMain();
        if (closed) throw new IllegalStateException("Session is closed");
        if (fix == null || !sourceId.equals(fix.getSourceId())) throw new IllegalArgumentException("Fix belongs to another source");
        String text=editor.getText();
        if (!fix.isApplicableTo(text)) throw new IllegalStateException("The source changed; request a fresh analysis");
        for (TextEdit edit : fix.getEdits()) if (edit.getEndOffset() > text.length()) throw new IllegalStateException("Edit is outside source");
        for (TextEdit edit : fix.getEdits()) editor.replace(edit.getStartOffset(), edit.getEndOffset(), edit.getReplacement());
    }
    private void cancelQueued() {
        if (queued != null) {
            queued.cancel(false);
            if (queued instanceof Runnable) worker.remove((Runnable) queued);
            queued=null;
        }
    }
    private void reportError(long requested, Throwable error) {
        if (closed || requested != generation) return;
        if (errorListener != null) errorListener.onError(error);
        else Log.e("ShimaAnalyzer", "Analysis failed", error);
    }
    @Override public void close() {
        requireMain();
        if (closed) return;
        closed=true; generation++;
        if (delayed != null) { main.removeCallbacks(delayed); delayed=null; }
        main.removeCallbacksAndMessages(null);
        try {
            if (subscription != null) subscription.close();
        } finally {
            subscription=null; analyzer.cancel(); cancelQueued();
            // The engine is closed after any currently running native parse, never on the UI thread.
            worker.execute(new Runnable() { @Override public void run() { analyzer.close(); } });
            worker.shutdown();
            editor=null; workspace=null; listener=null; errorListener=null;
        }
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Editor operations must run on the main thread");
    }
}
