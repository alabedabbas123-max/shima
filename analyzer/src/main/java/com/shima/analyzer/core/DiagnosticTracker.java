package com.shima.analyzer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Keeps the previous findings visible while a newer snapshot is being analyzed.
 * Offsets are approximate after an edit; a pending finding is not confirmation
 * that the current text still has the error. Never apply its old fixes.
 * The host serializes calls, normally on its editor's main thread.
 */
public final class DiagnosticTracker {
    public static final class Mark {
        private final Diagnostic original;
        private final int start, end;
        private final boolean pending;
        private Mark(Diagnostic original, int start, int end, boolean pending) {
            this.original=original; this.start=start; this.end=end; this.pending=pending;
        }
        /** The original finding belongs to the older snapshot when isPending() is true. */
        public Diagnostic getOriginal() { return original; }
        public int getStartOffset() { return start; }
        public int getEndOffset() { return end; }
        public boolean isPending() { return pending; }
    }
    private List<Mark> marks=Collections.emptyList();
    private boolean pending;

    /** Replace only with findings computed for the current document revision. */
    public void setCurrent(List<Diagnostic> diagnostics) {
        if (diagnostics==null) throw new NullPointerException("diagnostics");
        ArrayList<Mark> next=new ArrayList<>(diagnostics.size());
        for (Diagnostic d : diagnostics) if (d!=null)
            next.add(new Mark(d,d.getRange().getStartOffset(),d.getRange().getEndOffset(),false));
        marks=Collections.unmodifiableList(next); pending=false;
    }

    /**
     * Track one UTF-16 replacement: [start,start+removed) becomes inserted
     * code units, and newLength is the current document length. Overlapping
     * markers are kept at an approximate anchor, not declared fixed.
     */
    public void applyEdit(int start,int removed,int inserted,int newLength) {
        long oldLength=(long)newLength-inserted+removed;
        if (start<0 || removed<0 || inserted<0 || newLength<0 || oldLength<0
                || (long)start+removed>oldLength || (long)start+inserted>newLength)
            throw new IllegalArgumentException("Invalid document edit");
        int oldEnd=start+removed, newEnd=start+inserted, delta=inserted-removed;
        ArrayList<Mark> next=new ArrayList<>(marks.size());
        for (Mark mark : marks) {
            int a=mark.start, b=mark.end;
            if (oldEnd<=a) { a+=delta; b+=delta; }
            else if (start<b) {
                a=Math.min(a,start);
                b=Math.max(newEnd, b>oldEnd ? b+delta : newEnd);
            }
            a=Math.max(0,Math.min(newLength,a));
            b=Math.max(a,Math.min(newLength,b));
            next.add(new Mark(mark.original,a,b,true));
        }
        marks=Collections.unmodifiableList(next); pending=true;
    }
    public boolean isPending() { return pending; }
    public List<Mark> getMarks() { return marks; }
}
