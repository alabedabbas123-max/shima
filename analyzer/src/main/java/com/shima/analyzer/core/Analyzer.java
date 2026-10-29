package com.shima.analyzer.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Synchronous, UI-independent engine. Call it on a worker thread. One instance
 * serializes analyses and owns a bounded LRU of native trees. Always close it.
 */
public final class Analyzer implements AutoCloseable {
    private final AnalysisConfig config;
    private final LinkedHashMap<String, Document> documents = new LinkedHashMap<>(16, 0.75f, true);
    private final Object cancelLock = new Object();
    private int revision;
    private Document active;
    private boolean closed;
    private List<ResourceFile> cachedResources;
    private boolean cachedResourcesComplete;
    private ResourceIndex cachedResourceIndex;

    public Analyzer(AnalysisConfig config) { this.config = Objects.requireNonNull(config, "config"); }
    public Analyzer() { this(AnalysisConfig.builder().build()); }

    public synchronized AnalysisResult analyze(SourceFile source) {
        ensureOpen(); Objects.requireNonNull(source, "source");
        final int token = currentRevision();
        if (source.getLanguage() == Language.XML) {
            XmlAnalyzer.Parsed xml=XmlAnalyzer.inspect(source, config, new XmlAnalyzer.Stop() {
                @Override public boolean cancelled() { return isCancelled(token); }
            });
            return isCancelled(token) ? cancelled(source) : xml.result;
        }
        Parsed parsed = parse(source, token);
        if (isCancelled(token) || parsed.status == AnalysisResult.Status.CANCELLED) return cancelled(source);
        SymbolTable symbols = newSymbols();
        SemanticRules.addProjectDeclarations(parsed, symbols);
        AnalysisResult result = SemanticRules.build(parsed, symbols, config, parsed.status == AnalysisResult.Status.COMPLETE);
        return isCancelled(token) ? cancelled(source) : result;
    }

