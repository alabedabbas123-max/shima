package com.shima.analyzer.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.shima.analyzer.dependencies.MavenInstaller;

/** Optional AIDE-inspired UI. Create/show/close on main; installation stays on the host's worker. */
public final class DependencyProgressDialog implements MavenInstaller.Listener, AutoCloseable {
    private static final int ACCENT=Color.rgb(3,169,244);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final MavenInstaller.Cancellation cancellation;
    private AlertDialog dialog;
    private TextView artifact;
    private ProgressBar progress;
    private volatile boolean closed;
    private final Object progressLock=new Object();
    private MavenInstaller.Progress latest;
    private boolean progressPosted;

    public DependencyProgressDialog(Activity activity, MavenInstaller.Cancellation cancellation) {
        requireMain();
        if (activity==null || cancellation==null) throw new NullPointerException("activity/cancellation");
        this.cancellation=cancellation;
        int pad=(int) (activity.getResources().getDisplayMetrics().density*20+0.5f);
        LinearLayout row=new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad,pad,pad,pad); row.setBackgroundColor(Color.rgb(48,48,48));
        ProgressBar spinner=new ProgressBar(activity,null,android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        row.addView(spinner);
        LinearLayout details=new LinearLayout(activity); details.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams detailsParams=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1);
        detailsParams.leftMargin=pad; row.addView(details,detailsParams);
        artifact=new TextView(activity); artifact.setTextColor(Color.LTGRAY); artifact.setTextSize(15);
        artifact.setSingleLine(true); artifact.setEllipsize(TextUtils.TruncateAt.END); artifact.setText("Preparing dependencies…");
        details.addView(artifact,new LinearLayout.LayoutParams(-1,-2));
        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT)); progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        progress.setIndeterminate(true); progress.setMax(1000);
        LinearLayout.LayoutParams barParams=new LinearLayout.LayoutParams(-1,-2); barParams.topMargin=pad/2;
        details.addView(progress,barParams);
        dialog=new AlertDialog.Builder(activity,AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Downloading Maven Libraries").setView(row)
                .setNegativeButton("CANCEL",new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d,int which) { DependencyProgressDialog.this.cancellation.cancel(); }
                }).setPositiveButton("HIDE",new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d,int which) { /* Hide does not cancel the worker. */ }
                }).create();
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface d) { DependencyProgressDialog.this.cancellation.cancel(); }
        });
    }
    public void show() {
        requireMain(); if (closed) throw new IllegalStateException("Dialog is closed");
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(ACCENT);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(ACCENT);
        MavenInstaller.Progress event; synchronized (progressLock) { event=latest; }
        if (event!=null) render(event);
    }
    public void hide() { requireMain(); if (!closed && dialog.isShowing()) dialog.dismiss(); }
    @Override public void onProgress(MavenInstaller.Progress event) {
        synchronized (progressLock) {
            if (closed) return;
            latest=event;
            if (progressPosted) return;
            progressPosted=true;
        }
        main.post(new Runnable() { @Override public void run() {
            MavenInstaller.Progress current;
            synchronized (progressLock) { current=latest; progressPosted=false; }
            if (!closed && current!=null) render(current);
        }});
    }
    private void render(MavenInstaller.Progress event) {
        if (dialog==null || !dialog.isShowing()) return;
        artifact.setText(event.getArtifact());
        long total=event.getTotalBytes();
        if (total<=0) progress.setIndeterminate(true);
        else {
            progress.setIndeterminate(false);
            progress.setProgress((int) Math.min(1000L, event.getBytes()*1000L/total));
        }
    }
    /** Releases Activity references; hiding does not cancel the installation. */
    @Override public void close() {
        requireMain(); if (closed) return; closed=true;
        synchronized (progressLock) { latest=null; progressPosted=false; }
        main.removeCallbacksAndMessages(null);
        if (dialog!=null) dialog.dismiss();
        dialog=null; artifact=null; progress=null;
    }
    private static void requireMain() {
        if (Looper.myLooper()!=Looper.getMainLooper()) throw new IllegalStateException("Dialog operations must run on main thread");
    }
}
