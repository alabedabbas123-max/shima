package com.shima.analyzer.dependencies;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Extracts only literal Maven GAVs from common Groovy/Kotlin Gradle declarations. Never executes Gradle. */
public final class BuildDependencyScanner {
    public static final class Result {
        private final List<MavenCoordinate> coordinates;
        private final List<String> unsupported;
        Result(Set<MavenCoordinate> coordinates,List<String> unsupported) {
            this.coordinates=Collections.unmodifiableList(new ArrayList<>(coordinates));
            this.unsupported=Collections.unmodifiableList(new ArrayList<>(unsupported));
        }
        public List<MavenCoordinate> getCoordinates() { return coordinates; }
        public List<String> getUnsupported() { return unsupported; }
        public boolean isComplete() { return unsupported.isEmpty(); }
    }
    private static final class Token {
        final String value; final int offset; final boolean string, dynamic;
        Token(String value,int offset,boolean string,boolean dynamic) { this.value=value; this.offset=offset; this.string=string; this.dynamic=dynamic; }
    }
    public Result scan(String text) {
        if (text==null) throw new NullPointerException("text");
        if (text.length()>2_000_000) throw new IllegalArgumentException("Build file exceeds size limit");
        List<Token> tokens=tokenize(text); Set<MavenCoordinate> coordinates=new LinkedHashSet<>(); List<String> unsupported=new ArrayList<>();
        for (int i=0;i<tokens.size();i++) {
            Token token=tokens.get(i); if (token.string || !configuration(token.value)) continue;
            int j=i+1;
            if (j<tokens.size() && tokens.get(j).value.equals("(")) j++;
            if (j>=tokens.size() || !tokens.get(j).string || tokens.get(j).dynamic) {
                unsupported.add("Unsupported dependency expression at offset "+token.offset+" ("+token.value+")"); continue;
            }
            try { coordinates.add(MavenCoordinate.parse(tokens.get(j).value)); }
            catch (IllegalArgumentException bad) { unsupported.add("Unsupported coordinate at offset "+tokens.get(j).offset+": "+tokens.get(j).value); }
            int after=j+1;
            if (after<tokens.size() && tokens.get(after).value.equals(")")) after++;
            if (after<tokens.size() && (tokens.get(after).value.equals("{") || tokens.get(after).value.equals(",")))
                unsupported.add("Dependency options/exclusions are not resolved at offset "+token.offset);
        }
        return new Result(coordinates,unsupported);
    }
    private static boolean configuration(String name) {
        return name.equals("implementation") || name.equals("api") || name.equals("compile") || name.equals("compileOnly") ||
                name.equals("runtimeOnly") || name.equals("testImplementation") || name.equals("androidTestImplementation") ||
                name.endsWith("Implementation") || name.endsWith("CompileOnly") || name.endsWith("RuntimeOnly");
    }
    private static List<Token> tokenize(String text) {
        ArrayList<Token> out=new ArrayList<>(); int i=0,n=text.length();
        while (i<n) {
            char c=text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c=='/' && i+1<n && text.charAt(i+1)=='/') { i+=2; while (i<n && text.charAt(i)!='\n') i++; continue; }
            if (c=='/' && i+1<n && text.charAt(i+1)=='*') { int end=text.indexOf("*/",i+2); i=end<0?n:end+2; continue; }
            if (c=='\'' || c=='"') {
                int start=i++; boolean dynamic=false; StringBuilder value=new StringBuilder();
                if (i+1<n && text.charAt(i)==c && text.charAt(i+1)==c) {
                    int end=text.indexOf(""+c+c+c,i+2); i=end<0?n:end+3; out.add(new Token("",start,true,true)); continue;
                }
                boolean closed=false;
                while (i<n) {
                    char x=text.charAt(i++);
                    if (x==c) { closed=true; break; }
                    if (x=='\\') { dynamic=true; if (i<n) value.append(text.charAt(i++)); }
                    else { if (x=='$') dynamic=true; value.append(x); }
                }
                out.add(new Token(value.toString(),start,true,dynamic || !closed)); continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start=i++; while (i<n && Character.isJavaIdentifierPart(text.charAt(i))) i++;
                out.add(new Token(text.substring(start,i),start,false,false)); continue;
            }
            out.add(new Token(String.valueOf(c),i++,false,false));
        }
        return out;
    }
}
