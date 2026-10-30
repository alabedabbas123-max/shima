package com.shima.analyzer.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Results for source files and XML resources from the same workspace snapshot. */
public final class WorkspaceResult {
    private final Map<String,AnalysisResult> sources, resources;
    private final boolean resourceIndexComplete;
    private final int parsedXmlCount, reusedXmlCount;
    WorkspaceResult(Map<String,AnalysisResult> sources, Map<String,AnalysisResult> resources, boolean complete) {
        this(sources,resources,complete,0,0);
    }
    WorkspaceResult(Map<String,AnalysisResult> sources, Map<String,AnalysisResult> resources, boolean complete, int parsedXmlCount, int reusedXmlCount) {
        this.sources=Collections.unmodifiableMap(new LinkedHashMap<>(sources));
        this.resources=Collections.unmodifiableMap(new LinkedHashMap<>(resources));
        resourceIndexComplete=complete;
        this.parsedXmlCount=parsedXmlCount; this.reusedXmlCount=reusedXmlCount;
    }
    public Map<String,AnalysisResult> getSources() { return sources; }
    public Map<String,AnalysisResult> getResources() { return resources; }
    public boolean isResourceIndexComplete() { return resourceIndexComplete; }
    /** Number of XML documents parsed in this run; reused documents are not re-parsed. */
    public int getParsedXmlCount() { return parsedXmlCount; }
    public int getReusedXmlCount() { return reusedXmlCount; }
}
