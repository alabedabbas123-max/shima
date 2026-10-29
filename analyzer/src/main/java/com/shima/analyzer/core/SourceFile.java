package com.shima.analyzer.core;

import java.util.Objects;

/** Immutable snapshot of one source file; id must be stable and unique within a project. */
public final class SourceFile {
    private final String id;
    private final Language language;
    private final String text;

    public SourceFile(String id, Language language, String text) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("A stable, non-empty source id is required");
        this.id = id;
        this.language = Objects.requireNonNull(language, "language");
        this.text = Objects.requireNonNull(text, "text");
    }
    public String getId() { return id; }
    public Language getLanguage() { return language; }
    public String getText() { return text; }
}
