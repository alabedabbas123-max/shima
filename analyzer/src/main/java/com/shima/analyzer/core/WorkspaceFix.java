package com.shima.analyzer.core;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cross-file suggestion. Preview validates every source/target snapshot and
 * returns changed texts only; the host must commit them atomically, rechecking
 * the same snapshots immediately before writing. It never performs file I/O.
 */
public final class WorkspaceFix {
    private final String title;
    private final Map<String, byte[]> expected;
    private final Map<String, List<TextEdit>> edits;

    WorkspaceFix(String title, Map<String, String> snapshots, Map<String, List<TextEdit>> changes) {
        this.title=Objects.requireNonNull(title, "title");
        LinkedHashMap<String, byte[]> hashes=new LinkedHashMap<>();
        for (Map.Entry<String,String> entry : snapshots.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty()) throw new IllegalArgumentException("Missing file id");
            hashes.put(entry.getKey(), entry.getValue() == null ? null : QuickFix.fingerprint(entry.getValue()));
        }
        LinkedHashMap<String, List<TextEdit>> ordered=new LinkedHashMap<>();
        for (Map.Entry<String,List<TextEdit>> entry : changes.entrySet()) {
            if (!hashes.containsKey(entry.getKey())) throw new IllegalArgumentException("Every target needs a snapshot guard");
            ArrayList<TextEdit> list=new ArrayList<>(entry.getValue());
            Collections.sort(list, new Comparator<TextEdit>() {
                @Override public int compare(TextEdit a, TextEdit b) { return Integer.compare(b.getStartOffset(), a.getStartOffset()); }
            });
            int previous=Integer.MAX_VALUE;
            for (TextEdit edit : list) {
                if (edit.getEndOffset() > previous) throw new IllegalArgumentException("Overlapping edits");
                previous=edit.getStartOffset();
            }
            ordered.put(entry.getKey(), Collections.unmodifiableList(list));
        }
        if (ordered.isEmpty()) throw new IllegalArgumentException("No changes");
        expected=Collections.unmodifiableMap(hashes);
        edits=Collections.unmodifiableMap(ordered);
    }
    public String getTitle() { return title; }
    public Map<String, List<TextEdit>> getEdits() { return edits; }
    public java.util.Set<String> getGuardedIds() { return expected.keySet(); }
    /** Missing map keys represent nonexistent files. Null values are not valid snapshots. */
    public Map<String,String> preview(Map<String,String> current) {
        Objects.requireNonNull(current, "current");
        for (Map.Entry<String,byte[]> entry : expected.entrySet()) {
            String now=current.get(entry.getKey());
            byte[] hash=entry.getValue();
            if (hash == null) {
                if (current.containsKey(entry.getKey())) throw new IllegalStateException("A target file already exists: " + entry.getKey());
            } else if (now == null || !MessageDigest.isEqual(hash, QuickFix.fingerprint(now))) {
                throw new IllegalStateException("Workspace changed: " + entry.getKey());
            }
        }
        LinkedHashMap<String,String> changed=new LinkedHashMap<>();
        for (Map.Entry<String,List<TextEdit>> entry : edits.entrySet()) {
            String original=current.get(entry.getKey());
            if (original == null) original="";
            StringBuilder result=new StringBuilder(original);
            for (TextEdit edit : entry.getValue()) {
                if (edit.getEndOffset() > original.length()) throw new IllegalStateException("Edit is outside source");
                result.replace(edit.getStartOffset(), edit.getEndOffset(), edit.getReplacement());
            }
            changed.put(entry.getKey(), result.toString());
        }
        return Collections.unmodifiableMap(changed);
    }
}
