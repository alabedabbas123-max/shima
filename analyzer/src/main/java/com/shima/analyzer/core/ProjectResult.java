package com.shima.analyzer.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProjectResult {
    private final Map<String, AnalysisResult> files;
    ProjectResult(Map<String, AnalysisResult> files) { this.files=Collections.unmodifiableMap(new LinkedHashMap<>(files)); }
    public Map<String, AnalysisResult> getFiles() { return files; }
    public boolean isComplete() {
        for (AnalysisResult file : files.values()) if (file.getStatus() != AnalysisResult.Status.COMPLETE) return false;
        return true;
    }
}