    /**
     * Analyze all snapshots and construct a project type index first. Unchanged
     * ASTs are reused, but project-dependent rules are recomputed for every file.
     * Duplicate IDs or project budget violations throw IllegalArgumentException.
     */
    public synchronized ProjectResult analyzeProject(List<SourceFile> sources) {
        ensureOpen(); Objects.requireNonNull(sources, "sources");
        if (sources.size() > config.getMaxProjectFiles()) throw new IllegalArgumentException("Project file limit exceeded");
        List<SourceFile> snapshot = new ArrayList<>(sources.size());
        Set<String> ids = new LinkedHashSet<>();
        long chars = 0;
        for (SourceFile source : sources) {
            Objects.requireNonNull(source, "source");
            if (source.getLanguage() == Language.XML) throw new IllegalArgumentException("Use analyzeWorkspace for XML resources");
            if (!ids.add(source.getId())) throw new IllegalArgumentException("Duplicate source id: " + source.getId());
            chars += source.getText().length();
            if (chars > config.getMaxProjectChars()) throw new IllegalArgumentException("Project character limit exceeded");
            snapshot.add(source);
        }
        // A removed project file must not leave a stale native tree in the LRU.
        Iterator<Map.Entry<String, Document>> iterator = documents.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Document> entry = iterator.next();
            if (!ids.contains(entry.getKey())) { entry.getValue().close(); iterator.remove(); }
        }
        int token = currentRevision();
        List<Parsed> parsedFiles = new ArrayList<>(snapshot.size());
        SymbolTable symbols = newSymbols();
        boolean completeIndex = true;
        for (SourceFile source : snapshot) {
            if (isCancelled(token)) return cancelledProject(snapshot);
            Parsed parsed = parse(source, token);
            if (parsed.status == AnalysisResult.Status.CANCELLED) return cancelledProject(snapshot);
            parsedFiles.add(parsed);
            if (parsed.status != AnalysisResult.Status.COMPLETE || SemanticRules.hasSyntaxErrors(parsed)) completeIndex = false;
            SemanticRules.addProjectDeclarations(parsed, symbols);
        }
        LinkedHashMap<String, AnalysisResult> results = new LinkedHashMap<>();
        for (Parsed parsed : parsedFiles) {
            if (isCancelled(token)) return cancelledProject(snapshot);
            results.put(parsed.source.getId(), SemanticRules.build(parsed, symbols, config, completeIndex));
        }
        return isCancelled(token) ? cancelledProject(snapshot) : new ProjectResult(results);
    }

    /** Analyze the supplied source/resource snapshots. Run on a worker thread. */
    public synchronized WorkspaceResult analyzeWorkspace(final WorkspaceSnapshot snapshot) {
        ensureOpen(); Objects.requireNonNull(snapshot, "snapshot");
        if ((long) snapshot.getSources().size()+snapshot.getResources().size()>config.getMaxProjectFiles())
            throw new IllegalArgumentException("Workspace file limit exceeded");
        long chars=0;
        Set<String> sourceIds=new LinkedHashSet<>();
        for (SourceFile source : snapshot.getSources()) { chars+=source.getText().length(); sourceIds.add(source.getId()); }
        for (ResourceFile resource : snapshot.getResources()) if (resource.getText()!=null) chars+=resource.getText().length();
        if (chars>config.getMaxProjectChars()) throw new IllegalArgumentException("Workspace character limit exceeded");
        Iterator<Map.Entry<String,Document>> iterator=documents.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String,Document> entry=iterator.next();
            if (!sourceIds.contains(entry.getKey())) { entry.getValue().close(); iterator.remove(); }
        }
        final int token=currentRevision();
        XmlAnalyzer.Stop stop=new XmlAnalyzer.Stop() { @Override public boolean cancelled() { return isCancelled(token); } };
        ResourceIndex resources;
        if (sameResources(snapshot)) resources=cachedResourceIndex;
        else {
            resources=new ResourceIndex(snapshot,config,stop);
            if (!isCancelled(token)) {
                cachedResources=new ArrayList<>(snapshot.getResources());
                cachedResourcesComplete=snapshot.areResourcesComplete(); cachedResourceIndex=resources;
            }
        }
        if (isCancelled(token)) return cancelledWorkspace(snapshot);
        ArrayList<Parsed> parsed=new ArrayList<>();
        SymbolTable symbols=newSymbols();
        boolean completeIndex=true;
        for (SourceFile source : snapshot.getSources()) {
            if (isCancelled(token)) return cancelledWorkspace(snapshot);
            Parsed item=parse(source,token);
            if (item.status==AnalysisResult.Status.CANCELLED) return cancelledWorkspace(snapshot);
            parsed.add(item);
            if (item.status!=AnalysisResult.Status.COMPLETE || SemanticRules.hasSyntaxErrors(item)) completeIndex=false;
            SemanticRules.addProjectDeclarations(item,symbols);
        }
        Map<String,List<Diagnostic>> extra=WorkspaceRules.sourceRules(parsed,resources,snapshot,config,stop);
        if (isCancelled(token)) return cancelledWorkspace(snapshot);
        Map<String,List<Diagnostic>> xmlExtra=WorkspaceRules.resourceRules(resources,snapshot,config,stop);
        if (isCancelled(token)) return cancelledWorkspace(snapshot);
        LinkedHashMap<String,AnalysisResult> sourceResults=new LinkedHashMap<>(), xmlResults=new LinkedHashMap<>();
        for (Parsed item : parsed) {
            if (isCancelled(token)) return cancelledWorkspace(snapshot);
            AnalysisResult base=SemanticRules.build(item,symbols,config,completeIndex);
            sourceResults.put(item.source.getId(), WorkspaceRules.merge(item.source,base,extra.get(item.source.getId()),config.getMaxDiagnostics()));
        }
        for (Map.Entry<String,XmlAnalyzer.Parsed> entry : resources.xml.entrySet()) {
            SourceFile source=new SourceFile(entry.getKey(),Language.XML,resources.files.get(entry.getKey()).getText());
            xmlResults.put(entry.getKey(), WorkspaceRules.merge(source,entry.getValue().result,xmlExtra.get(entry.getKey()),config.getMaxDiagnostics()));
        }
        return isCancelled(token) ? cancelledWorkspace(snapshot) : new WorkspaceResult(sourceResults,xmlResults,resources.complete);
    }
    private boolean sameResources(WorkspaceSnapshot snapshot) {
        if (cachedResourceIndex==null || cachedResourcesComplete!=snapshot.areResourcesComplete() || cachedResources.size()!=snapshot.getResources().size()) return false;
        for (int i=0;i<cachedResources.size();i++) {
            ResourceFile previous=cachedResources.get(i), current=snapshot.getResources().get(i);
            if (!previous.getPath().equals(current.getPath()) || !Objects.equals(previous.getText(),current.getText())) return false;
        }
        return true;
    }
    private static WorkspaceResult cancelledWorkspace(WorkspaceSnapshot snapshot) {
        LinkedHashMap<String,AnalysisResult> sourceResults=new LinkedHashMap<>(), xmlResults=new LinkedHashMap<>();
        for (SourceFile source : snapshot.getSources()) sourceResults.put(source.getId(),cancelled(source));
        for (ResourceFile resource : snapshot.getResources()) if (resource.getText()!=null && resource.getPath().toLowerCase(java.util.Locale.US).endsWith(".xml")) {
            SourceFile source=new SourceFile(resource.getPath(),Language.XML,resource.getText());
            xmlResults.put(resource.getPath(),cancelled(source));
        }
        return new WorkspaceResult(sourceResults,xmlResults,false);
    }

    /** Can be called from any thread; a running parse and subsequent project passes will stop cooperatively. */
    public void cancel() {
        synchronized (cancelLock) {
            revision++;
            if (active != null) NativeBridge.setRevision(active.handle, revision);
        }
    }

    /** Clears retained syntax trees and result cache. Do not call concurrently with analyze on the UI thread. */
    public synchronized void clearCache() {
        ensureOpen();
        for (Document state : documents.values()) state.close();
        documents.clear(); cachedResources=null; cachedResourceIndex=null;
    }

    /** Waits for any synchronous analysis to finish; editor sessions close the engine on their worker. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Document state : documents.values()) state.close();
        documents.clear(); cachedResources=null; cachedResourceIndex=null;
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Analyzer is closed"); }
    private SymbolTable newSymbols() {
        SymbolTable symbols = new SymbolTable(config.getSymbols());
        for (String type : config.androidTypes().keySet()) symbols.add(type);
        return symbols;
    }
    private int currentRevision() { synchronized (cancelLock) { return revision; } }
    private boolean isCancelled(int token) { synchronized (cancelLock) { return revision != token; } }

    private Parsed parse(SourceFile source, int token) {
        if (source.getText().length() > config.getMaxDocumentChars())
            return Parsed.unavailable(source, "DOCUMENT_TOO_LARGE", "Document exceeds the configured character budget.");
        Document state = documents.get(source.getId());
        if (state != null && state.language != source.getLanguage()) {
            state.close(); documents.remove(source.getId()); state = null;
        }
        if (state == null) {
            if (documents.size() >= config.getMaxCachedFiles()) {
                Iterator<Document> iterator = documents.values().iterator();
                Document eldest = iterator.next(); iterator.remove(); eldest.close();
            }
            long handle = NativeBridge.create(source.getLanguage() == Language.JAVA ? 0 : 1);
            if (handle == 0) return Parsed.unavailable(source, "PARSER_UNAVAILABLE", "Could not initialize the native parser.");
            state = new Document(source.getLanguage(), handle);
            documents.put(source.getId(), state);
        }
        String text = source.getText();
        if (state.text != null && state.text.equals(text))
            return new Parsed(source, state.events, state.status, AnalysisResult.ParseMode.CACHED, state.reasonCode, state.reason);

        Edit edit = state.text == null ? Edit.full() : Edit.between(state.text, text);
        synchronized (cancelLock) {
            if (revision != token) return Parsed.cancelled(source);
            active = state;
            NativeBridge.setRevision(state.handle, token);
        }
        int[] events;
        try {
            events = NativeBridge.parse(state.handle, text, token, config.getTimeoutMillis(), config.getMaxEvents(),
                    edit.start, edit.oldEnd, edit.newEnd, edit.startRow, edit.startColumn,
                    edit.oldRow, edit.oldColumn, edit.newRow, edit.newColumn);
        } finally {
            synchronized (cancelLock) { active = null; }
        }
        if (isCancelled(token) || (events != null && events.length >= 2 && events[0] == 1)) {
            // A cancellation can race with the native tree commit. Discard the
            // state so the next edit is never applied to the wrong snapshot.
            documents.remove(source.getId());
            state.close();
            return Parsed.cancelled(source);
        }
        if (events == null || events.length < 2) {
            documents.remove(source.getId());
            state.close();
            return Parsed.unavailable(source, "PARSER_FAILURE", "The parser returned no result.");
        }
        if (events[0] == 2) return Parsed.unavailable(source, "ANALYSIS_TIMEOUT", "Analysis exceeded its time budget; results are incomplete.");
        if (events[0] == 4) return Parsed.unavailable(source, "PARSER_FAILURE", "The parser could not complete this document.");
        AnalysisResult.Status status = events[0] == 3 ? AnalysisResult.Status.PARTIAL : AnalysisResult.Status.COMPLETE;
        String reasonCode = status == AnalysisResult.Status.PARTIAL ? "ANALYSIS_LIMIT" : null;
        String reason = status == AnalysisResult.Status.PARTIAL ? "Analysis stopped at the configured event or node limit." : null;
        AnalysisResult.ParseMode mode = state.text == null ? AnalysisResult.ParseMode.FULL : AnalysisResult.ParseMode.INCREMENTAL;
        state.text=text; state.events=events; state.status=status; state.reasonCode=reasonCode; state.reason=reason;
        return new Parsed(source, events, status, mode, reasonCode, reason);
    }

    private static AnalysisResult cancelled(SourceFile source) {
        return new AnalysisResult(source, AnalysisResult.Status.CANCELLED, AnalysisResult.ParseMode.NONE, new ArrayList<Diagnostic>());
    }
    private static ProjectResult cancelledProject(List<SourceFile> snapshot) {
        LinkedHashMap<String, AnalysisResult> results = new LinkedHashMap<>();
        for (SourceFile source : snapshot) results.put(source.getId(), cancelled(source));
        return new ProjectResult(results);
    }

    static final class Parsed {
        final SourceFile source;
        final int[] events;
        final AnalysisResult.Status status;
        final AnalysisResult.ParseMode mode;
        final String reasonCode, reason;
        Parsed(SourceFile source, int[] events, AnalysisResult.Status status, AnalysisResult.ParseMode mode, String reasonCode, String reason) {
            this.source=source; this.events=events; this.status=status; this.mode=mode; this.reasonCode=reasonCode; this.reason=reason;
        }
        static Parsed unavailable(SourceFile source, String code, String reason) {
            return new Parsed(source, null, AnalysisResult.Status.PARTIAL, AnalysisResult.ParseMode.NONE, code, reason);
        }
        static Parsed cancelled(SourceFile source) {
            return new Parsed(source, null, AnalysisResult.Status.CANCELLED, AnalysisResult.ParseMode.NONE, null, null);
        }
    }
    private static final class Document {
        final Language language;
        final long handle;
        String text, reasonCode, reason;
        int[] events;
        AnalysisResult.Status status;
        Document(Language language, long handle) { this.language=language; this.handle=handle; }
        void close() { NativeBridge.destroy(handle); }
    }
    private static final class Edit {
        int start=-1, oldEnd, newEnd, startRow, startColumn, oldRow, oldColumn, newRow, newColumn;
        static Edit full() { return new Edit(); }
        static Edit between(String oldText, String newText) {
            Edit edit = new Edit();
            int start = 0, limit = Math.min(oldText.length(), newText.length());
            while (start < limit && oldText.charAt(start) == newText.charAt(start)) start++;
            // An edit may form or break a pair on either side of the diff.
            if (splitsPair(oldText, start) || splitsPair(newText, start)) start--;
            int oldEnd = oldText.length(), newEnd = newText.length();
            while (oldEnd > start && newEnd > start && oldText.charAt(oldEnd-1) == newText.charAt(newEnd-1)) { oldEnd--; newEnd--; }
            // The equal suffix has the same length in both snapshots, so if a
            // boundary splits a pair, extending both ends preserves that suffix.
            if (splitsPair(oldText, oldEnd) || splitsPair(newText, newEnd)) { oldEnd++; newEnd++; }
            LineIndex oldLines = new LineIndex(oldText), newLines = new LineIndex(newText);
            edit.start=start; edit.oldEnd=oldEnd; edit.newEnd=newEnd;
            edit.startRow=oldLines.row(start); edit.startColumn=oldLines.column(start);
            edit.oldRow=oldLines.row(oldEnd); edit.oldColumn=oldLines.column(oldEnd);
            edit.newRow=newLines.row(newEnd); edit.newColumn=newLines.column(newEnd);
            return edit;
        }
        private static boolean splitsPair(String text, int offset) {
            return offset > 0 && offset < text.length()
                    && Character.isHighSurrogate(text.charAt(offset-1)) && Character.isLowSurrogate(text.charAt(offset));
        }
    }
}
