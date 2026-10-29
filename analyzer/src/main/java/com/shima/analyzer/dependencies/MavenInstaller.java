package com.shima.analyzer.dependencies;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Bounded synchronous Maven subset. Invoke on a worker. It downloads only;
 * it never executes plugins, loads code, modifies Gradle or changes a classpath.
 */
public final class MavenInstaller {
    public interface Listener { void onProgress(Progress progress); }
    public interface Transport { Download open(URL url) throws IOException; }
    public static final class Download implements Closeable {
        private final InputStream stream; private final long length; private final Closeable close;
        public Download(InputStream stream,long length,Closeable close) { this.stream=stream; this.length=length; this.close=close; }
        public InputStream getStream() { return stream; }
        public long getLength() { return length; }
        @Override public void close() throws IOException { try { stream.close(); } finally { if (close!=null) close.close(); } }
    }
    public static final class Cancellation {
        private final AtomicBoolean cancelled=new AtomicBoolean();
        public void cancel() { cancelled.set(true); }
        public boolean isCancelled() { return cancelled.get(); }
    }
    public static final class Progress {
        private final String artifact; private final long bytes,totalBytes;
        Progress(String artifact,long bytes,long totalBytes) { this.artifact=artifact; this.bytes=bytes; this.totalBytes=totalBytes; }
        public String getArtifact() { return artifact; }
        public long getBytes() { return bytes; }
        /** -1 means the server did not send Content-Length. */
        public long getTotalBytes() { return totalBytes; }
    }
    public static final class Report {
        private final Map<String,File> files; private final List<String> issues; private final boolean cancelled;
        Report(Map<String,File> files,List<String> issues,boolean cancelled) {
            this.files=Collections.unmodifiableMap(new LinkedHashMap<>(files)); this.issues=Collections.unmodifiableList(new ArrayList<>(issues)); this.cancelled=cancelled;
        }
        public Map<String,File> getFiles() { return files; }
        public List<String> getIssues() { return issues; }
        public boolean isCancelled() { return cancelled; }
        public boolean isComplete() { return !cancelled && issues.isEmpty(); }
    }
    public enum CacheState { PRESENT, MISSING, UNKNOWN }
    /** Describes only this module's POM and binary, not its transitive dependencies. */
    public static final class DirectCacheStatus {
        private final CacheState state; private final List<String> issues;
        DirectCacheStatus(CacheState state,List<String> issues) { this.state=state; this.issues=Collections.unmodifiableList(new ArrayList<>(issues)); }
        public CacheState getState() { return state; }
        public List<String> getIssues() { return issues; }
    }
    public static final class Options {
        private boolean requireSha256=true;
        private int maxArtifacts=128, maxDepth=12;
        private long maxPomBytes=1_000_000L, maxArtifactBytes=25_000_000L, maxTotalBytes=200_000_000L;
        /** Disabling this accepts unverified remote content; only use repositories explicitly trusted by the host. */
        public Options requireSha256(boolean value) { requireSha256=value; return this; }
        public Options maxArtifacts(int value) { maxArtifacts=value; return this; }
        public Options maxDepth(int value) { maxDepth=value; return this; }
        public Options maxPomBytes(long value) { maxPomBytes=value; return this; }
        public Options maxArtifactBytes(long value) { maxArtifactBytes=value; return this; }
        public Options maxTotalBytes(long value) { maxTotalBytes=value; return this; }
        private Options copy() {
            Options o=new Options(); o.requireSha256=requireSha256; o.maxArtifacts=maxArtifacts; o.maxDepth=maxDepth;
            o.maxPomBytes=maxPomBytes; o.maxArtifactBytes=maxArtifactBytes; o.maxTotalBytes=maxTotalBytes;
            if (o.maxArtifacts<1 || o.maxArtifacts>5000 || o.maxDepth<0 || o.maxDepth>100 || o.maxPomBytes<1 || o.maxPomBytes>5_000_000L || o.maxArtifactBytes<1 || o.maxArtifactBytes>500_000_000L || o.maxTotalBytes<1 || o.maxTotalBytes>2_000_000_000L)
                throw new IllegalArgumentException("Invalid dependency budgets");
            return o;
        }
    }
    /** HTTPS only, no redirects: the host must explicitly approve each repository. */
    public static final class HttpsTransport implements Transport {
        @Override public Download open(URL url) throws IOException {
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("Only HTTPS is allowed");
            final HttpsURLConnection connection=(HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(15000); connection.setReadTimeout(15000); connection.setInstanceFollowRedirects(false);
            int code=connection.getResponseCode();
            if (code!=200) { connection.disconnect(); throw new IOException("HTTP "+code+" for "+url); }
            return new Download(connection.getInputStream(),connection.getContentLength(),new Closeable() { @Override public void close() { connection.disconnect(); } });
        }
    }
    private static final class IssueList extends ArrayList<String> {
        private static final long serialVersionUID=1L;
        @Override public boolean add(String issue) {
            if (size()<1023) return super.add(issue);
            if (size()==1023) return super.add("ISSUE_LIMIT: additional issues omitted");
            return false;
        }
    }
    private static final class Work { final MavenCoordinate coordinate; final int depth; Work(MavenCoordinate c,int d) { coordinate=c; depth=d; } }
    private static final class Cancelled extends IOException { private static final long serialVersionUID=1L; }
    private final File root; private final List<URL> repositories; private final Map<String,String> hashes; private final Options options; private final Transport transport;

