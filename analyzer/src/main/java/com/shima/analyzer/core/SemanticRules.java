package com.shima.analyzer.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative, AST-driven checks; this is intentionally not a compiler or classpath resolver. */
final class SemanticRules {
    private static final int ERROR=1, MISSING=2, TYPE=3, PACKAGE=4, IMPORT=5, DECLARATION=6, INIT=7, CALL=8, LOCAL=9;
    private static final int HEADER=2, STRIDE=6, MAX_IMPORT_FIXES=8;
    private static final String IDENT = "[\\p{L}_$][\\p{L}\\p{N}_$]*";
    private static final Pattern NAME = Pattern.compile(IDENT + "(?:\\." + IDENT + ")*");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("\\bpackage\\s+(" + IDENT + "(?:\\." + IDENT + ")*)");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("\\bimport\\s+(?:(static)\\s+)?(" + IDENT + "(?:\\." + IDENT + ")*)(\\.\\*)?(?:\\s+as\\s+(" + IDENT + "))?");
    private static final Set<String> JAVA_LANG = set("String", "Object", "Class", "Throwable", "Exception", "RuntimeException", "Error", "System", "Math", "Integer", "Long", "Boolean", "Character", "Double", "Float", "Byte", "Short", "Number", "Enum", "Void", "Iterable", "Comparable", "Override", "Deprecated", "SuppressWarnings", "StringBuilder", "StringBuffer", "Thread", "Runnable");
    private static final Set<String> KOTLIN_DEFAULT = set("String", "Any", "Nothing", "Unit", "Int", "Long", "Boolean", "Char", "Double", "Float", "Byte", "Short", "Number", "List", "MutableList", "ArrayList", "Map", "MutableMap", "HashMap", "Set", "MutableSet", "HashSet", "Collection", "Iterable", "Iterator", "Sequence", "Exception", "Throwable", "Array", "IntArray", "LongArray");
    private static final Set<String> JAVA_NUMERIC = set("byte", "short", "int", "long", "float", "double");
    private static final Set<String> KOTLIN_NUMERIC = set("Byte", "Short", "Int", "Long", "Float", "Double");
    private SemanticRules() { }
    private static Set<String> set(String... items) { return new HashSet<>(Arrays.asList(items)); }

    static boolean hasSyntaxErrors(Analyzer.Parsed parsed) {
        if (parsed.events == null) return true;
        for (int i=HEADER; i+STRIDE<=parsed.events.length; i+=STRIDE)
            if (parsed.events[i] == ERROR || parsed.events[i] == MISSING) return true;
        return false;
    }

    static void addProjectDeclarations(Analyzer.Parsed parsed, SymbolTable symbols) {
        if (parsed.events == null) return;
        String pkg = packageName(parsed);
        for (int i=HEADER; i+STRIDE<=parsed.events.length; i+=STRIDE) {
            if (parsed.events[i] == DECLARATION) {
                String name = slice(parsed.source.getText(), parsed.events[i+1], parsed.events[i+2]);
                if (NAME.matcher(name).matches()) symbols.add(pkg.isEmpty() ? name : pkg + "." + name);
            }
        }
    }

