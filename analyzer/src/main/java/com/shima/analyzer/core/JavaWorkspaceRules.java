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
    private static final int H=2, S=6, CLASS=11, EXTENDS=12, METHOD=13, PARAMETERS=14, PARAMETER=15, FIELD=16, LOCAL=17, CALL=18, ASSIGNED=19, OPAQUE=20;
    private static final int PRIVATE=1, PUBLIC=2, STATIC=4, ABSTRACT=8, IMPLEMENTS=16;
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
        MethodInfo(ClassInfo owner,String name,int ns,int ne,int start,int end,int flags) {
            this.owner=owner; this.name=name; nameStart=ns; nameEnd=ne; this.start=start; this.end=end; this.flags=flags;
        }
        boolean knownSignature() { return parameterCount>=0 && parameterCount==parameterTypes.size(); }
    }
    private static final class VarInfo {
        final ClassInfo owner; final MethodInfo method; final String name,type; final int start,end,flags;
        VarInfo(ClassInfo owner,MethodInfo method,String name,String type,int start,int end,int flags) {
            this.owner=owner; this.method=method; this.name=name; this.type=type; this.start=start; this.end=end; this.flags=flags;
        }
    }
    private static final class CallInfo {
        final ClassInfo owner; final MethodInfo method; final String name; final int nameStart,nameEnd,start,end,flags;
        String expected;
        CallInfo(ClassInfo owner,MethodInfo method,String name,int ns,int ne,int start,int end,int flags) {
            this.owner=owner; this.method=method; this.name=name; nameStart=ns; nameEnd=ne; this.start=start; this.end=end; this.flags=flags;
        }
    }
    static void add(List<Analyzer.Parsed> parsed, Map<String,List<Diagnostic>> out, WorkspaceSnapshot snapshot, XmlAnalyzer.Stop stop) {
        ArrayList<ClassInfo> classes=new ArrayList<>(); ArrayList<VarInfo> locals=new ArrayList<>(); ArrayList<CallInfo> calls=new ArrayList<>();
        Map<String,Analyzer.Parsed> eligible=new HashMap<>();
        Map<Analyzer.Parsed,List<int[]>> opaqueScopes=new HashMap<>();
        Map<Analyzer.Parsed,List<ClassInfo>> classesByFile=new HashMap<>();
        for (Analyzer.Parsed p : parsed) {
            if (stop.cancelled()) return;
            if (p.source.getLanguage()!=Language.JAVA || p.status!=AnalysisResult.Status.COMPLETE || p.events==null || SemanticRules.hasSyntaxErrors(p)) continue;
            // Java translates Unicode escapes before lexing; do not make guesses using raw-source identifiers.
            if (p.source.getText().contains("\\u")) continue;
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
                if (kind!=PARAMETERS && kind!=PARAMETER && kind!=FIELD && kind!=LOCAL && kind!=CALL) continue;
                if (!valid(text,a,b) || !valid(text,c,d) || opaque(opaqueScopes.get(p),a)) continue;
                ClassInfo owner=owner(fileClasses,p,a); if (owner==null) continue;
                MethodInfo method=method(owner,a);
                if (kind==PARAMETERS && method!=null) {
                    method.parameterCount=flags; method.returnType=text.substring(c,d).trim();
                } else if (kind==PARAMETER && method!=null) {
                    method.parameterTypes.add(normalizeType(text.substring(a,b))+(flags!=0 ? "[]" : ""));
                } else if (kind==FIELD) {
                    owner.fields.add(new VarInfo(owner,null,text.substring(a,b),text.substring(c,d).trim(),a,b,flags));
                } else if (kind==LOCAL && method!=null) {
                    locals.add(new VarInfo(owner,method,text.substring(a,b),text.substring(c,d).trim(),a,b,flags));
                } else if (kind==CALL && method!=null) {
                    CallInfo call=new CallInfo(owner,method,text.substring(a,b),a,b,c,d,flags);
                    calls.add(call); callsByRange.put(rangeKey(c,d),call);
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
        missingCalls(calls,locals,classes,out,snapshot,stop);
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
    private static void missingCalls(List<CallInfo> calls,List<VarInfo> locals,List<ClassInfo> classes,Map<String,List<Diagnostic>> out,WorkspaceSnapshot snapshot,XmlAnalyzer.Stop stop) {
        for (CallInfo call : calls) {
            if (stop.cancelled()) return;
            if ((call.flags>>4)!=0) continue; // only no-argument calls
            String text=call.owner.file.source.getText(), expression=text.substring(call.start,call.end), type=call.expected;
            if (type==null && (call.flags&2)!=0) type="void";
            if (!safeReturnType(type)) continue;
            boolean receiver=(call.flags&1)!=0; ClassInfo target=call.owner;
            if (receiver) {
                Matcher match=Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\.\\s*"+Pattern.quote(call.name)+"\\s*\\(\\s*\\)").matcher(expression);
                if (!match.matches()) continue;
                String variable=match.group(1);
                if (variable.equals("this")) receiver=false;
                else {
                    VarInfo resolved=null;
                    for (VarInfo local : locals) if (local.method==call.method && local.name.equals(variable) && local.start<call.start) {
                        if (resolved!=null) { resolved=null; break; } resolved=local;
                    }
                    if (resolved==null) continue;
                    ClassInfo found=null;
                    for (ClassInfo candidate : classes) if (candidate.name.equals(resolved.type)) {
                        if (found!=null) { found=null; break; } found=candidate;
                    }
                    if (found==null || !visibleFrom(found,call.owner,classes)) continue;
                    target=found;
                }
            }
            if (target.superText!=null || (target.flags&IMPLEMENTS)!=0 || isNested(target,classes) || OBJECT_METHODS.contains(call.name) || hasStaticImport(call.owner.file)) continue;
            if (type.equals("String")) {
                boolean ambiguous=hasConflictingImport(call.owner.file,"String","java.lang.String") || hasConflictingImport(target.file,"String","java.lang.String");
                for (ClassInfo candidate : classes) if (candidate.name.equals("String")) ambiguous=true;
                if (ambiguous) continue;
            }
            boolean exists=false; for (MethodInfo method : target.methods) if (method.name.equals(call.name)) { exists=true; break; }
            if (exists) continue;
            int same=0; for (CallInfo other : calls) if (other.owner==call.owner && other.name.equals(call.name)) same++;
            List<QuickFix> fixes=Collections.emptyList();
            List<WorkspaceFix> workspaceFixes=Collections.emptyList();
            if (same==1) {
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
            add(out.get(call.owner.file.source.getId()),"UNRESOLVED_METHOD","No local no-argument method '"+call.name+"' was found in the supported scope.",Diagnostic.Severity.ERROR,call.owner.lines,call.nameStart,call.nameEnd,fixes,workspaceFixes);
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
