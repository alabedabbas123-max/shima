package com.shima.analyzer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Explicit, bounded budgets. These reduce pressure; they cannot guarantee that a process never runs out of memory. */
public final class AnalysisConfig {
    private static final Pattern FQCN = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)+");
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");
    private final int minApi, compileApi, maxDocumentChars, maxProjectChars, maxProjectFiles;
    private final int maxCachedFiles, maxDiagnostics, maxEvents, timeoutMillis;
    private final List<String> symbols;
    private final Map<String, ApiCatalog.Entry> androidTypes, androidStaticMethods;

    private AnalysisConfig(Builder b) {
        // The analyzer's own Android minSdk is unrelated to the project being
        // analyzed. Zero means the host could not determine that project level.
        range("minApi", b.minApi, 0, 10_000);
        range("compileApi", b.compileApi, 0, 10_000);
        if (b.minApi > 0 && b.compileApi > 0 && b.compileApi < b.minApi)
            throw new IllegalArgumentException("compileApi must be >= minApi when both are known");
        range("maxDocumentChars", b.maxDocumentChars, 1, 2_000_000);
        range("maxProjectChars", b.maxProjectChars, 1, 20_000_000);
        range("maxProjectFiles", b.maxProjectFiles, 1, 5000);
        range("maxCachedFiles", b.maxCachedFiles, 1, 128);
        range("maxDiagnostics", b.maxDiagnostics, 1, 2000);
        range("maxEvents", b.maxEvents, 1, 100_000);
        range("timeoutMillis", b.timeoutMillis, 1, 60_000);
        if (b.symbols.size() > 50_000) throw new IllegalArgumentException("Too many external symbols");
        if ((long) b.androidTypes.size() + b.androidStaticMethods.size() > 50_000)
            throw new IllegalArgumentException("Too many Android API metadata entries");
        minApi=b.minApi; compileApi=b.compileApi; maxDocumentChars=b.maxDocumentChars;
        maxProjectChars=b.maxProjectChars; maxProjectFiles=b.maxProjectFiles; maxCachedFiles=b.maxCachedFiles;
        maxDiagnostics=b.maxDiagnostics; maxEvents=b.maxEvents; timeoutMillis=b.timeoutMillis;
        symbols=Collections.unmodifiableList(new ArrayList<>(b.symbols));
        androidTypes=Collections.unmodifiableMap(new HashMap<>(b.androidTypes));
        androidStaticMethods=Collections.unmodifiableMap(new HashMap<>(b.androidStaticMethods));
    }
    private static void range(String name, int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be in [" + min + ", " + max + "]");
    }
    public static Builder builder() { return new Builder(); }
    public int getMinApi() { return minApi; }
    public int getCompileApi() { return compileApi; }
    public int getMaxDocumentChars() { return maxDocumentChars; }
    public int getMaxProjectChars() { return maxProjectChars; }
    public int getMaxProjectFiles() { return maxProjectFiles; }
    public int getMaxCachedFiles() { return maxCachedFiles; }
    public int getMaxDiagnostics() { return maxDiagnostics; }
    public int getMaxEvents() { return maxEvents; }
    public int getTimeoutMillis() { return timeoutMillis; }
    public List<String> getSymbols() { return symbols; }
    Map<String, ApiCatalog.Entry> androidTypes() { return androidTypes; }
    Map<String, ApiCatalog.Entry> androidStaticMethods() { return androidStaticMethods; }

    /** Configurations are immutable value objects; useful when reusing a worker-owned Analyzer. */
    @Override public boolean equals(Object other) {
        if (this==other) return true;
        if (!(other instanceof AnalysisConfig)) return false;
        AnalysisConfig that=(AnalysisConfig) other;
        return minApi==that.minApi && compileApi==that.compileApi
                && maxDocumentChars==that.maxDocumentChars && maxProjectChars==that.maxProjectChars
                && maxProjectFiles==that.maxProjectFiles && maxCachedFiles==that.maxCachedFiles
                && maxDiagnostics==that.maxDiagnostics && maxEvents==that.maxEvents
                && timeoutMillis==that.timeoutMillis && symbols.equals(that.symbols)
                && androidTypes.equals(that.androidTypes) && androidStaticMethods.equals(that.androidStaticMethods);
    }
    @Override public int hashCode() {
        return java.util.Objects.hash(minApi,compileApi,maxDocumentChars,maxProjectChars,maxProjectFiles,
                maxCachedFiles,maxDiagnostics,maxEvents,timeoutMillis,symbols,androidTypes,androidStaticMethods);
    }

    public static final class Builder {
        private int minApi=0, compileApi=0, maxDocumentChars=200_000, maxProjectChars=2_000_000;
        private int maxProjectFiles=500, maxCachedFiles=8, maxDiagnostics=100, maxEvents=12_000, timeoutMillis=1000;
        private final List<String> symbols = new ArrayList<>();
        private final Map<String, ApiCatalog.Entry> androidTypes = new HashMap<>();
        private final Map<String, ApiCatalog.Entry> androidStaticMethods = new HashMap<>();
        /** Project minSdk, or 0 if unknown. This is not the host app's minSdk. */
        public Builder minApi(int value) { minApi=value; return this; }
        /** Project compileSdk, or 0 if unknown. Never substitute the device API. */
        public Builder compileApi(int value) { compileApi=value; return this; }
        public Builder maxDocumentChars(int value) { maxDocumentChars=value; return this; }
        public Builder maxProjectChars(int value) { maxProjectChars=value; return this; }
        public Builder maxProjectFiles(int value) { maxProjectFiles=value; return this; }
        public Builder maxCachedFiles(int value) { maxCachedFiles=value; return this; }
        public Builder maxDiagnostics(int value) { maxDiagnostics=value; return this; }
        public Builder maxEvents(int value) { maxEvents=value; return this; }
        public Builder timeoutMillis(int value) { timeoutMillis=value; return this; }
        /** Add fully-qualified types supplied by the host (not the entire classpath). */
        public Builder addSymbol(String fqcn) {
            if (fqcn == null || !FQCN.matcher(fqcn).matches()) throw new IllegalArgumentException("Invalid fully-qualified type: " + fqcn);
            symbols.add(fqcn); return this;
        }
        /**
         * Supply or override Android platform type metadata. Deprecated API 0
         * means unknown/not deprecated. The host is responsible for accuracy.
         */
        public Builder addAndroidApiType(String fqcn, int sinceApi, int deprecatedApi) {
            androidOwner(fqcn);
            androidTypes.put(fqcn, apiEntry(sinceApi, deprecatedApi));
            return this;
        }
        /** Supply or override a static method by owner and name; overloads are not distinguished. */
        public Builder addAndroidApiStaticMethod(String owner, String method, int sinceApi, int deprecatedApi) {
            androidOwner(owner);
            if (method == null || !IDENTIFIER.matcher(method).matches()) throw new IllegalArgumentException("Invalid method name: " + method);
            androidStaticMethods.put(owner + "#" + method, apiEntry(sinceApi, deprecatedApi));
            return this;
        }
        private static void androidOwner(String fqcn) {
            if (fqcn == null || !fqcn.startsWith("android.") || !FQCN.matcher(fqcn).matches())
                throw new IllegalArgumentException("Expected an android.* fully-qualified type: " + fqcn);
        }
        private static ApiCatalog.Entry apiEntry(int sinceApi, int deprecatedApi) {
            range("sinceApi", sinceApi, 1, 10_000);
            if (deprecatedApi != 0) range("deprecatedApi", deprecatedApi, sinceApi, 10_000);
            return new ApiCatalog.Entry(sinceApi, deprecatedApi);
        }
        public AnalysisConfig build() { return new AnalysisConfig(this); }
    }
}
