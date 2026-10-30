package com.shima.analyzer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Workspace-only rules. Never infer an error from an explicitly incomplete index. */
final class WorkspaceRules {
    private static final int HEADER=2, STRIDE=6, RESOURCE=10, CLASS=11;
    private static final Pattern R=Pattern.compile("R\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern XML_REF=Pattern.compile("@([a-z][a-z0-9_]*)/([A-Za-z_][A-Za-z0-9_]*)");
    private WorkspaceRules() { }
    /** Retains one extra diagnostic so merge can mark truncated results PARTIAL. */
    private static final class DiagnosticList extends ArrayList<Diagnostic> {
        private static final long serialVersionUID=1L;
        private final int limit;
        private final int[] severities=new int[3];
        boolean resourceIncomplete;
        DiagnosticList(int limit) { this.limit=limit; }
        @Override public boolean add(Diagnostic value) {
            if ("RESOURCE_INDEX_INCOMPLETE".equals(value.getCode())) resourceIncomplete=true;
            int severity=value.getSeverity().ordinal();
            if (size()<=limit) { severities[severity]++; return super.add(value); } // one extra records truncation
            int worst=severities[2]>0 ? 2 : severities[1]>0 ? 1 : 0;
            if (severity>=worst) return false;
            for (int i=size()-1;i>=0;i--) if (get(i).getSeverity().ordinal()==worst) {
                set(i,value); severities[worst]--; severities[severity]++; return true;
            }
            return false;
        }
    }
    static Map<String,List<Diagnostic>> sourceRules(List<Analyzer.Parsed> parsed, ResourceIndex index, WorkspaceSnapshot snapshot, AnalysisConfig config, XmlAnalyzer.Stop stop, boolean projectIndexComplete) {
        LinkedHashMap<String,List<Diagnostic>> out=new LinkedHashMap<>();
        for (Analyzer.Parsed p : parsed) {
            if (stop.cancelled()) return out;
            ArrayList<Diagnostic> list=new DiagnosticList(config.getMaxDiagnostics());
            out.put(p.source.getId(),list);
            if (p.status!=AnalysisResult.Status.COMPLETE || p.events==null || SemanticRules.hasSyntaxErrors(p)) continue;
            if (!index.complete && localR(p,snapshot) && hasResourceReference(p)) {
                list.add(new Diagnostic("RESOURCE_INDEX_INCOMPLETE", "Resource snapshots are incomplete; missing-resource checks were skipped.",
                        Diagnostic.Severity.INFO, new LineIndex(p.source.getText()).range(0,0), Collections.<QuickFix>emptyList()));
            }
            if (index.complete && localR(p, snapshot)) {
                String text=p.source.getText(); LineIndex lines=new LineIndex(text);
                for (int i=HEADER;i+STRIDE<=p.events.length;i+=STRIDE) if (p.events[i]==RESOURCE) {
                    if (stop.cancelled()) return out;
                    if (list.size()>config.getMaxDiagnostics()) break;
                    int start=p.events[i+1], end=p.events[i+2];
                    if (start<0 || end>text.length() || end<start) continue;
                    Matcher r=R.matcher(text.substring(start,end));
                    if (!r.matches()) continue;
                    String type=r.group(1), name=r.group(2);
                    if (!ResourceIndex.SUPPORTED.contains(type) || index.contains(type,name)) continue;
                    List<WorkspaceFix> fixes=Collections.emptyList();
                    if (type.equals("id")) {
                        WorkspaceFix fix=index.createIdFix(name,p.source.getId(),text,snapshot);
                        if (fix!=null) fixes=Collections.singletonList(fix);
                    }
                    list.add(new Diagnostic("MISSING_RESOURCE", "Resource R."+type+"."+name+" is absent from the supplied resource index.",
                            Diagnostic.Severity.ERROR, lines.range(start,end), Collections.<QuickFix>emptyList(),fixes));
                }
            }
        }
        JavaWorkspaceRules.add(parsed, out, snapshot, stop, projectIndexComplete);
        return out;
    }
    static Map<String,List<Diagnostic>> resourceRules(ResourceIndex index, WorkspaceSnapshot snapshot, AnalysisConfig config, XmlAnalyzer.Stop stop) {
        LinkedHashMap<String,List<Diagnostic>> out=new LinkedHashMap<>();
        for (Map.Entry<String,XmlAnalyzer.Parsed> entry : index.xml.entrySet()) {
            if (stop.cancelled()) return out;
            ArrayList<Diagnostic> list=new DiagnosticList(config.getMaxDiagnostics()); out.put(entry.getKey(),list);
            if (!entry.getValue().complete()) continue;
            String text=index.files.get(entry.getKey()).getText(); LineIndex lines=new LineIndex(text);
            if (!index.complete) {
                boolean hasReference=false;
                for (XmlAnalyzer.Tag tag : entry.getValue().tags) for (XmlAnalyzer.Attribute attr : tag.attributes)
                    if (XML_REF.matcher(attr.value).matches()) hasReference=true;
                if (hasReference) list.add(new Diagnostic("RESOURCE_INDEX_INCOMPLETE", "Resource snapshots are incomplete; XML reference checks were skipped.",
                        Diagnostic.Severity.INFO, lines.range(0,0), Collections.<QuickFix>emptyList()));
                continue;
            }
            resourceTags: for (XmlAnalyzer.Tag tag : entry.getValue().tags) for (XmlAnalyzer.Attribute attr : tag.attributes) {
                if (stop.cancelled()) return out;
                if (list.size()>config.getMaxDiagnostics()) break resourceTags;
                Matcher ref=XML_REF.matcher(attr.value);
                if (!ref.matches()) continue; // @+id, @android:, data-binding and theme refs are excluded.
                String type=ref.group(1), name=ref.group(2);
                if (!ResourceIndex.SUPPORTED.contains(type) || index.contains(type,name)) continue;
                List<WorkspaceFix> fixes=Collections.emptyList();
                if (type.equals("id")) {
                    WorkspaceFix fix=index.createIdFix(name,entry.getKey(),text,snapshot);
                    if (fix!=null) fixes=Collections.singletonList(fix);
                }
                list.add(new Diagnostic("MISSING_RESOURCE", "Resource @"+type+"/"+name+" is absent from the supplied resource index.",
                        Diagnostic.Severity.ERROR, lines.range(attr.start,attr.end), Collections.<QuickFix>emptyList(),fixes));
            }
        }
        return out;
    }
    private static boolean hasResourceReference(Analyzer.Parsed parsed) {
        String text=parsed.source.getText();
        for (int i=HEADER;i+STRIDE<=parsed.events.length;i+=STRIDE) if (parsed.events[i]==RESOURCE) {
            int a=parsed.events[i+1], b=parsed.events[i+2];
            if (a>=0 && b>=a && b<=text.length() && R.matcher(text.substring(a,b)).matches()) return true;
        }
        return false;
    }
    private static boolean localR(Analyzer.Parsed parsed, WorkspaceSnapshot snapshot) {
        String text=parsed.source.getText();
        // Conservative shadowing guard; matching comments may only suppress a check.
        if (Pattern.compile("\\b(?:class|object|val|var)\\s+R\\b|\\bR\\s*:").matcher(text).find()) return false;
        for (int i=HEADER;i+STRIDE<=parsed.events.length;i+=STRIDE) {
            int kind=parsed.events[i], start=parsed.events[i+1], end=parsed.events[i+2];
            if (start<0 || end>text.length() || start>end) continue;
            String part=text.substring(start,end);
            if ((kind==CLASS || kind==16 || kind==17) && part.equals("R")) return false;
            int otherStart=parsed.events[i+3], otherEnd=parsed.events[i+4];
            if (kind==15 && otherStart>=0 && otherEnd<=text.length() && otherEnd>=otherStart &&
                    Pattern.compile("\\bR\\s*$").matcher(text.substring(otherStart,otherEnd)).find()) return false;
            if (kind==5) { // AST import event, so strings/comments do not trigger this check.
                Matcher m=Pattern.compile("\\bimport\\s+([A-Za-z_$][A-Za-z0-9_$.]*\\.R)\\s*;?").matcher(part);
                if (m.find() && (snapshot.getNamespace()==null || !m.group(1).equals(snapshot.getNamespace()+".R"))) return false;
            }
        }
        return true;
    }
    static AnalysisResult merge(SourceFile source, AnalysisResult base, List<Diagnostic> extras, int limit) {
        if (extras==null || extras.isEmpty()) return base;
        ArrayList<Diagnostic> all=new ArrayList<>(base.getDiagnostics());
        all.addAll(extras);
        boolean truncated=all.size()>limit;
        if (truncated) {
            // Preserve errors before less urgent warnings/info when a limit is reached;
            // unused warnings must not crowd real errors out of a bounded result.
            Collections.sort(all,new Comparator<Diagnostic>() {
                @Override public int compare(Diagnostic a,Diagnostic b) {
                    int severity=Integer.compare(a.getSeverity().ordinal(),b.getSeverity().ordinal());
                    if (severity!=0) return severity;
                    int position=Integer.compare(a.getRange().getStartOffset(),b.getRange().getStartOffset());
                    return position!=0 ? position : a.getCode().compareTo(b.getCode());
                }
            });
            all=new ArrayList<>(all.subList(0,limit));
        }
        Collections.sort(all,new Comparator<Diagnostic>() {
            @Override public int compare(Diagnostic a, Diagnostic b) {
                int position=Integer.compare(a.getRange().getStartOffset(),b.getRange().getStartOffset());
                return position!=0 ? position : a.getCode().compareTo(b.getCode());
            }
        });
        java.util.LinkedHashSet<String> reasons=new java.util.LinkedHashSet<>(base.getIncompleteReasons());
        if (truncated) reasons.add("DIAGNOSTIC_LIMIT");
        if (extras instanceof DiagnosticList && ((DiagnosticList) extras).resourceIncomplete) reasons.add("RESOURCE_INDEX_INCOMPLETE");
        for (Diagnostic diagnostic : extras) if (diagnostic.getCode().equals("RESOURCE_INDEX_INCOMPLETE")) reasons.add("RESOURCE_INDEX_INCOMPLETE");
        AnalysisResult.Status status=!reasons.isEmpty() && base.getStatus()==AnalysisResult.Status.COMPLETE ? AnalysisResult.Status.PARTIAL : base.getStatus();
        return new AnalysisResult(source,status,base.getParseMode(),all,new ArrayList<>(reasons));
    }
}
