package com.shima.analyzer.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import com.shima.analyzer.core.Diagnostic;
import com.shima.analyzer.core.DiagnosticTracker;
import java.util.List;

/** Optional visual layer for EditText. The host supplies diagnostics from the current editor snapshot. */
public final class EditTextSquiggles implements AutoCloseable {
    private EditText editor;
    private final Marks marks=new Marks();
    private final TextWatcher watcher;
    private final View.OnLayoutChangeListener layoutListener;
    private final ViewTreeObserver.OnScrollChangedListener scrollListener;
    private boolean closed;
    public EditTextSquiggles(EditText editor) {
        requireMain(); if (editor==null) throw new NullPointerException("editor"); this.editor=editor;
        watcher=new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                marks.tracker.applyEdit(start,before,count,s.length());
                marks.invalidateSelf();
            }
            @Override public void afterTextChanged(Editable s) { }
        };
        layoutListener=new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v,int l,int t,int r,int b,int oldL,int oldT,int oldR,int oldB) { bounds(); }
        };
        scrollListener=new ViewTreeObserver.OnScrollChangedListener() { @Override public void onScrollChanged() { marks.invalidateSelf(); } };
        editor.addTextChangedListener(watcher); editor.addOnLayoutChangeListener(layoutListener);
        editor.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        bounds(); editor.getOverlay().add(marks);
    }
    /** ERROR = red, WARNING = yellow, INFO is not painted. Replace only with a current result. */
    public void setDiagnostics(List<Diagnostic> diagnostics) {
        requireMain(); if (closed) throw new IllegalStateException("Renderer is closed");
        marks.tracker.setCurrent(diagnostics); marks.invalidateSelf();
    }
    /** True after an edit until the host supplies a result for the latest snapshot. */
    public boolean isPending() { requireMain(); return marks.tracker.isPending(); }
    private void bounds() { if (editor!=null) marks.setBounds(0,0,editor.getWidth(),editor.getHeight()); }
    @Override public void close() {
        requireMain(); if (closed) return; closed=true;
        editor.removeTextChangedListener(watcher); editor.removeOnLayoutChangeListener(layoutListener);
        ViewTreeObserver observer=editor.getViewTreeObserver(); if (observer.isAlive()) observer.removeOnScrollChangedListener(scrollListener);
        editor.getOverlay().remove(marks); marks.tracker.setCurrent(java.util.Collections.<Diagnostic>emptyList()); editor=null;
    }
    private final class Marks extends Drawable {
        final DiagnosticTracker tracker=new DiagnosticTracker();
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path=new Path();
        @Override public void draw(Canvas canvas) {
            if (editor==null || tracker.getMarks().isEmpty()) return;
            Layout layout=editor.getLayout(); if (layout==null) return;
            int length=editor.getText().length(); float density=editor.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1f,density));
            int save=canvas.save();
            canvas.clipRect(editor.getCompoundPaddingLeft(),editor.getExtendedPaddingTop(),
                    editor.getWidth()-editor.getCompoundPaddingRight(),editor.getHeight()-editor.getExtendedPaddingBottom());
            canvas.translate(editor.getCompoundPaddingLeft()-editor.getScrollX(),editor.getExtendedPaddingTop()-editor.getScrollY());
            int visibleFirst=layout.getLineForVertical(editor.getScrollY());
            int visibleLast=layout.getLineForVertical(editor.getScrollY()+Math.max(0,editor.getHeight()-editor.getExtendedPaddingTop()-editor.getExtendedPaddingBottom()));
            float visibleLeft=editor.getScrollX();
            float visibleRight=visibleLeft+Math.max(0,editor.getWidth()-editor.getCompoundPaddingLeft()-editor.getCompoundPaddingRight());
            for (DiagnosticTracker.Mark mark : tracker.getMarks()) {
                Diagnostic diagnostic=mark.getOriginal();
                if (diagnostic.getSeverity()==Diagnostic.Severity.INFO) continue;
                int start=Math.max(0,Math.min(length,mark.getStartOffset()));
                int end=Math.max(start,Math.min(length,mark.getEndOffset()));
                if (start==end && length>0) { if (start==length) start--; else end++; }
                if (start==end) continue;
                paint.setColor(diagnostic.getSeverity()==Diagnostic.Severity.ERROR ? Color.rgb(239,83,80) : Color.rgb(255,213,79));
                paint.setAlpha(mark.isPending() ? 150 : 255);
                int first=Math.max(visibleFirst,layout.getLineForOffset(start)), last=Math.min(visibleLast,layout.getLineForOffset(Math.max(start,end-1)));
                for (int line=first;line<=last;line++) {
                    int from=Math.max(start,layout.getLineStart(line)), to=Math.min(end,layout.getLineVisibleEnd(line));
                    if (to<=from) continue;
                    float a=layout.getPrimaryHorizontal(from), b=layout.getPrimaryHorizontal(to);
                    float left=Math.max(visibleLeft,Math.min(a,b)), right=Math.min(visibleRight,Math.max(a,b));
                    if (right<=left) continue;
                    if (right-left<1f) right=Math.min(visibleRight,left+3f*density);
                    float y=layout.getLineBottom(line)-Math.max(2f,density*2f);
                    float step=Math.max(2f,density*2f), amplitude=Math.max(1f,density);
                    path.reset(); path.moveTo(left,y); boolean up=true;
                    for (float x=left+step;x<right;x+=step) { path.lineTo(x,y+(up?-amplitude:amplitude)); up=!up; }
                    path.lineTo(right,y); canvas.drawPath(path,paint);
                }
            }
            canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    private static void requireMain() { if (Looper.myLooper()!=Looper.getMainLooper()) throw new IllegalStateException("Renderer operations must run on main thread"); }
}
