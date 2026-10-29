package com.shima.analyzer.sample;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.shima.analyzer.AnalysisHandle;
import com.shima.analyzer.CodeAnalyzer;
import com.shima.analyzer.core.AnalysisResult;
import com.shima.analyzer.core.Diagnostic;
import com.shima.analyzer.core.Language;
import com.shima.analyzer.core.ResourceFile;
import com.shima.analyzer.core.WorkspaceFix;
import com.shima.analyzer.core.WorkspaceSnapshot;
import com.shima.analyzer.ui.DiagnosticActions;
import com.shima.analyzer.ui.EditTextSquiggles;
import java.util.LinkedHashMap;
import java.util.Map;

/** External-AAR example. The resource map is intentionally in memory, not a real project store. */
public final class MainActivity extends Activity {
    private AnalysisHandle handle;
    private AnalysisResult latest;
    private EditText editor;
    private EditTextSquiggles squiggles;
    private final Map<String,String> resources=new LinkedHashMap<>();
    private WorkspaceSnapshot workspace() {
        WorkspaceSnapshot.Builder builder=WorkspaceSnapshot.builder().namespace("demo").resourceIndexComplete(true);
        for (Map.Entry<String,String> entry : resources.entrySet()) builder.addResource(new ResourceFile(entry.getKey(),entry.getValue()));
        return builder.build();
    }
    private final DiagnosticActions.WorkspaceFixListener workspaceFixListener=new DiagnosticActions.WorkspaceFixListener() {
        @Override public void onWorkspaceFix(WorkspaceFix fix) {
            Map<String,String> snapshots=new LinkedHashMap<>(resources);
            snapshots.put("Main.java",editor.getText().toString());
            try {
                Map<String,String> changed=fix.preview(snapshots);
                for (Map.Entry<String,String> entry : changed.entrySet()) {
                    if (entry.getKey().equals("Main.java")) editor.setText(entry.getValue());
                    else resources.put(entry.getKey(),entry.getValue());
                }
                handle.updateWorkspace(workspace());
                Toast.makeText(MainActivity.this,"Updated in-memory sample resource snapshot",Toast.LENGTH_SHORT).show();
            } catch (IllegalStateException stale) {
                Toast.makeText(MainActivity.this,"Workspace changed; request a new analysis",Toast.LENGTH_SHORT).show();
            }
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,24,24,24);
        final TextView status=new TextView(this);
        editor=new EditText(this); editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setText("package demo;\nimport android.widget.BaseAdapter;\n\nclass Main extends BaseAdapter {\n    int key = R.id.key;\n}\n");
        Button actions=new Button(this); actions.setText("Show first diagnostic and fixes");
        root.addView(status); root.addView(editor,new LinearLayout.LayoutParams(-1,0,1)); root.addView(actions);
        setContentView(root);
        squiggles=new EditTextSquiggles(editor);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { latest=null; }
            @Override public void afterTextChanged(Editable editable) { }
        });
        handle=CodeAnalyzer.with(this).attachTo(editor).sourceId("Main.java").language(Language.JAVA).workspace(workspace())
                .onResult(new CodeAnalyzer.ResultListener() {
                    @Override public void onResult(AnalysisResult result) {
                        latest=result; squiggles.setDiagnostics(result.getDiagnostics());
                        int errors=0,warnings=0;
                        for (Diagnostic diagnostic : result.getDiagnostics()) {
                            if (diagnostic.getSeverity()==Diagnostic.Severity.ERROR) errors++;
                            else if (diagnostic.getSeverity()==Diagnostic.Severity.WARNING) warnings++;
                        }
                        StringBuilder text=new StringBuilder(result.getStatus()+" · errors "+errors+" · warnings "+warnings);
                        for (Diagnostic d : result.getDiagnostics()) text.append("\n").append(d.getSeverity()).append(" L").append(d.getRange().getStartLine()).append(": ").append(d.getMessage());
                        status.setText(text);
                    }
                }).onError(new CodeAnalyzer.ErrorListener() {
                    @Override public void onError(Throwable error) { status.setText("Analysis failed: "+error.getMessage()); }
                }).start();
        actions.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (latest!=null && !latest.getDiagnostics().isEmpty())
                    DiagnosticActions.show(MainActivity.this,view,handle,latest.getDiagnostics().get(0),workspaceFixListener);
            }
        });
        editor.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view,MotionEvent event) {
                if (event.getAction()==MotionEvent.ACTION_UP && latest!=null)
                    DiagnosticActions.showAt(MainActivity.this,editor,event.getX(),event.getY(),handle,latest.getDiagnostics(),workspaceFixListener);
                return false; // Keep EditText's normal cursor and keyboard behaviour.
            }
        });
    }
    @Override protected void onDestroy() {
        if (handle!=null) { handle.close(); handle=null; }
        if (squiggles!=null) { squiggles.close(); squiggles=null; }
        latest=null; super.onDestroy();
    }
}
