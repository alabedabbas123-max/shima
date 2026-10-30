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
    private final List<String> incompleteReasons;
    AnalysisResult(SourceFile source, Status status, ParseMode parseMode, List<Diagnostic> diagnostics) {
        this(source,status,parseMode,diagnostics,inferReasons(status,diagnostics));
    }
    AnalysisResult(SourceFile source, Status status, ParseMode parseMode, List<Diagnostic> diagnostics, List<String> reasons) {
        sourceId=source.getId(); language=source.getLanguage(); this.status=status; this.parseMode=parseMode;
        this.diagnostics=Collections.unmodifiableList(new ArrayList<>(diagnostics));
        java.util.LinkedHashSet<String> unique=new java.util.LinkedHashSet<>();
        if (status==Status.PARTIAL && reasons!=null) for (String reason : reasons)
            if (reason!=null && !reason.isEmpty()) unique.add(reason);
        if (status==Status.PARTIAL && unique.isEmpty()) unique.add("UNKNOWN");
        incompleteReasons=Collections.unmodifiableList(new ArrayList<>(unique));
    }
    private static List<String> inferReasons(Status status,List<Diagnostic> diagnostics) {
        ArrayList<String> reasons=new ArrayList<>();
        if (status==Status.PARTIAL) for (Diagnostic d : diagnostics) {
            String c=d.getCode();
            if (c.equals("DOCUMENT_TOO_LARGE") || c.equals("ANALYSIS_LIMIT") || c.equals("ANALYSIS_TIMEOUT")
                    || c.equals("PARSER_FAILURE") || c.equals("PARSER_UNAVAILABLE")
                    || c.equals("XML_ANALYSIS_LIMIT") || c.equals("XML_PARSER_FAILURE")
                    || c.equals("XML_DTD_FORBIDDEN") || c.equals("PROJECT_INDEX_INCOMPLETE")
                    || c.equals("RESOURCE_INDEX_INCOMPLETE")) reasons.add(c);
        }
        return reasons;
    }
    public String getSourceId() { return sourceId; }
    public Language getLanguage() { return language; }
    public Status getStatus() { return status; }
    public ParseMode getParseMode() { return parseMode; }
    public List<Diagnostic> getDiagnostics() { return diagnostics; }
    /** Machine-readable causes of PARTIAL (empty for COMPLETE/CANCELLED), not source-code errors. */
    public List<String> getIncompleteReasons() { return incompleteReasons; }
}
