package com.shima.analyzer.core;

import java.util.Objects;

/** Host-provided resource snapshot. Binary resources use null text. */
public final class ResourceFile {
    private final String path, text;
    public ResourceFile(String path, String text) {
        Objects.requireNonNull(path, "path");
        String normalized=path.replace('\\','/');
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains("../") || normalized.equals(".."))
            throw new IllegalArgumentException("Resource path must be a relative workspace id");
        this.path=normalized;
        this.text=text;
    }
    public String getPath() { return path; }
    /** Null means a binary resource or unavailable contents. */
    public String getText() { return text; }
}
