package com.shima.analyzer.dependencies;

import java.util.Objects;

/** Exact Maven GAV; dynamic versions, classifiers and snapshots require another resolver. */
public final class MavenCoordinate {
    private final String group, artifact, version;
    public MavenCoordinate(String group,String artifact,String version) {
        if (group==null || !group.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*") || group.contains("..")) throw new IllegalArgumentException("Invalid Maven group");
        if (artifact==null || !artifact.matches("[A-Za-z0-9_.-]+") || artifact.contains("..") || artifact.startsWith(".")) throw new IllegalArgumentException("Invalid Maven artifact");
        if (version==null || !version.matches("[A-Za-z0-9_.-]+") || version.contains("..") || version.startsWith(".") || version.endsWith("-SNAPSHOT")) throw new IllegalArgumentException("Version must be exact and non-SNAPSHOT");
        this.group=group; this.artifact=artifact; this.version=version;
    }
    public static MavenCoordinate parse(String text) {
        String[] pieces=Objects.requireNonNull(text,"text").split(":",-1);
        if (pieces.length!=3) throw new IllegalArgumentException("Expected group:artifact:version");
        return new MavenCoordinate(pieces[0],pieces[1],pieces[2]);
    }
    public String getGroup() { return group; }
    public String getArtifact() { return artifact; }
    public String getVersion() { return version; }
    public String getModule() { return group+":"+artifact; }
    public String relativePath(String extension) {
        if (!extension.equals("pom") && !extension.equals("jar") && !extension.equals("aar")) throw new IllegalArgumentException("Unsupported artifact type");
        return group.replace('.','/')+"/"+artifact+"/"+version+"/"+artifact+"-"+version+"."+extension;
    }
    public String lockKey(String extension) { return toString()+"@"+extension; }
    @Override public String toString() { return group+":"+artifact+":"+version; }
    @Override public boolean equals(Object other) { return other instanceof MavenCoordinate && toString().equals(other.toString()); }
    @Override public int hashCode() { return toString().hashCode(); }
}
