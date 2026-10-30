package com.shima.analyzer.dependencies;

import com.shima.analyzer.core.AnalysisConfig;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Optional, offline index of public top-level types in the exact JAR/AAR files
 * selected by the host's build resolver. It never loads or executes those classes.
 * This provides import candidates, not member signatures or a Java/Kotlin classpath.
 */
public final class ClasspathSymbols {
    private static final int MAX_ARTIFACTS=512, MAX_ENTRIES=200_000, MAX_SYMBOLS=50_000;
    private static final long MAX_CLASS_BYTES=4L*1024*1024, MAX_NESTED_BYTES=128L*1024*1024;
    private static final String TYPE="[A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z_$][A-Za-z0-9_$]*)+";
    private ClasspathSymbols() { }

    public static final class Result {
        private final List<String> symbols, issues;
        private Result(Set<String> names,List<String> issues) {
            ArrayList<String> sorted=new ArrayList<>(names); Collections.sort(sorted);
            this.symbols=Collections.unmodifiableList(sorted);
            this.issues=Collections.unmodifiableList(new ArrayList<>(issues));
        }
        public List<String> getSymbols() { return symbols; }
        public List<String> getIssues() { return issues; }
        /** No known read failures/limits; the index still covers only public top-level types. */
        public boolean isComplete() { return issues.isEmpty(); }
        public void addTo(AnalysisConfig.Builder builder) {
            if (builder==null) throw new NullPointerException("builder");
            for (String name : symbols) builder.addSymbol(name);
        }
    }
    private static final class State {
        final LinkedHashSet<String> names=new LinkedHashSet<>(), duplicates=new LinkedHashSet<>();
        final ArrayList<String> issues=new ArrayList<>();
        int entries;
        void issue(String message) { if (issues.size()<100) issues.add(message); }
        boolean allowEntry() {
            if (++entries<=MAX_ENTRIES) return true;
            if (entries==MAX_ENTRIES+1) issue("Classpath entry limit reached");
            return false;
        }
        void add(String name) {
            if (duplicates.contains(name)) return;
            if (names.contains(name)) { names.remove(name); duplicates.add(name); issue("Duplicate class: "+name); return; }
            if (names.size()>=MAX_SYMBOLS) { issue("Classpath symbol limit reached"); return; }
            names.add(name);
        }
    }

    /** Pass the resolved artifacts for the active variant/version, not a directory of all cached versions. */
    public static Result scan(List<File> artifacts) {
        if (artifacts==null) throw new NullPointerException("artifacts");
        State state=new State();
        if (artifacts.size()>MAX_ARTIFACTS) state.issue("Classpath artifact limit reached");
        int count=Math.min(artifacts.size(),MAX_ARTIFACTS);
        for (int i=0;i<count;i++) {
            File file=artifacts.get(i);
            if (file==null || !file.isFile()) { state.issue("Classpath artifact is missing"); continue; }
            String name=file.getName().toLowerCase(java.util.Locale.US);
            if (!name.endsWith(".jar") && !name.endsWith(".aar")) { state.issue("Unsupported classpath artifact: "+file.getName()); continue; }
            try (ZipFile zip=new ZipFile(file)) {
                Enumeration<? extends ZipEntry> entries=zip.entries();
                boolean hasAarJar=false;
                while (entries.hasMoreElements()) {
                    ZipEntry entry=entries.nextElement();
                    if (!state.allowEntry()) break;
                    if (entry.isDirectory()) continue;
                    String path=entry.getName();
                    if (name.endsWith(".aar")) {
                        if (!(path.equals("classes.jar") || (path.startsWith("libs/") && path.endsWith(".jar") && path.indexOf('/',5)<0))) continue;
                        hasAarJar=true;
                        if (entry.getSize()>MAX_NESTED_BYTES) { state.issue("AAR nested JAR is too large: "+path); continue; }
                        try (InputStream raw=zip.getInputStream(entry);
                             ZipInputStream nested=new ZipInputStream(new BoundedInputStream(raw,MAX_NESTED_BYTES))) {
                            ZipEntry member; long unpacked=0;
                            while ((member=nested.getNextEntry())!=null) {
                                if (!state.allowEntry()) break;
                                long remaining=MAX_NESTED_BYTES-unpacked;
                                if (remaining<=0 || member.getSize()>remaining) throw new IOException("nested uncompressed limit");
                                BoundedInputStream body=new BoundedInputStream(nested,remaining);
                                if (!member.isDirectory()) inspectClass(member.getName(),member.getSize(),body,state);
                                // ZipInputStream.closeEntry() can otherwise inflate an unbounded zip bomb.
                                byte[] discard=new byte[8192];
                                while (body.read(discard,0,discard.length)!=-1) { }
                                unpacked+=body.consumed();
                                nested.closeEntry();
                            }
                        } catch (IOException ex) { state.issue("Cannot read nested JAR "+path+": "+ex.getClass().getSimpleName()); }
                    } else {
                        try (InputStream input=zip.getInputStream(entry)) { inspectClass(path,entry.getSize(),input,state); }
                    }
                }
                // Resource-only AARs legitimately have no classes.jar.
                if (name.endsWith(".aar") && !hasAarJar && zip.size()==0) state.issue("Empty AAR: "+file.getName());
            } catch (IOException | RuntimeException ex) { state.issue("Cannot read "+file.getName()+": "+ex.getClass().getSimpleName()); }
        }
        return new Result(state.names,state.issues);
    }

