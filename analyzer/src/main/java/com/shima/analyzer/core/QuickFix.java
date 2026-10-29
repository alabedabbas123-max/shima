package com.shima.analyzer.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable edits bound to the exact source snapshot which produced them. */
public final class QuickFix {
    private final String title, sourceId;
    private final byte[] fingerprint;
    private final List<TextEdit> edits;

    QuickFix(String title, String sourceId, byte[] fingerprint, List<TextEdit> edits) {
        this.title=Objects.requireNonNull(title, "title"); this.sourceId=Objects.requireNonNull(sourceId, "sourceId");
        this.fingerprint=fingerprint.clone();
        ArrayList<TextEdit> copy = new ArrayList<>(edits);
        Collections.sort(copy, new Comparator<TextEdit>() {
            @Override public int compare(TextEdit a, TextEdit b) { return Integer.compare(b.getStartOffset(), a.getStartOffset()); }
        });
        int previousStart = Integer.MAX_VALUE;
        for (TextEdit edit : copy) {
            if (edit.getEndOffset() > previousStart) throw new IllegalArgumentException("Overlapping edits");
            previousStart = edit.getStartOffset();
        }
        this.edits=Collections.unmodifiableList(copy);
    }
    public String getTitle() { return title; }
    public String getSourceId() { return sourceId; }
    /** Edits are sorted from end to start, so offsets remain valid when applied in order. */
    public List<TextEdit> getEdits() { return edits; }
    public boolean isApplicableTo(String currentText) {
        return currentText != null && MessageDigest.isEqual(fingerprint, fingerprint(currentText));
    }
    public String applyTo(String currentText) {
        if (!isApplicableTo(currentText)) throw new IllegalStateException("The source changed; request a fresh analysis before applying this fix");
        StringBuilder builder = new StringBuilder(currentText);
        for (TextEdit edit : edits) {
            if (edit.getEndOffset() > currentText.length()) throw new IllegalStateException("Edit is outside source");
            builder.replace(edit.getStartOffset(), edit.getEndOffset(), edit.getReplacement());
        }
        return builder.toString();
    }
    static byte[] fingerprint(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Hash UTF-16 code units, including unpaired surrogates, without a
            // second full-sized byte array or lossy charset replacement.
            byte[] block = new byte[4096];
            int used = 0;
            for (int i=0; i<text.length(); i++) {
                char c = text.charAt(i);
                block[used++] = (byte) (c >>> 8); block[used++] = (byte) c;
                if (used == block.length) { digest.update(block, 0, used); used = 0; }
            }
            digest.update(block, 0, used);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
