package com.shima.analyzer.ui;

import android.app.Activity;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.EditText;
import java.util.List;
import android.widget.Toast;
import com.shima.analyzer.AnalysisHandle;
import com.shima.analyzer.core.Diagnostic;
import com.shima.analyzer.core.QuickFix;
import com.shima.analyzer.core.WorkspaceFix;

/** Optional Toast + action menu; workspace writes remain the host's responsibility. */
public final class DiagnosticActions {
    public interface WorkspaceFixListener { void onWorkspaceFix(WorkspaceFix fix); }
    private DiagnosticActions() { }
    /** The host can call this from its existing touch handler without replacing editor behaviour. */
    public static boolean showAt(Activity activity, EditText editor, float x, float y, AnalysisHandle handle,
                                 List<Diagnostic> diagnostics, WorkspaceFixListener listener) {
        if (Looper.myLooper()!=Looper.getMainLooper()) throw new IllegalStateException("Show actions on the main thread");
        if (editor==null || diagnostics==null) throw new NullPointerException("editor/diagnostics");
        int offset=editor.getOffsetForPosition(x,y);
        for (Diagnostic diagnostic : diagnostics) {
            int start=diagnostic.getRange().getStartOffset(), end=diagnostic.getRange().getEndOffset();
            if (offset>=start && (offset<end || (start==end && offset==start))) {
                show(activity,editor,handle,diagnostic,listener); return true;
            }
        }
        return false;
    }
    public static void show(final Activity activity, View anchor, final AnalysisHandle handle,
                            final Diagnostic diagnostic, final WorkspaceFixListener workspaceListener) {
        if (Looper.myLooper()!=Looper.getMainLooper()) throw new IllegalStateException("Show actions on the main thread");
        if (activity==null || anchor==null || diagnostic==null) throw new NullPointerException("activity/anchor/diagnostic");
        Toast.makeText(activity,diagnostic.getMessage(),Toast.LENGTH_LONG).show();
        final int localCount=diagnostic.getQuickFixes().size();
        final int workspaceCount=workspaceListener==null ? 0 : diagnostic.getWorkspaceFixes().size();
        if (localCount+workspaceCount==0) return;
        PopupMenu menu=new PopupMenu(activity,anchor);
        for (int i=0;i<localCount;i++) menu.getMenu().add(0,i, i, diagnostic.getQuickFixes().get(i).getTitle());
        for (int i=0;i<workspaceCount;i++) menu.getMenu().add(0,localCount+i,localCount+i,diagnostic.getWorkspaceFixes().get(i).getTitle());
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                int id=item.getItemId();
                if (id<localCount) {
                    if (handle==null) return false;
                    QuickFix fix=diagnostic.getQuickFixes().get(id);
                    try { handle.applyFix(fix); }
                    catch (IllegalStateException stale) { Toast.makeText(activity,"Text changed; request a new analysis.",Toast.LENGTH_SHORT).show(); }
                } else if (workspaceListener!=null && id-localCount<workspaceCount) workspaceListener.onWorkspaceFix(diagnostic.getWorkspaceFixes().get(id-localCount));
                return true;
            }
        });
        menu.show();
    }
}
