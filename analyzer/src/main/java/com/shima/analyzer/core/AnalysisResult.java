package com.shima.analyzer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** COMPLETE means analysis finished within budgets, not that a compiler would accept the file.
 * Semantic rules are intentionally skipped while syntax errors are present. */
public final class AnalysisResult {
    public enum Status { COMPLETE, PARTIAL, CANCELLED }
    public enum ParseMode { FULL, INCREMENTAL, CACHED, NONE }
    private final String sourceId;
    private final Language language;
    private final Status status;
    private final ParseMode parseMode;
    private final List<Diagnostic> diagnostics;
    AnalysisResult(SourceFile source, Status status, ParseMode parseMode, List<Diagnostic> diagnostics) {
        sourceId=source.getId(); language=source.getLanguage(); this.status=status; this.parseMode=parseMode;
        this.diagnostics=Collections.unmodifiableList(new ArrayList<>(diagnostics));
    }
    public String getSourceId() { return sourceId; }
    public Language getLanguage() { return language; }
    public Status getStatus() { return status; }
    public ParseMode getParseMode() { return parseMode; }
    public List<Diagnostic> getDiagnostics() { return diagnostics; }
}
