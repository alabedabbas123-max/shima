package com.shima.analyzer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A machine-readable finding. Its range refers to the result's exact source snapshot. */
public final class Diagnostic {
    public enum Severity { ERROR, WARNING, INFO }
    private final String code, message;
    private final Severity severity;
    private final SourceRange range;
    private final List<QuickFix> fixes;
    private final List<WorkspaceFix> workspaceFixes;
    Diagnostic(String code, String message, Severity severity, SourceRange range, List<QuickFix> fixes) {
        this(code, message, severity, range, fixes, Collections.<WorkspaceFix>emptyList());
    }
    Diagnostic(String code, String message, Severity severity, SourceRange range, List<QuickFix> fixes, List<WorkspaceFix> workspaceFixes) {
        this.code=code; this.message=message; this.severity=severity; this.range=range;
        this.fixes=Collections.unmodifiableList(new ArrayList<>(fixes));
        this.workspaceFixes=Collections.unmodifiableList(new ArrayList<>(workspaceFixes));
    }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public Severity getSeverity() { return severity; }
    public SourceRange getRange() { return range; }
    public List<QuickFix> getQuickFixes() { return fixes; }
    /** Cross-file edits require an explicit host-controlled workspace transaction. */
    public List<WorkspaceFix> getWorkspaceFixes() { return workspaceFixes; }
}
