package com.shima.analyzer.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deliberately narrow Java-only structural checks; not a general type resolver. */
final class JavaWorkspaceRules {
    private static final int H=2, S=6, CLASS=11, EXTENDS=12, METHOD=13, PARAMETERS=14, PARAMETER=15, FIELD=16, LOCAL=17, CALL=18, ASSIGNED=19, OPAQUE=20, MEMBER_ACCESS=21, LOCAL_SCOPE=22;
    private static final int PRIVATE=1, PUBLIC=2, STATIC=4, ABSTRACT=8, IMPLEMENTS=16, DECL_ARRAY=32;
    private static final Set<String> OBJECT_METHODS=new HashSet<>(Arrays.asList("getClass","hashCode","equals","clone","toString","notify","notifyAll","wait","finalize"));
    private JavaWorkspaceRules() { }
    private static final class ClassInfo {
        final Analyzer.Parsed file; final LineIndex lines; final String name; final int nameStart, nameEnd, start, end, flags;
        int superStart=-1, superEnd=-1; String superText;
        final List<MethodInfo> methods=new ArrayList<>(); final List<VarInfo> fields=new ArrayList<>();
        ClassInfo(Analyzer.Parsed file,LineIndex lines,String name,int nameStart,int nameEnd,int start,int end,int flags) {
            this.file=file; this.lines=lines; this.name=name; this.nameStart=nameStart; this.nameEnd=nameEnd; this.start=start; this.end=end; this.flags=flags;
        }
    }
    private static final class MethodInfo {
        final ClassInfo owner; final String name; final int nameStart,nameEnd,start,end,flags;
        int parameterCount=-1; String returnType;
        final List<String> parameterTypes=new ArrayList<>();
        final Map<String,String> parameterNames=new HashMap<>();
        boolean parameterNamesIncomplete;
        MethodInfo(ClassInfo owner,String name,int ns,int ne,int start,int end,int flags) {
            this.owner=owner; this.name=name; nameStart=ns; nameEnd=ne; this.start=start; this.end=end; this.flags=flags;
        }
        boolean knownSignature() { return parameterCount>=0 && parameterCount==parameterTypes.size(); }
    }
    private static final class VarInfo {
        final ClassInfo owner; final MethodInfo method; final String name,type; final int start,end,flags;
        int scopeStart=-1, scopeEnd=-1;
        VarInfo(ClassInfo owner,MethodInfo method,String name,String type,int start,int end,int flags) {
            this.owner=owner; this.method=method; this.name=name; this.type=type; this.start=start; this.end=end; this.flags=flags;
        }
    }
    private static final class CallInfo {
        final ClassInfo owner; final MethodInfo method; final String name; final int nameStart,nameEnd,start,flags;
        String expected;
        CallInfo(ClassInfo owner,MethodInfo method,String name,int ns,int ne,int start,int flags) {
            this.owner=owner; this.method=method; this.name=name; nameStart=ns; nameEnd=ne; this.start=start; this.flags=flags;
        }
    }
    private static final class AccessInfo {
        final ClassInfo owner; final MethodInfo method; final String name; final int nameStart,nameEnd,start;
        AccessInfo(ClassInfo owner,MethodInfo method,String name,int nameStart,int nameEnd,int start) {
            this.owner=owner; this.method=method; this.name=name; this.nameStart=nameStart; this.nameEnd=nameEnd; this.start=start;
        }
    }
    static void add(List<Analyzer.Parsed> parsed, Map<String,List<Diagnostic>> out, WorkspaceSnapshot snapshot, XmlAnalyzer.Stop stop, boolean projectIndexComplete) {
        ArrayList<ClassInfo> classes=new ArrayList<>(); ArrayList<VarInfo> locals=new ArrayList<>(); ArrayList<CallInfo> calls=new ArrayList<>();
        ArrayList<AccessInfo> accesses=new ArrayList<>();
        Map<String,Analyzer.Parsed> eligible=new HashMap<>();
        Map<Analyzer.Parsed,List<int[]>> opaqueScopes=new HashMap<>();
        Map<Analyzer.Parsed,List<ClassInfo>> classesByFile=new HashMap<>();
        for (Analyzer.Parsed p : parsed) {
            if (stop.cancelled()) return;
            if (p.source.getLanguage()!=Language.JAVA || p.status!=AnalysisResult.Status.COMPLETE || p.events==null || SemanticRules.hasSyntaxErrors(p)) continue;
            eligible.put(p.source.getId(),p);
            opaqueScopes.put(p,opaqueRanges(p));
            String text=p.source.getText(); LineIndex lines=new LineIndex(text);
            List<ClassInfo> fileClasses=new ArrayList<>(); classesByFile.put(p,fileClasses);
            for (int i=H;i+S<=p.events.length;i+=S) if (p.events[i]==CLASS) {
                if (stop.cancelled()) return;
                int ns=p.events[i+1], ne=p.events[i+2], bs=p.events[i+3], be=p.events[i+4];
                if (valid(text,ns,ne) && valid(text,bs,be) && !opaque(opaqueScopes.get(p),ns)) {
                    ClassInfo cl=new ClassInfo(p,lines,text.substring(ns,ne),ns,ne,bs,be,p.events[i+5]);
                    classes.add(cl); fileClasses.add(cl);
                }
            }
        }
        for (Analyzer.Parsed p : eligible.values()) {
            if (stop.cancelled()) return;
            String text=p.source.getText(); int[] e=p.events; List<ClassInfo> fileClasses=classesByFile.get(p);
            Map<Long,CallInfo> callsByRange=new HashMap<>();
            Map<Integer,VarInfo> localsByOffset=new HashMap<>();
            for (int i=H;i+S<=e.length;i+=S) {
                if (stop.cancelled()) return;
                int kind=e[i], a=e[i+1], b=e[i+2], c=e[i+3], d=e[i+4], flags=e[i+5];
                if (kind!=EXTENDS && kind!=METHOD) continue;
                if (!valid(text,a,b) || !valid(text,c,d) || opaque(opaqueScopes.get(p),a)) continue;
                ClassInfo owner=owner(fileClasses,p,a);
                if (kind==EXTENDS) {
                    for (ClassInfo cl : fileClasses) if (cl.start==c && cl.end==d) { cl.superStart=a; cl.superEnd=b; cl.superText=text.substring(a,b); }
                } else if (kind==METHOD && owner!=null) {
                    owner.methods.add(new MethodInfo(owner,text.substring(a,b),a,b,c,d,flags));
                }
            }
            for (int i=H;i+S<=e.length;i+=S) {
                if (stop.cancelled()) return;
                int kind=e[i], a=e[i+1], b=e[i+2], c=e[i+3], d=e[i+4], flags=e[i+5];
                if (kind!=PARAMETERS && kind!=PARAMETER && kind!=FIELD && kind!=LOCAL && kind!=CALL && kind!=MEMBER_ACCESS && kind!=LOCAL_SCOPE) continue;
                if (!valid(text,a,b) || !valid(text,c,d) || opaque(opaqueScopes.get(p),a)) continue;
                ClassInfo owner=owner(fileClasses,p,a); if (owner==null) continue;
                MethodInfo method=method(owner,a);
                if (kind==PARAMETERS && method!=null) {
                    method.parameterCount=flags; method.returnType=text.substring(c,d).trim();
                } else if (kind==PARAMETER && method!=null) {
                    String type=normalizeType(text.substring(a,b))+(flags!=0 ? "[]" : "");
                    method.parameterTypes.add(type);
                    Matcher param=Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:\\[\\s*\\]\\s*)*$").matcher(text.substring(c,d));
                    if (param.find()) method.parameterNames.put(param.group(1),type);
                    else method.parameterNamesIncomplete=true;
                } else if (kind==FIELD) {
                    owner.fields.add(new VarInfo(owner,null,text.substring(a,b),text.substring(c,d).trim()+((flags&DECL_ARRAY)!=0 ? "[]" : ""),a,b,flags));
                } else if (kind==LOCAL && method!=null) {
                    VarInfo local=new VarInfo(owner,method,text.substring(a,b),text.substring(c,d).trim()+((flags&DECL_ARRAY)!=0 ? "[]" : ""),a,b,flags);
                    locals.add(local); localsByOffset.put(a,local);
                } else if (kind==LOCAL_SCOPE && method!=null) {
                    VarInfo local=localsByOffset.get(a);
                    if (local!=null) { local.scopeStart=c; local.scopeEnd=d; }
                } else if (kind==CALL && method!=null) {
                    CallInfo call=new CallInfo(owner,method,text.substring(a,b),a,b,c,flags);
                    calls.add(call); callsByRange.put(rangeKey(c,d),call);
                } else if (kind==MEMBER_ACCESS && method!=null) {
                    accesses.add(new AccessInfo(owner,method,text.substring(a,b),a,b,c));
                }
            }
            for (int i=H;i+S<=e.length;i+=S) if (e[i]==ASSIGNED) {
                if (stop.cancelled()) return;
                int start=e[i+1], end=e[i+2], t=e[i+3], u=e[i+4];
                if (!valid(text,t,u)) continue;
                CallInfo call=callsByRange.get(rangeKey(start,end));
                if (call!=null) call.expected=text.substring(t,u).trim();
            }
        }
        Map<Analyzer.Parsed,Map<String,Integer>> countsByFile=new HashMap<>();
        for (ClassInfo cl : classes) {
            if (stop.cancelled()) return;
            duplicates(cl,out.get(cl.file.source.getId()));
            Map<String,Integer> counts=countsByFile.get(cl.file);
            if (counts==null) { counts=identifierCounts(cl.file.source.getText()); countsByFile.put(cl.file,counts); }
            unused(cl,locals,out.get(cl.file.source.getId()),counts);
            checkBaseAdapter(cl,classes,out.get(cl.file.source.getId()),snapshot.isOverrideEnforced());
        }
        // A batch/subset cannot prove that a receiver's declarations are absent elsewhere.
        if (projectIndexComplete) {
            missingCalls(calls,locals,classes,out,snapshot,stop);
            missingFields(accesses,locals,classes,out,stop);
        }
    }
    private static long rangeKey(int a,int b) { return ((long)a<<32) | (b&0xffffffffL); }
    private static boolean valid(String s,int a,int b) { return a>=0 && b>=a && b<=s.length(); }
    private static List<int[]> opaqueRanges(Analyzer.Parsed parsed) {
        List<int[]> ranges=new ArrayList<>();
        for (int i=H;i+S<=parsed.events.length;i+=S) if (parsed.events[i]==OPAQUE) ranges.add(new int[]{parsed.events[i+1],parsed.events[i+2]});
        Collections.sort(ranges,new java.util.Comparator<int[]>() { @Override public int compare(int[] a,int[] b) { return Integer.compare(a[0],b[0]); } });
        List<int[]> merged=new ArrayList<>();
        for (int[] range : ranges) {
            if (!merged.isEmpty() && range[0]<=merged.get(merged.size()-1)[1]) merged.get(merged.size()-1)[1]=Math.max(merged.get(merged.size()-1)[1],range[1]);
            else merged.add(range);
        }
        return merged;
    }
    private static boolean opaque(List<int[]> ranges,int offset) {
        int low=0, high=ranges.size()-1;
        while (low<=high) {
            int middle=(low+high)>>>1; int[] range=ranges.get(middle);
            if (offset<range[0]) high=middle-1; else if (offset>=range[1]) low=middle+1; else return true;
        }
        return false;
    }
    private static ClassInfo owner(List<ClassInfo> classes,Analyzer.Parsed p,int offset) {
        ClassInfo best=null; for (ClassInfo cl : classes) if (cl.file==p && cl.start<=offset && offset<cl.end && (best==null || cl.end-cl.start<best.end-best.start)) best=cl; return best;
    }
    private static MethodInfo method(ClassInfo owner,int offset) {
        int low=0, high=owner.methods.size()-1;
        while (low<=high) {
            int middle=(low+high)>>>1; MethodInfo m=owner.methods.get(middle);
            if (offset<m.start) high=middle-1; else if (offset>=m.end) low=middle+1; else return m;
        }
        return null;
    }
    private static String normalizeType(String value) { return value.replaceAll("\\s+",""); }
    private static void add(List<Diagnostic> out,String code,String message,Diagnostic.Severity severity,LineIndex lines,int start,int end,List<QuickFix> fixes,List<WorkspaceFix> workspaceFixes) {
        out.add(new Diagnostic(code,message,severity,lines.range(start,end),fixes,workspaceFixes));
    }
    private static void duplicates(ClassInfo cl,List<Diagnostic> out) {
        Map<String,List<VarInfo>> fields=new LinkedHashMap<>();
        for (VarInfo f : cl.fields) {
            List<VarInfo> group=fields.get(f.name);
            if (group==null) { group=new ArrayList<>(); fields.put(f.name,group); }
            group.add(f);
        }
        for (List<VarInfo> group : fields.values()) if (group.size()>1) for (VarInfo field : group)
            add(out,"DUPLICATE_FIELD","Duplicate field '"+field.name+"' in the same class.",Diagnostic.Severity.ERROR,cl.lines,field.start,field.end,Collections.<QuickFix>emptyList(),Collections.<WorkspaceFix>emptyList());
        Map<String,List<MethodInfo>> signatures=new LinkedHashMap<>();
        for (MethodInfo m : cl.methods) if (m.knownSignature()) {
            String key=m.name+"("+join(m.parameterTypes)+")";
            List<MethodInfo> group=signatures.get(key);
            if (group==null) { group=new ArrayList<>(); signatures.put(key,group); }
            group.add(m);
        }
        for (List<MethodInfo> group : signatures.values()) if (group.size()>1) for (MethodInfo m : group)
            add(out,"DUPLICATE_METHOD","Duplicate method signature '"+m.name+"' in the same class.",Diagnostic.Severity.ERROR,cl.lines,m.nameStart,m.nameEnd,Collections.<QuickFix>emptyList(),Collections.<WorkspaceFix>emptyList());
    }
    private static void unused(ClassInfo cl,List<VarInfo> locals,List<Diagnostic> out,Map<String,Integer> counts) {
        String text=cl.file.source.getText();
        if (text.contains("\"\"\"") || text.contains("\\u")) return; // Escaped text blocks/Unicode preprocessing need full Java lexing.
        for (VarInfo field : cl.fields) if ((field.flags&PRIVATE)!=0 && count(counts,field.name)==1)
            add(out,"UNUSED_FIELD","Private field '"+field.name+"' appears unused; reflection or generated code may use it.",Diagnostic.Severity.WARNING,cl.lines,field.start,field.end,Collections.<QuickFix>emptyList(),Collections.<WorkspaceFix>emptyList());
        for (MethodInfo m : cl.methods) if ((m.flags&PRIVATE)!=0 && count(counts,m.name)==1 && !hasOverride(m))
            add(out,"UNUSED_METHOD","Private method '"+m.name+"' appears unused; reflection or generated code may use it.",Diagnostic.Severity.WARNING,cl.lines,m.nameStart,m.nameEnd,Collections.<QuickFix>emptyList(),Collections.<WorkspaceFix>emptyList());
        for (VarInfo local : locals) if (local.owner==cl && count(counts,local.name)==1)
            add(out,"UNUSED_LOCAL","Local variable '"+local.name+"' appears unused.",Diagnostic.Severity.WARNING,cl.lines,local.start,local.end,Collections.<QuickFix>emptyList(),Collections.<WorkspaceFix>emptyList());
        if ((cl.flags&PRIVATE)!=0 && count(counts,cl.name)==1)
            add(out,"UNUSED_CLASS","Private nested class '"+cl.name+"' appears unused.",Diagnostic.Severity.WARNING,cl.lines,cl.nameStart,cl.nameEnd,Collections.<QuickFix>emptyList(),Collections.<WorkspaceFix>emptyList());
    }
    private static int count(Map<String,Integer> counts,String name) { Integer n=counts.get(name); return n==null?0:n; }
    private static String join(List<String> values) {
        StringBuilder result=new StringBuilder();
        for (String value : values) { if (result.length()>0) result.append(", "); result.append(value); }
        return result.toString();
    }
    /** Lexes identifiers while skipping comments, ordinary strings and Java text blocks. */
    private static Map<String,Integer> identifierCounts(String s) {
        Map<String,Integer> result=new HashMap<>(); int i=0,n=s.length();
        while (i<n) {
            char c=s.charAt(i);
            if (c=='/' && i+1<n && s.charAt(i+1)=='/') { i+=2; while (i<n && s.charAt(i)!='\n') i++; }
            else if (c=='/' && i+1<n && s.charAt(i+1)=='*') { int end=s.indexOf("*/",i+2); i=end<0?n:end+2; }
            else if (c=='"' && s.startsWith("\"\"\"",i)) { int end=s.indexOf("\"\"\"",i+3); i=end<0?n:end+3; }
            else if (c=='"' || c=='\'') { char q=c; i++; while (i<n) { char x=s.charAt(i++); if (x=='\\' && i<n) i++; else if (x==q) break; } }
            else if (Character.isJavaIdentifierStart(c)) { int start=i++; while (i<n && Character.isJavaIdentifierPart(s.charAt(i))) i++; String name=s.substring(start,i); result.put(name,count(result,name)+1); }
            else i++;
        }
        return result;
    }
    private static boolean hasOverride(MethodInfo m) {
        return Pattern.compile("@\\s*(?:java\\.lang\\.)?Override\\b").matcher(m.owner.file.source.getText().substring(m.start,m.nameStart)).find();
    }
    private static final String[] REQUIRED={"getCount","getItem","getItemId","getView"};
    private static final String[][] PARAMS={{},{"int"},{"int"},{"int","View","ViewGroup"}};
    private static boolean signature(MethodInfo method,int required) {
        if (!method.name.equals(REQUIRED[required]) || !method.knownSignature() || method.parameterTypes.size()!=PARAMS[required].length) return false;
        for (int i=0;i<PARAMS[required].length;i++) {
            String actual=method.parameterTypes.get(i), expected=PARAMS[required][i];
            if (expected.equals("int")) { if (!actual.equals("int")) return false; }
            else if (!actual.equals("android.view."+expected) &&
                    !(actual.equals(expected) && hasImport(method.owner.file,"android.view."+expected))) return false;
        }
        return true;
    }
    private static boolean definitelyWrongReturn(MethodInfo method,int required) {
        String type=normalizeType(method.returnType);
        if (required==0) return !type.equals("int");
        if (required==2) return !type.equals("long");
        return Arrays.asList("void","boolean","byte","short","int","long","float","double","char").contains(type);
    }
    private static void checkBaseAdapter(ClassInfo cl,List<ClassInfo> classes,List<Diagnostic> out,boolean enforceOverride) {
        if ((cl.flags&IMPLEMENTS)!=0 || cl.superText==null || !cl.superText.matches("(?s)extends\\s+(?:android\\.widget\\.)?BaseAdapter\\s*")) return;
        String text=cl.file.source.getText();
        if (!cl.superText.contains("android.widget.BaseAdapter") && !hasImport(cl.file,"android.widget.BaseAdapter")) return;
        for (ClassInfo other : classes) if (other.file==cl.file && other.name.equals("BaseAdapter")) return;
        ArrayList<Integer> missing=new ArrayList<>(); boolean conflict=false;
        for (int i=0;i<REQUIRED.length;i++) {
            MethodInfo implemented=null;
            for (MethodInfo m : cl.methods) if (signature(m,i)) { implemented=m; break; }
            if (implemented==null) {
                boolean ambiguous=false;
                for (MethodInfo m : cl.methods) if (m.name.equals(REQUIRED[i]) && (!m.knownSignature() || m.parameterCount==PARAMS[i].length)) ambiguous=true;
                if (!ambiguous) missing.add(i); // Unknown type resolution could already satisfy the method.
            }
            else if ((implemented.flags&PUBLIC)==0 || (implemented.flags&STATIC)!=0 || definitelyWrongReturn(implemented,i)) { missing.add(i); conflict=true; }
            else if (!hasOverride(implemented)) {
                String newline=text.contains("\r\n")?"\r\n":"\n";
                String indent=indentAt(text,implemented.start);
                QuickFix fix=fix("Add @Override",cl.file.source,new TextEdit(implemented.start,implemented.start,"@Override"+newline+indent));
                add(out,"MISSING_OVERRIDE_ANNOTATION","This method overrides BaseAdapter; @Override is a style policy, not a Java syntax requirement.",
                        enforceOverride?Diagnostic.Severity.ERROR:Diagnostic.Severity.WARNING,cl.lines,implemented.nameStart,implemented.nameEnd,Collections.singletonList(fix),Collections.<WorkspaceFix>emptyList());
            }
        }
        if ((cl.flags&ABSTRACT)!=0 || missing.isEmpty()) return;
        ArrayList<String> names=new ArrayList<>(); for (int i : missing) names.add(REQUIRED[i]);
        List<QuickFix> fixes=Collections.emptyList();
        if (!conflict) {
            QuickFix fix=baseAdapterFix(cl,missing,classes);
            if (fix!=null) fixes=Collections.singletonList(fix);
        }
        add(out,"MISSING_ABSTRACT_METHODS","BaseAdapter requires implementations: "+join(names)+".",Diagnostic.Severity.ERROR,cl.lines,cl.superStart,cl.superEnd,fixes,Collections.<WorkspaceFix>emptyList());
    }
    private static boolean hasImport(Analyzer.Parsed p,String fqcn) {
        String text=p.source.getText();
        for (int i=H;i+S<=p.events.length;i+=S) if (p.events[i]==5 && valid(text,p.events[i+1],p.events[i+2])) {
            String part=text.substring(p.events[i+1],p.events[i+2]);
            if (part.matches("(?s)import\\s+"+Pattern.quote(fqcn)+"\\s*;")) return true;
        }
        return false;
    }
    private static boolean hasConflictingImport(Analyzer.Parsed p,String simple,String expected) {
        String text=p.source.getText();
        for (int i=H;i+S<=p.events.length;i+=S) if (p.events[i]==5 && valid(text,p.events[i+1],p.events[i+2])) {
            String part=text.substring(p.events[i+1],p.events[i+2]);
            if (part.contains("*")) return true;
            Matcher m=Pattern.compile("import\\s+([A-Za-z0-9_$.]+)\\s*;").matcher(part);
            if (m.find() && m.group(1).endsWith("."+simple) && !m.group(1).equals(expected)) return true;
        }
        return false;
    }
    private static QuickFix baseAdapterFix(ClassInfo cl,List<Integer> missing,List<ClassInfo> classes) {
        String text=cl.file.source.getText(), newline=text.contains("\r\n")?"\r\n":"\n";
        boolean viewMissing=missing.contains(3), viewConflict=hasConflictingImport(cl.file,"View","android.view.View"), groupConflict=hasConflictingImport(cl.file,"ViewGroup","android.view.ViewGroup");
        for (ClassInfo c : classes) if (c.name.equals("View")) viewConflict=true; else if (c.name.equals("ViewGroup")) groupConflict=true;
        String view=viewConflict?"android.view.View":"View", group=groupConflict?"android.view.ViewGroup":"ViewGroup";
        String indent=indentAt(text,cl.nameStart)+"    ";
        StringBuilder body=new StringBuilder(newline);
        for (int i : missing) {
            body.append(indent).append("@Override").append(newline).append(indent).append("public ");
            if (i==0) body.append("int getCount() { return 0; }");
            else if (i==1) body.append("java.lang.Object getItem(int position) { return null; }");
            else if (i==2) body.append("long getItemId(int position) { return 0L; }");
            else body.append(view).append(" getView(int position, ").append(view).append(" convertView, ").append(group).append(" parent) { return null; }");
            body.append(newline).append(newline);
        }
        body.append(indentAt(text,cl.nameStart));
        List<TextEdit> edits=new ArrayList<>(); edits.add(new TextEdit(cl.end-1,cl.end-1,body.toString()));
        if (viewMissing) {
            ArrayList<String> imports=new ArrayList<>();
            if (!viewConflict && !hasImport(cl.file,"android.view.View")) imports.add("android.view.View");
            if (!groupConflict && !hasImport(cl.file,"android.view.ViewGroup")) imports.add("android.view.ViewGroup");
            if (!imports.isEmpty()) {
                int at=0; boolean existing=false;
                for (int i=H;i+S<=cl.file.events.length;i+=S) if (cl.file.events[i]==5) { at=Math.max(at,cl.file.events[i+2]); existing=true; }
                if (!existing) for (int i=H;i+S<=cl.file.events.length;i+=S) if (cl.file.events[i]==4) at=Math.max(at,cl.file.events[i+2]);
                StringBuilder block=new StringBuilder();
                if (at>0) block.append(newline);
                for (String imp : imports) block.append("import ").append(imp).append(';').append(newline);
                if (at==0) block.append(newline);
                edits.add(new TextEdit(at,at,block.toString()));
            }
        }
        return new QuickFix("Implement BaseAdapter methods",cl.file.source.getId(),QuickFix.fingerprint(text),edits);
    }
    private static String indentAt(String text,int offset) {
        int start=text.lastIndexOf('\n',Math.max(0,offset-1))+1, i=start;
        while (i<offset && (text.charAt(i)==' ' || text.charAt(i)=='\t')) i++;
        return text.substring(start,i);
    }
    private static QuickFix fix(String title,SourceFile source,TextEdit edit) { return new QuickFix(title,source.getId(),QuickFix.fingerprint(source.getText()),Collections.singletonList(edit)); }
    /** Resolves only uniquely named project source classes; external/ambiguous bases stay unknown. */
    private static final class SourceTypes {
        static final int PRESENT=1, ABSENT=0, UNKNOWN=-1;
        final List<ClassInfo> classes;
        final Map<String,List<ClassInfo>> byName=new HashMap<>();
        final Map<MethodInfo,Map<String,List<VarInfo>>> localsByMethod=new HashMap<>();
        SourceTypes(List<ClassInfo> classes,List<VarInfo> locals) {
            this.classes=classes;
            for (VarInfo local : locals) {
                Map<String,List<VarInfo>> names=localsByMethod.get(local.method);
                if (names==null) { names=new HashMap<>(); localsByMethod.put(local.method,names); }
                List<VarInfo> declarations=names.get(local.name);
                if (declarations==null) { declarations=new ArrayList<>(); names.put(local.name,declarations); }
                declarations.add(local);
            }
            for (ClassInfo c : classes) {
                List<ClassInfo> bucket=byName.get(c.name);
                if (bucket==null) { bucket=new ArrayList<>(); byName.put(c.name,bucket); }
                bucket.add(c);
            }
        }
        ClassInfo type(String raw,ClassInfo from) {
            if (raw==null || from==null) return null;
            String name=raw.trim();
            if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) return null;
            int dot=name.lastIndexOf('.'); String simple=dot<0 ? name : name.substring(dot+1);
            List<ClassInfo> candidates=byName.get(simple);
            if (candidates==null) return null;
            ClassInfo found=null;
            for (ClassInfo candidate : candidates) {
                // A same-file nested/local type may be out of scope or shadow an import.
                // Resolve only top-level source types until declaration scopes are indexed.
                if (isNested(candidate,classes)) continue;
                if (dot>=0) {
                    String pkg=packageName(candidate.file);
                    if (pkg==null || !(pkg.isEmpty()?candidate.name:pkg+"."+candidate.name).equals(name)) continue;
                }
                if (!visibleFrom(candidate,from,classes)) continue;
                if (found!=null && found!=candidate) return null;
                found=candidate;
            }
            return found;
        }
        ClassInfo parent(ClassInfo child) {
            if (child.superText==null) return null;
            Matcher match=Pattern.compile("\\s*extends\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*").matcher(child.superText);
            if (!match.matches()) return null;
            return type(match.group(1),child);
        }
        boolean root(ClassInfo cl) {
            // A source/imported class named Object can shadow java.lang.Object.
            return cl.superText==null || cl.superText.matches("\\s*extends\\s+java\\.lang\\.Object\\s*");
        }
        int method(ClassInfo start,String name) {
            Set<ClassInfo> seen=new HashSet<>(); ClassInfo cl=start;
            while (cl!=null && seen.add(cl) && seen.size()<=128) {
                for (MethodInfo method : cl.methods) if (method.name.equals(name)) return PRESENT;
                // Unindexed interfaces may supply default methods; never infer their absence.
                if ((cl.flags&IMPLEMENTS)!=0) return UNKNOWN;
                if (root(cl)) return OBJECT_METHODS.contains(name) ? PRESENT : ABSENT;
                cl=parent(cl);
            }
            return UNKNOWN;
        }
        int field(ClassInfo start,String name) {
            Set<ClassInfo> seen=new HashSet<>(); ClassInfo cl=start;
            while (cl!=null && seen.add(cl) && seen.size()<=128) {
                for (VarInfo field : cl.fields) if (field.name.equals(name)) return PRESENT;
                if ((cl.flags&IMPLEMENTS)!=0) return UNKNOWN;
                if (root(cl)) return ABSENT;
                cl=parent(cl);
            }
            return UNKNOWN;
        }
        VarInfo fieldDeclaration(ClassInfo start,String name) {
            Set<ClassInfo> seen=new HashSet<>(); ClassInfo cl=start;
            while (cl!=null && seen.add(cl) && seen.size()<=128) {
                VarInfo found=null;
                for (VarInfo field : cl.fields) if (field.name.equals(name)) {
                    if (found!=null) return null;
                    found=field;
                }
                if (found!=null) return found;
                if ((cl.flags&IMPLEMENTS)!=0 || root(cl)) return null;
                cl=parent(cl);
            }
            return null;
        }
        ClassInfo receiver(String expression,ClassInfo owner,MethodInfo method,int offset) {
            String value=expression.replaceAll("\\s+","");
            if (value.equals("this")) return owner;
            if (value.equals("super")) return parent(owner);
            boolean explicit=value.startsWith("this.");
            String name=explicit ? value.substring(5) : value;
            if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) return null;
            if (!explicit) {
                // A parameter/local shadows a field. Ambiguous scope is not an absence proof.
                if (method.parameterNamesIncomplete || !method.knownSignature()) return null;
                String parameter=method.parameterNames.get(name);
                VarInfo local=null;
                Map<String,List<VarInfo>> names=localsByMethod.get(method);
                List<VarInfo> declarations=names==null ? null : names.get(name);
                if (declarations!=null) for (VarInfo item : declarations) if (item.start<offset) {
                    if (item.scopeStart<0) return null; // e.g. for-init without a safely recorded scope
                    if (offset<item.scopeStart || offset>=item.scopeEnd) continue;
                    if (local!=null) return null;
                    local=item;
                }
                if (parameter!=null && local!=null) return null;
                if (parameter!=null) return type(parameter,owner);
                if (local!=null) return type(local.type,owner);
            }
            VarInfo field=fieldDeclaration(owner,name);
            if (field==null || (field.owner!=owner && (field.flags&PRIVATE)!=0)) return null;
            return type(field.type,owner);
        }
    }
    private static String receiverText(String text,int start,int nameStart) {
        if (start<0 || start>nameStart || nameStart>text.length()) return null;
        String prefix=text.substring(start,nameStart).trim();
        if (!prefix.endsWith(".")) return null;
        return prefix.substring(0,prefix.length()-1).trim();
    }
    private static void missingCalls(List<CallInfo> calls,List<VarInfo> locals,List<ClassInfo> classes,Map<String,List<Diagnostic>> out,WorkspaceSnapshot snapshot,XmlAnalyzer.Stop stop) {
        SourceTypes types=new SourceTypes(classes,locals);
        Map<ClassInfo,Map<String,Integer>> callCounts=new HashMap<>();
        for (CallInfo call : calls) {
            Map<String,Integer> names=callCounts.get(call.owner);
            if (names==null) { names=new HashMap<>(); callCounts.put(call.owner,names); }
            Integer previous=names.get(call.name);
            names.put(call.name,previous==null ? 1 : previous+1);
        }
        for (CallInfo call : calls) {
            if (stop.cancelled()) return;
            String text=call.owner.file.source.getText(), type=call.expected;
            if (type==null && (call.flags&2)!=0) type="void";
            boolean receiver=(call.flags&1)!=0;
            // Unqualified calls in a nested/local class can resolve to an enclosing instance.
            if (!receiver && isNested(call.owner,classes)) continue;
            ClassInfo target=call.owner;
            if (receiver) {
                String expression=receiverText(text,call.start,call.nameStart);
                if (expression==null) continue;
                target=types.receiver(expression,call.owner,call.method,call.start);
                if (target==null) continue;
                if (expression.replaceAll("\\s+","").equals("this")) receiver=false;
            }
            if (!receiver && hasStaticImport(call.owner.file)) continue;
            if (types.method(target,call.name)!=SourceTypes.ABSENT) continue;
            int same=callCounts.get(call.owner).get(call.name);
            List<QuickFix> fixes=Collections.emptyList();
            List<WorkspaceFix> workspaceFixes=Collections.emptyList();
            boolean fixable=same==1 && (call.flags>>4)==0 && safeReturnType(type) && !isNested(target,classes);
            if ("String".equals(type)) {
                if (hasConflictingImport(call.owner.file,"String","java.lang.String") || hasConflictingImport(target.file,"String","java.lang.String")) fixable=false;
                for (ClassInfo candidate : classes) if (candidate.name.equals("String")) fixable=false;
            }
            if (fixable) {
                String newline=text.contains("\r\n")?"\r\n":"\n", targetText=target.file.source.getText();
                String indent=indentAt(targetText,target.nameStart)+"    ";
                String body=defaultReturn(type,target,call.name);
                String decl=newline+indent+(receiver?"public ":"private ")+(!receiver && (call.method.flags&STATIC)!=0?"static ":"")+type+" "+call.name+"() {"+(body.isEmpty()?"":" "+body+" ")+"}"+newline+indentAt(targetText,target.nameStart);
                TextEdit edit=new TextEdit(target.end-1,target.end-1,decl);
                if (target.file==call.owner.file) fixes=Collections.singletonList(fix("Create method "+call.name,target.file.source,edit));
                else {
                    Map<String,String> guards=new LinkedHashMap<>();
                    guards.put(call.owner.file.source.getId(),text); guards.put(target.file.source.getId(),targetText);
                    Map<String,List<TextEdit>> changes=new LinkedHashMap<>();
                    changes.put(target.file.source.getId(),Collections.singletonList(edit));
                    workspaceFixes=Collections.singletonList(new WorkspaceFix("Create method "+call.name+" in "+target.name,guards,changes));
                }
            }
            add(out.get(call.owner.file.source.getId()),"UNRESOLVED_METHOD","Method '"+call.name+"' was not found in the known project class hierarchy.",Diagnostic.Severity.ERROR,call.owner.lines,call.nameStart,call.nameEnd,fixes,workspaceFixes);
        }
    }
    private static void missingFields(List<AccessInfo> accesses,List<VarInfo> locals,List<ClassInfo> classes,Map<String,List<Diagnostic>> out,XmlAnalyzer.Stop stop) {
        SourceTypes types=new SourceTypes(classes,locals);
        for (AccessInfo access : accesses) {
            if (stop.cancelled()) return;
            String text=access.owner.file.source.getText();
            String expression=receiverText(text,access.start,access.nameStart);
            if (expression==null) continue;
            ClassInfo target=types.receiver(expression,access.owner,access.method,access.start);
            if (target==null || types.field(target,access.name)!=SourceTypes.ABSENT) continue;
            add(out.get(access.owner.file.source.getId()),"UNRESOLVED_FIELD","Field '"+access.name+"' was not found in the known project class hierarchy.",Diagnostic.Severity.ERROR,access.owner.lines,access.nameStart,access.nameEnd,Collections.<QuickFix>emptyList(),Collections.<WorkspaceFix>emptyList());
        }
    }
    private static boolean visibleFrom(ClassInfo target,ClassInfo caller,List<ClassInfo> classes) {
        if (target.file==caller.file) return true;
        if (isNested(target,classes)) return false;
        String targetPackage=packageName(target.file), callerPackage=packageName(caller.file);
        if (targetPackage==null || callerPackage==null) return false;
        String qualified=targetPackage.isEmpty()?target.name:targetPackage+"."+target.name;
        if (hasConflictingImport(caller.file,target.name,qualified)) return false;
        if (targetPackage.equals(callerPackage)) return true;
        return (target.flags&PUBLIC)!=0 && !targetPackage.isEmpty() && hasImport(caller.file,qualified);
    }
    private static String packageName(Analyzer.Parsed parsed) {
        String text=parsed.source.getText();
        for (int i=H;i+S<=parsed.events.length;i+=S) if (parsed.events[i]==4 && valid(text,parsed.events[i+1],parsed.events[i+2])) {
            Matcher matcher=Pattern.compile("\\bpackage\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;").matcher(text.substring(parsed.events[i+1],parsed.events[i+2]));
            return matcher.find() ? matcher.group(1) : null;
        }
        return "";
    }
    private static boolean isNested(ClassInfo target,List<ClassInfo> classes) {
        for (ClassInfo other : classes) if (other!=target && other.file==target.file && other.start<target.nameStart && target.end<=other.end) return true;
        return false;
    }
    private static boolean hasStaticImport(Analyzer.Parsed p) {
        String text=p.source.getText(); for (int i=H;i+S<=p.events.length;i+=S) if (p.events[i]==5 && valid(text,p.events[i+1],p.events[i+2]) && text.substring(p.events[i+1],p.events[i+2]).matches("(?s)import\\s+static\\b.*")) return true; return false;
    }
    private static boolean safeReturnType(String type) { return type!=null && Arrays.asList("void","boolean","byte","short","int","long","float","double","char","String","java.lang.String").contains(type); }
    private static String defaultReturn(String type,ClassInfo target,String method) {
        if (type.equals("void")) return "";
        if (method.startsWith("get") && method.length()>3) {
            String name=method.substring(3,4).toLowerCase(Locale.US)+method.substring(4);
            for (VarInfo f : target.fields) if (f.name.equals(name) && f.type.equals(type)) return "return "+name+";";
        }
        if (type.equals("boolean")) return "return false;";
        if (type.equals("long")) return "return 0L;";
        if (type.equals("float")) return "return 0f;";
        if (type.equals("double")) return "return 0d;";
        if (type.equals("char")) return "return '\\0';";
        if (type.equals("String") || type.equals("java.lang.String")) return "return null;";
        return "return 0;";
    }
}
