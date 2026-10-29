package com.shima.analyzer.core;

import java.util.Objects;

/** Replace [startOffset, endOffset) with replacement, relative to the original snapshot. */
public final class TextEdit {
    private final int startOffset, endOffset;
    private final String replacement;
    public TextEdit(int startOffset, int endOffset, String replacement) {
        if (startOffset < 0 || endOffset < startOffset) throw new IllegalArgumentException("Invalid edit range");
        this.startOffset=startOffset; this.endOffset=endOffset;
        this.replacement=Objects.requireNonNull(replacement, "replacement");
    }
    public int getStartOffset() { return startOffset; }
    public int getEndOffset() { return endOffset; }
    public String getReplacement() { return replacement; }
}
