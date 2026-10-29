package com.shima.analyzer.core;

/** Half-open UTF-16 offsets, plus 1-based line/column coordinates. */
public final class SourceRange {
    private final int startOffset, endOffset, startLine, startColumn, endLine, endColumn;
    SourceRange(int start, int end, int startLine, int startColumn, int endLine, int endColumn) {
        this.startOffset=start; this.endOffset=end; this.startLine=startLine; this.startColumn=startColumn;
        this.endLine=endLine; this.endColumn=endColumn;
    }
    public int getStartOffset() { return startOffset; }
    public int getEndOffset() { return endOffset; }
    public int getStartLine() { return startLine; }
    public int getStartColumn() { return startColumn; }
    public int getEndLine() { return endLine; }
    public int getEndColumn() { return endColumn; }
}
