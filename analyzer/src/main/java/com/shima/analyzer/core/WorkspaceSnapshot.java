package com.shima.analyzer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable host-supplied project snapshots; no Gradle or disk scanning is implied. */
public final class WorkspaceSnapshot {
    private final List<SourceFile> sources;
    private final List<ResourceFile> resources;
    private final boolean resourcesComplete, projectIndexComplete, enforceOverride;
    private final String namespace, idsFile;

    private WorkspaceSnapshot(Builder b) {
        Set<String> ids=new LinkedHashSet<>();
        for (SourceFile s : b.sources) {
            if (s.getLanguage() == Language.XML) throw new IllegalArgumentException("Use addResource for XML");
            if (!ids.add(s.getId())) throw new IllegalArgumentException("Duplicate file id: " + s.getId());
        }
        for (ResourceFile r : b.resources) if (!ids.add(r.getPath())) throw new IllegalArgumentException("Duplicate file id: " + r.getPath());
        if (b.idsFile == null || !b.idsFile.matches("(?:[A-Za-z0-9_.-]+/)*res/values/ids\\.xml"))
            throw new IllegalArgumentException("idsFile must be a relative res/values/ids.xml path");
        new ResourceFile(b.idsFile, null); // Also reject traversal segments in a proposed edit target.
        if (b.namespace != null && !b.namespace.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
            throw new IllegalArgumentException("Invalid Android namespace");
        sources=Collections.unmodifiableList(new ArrayList<>(b.sources));
        resources=Collections.unmodifiableList(new ArrayList<>(b.resources));
        resourcesComplete=b.resourcesComplete; projectIndexComplete=b.projectIndexComplete; enforceOverride=b.enforceOverride;
        namespace=b.namespace; idsFile=b.idsFile;
    }
    public static Builder builder() { return new Builder(); }
    public List<SourceFile> getSources() { return sources; }
    public List<ResourceFile> getResources() { return resources; }
    /** The host explicitly asserts that project and dependency resources are all included. */
    public boolean areResourcesComplete() { return resourcesComplete; }
    /** False for a subset/batch of the project's sources; absence-based checks are suppressed. */
    public boolean isProjectIndexComplete() { return projectIndexComplete; }
    public boolean isOverrideEnforced() { return enforceOverride; }
    public String getNamespace() { return namespace; }
    public String getIdsFile() { return idsFile; }
    /** Replaces an editor snapshot while retaining the other files/resources. */
    public WorkspaceSnapshot withSource(SourceFile replacement) {
        Builder b=builder().resourceIndexComplete(resourcesComplete).projectIndexComplete(projectIndexComplete).requireOverride(enforceOverride).idsFile(idsFile);
        if (namespace != null) b.namespace(namespace);
        boolean replaced=false;
        for (SourceFile source : sources) {
            if (source.getId().equals(replacement.getId())) { b.addSource(replacement); replaced=true; }
            else b.addSource(source);
        }
        if (!replaced) b.addSource(replacement);
        for (ResourceFile resource : resources) b.addResource(resource);
        return b.build();
    }
    /** Replaces an XML editor snapshot while retaining the remaining workspace files. */
    public WorkspaceSnapshot withResource(ResourceFile replacement) {
        Builder b=builder().resourceIndexComplete(resourcesComplete).projectIndexComplete(projectIndexComplete).requireOverride(enforceOverride).idsFile(idsFile);
        if (namespace != null) b.namespace(namespace);
        for (SourceFile source : sources) b.addSource(source);
        boolean replaced=false;
        for (ResourceFile resource : resources) {
            if (resource.getPath().equals(replacement.getPath())) { b.addResource(replacement); replaced=true; }
            else b.addResource(resource);
        }
        if (!replaced) b.addResource(replacement);
        return b.build();
    }
    public static final class Builder {
        private final List<SourceFile> sources=new ArrayList<>();
        private final List<ResourceFile> resources=new ArrayList<>();
        private boolean resourcesComplete, projectIndexComplete=true, enforceOverride;
        private String namespace, idsFile="res/values/ids.xml";
        public Builder addSource(SourceFile source) { if (source == null) throw new NullPointerException("source"); sources.add(source); return this; }
        public Builder addResource(ResourceFile resource) { if (resource == null) throw new NullPointerException("resource"); resources.add(resource); return this; }
        public Builder resourceIndexComplete(boolean value) { resourcesComplete=value; return this; }
        public Builder projectIndexComplete(boolean value) { projectIndexComplete=value; return this; }
        public Builder requireOverride(boolean value) { enforceOverride=value; return this; }
        public Builder namespace(String value) { namespace=value; return this; }
        public Builder idsFile(String value) { idsFile=value; return this; }
        public WorkspaceSnapshot build() { return new WorkspaceSnapshot(this); }
    }
}