    public MavenInstaller(File cache, List<String> repositories, Map<String,String> sha256) throws IOException {
        this(cache,repositories,sha256,new Options(),new HttpsTransport());
    }
    /** Custom transports are intended for host-controlled integrations and tests. */
    public MavenInstaller(File cache, List<String> repositories, Map<String,String> sha256, Options options, Transport transport) throws IOException {
        if (cache==null || repositories==null || repositories.isEmpty() || sha256==null || options==null || transport==null) throw new IllegalArgumentException("Missing installer configuration");
        if (repositories.size()>32 || sha256.size()>10000) throw new IllegalArgumentException("Installer configuration exceeds limits");
        root=cache.getCanonicalFile();
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create cache: "+root);
        ArrayList<URL> bases=new ArrayList<>();
        for (String value : repositories) {
            URL url=new URL(value.endsWith("/") ? value : value+"/");
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty() || url.getUserInfo()!=null || url.getQuery()!=null || url.getRef()!=null || url.getPath().contains(".."))
                throw new IllegalArgumentException("Repositories must be explicit HTTPS base URLs");
            bases.add(url);
        }
        this.repositories=Collections.unmodifiableList(bases); this.options=options.copy(); this.transport=transport;
        HashMap<String,String> copy=new HashMap<>();
        for (Map.Entry<String,String> item : sha256.entrySet()) {
            if (item.getValue()==null || !item.getValue().matches("(?i)[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256 for "+item.getKey());
            copy.put(item.getKey(),item.getValue().toLowerCase(Locale.US));
        }
        hashes=Collections.unmodifiableMap(copy);
    }
    /** Lock keys are group:artifact:version@pom / @jar / @aar. */
    public synchronized Report install(List<MavenCoordinate> requested, Cancellation cancellation, Listener listener) {
        if (requested==null || cancellation==null) throw new NullPointerException("requested/cancellation");
        LinkedHashMap<String,File> files=new LinkedHashMap<>(); ArrayList<String> issues=new IssueList();
        if (requested.size()>options.maxArtifacts) throw new IllegalArgumentException("Too many requested dependencies");
        ArrayDeque<Work> queue=new ArrayDeque<>();
        for (MavenCoordinate coordinate : requested) { if (coordinate==null) throw new NullPointerException("coordinate"); queue.add(new Work(coordinate,0)); }
        Map<String,String> versions=new HashMap<>(); Set<MavenCoordinate> visited=new HashSet<>(); long[] total={0};
        try {
            while (!queue.isEmpty()) {
                if (cancellation.isCancelled()) throw new Cancelled();
                Work work=queue.removeFirst(); MavenCoordinate c=work.coordinate;
                String previous=versions.get(c.getModule());
                if (previous!=null && !previous.equals(c.getVersion())) { issues.add("VERSION_CONFLICT: "+c.getModule()+" uses "+previous+" and "+c.getVersion()); continue; }
                versions.put(c.getModule(),c.getVersion());
                if (!visited.add(c)) continue;
                if (visited.size()>options.maxArtifacts || work.depth>options.maxDepth) { issues.add("DEPENDENCY_LIMIT: "+c); continue; }
                File pom;
                try { pom=fetch(c,"pom",options.maxPomBytes,cancellation,listener,total); files.put(c.lockKey("pom"),pom); }
                catch (Cancelled cancelled) { throw cancelled; }
                catch (IOException failure) { issues.add("POM_UNAVAILABLE: "+c+": "+failure.getMessage()); continue; }
                Pom data;
                try { data=parsePom(pom,c,issues,options.maxPomBytes,options.maxArtifacts); }
                catch (Exception failure) { issues.add("INVALID_POM: "+c+": "+failure.getMessage()); continue; }
                if (!data.packaging.equals("pom")) {
                    if (!data.packaging.equals("aar") && !data.packaging.equals("jar")) issues.add("UNSUPPORTED_PACKAGING: "+c+"@"+data.packaging);
                    else try { files.put(c.lockKey(data.packaging),fetch(c,data.packaging,options.maxArtifactBytes,cancellation,listener,total)); }
                    catch (Cancelled cancelled) { throw cancelled; }
                    catch (IOException failure) { issues.add("ARTIFACT_UNAVAILABLE: "+c+": "+failure.getMessage()); }
                }
                for (MavenCoordinate dependency : data.dependencies) {
                    if (queue.size()+visited.size()>=options.maxArtifacts) { issues.add("DEPENDENCY_LIMIT: graph exceeds configured limit"); break; }
                    queue.addLast(new Work(dependency,work.depth+1));
                }
            }
            return new Report(files,issues,false);
        } catch (Cancelled cancelled) { return new Report(files,issues,true); }
    }
    /** Offline direct-module check; run on a worker. Never claims the transitive graph is present. */
    public synchronized DirectCacheStatus inspectDirect(MavenCoordinate coordinate) throws IOException {
        ArrayList<String> issues=new IssueList();
        File pom=findCached(coordinate,"pom");
        if (pom==null) return new DirectCacheStatus(CacheState.MISSING,issues);
        Pom data;
        try { data=parsePom(pom,coordinate,issues,options.maxPomBytes,options.maxArtifacts); }
        catch (Exception invalid) { issues.add("INVALID_POM: "+coordinate+": "+invalid.getMessage()); return new DirectCacheStatus(CacheState.UNKNOWN,issues); }
        if (!data.packaging.equals("pom") && !data.packaging.equals("jar") && !data.packaging.equals("aar")) {
            issues.add("UNSUPPORTED_PACKAGING: "+coordinate+"@"+data.packaging);
            return new DirectCacheStatus(CacheState.UNKNOWN,issues);
        }
        if (!data.packaging.equals("pom") && findCached(coordinate,data.packaging)==null) return new DirectCacheStatus(CacheState.MISSING,issues);
        return new DirectCacheStatus(issues.isEmpty()?CacheState.PRESENT:CacheState.UNKNOWN,issues);
    }
    /** Returns a cache entry only when it meets the configured integrity policy. Does not prove transitive completeness. */
    public synchronized File findCached(MavenCoordinate coordinate,String extension) throws IOException {
        String expected=hashes.get(coordinate.lockKey(extension));
        if (expected==null && options.requireSha256) return null;
        File file=new File(root,coordinate.relativePath(extension)).getCanonicalFile();
        if (!file.getPath().startsWith(root.getPath()+File.separator)) throw new IOException("Cache path escapes root");
        long limit=extension.equals("pom") ? options.maxPomBytes : options.maxArtifactBytes;
        if (!file.isFile() || file.length()>limit) return null;
        return expected==null || expected.equals(hash(file,new Cancellation())) ? file : null;
    }
    private File fetch(MavenCoordinate coordinate,String extension,long maxBytes,Cancellation cancel,Listener listener,long[] total) throws IOException {
        String key=coordinate.lockKey(extension), expected=hashes.get(key);
        if (expected==null && options.requireSha256) throw new IOException("No trusted SHA-256 lock for "+key);
        File target=new File(root,coordinate.relativePath(extension)).getCanonicalFile();
        if (!target.getPath().startsWith(root.getPath()+File.separator)) throw new IOException("Cache path escapes root");
        File dir=target.getParentFile(); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create cache directory");
        if (target.isFile() && target.length()<=maxBytes && (expected==null || expected.equals(hash(target,cancel)))) {
            if (listener!=null) listener.onProgress(new Progress(key,target.length(),target.length()));
            return target;
        }
        IOException last=null;
        for (URL base : repositories) {
            if (cancel.isCancelled()) throw new Cancelled();
            File temp=File.createTempFile("shima-", ".part",dir);
            try (Download download=transport.open(new URL(base,coordinate.relativePath(extension))); FileOutputStream output=new FileOutputStream(temp)) {
                if (download.getLength()>maxBytes) throw new IOException("Artifact exceeds byte limit");
                MessageDigest digest=sha(); byte[] buffer=new byte[8192]; long bytes=0;
                int n;
                while ((n=download.getStream().read(buffer))!=-1) {
                    if (cancel.isCancelled()) throw new Cancelled();
                    bytes+=n; total[0]+=n;
                    if (bytes>maxBytes || total[0]>options.maxTotalBytes) throw new IOException("Download budget exceeded");
                    digest.update(buffer,0,n); output.write(buffer,0,n);
                    if (listener!=null) listener.onProgress(new Progress(key,bytes,download.getLength()));
                }
                if (cancel.isCancelled()) throw new Cancelled();
                output.flush();
                if (download.getLength()>=0 && bytes!=download.getLength()) throw new IOException("Incomplete download for "+key);
                if (expected!=null && !expected.equals(hex(digest.digest()))) throw new IOException("SHA-256 mismatch for "+key);
                if (target.exists() && !target.delete()) throw new IOException("Cannot replace cached artifact");
                if (!temp.renameTo(target)) throw new IOException("Cannot publish cached artifact");
                return target;
            } catch (Cancelled cancelled) { throw cancelled; }
            catch (IOException error) { last=error; }
            finally { if (temp.exists()) temp.delete(); }
        }
        throw last==null ? new IOException("No repository") : last;
    }
    private static String hash(File file,Cancellation cancel) throws IOException {
        MessageDigest digest=sha(); byte[] b=new byte[8192];
        try (InputStream input=new FileInputStream(file)) { int n; while ((n=input.read(b))!=-1) { if (cancel.isCancelled()) throw new Cancelled(); digest.update(b,0,n); } }
        return hex(digest.digest());
    }
    private static MessageDigest sha() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private static String hex(byte[] bytes) { StringBuilder out=new StringBuilder(bytes.length*2); for (byte b : bytes) { out.append(Character.forDigit((b>>>4)&15,16)); out.append(Character.forDigit(b&15,16)); } return out.toString(); }
    private static final class Pom { String packaging="jar"; final List<MavenCoordinate> dependencies=new ArrayList<>(); }
    private static Pom parsePom(File file,MavenCoordinate coordinate,List<String> issues,long maxBytes,int maxDependencies) throws Exception {
        byte[] bytes;
        try (InputStream in=new FileInputStream(file); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int n; long count=0;
            while ((n=in.read(buffer))!=-1) { count+=n; if (count>maxBytes) throw new IOException("POM exceeds byte limit"); out.write(buffer,0,n); }
            bytes=out.toByteArray();
        }
        String raw=new String(bytes,"UTF-8");
        if (raw.indexOf('\0')>=0 || raw.toUpperCase(Locale.US).contains("<!DOCTYPE")) throw new IOException("DTD or unsupported POM encoding forbidden");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(false);
        disable(factory,"http://xml.org/sax/features/external-general-entities"); disable(factory,"http://xml.org/sax/features/external-parameter-entities");
        disable(factory,"http://apache.org/xml/features/nonvalidating/load-external-dtd");
        DocumentBuilder builder=factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void error(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
            @Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
        });
        builder.setEntityResolver(new org.xml.sax.EntityResolver() {
            @Override public InputSource resolveEntity(String publicId,String systemId) throws SAXException { throw new SAXException("External entity forbidden"); }
        });
        Element root=builder.parse(new InputSource(new StringReader(raw))).getDocumentElement();
        if (!root.getTagName().equals("project")) throw new IOException("Not a Maven project");
        String group=text(root,"groupId"), artifact=text(root,"artifactId"), version=text(root,"version");
        if (artifact==null || !artifact.equals(coordinate.getArtifact()) || (group!=null && !group.equals(coordinate.getGroup())) || (version!=null && !version.equals(coordinate.getVersion())) ||
                ((group==null || version==null) && child(root,"parent")==null))
            throw new IOException("POM identity is missing, dynamic or mismatched");
        Pom result=new Pom(); String packaging=text(root,"packaging"); if (packaging!=null) result.packaging=packaging;
        if (child(root,"parent")!=null || child(root,"profiles")!=null || child(root,"properties")!=null || child(root,"dependencyManagement")!=null ||
                child(root,"repositories")!=null || child(root,"modules")!=null || child(root,"distributionManagement")!=null)
            issues.add("POM_INCOMPLETE: inheritance, profiles, properties, management, repository declarations, modules or relocation are not resolved for "+coordinate);
        Element dependencies=child(root,"dependencies");
        if (dependencies==null) return result;
        NodeList nodes=dependencies.getChildNodes();
        for (int i=0;i<nodes.getLength();i++) {
            if (!(nodes.item(i) instanceof Element) || !((Element)nodes.item(i)).getTagName().equals("dependency")) continue;
            Element d=(Element)nodes.item(i); String scope=text(d,"scope"), optional=text(d,"optional"), kind=text(d,"type");
            if (optional!=null && !"true".equalsIgnoreCase(optional) && !"false".equalsIgnoreCase(optional)) { issues.add("UNSUPPORTED_DEPENDENCY_METADATA: "+coordinate+" optional="+optional); continue; }
            if ("true".equalsIgnoreCase(optional) || "test".equals(scope) || "provided".equals(scope) || "system".equals(scope)) continue;
            if (scope!=null && !scope.equals("compile") && !scope.equals("runtime")) { issues.add("UNSUPPORTED_SCOPE: "+coordinate+" "+scope); continue; }
            if (child(d,"exclusions")!=null || child(d,"classifier")!=null || (kind!=null && !kind.equals("jar") && !kind.equals("aar"))) {
                issues.add("UNSUPPORTED_DEPENDENCY_METADATA: "+coordinate); continue;
            }
            if (kind!=null) issues.add("EXPLICIT_TYPE_UNVERIFIED: "+coordinate+" uses "+kind+"; child POM packaging is used");
            if (result.dependencies.size()>=maxDependencies) { issues.add("DEPENDENCY_LIMIT: too many dependencies in "+coordinate); break; }
            try { result.dependencies.add(new MavenCoordinate(text(d,"groupId"),text(d,"artifactId"),text(d,"version"))); }
            catch (IllegalArgumentException unsupported) { issues.add("UNRESOLVED_POM_DEPENDENCY: "+coordinate+" "+text(d,"groupId")+":"+text(d,"artifactId")); }
        }
        return result;
    }
    private static void disable(DocumentBuilderFactory factory,String feature) { try { factory.setFeature(feature,false); } catch (Exception ignored) { } }
    private static Element child(Element parent,String name) {
        NodeList list=parent.getChildNodes(); for (int i=0;i<list.getLength();i++) { Node node=list.item(i); if (node instanceof Element && ((Element)node).getTagName().equals(name)) return (Element)node; } return null;
    }
    private static String text(Element parent,String name) { Element e=child(parent,name); if (e==null) return null; String value=e.getTextContent().trim(); return value.isEmpty()?null:value; }
}
