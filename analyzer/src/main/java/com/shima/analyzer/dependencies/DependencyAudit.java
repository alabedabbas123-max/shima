package com.shima.analyzer.dependencies;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Checks literal direct Gradle declarations against the installer's verified local cache. */
public final class DependencyAudit {
    public static final class Result {
        private final List<MavenCoordinate> missing, unknown;
        private final List<String> issues;
        Result(List<MavenCoordinate> missing,List<MavenCoordinate> unknown,List<String> issues) {
            this.missing=Collections.unmodifiableList(new ArrayList<>(missing));
            this.unknown=Collections.unmodifiableList(new ArrayList<>(unknown));
            this.issues=Collections.unmodifiableList(new ArrayList<>(issues));
        }
        public List<MavenCoordinate> getMissing() { return missing; }
        public List<MavenCoordinate> getUnknown() { return unknown; }
        public List<String> getIssues() { return issues; }
    }
    /** Synchronous and offline, but may hash cached binaries; call on a worker thread. */
    public Result inspect(String buildFile,MavenInstaller installer) throws IOException {
        if (installer==null) throw new NullPointerException("installer");
        BuildDependencyScanner.Result scan=new BuildDependencyScanner().scan(buildFile);
        ArrayList<MavenCoordinate> missing=new ArrayList<>(), unknown=new ArrayList<>();
        ArrayList<String> issues=new ArrayList<>(scan.getUnsupported());
        for (MavenCoordinate coordinate : scan.getCoordinates()) {
            MavenInstaller.DirectCacheStatus status=installer.inspectDirect(coordinate);
            if (status.getState()==MavenInstaller.CacheState.MISSING) missing.add(coordinate);
            else if (status.getState()==MavenInstaller.CacheState.UNKNOWN) unknown.add(coordinate);
            issues.addAll(status.getIssues());
        }
        // This only checks direct literal declarations, never a complete Gradle graph.
        return new Result(missing,unknown,issues);
    }
}