    static AnalysisResult build(Analyzer.Parsed parsed, SymbolTable symbols, AnalysisConfig config, boolean completeIndex) {
        SourceFile source = parsed.source;
        String text = source.getText();
        // Rejected oversized input needs only a range at offset zero.
        LineIndex lines = new LineIndex(parsed.events == null ? "" : text);
        Findings findings = new Findings(source, lines, config.getMaxDiagnostics());
        boolean syntaxError = false;
        if (parsed.events != null) {
            for (int i=HEADER; i+STRIDE<=parsed.events.length; i+=STRIDE) {
                int kind=parsed.events[i], start=parsed.events[i+1], end=parsed.events[i+2], extra=parsed.events[i+5];
                if (kind == ERROR) {
                    syntaxError=true;
                    findings.add("SYNTAX_ERROR", "Unexpected or invalid syntax.", Diagnostic.Severity.ERROR, start, end, Collections.<QuickFix>emptyList());
                } else if (kind == MISSING) {
                    syntaxError=true;
                    if (findings.truncated) continue;
                    List<QuickFix> fixes = Collections.emptyList();
                    String message = "A required syntax element is missing.";
                    if (extra != 0 && ";})]".indexOf((char) extra) >= 0) {
                        String token = String.valueOf((char) extra);
                        message = "Expected '" + token + "'.";
                        fixes = Collections.singletonList(findings.fix("Insert '" + token + "'", new TextEdit(start, start, token)));
                    }
                    findings.add("MISSING_TOKEN", message, Diagnostic.Severity.ERROR, start, end, fixes);
                }
            }
        }
        if (parsed.reason != null) findings.add(parsed.reasonCode, parsed.reason, Diagnostic.Severity.INFO, 0, 0, Collections.<QuickFix>emptyList());
        if (!completeIndex && parsed.status == AnalysisResult.Status.COMPLETE) {
            findings.add("PROJECT_INDEX_INCOMPLETE", "Project index is incomplete; import suggestions were skipped.", Diagnostic.Severity.INFO, 0, 0, Collections.<QuickFix>emptyList());
        }
        if (!syntaxError && parsed.events != null) {
            Context context = new Context(parsed, symbols);
            for (int i=HEADER; i+STRIDE<=parsed.events.length; i+=STRIDE) {
                if (findings.truncated) break;
                int kind=parsed.events[i], start=parsed.events[i+1], end=parsed.events[i+2];
                int otherStart=parsed.events[i+3], otherEnd=parsed.events[i+4];
                if (kind == TYPE) {
                    String name = slice(text, start, end);
                    if (!NAME.matcher(name).matches()) continue;
                    Resolution resolved = context.resolve(name);
                    if (completeIndex && resolved.qualified == null && !resolved.candidates.isEmpty()) {
                        List<QuickFix> fixes = new ArrayList<>();
                        for (String candidate : resolved.candidates) {
                            // Types from the unnamed package cannot be imported by a named Java package.
                            if (candidate.indexOf('.') < 0) continue;
                            TextEdit edit = context.importEdit(candidate);
                            if (edit != null) fixes.add(findings.fix("Import " + candidate, edit));
                            if (fixes.size() >= MAX_IMPORT_FIXES) break;
                        }
                        if (!fixes.isEmpty()) findings.add("POSSIBLE_MISSING_IMPORT", "A known type named '" + name + "' may need an import.", Diagnostic.Severity.WARNING, start, end, fixes);
                    }
                    if (resolved.qualified != null) apiFinding(findings, ApiCatalog.type(config, resolved.qualified), resolved.qualified, start, end, config);
                } else if (kind == INIT && otherStart >= 0) {
                    String value = slice(text, start, end), type = slice(text, otherStart, otherEnd).trim();
                    if (incompatibleLiteral(source.getLanguage(), type, value))
                        findings.add("INCOMPATIBLE_LITERAL", "This literal is not compatible with declared type '" + type + "'.", Diagnostic.Severity.ERROR, start, end, Collections.<QuickFix>emptyList());
                } else if (kind == CALL && otherStart >= 0) {
                    String owner = slice(text, start, end), method = slice(text, otherStart, otherEnd);
                    if (!NAME.matcher(owner).matches() || !NAME.matcher(method).matches()) continue;
                    Resolution resolved = context.resolve(owner);
                    if (resolved.qualified != null) apiFinding(findings, ApiCatalog.method(config, resolved.qualified, method), resolved.qualified + "." + method, otherStart, otherEnd, config);
                }
            }
        }
        Collections.sort(findings.items, new Comparator<Diagnostic>() {
            @Override public int compare(Diagnostic a, Diagnostic b) {
                int offset = Integer.compare(a.getRange().getStartOffset(), b.getRange().getStartOffset());
                return offset != 0 ? offset : a.getCode().compareTo(b.getCode());
            }
        });
        AnalysisResult.Status status = parsed.status;
        if (status == AnalysisResult.Status.COMPLETE && (!completeIndex || findings.truncated)) status=AnalysisResult.Status.PARTIAL;
        ArrayList<String> reasons=new ArrayList<>();
        if (parsed.status==AnalysisResult.Status.PARTIAL) reasons.add(parsed.reasonCode==null ? "UNKNOWN" : parsed.reasonCode);
        if (!completeIndex) reasons.add("PROJECT_INDEX_INCOMPLETE");
        if (findings.truncated) reasons.add("DIAGNOSTIC_LIMIT");
        return new AnalysisResult(source, status, parsed.mode, findings.items, reasons);
    }