    private static void inspectClass(String path,long size,InputStream input,State state) {
        if (!path.endsWith(".class") || path.startsWith("META-INF/") || path.indexOf('$')>=0) return;
        String internal=path.substring(0,path.length()-6);
        if (!internal.matches(TYPE) || internal.endsWith("/package-info") || internal.endsWith("/module-info")) return;
        if (size>MAX_CLASS_BYTES) { state.issue("Class file is too large: "+path); return; }
        try (DataInputStream in=new DataInputStream(new BoundedInputStream(input,MAX_CLASS_BYTES))) {
            if (in.readInt()!=0xCAFEBABE) throw new IOException("bad magic");
            in.readUnsignedShort(); in.readUnsignedShort();
            int count=in.readUnsignedShort();
            if (count<2) throw new IOException("empty constant pool");
            String[] utf8=new String[count]; int[] classNames=new int[count];
            for (int i=1;i<count;i++) {
                int tag=in.readUnsignedByte();
                switch (tag) {
                    case 1: utf8[i]=in.readUTF(); break;
                    case 3: case 4: in.readInt(); break;
                    case 5: case 6: in.readLong(); i++; break;
                    case 7: classNames[i]=in.readUnsignedShort(); break;
                    case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                    case 9: case 10: case 11: case 12: case 17: case 18: in.readInt(); break;
                    case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                    default: throw new IOException("unknown constant pool tag");
                }
            }
            int flags=in.readUnsignedShort(), self=in.readUnsignedShort();
            in.readUnsignedShort(); // superclass
            if (self<=0 || self>=count || classNames[self]<=0 || classNames[self]>=count
                    || !internal.equals(utf8[classNames[self]])) throw new IOException("class name mismatch");
            if ((flags&0x0001)!=0 && (flags&0x1000)==0) state.add(internal.replace('/','.'));
        } catch (IOException | RuntimeException ex) { state.issue("Invalid class header: "+path); }
    }
    /** Limits decompression without closing the parent ZipInputStream for each class. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream source; private final long limit; private long remaining;
        BoundedInputStream(InputStream source,long limit) { this.source=source; this.limit=limit; remaining=limit; }
        long consumed() { return limit-remaining; }
        @Override public int read() throws IOException {
            if (remaining<=0) throw new EOFException("uncompressed limit reached");
            int result=source.read(); if (result>=0) remaining--; return result;
        }
        @Override public int read(byte[] bytes,int offset,int length) throws IOException {
            if (length==0) return 0;
            if (remaining<=0) throw new EOFException("uncompressed limit reached");
            int result=source.read(bytes,offset,(int)Math.min(length,remaining));
            if (result>0) remaining-=result; return result;
        }
    }
}
