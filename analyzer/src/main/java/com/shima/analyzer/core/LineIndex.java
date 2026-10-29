package com.shima.analyzer.core;

import java.util.Arrays;

/** 0-based UTF-16 points for Tree-sitter, 1-based presentation ranges. */
final class LineIndex {
    private final int[] starts;
    private final int length;
    LineIndex(String text) {
        length = text.length();
        int count = 1;
        for (int i=0; i<text.length(); i++) if (text.charAt(i) == '\n') count++;
        starts = new int[count];
        int line = 1;
        for (int i=0; i<text.length(); i++) if (text.charAt(i) == '\n') starts[line++] = i+1;
    }
    int row(int offset) {
        int index = Arrays.binarySearch(starts, offset);
        return index >= 0 ? index : -index - 2;
    }
    int column(int offset) { return offset - starts[row(offset)]; }
    SourceRange range(int start, int end) {
        start = Math.max(0, Math.min(length, start));
        end = Math.max(start, Math.min(length, end));
        int first = row(start), last = row(end);
        return new SourceRange(start, end, first+1, start-starts[first]+1, last+1, end-starts[last]+1);
    }
}