    private static void apiFinding(Findings findings, ApiCatalog.Entry entry, String name, int start, int end, AnalysisConfig config) {
        if (entry == null) return;
        if (config.getMinApi() > 0 && entry.since > config.getMinApi()) findings.add("ANDROID_API_LEVEL", name + " requires API " + entry.since + "; verify a runtime guard for minSdk " + config.getMinApi() + ".", Diagnostic.Severity.WARNING, start, end, Collections.<QuickFix>emptyList());
        if (config.getCompileApi() > 0 && entry.deprecated > 0 && entry.deprecated <= config.getCompileApi()) findings.add("ANDROID_DEPRECATED", name + " is deprecated since API " + entry.deprecated + ".", Diagnostic.Severity.WARNING, start, end, Collections.<QuickFix>emptyList());
    }

    private static boolean incompatibleLiteral(Language language, String type, String value) {
        if (type.endsWith("?")) return false;
        boolean numeric = (language == Language.JAVA ? JAVA_NUMERIC : KOTLIN_NUMERIC).contains(type);
        boolean bool = type.equals(language == Language.JAVA ? "boolean" : "Boolean");
        boolean character = type.equals(language == Language.JAVA ? "char" : "Char");
        boolean string = type.equals("String") || (language == Language.JAVA && type.equals("java.lang.String"));
        if (!numeric && !bool && !character && !string) return false;
        boolean isString = value.startsWith("\"");
        boolean isChar = value.startsWith("'");
        boolean isBool = value.equals("true") || value.equals("false");
        boolean isNull = value.equals("null");
        boolean isNumber = !value.isEmpty() && Character.isDigit(value.charAt(0));
        if (!(isString || isChar || isBool || isNull || isNumber)) return false;
        if (isNull) return language == Language.KOTLIN || !string;
        if (numeric) return isString || isBool || (isChar && language == Language.KOTLIN);
        if (bool) return isString || isChar || isNumber;
        if (character) return isString || isBool || (isNumber && language == Language.KOTLIN);
        return isChar || isBool || isNumber;
    }

    private static String packageName(Analyzer.Parsed parsed) {
        if (parsed.events != null) for (int i=HEADER; i+STRIDE<=parsed.events.length; i+=STRIDE) if (parsed.events[i] == PACKAGE) {
            Matcher m = PACKAGE_PATTERN.matcher(slice(parsed.source.getText(), parsed.events[i+1], parsed.events[i+2]));
            if (m.find()) return m.group(1);
        }
        return "";
    }
    private static String slice(String text, int start, int end) {
        if (start < 0 || end < start || end > text.length()) return "";
        return text.substring(start, end);
    }

    private static final class Context {
        final Analyzer.Parsed parsed;
        final SymbolTable symbols;
        final String pkg;
        final Map<String, String> imports = new HashMap<>();
        final Map<String, Resolution> resolutions = new HashMap<>();
        final Set<String> wildcardPackages = new HashSet<>(), localNames = new HashSet<>();
        int lastImportEnd = -1, packageEnd = -1;
        Context(Analyzer.Parsed parsed, SymbolTable symbols) {
            this.parsed=parsed; this.symbols=symbols; this.pkg=packageName(parsed);
            String text=parsed.source.getText();
            for (int i=HEADER; i+STRIDE<=parsed.events.length; i+=STRIDE) {
                int kind=parsed.events[i], start=parsed.events[i+1], end=parsed.events[i+2];
                if (kind == PACKAGE) packageEnd=Math.max(packageEnd, end);
                else if (kind == DECLARATION || kind == LOCAL) localNames.add(slice(text, start, end));
                else if (kind == IMPORT) {
                    lastImportEnd=Math.max(lastImportEnd, end);
                    Matcher matcher=IMPORT_PATTERN.matcher(slice(text, start, end));
                    if (!matcher.find() || matcher.group(1) != null) continue; // ignore static imports
                    String qualified=matcher.group(2);
                    if (matcher.group(3) != null) wildcardPackages.add(qualified);
                    else {
                        String alias=matcher.group(4);
                        String simple=alias != null ? alias : qualified.substring(qualified.lastIndexOf('.')+1);
                        imports.put(simple, qualified);
                    }
                }
            }
        }
        Resolution resolve(String name) {
            Resolution cached = resolutions.get(name);
            if (cached != null) return cached;
            Resolution result = resolveUncached(name);
            resolutions.put(name, result);
            return result;
        }
        private Resolution resolveUncached(String name) {
            if (name.indexOf('.') >= 0) {
                int firstDot=name.indexOf('.');
                String head=name.substring(0, firstDot);
                if (imports.containsKey(head)) return new Resolution(imports.get(head) + name.substring(firstDot), Collections.<String>emptyList());
                return new Resolution(name, Collections.<String>emptyList());
            }
            if (localNames.contains(name)) return new Resolution(pkg.isEmpty() ? name : pkg + "." + name, Collections.<String>emptyList());
            if (imports.containsKey(name)) return new Resolution(imports.get(name), Collections.<String>emptyList());
            if ((parsed.source.getLanguage() == Language.JAVA ? JAVA_LANG : KOTLIN_DEFAULT).contains(name)) return new Resolution(null, Collections.<String>emptyList());
            List<String> candidates=symbols.candidates(name);
            for (String candidate : candidates) {
                int dot=candidate.lastIndexOf('.');
                String owner=dot < 0 ? "" : candidate.substring(0, dot);
                if (owner.equals(pkg)) return new Resolution(candidate, Collections.<String>emptyList());
            }
            String match=null;
            for (String candidate : candidates) {
                int dot=candidate.lastIndexOf('.');
                String owner=dot < 0 ? "" : candidate.substring(0, dot);
                if (wildcardPackages.contains(owner)) {
                    if (match != null) return new Resolution(null, Collections.<String>emptyList());
                    match=candidate;
                }
            }
            if (match != null) return new Resolution(match, Collections.<String>emptyList());
            // An unknown wildcard import could provide this name; do not assert it is missing.
            if (!wildcardPackages.isEmpty()) return new Resolution(null, Collections.<String>emptyList());
            return new Resolution(null, candidates);
        }
        TextEdit importEdit(String qualified) {
            String text=parsed.source.getText();
            String eol=text.contains("\r\n") ? "\r\n" : "\n";
            String declaration="import " + qualified + (parsed.source.getLanguage() == Language.JAVA ? ";" : "");
            if (lastImportEnd >= 0) return new TextEdit(lastImportEnd, lastImportEnd, eol + declaration);
            if (packageEnd >= 0) return new TextEdit(packageEnd, packageEnd, eol + eol + declaration);
            // Kotlin shebang and file annotations must precede imports. Leave placement to the host.
            if (parsed.source.getLanguage() == Language.KOTLIN && (text.startsWith("#!") || text.contains("@file:"))) return null;
            return new TextEdit(0, 0, declaration + eol + eol);
        }
    }
    private static final class Resolution {
        final String qualified;
        final List<String> candidates;
        Resolution(String qualified, List<String> candidates) { this.qualified=qualified; this.candidates=candidates; }
    }
    private static final class Findings {
        final SourceFile source;
        final LineIndex lines;
        final int limit;
        final List<Diagnostic> items = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        byte[] fingerprint;
        boolean truncated;
        Findings(SourceFile source, LineIndex lines, int limit) { this.source=source; this.lines=lines; this.limit=limit; }
        QuickFix fix(String title, TextEdit edit) {
            if (fingerprint == null) fingerprint=QuickFix.fingerprint(source.getText());
            return new QuickFix(title, source.getId(), fingerprint, Collections.singletonList(edit));
        }
        void add(String code, String message, Diagnostic.Severity severity, int start, int end, List<QuickFix> fixes) {
            String key=code + ":" + start + ":" + end;
            if (!seen.add(key)) return;
            if (items.size() >= limit) { truncated=true; return; }
            items.add(new Diagnostic(code, message, severity, lines.range(start, end), fixes));
        }
    }
}
