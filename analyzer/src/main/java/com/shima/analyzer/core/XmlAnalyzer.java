package com.shima.analyzer.core;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded, UI-independent XML well-formedness and small layout-value checks. */
public final class XmlAnalyzer {
    private final AnalysisConfig config;
    public XmlAnalyzer() { this(AnalysisConfig.builder().build()); }
    public XmlAnalyzer(AnalysisConfig config) { this.config=Objects.requireNonNull(config, "config"); }
    public AnalysisResult analyze(ResourceFile file) {
        if (file.getText() == null) throw new IllegalArgumentException("XML text is required");
        return inspect(new SourceFile(file.getPath(), Language.XML, file.getText()), config, NEVER_CANCEL).result;
    }
    interface Stop { boolean cancelled(); }
    static final Stop NEVER_CANCEL=new Stop() { public boolean cancelled() { return false; } };
    static final class Attribute {
        final String name, value; final int start, end;
        Attribute(String name, String value, int start, int end) { this.name=name; this.value=value; this.start=start; this.end=end; }
    }
    static final class Tag {
        final String name; final int start, end; final List<Attribute> attributes;
        Tag(String name, int start, int end, List<Attribute> attributes) { this.name=name; this.start=start; this.end=end; this.attributes=attributes; }
        String attribute(String name) { for (Attribute a : attributes) if (a.name.equals(name)) return a.value; return null; }
    }
    static final class Parsed {
        final AnalysisResult result; final List<Tag> tags;
        Parsed(AnalysisResult result, List<Tag> tags) { this.result=result; this.tags=tags; }
        boolean complete() { return result.getStatus() == AnalysisResult.Status.COMPLETE && result.getDiagnostics().isEmpty(); }
    }
    private static final class Abort extends SAXException {
        private static final long serialVersionUID=1L;
        final boolean cancelled;
        Abort(boolean cancelled) { super(cancelled ? "Cancelled" : "XML analysis budget exceeded"); this.cancelled=cancelled; }
    }
    private static final class CountingHandler extends DefaultHandler {
        final long deadline; final int limit; final Stop stop; int events;
        CountingHandler(int timeoutMillis, int limit, Stop stop) {
            deadline=System.nanoTime() + timeoutMillis * 1_000_000L; this.limit=limit; this.stop=stop;
        }
        void check() throws Abort {
            if (stop.cancelled()) throw new Abort(true);
            if (++events > limit || System.nanoTime() >= deadline) throw new Abort(false);
        }
        @Override public void startElement(String uri, String local, String qName, Attributes attributes) throws SAXException { check(); }
        @Override public void endElement(String uri, String local, String qName) throws SAXException { check(); }
        @Override public void characters(char[] ch, int start, int length) throws SAXException { check(); }
        @Override public InputSource resolveEntity(String publicId, String systemId) throws SAXException { throw new SAXException("External entities are forbidden"); }
        @Override public void error(SAXParseException exception) throws SAXException { throw exception; }
        @Override public void fatalError(SAXParseException exception) throws SAXException { throw exception; }
    }
    static Parsed inspect(SourceFile source, AnalysisConfig config, Stop stop) {
        String text=source.getText();
        List<Diagnostic> diagnostics=new ArrayList<>();
        if (text.length() > config.getMaxDocumentChars()) {
            diagnostics.add(diagnostic("DOCUMENT_TOO_LARGE", "XML document exceeds the configured character budget.", Diagnostic.Severity.INFO, text, 0, 0));
            return new Parsed(new AnalysisResult(source, AnalysisResult.Status.PARTIAL, AnalysisResult.ParseMode.NONE, diagnostics), Collections.<Tag>emptyList());
        }
        // Reject all DTDs before invoking the parser, including internal entities.
        // This conservative scan may also reject the literal in an XML comment.
        int doctype=text.toUpperCase(Locale.US).indexOf("<!DOCTYPE");
        if (doctype >= 0) {
            diagnostics.add(diagnostic("XML_DTD_FORBIDDEN", "DTD and entity declarations are not supported.", Diagnostic.Severity.ERROR, text, doctype, Math.min(text.length(), doctype+9)));
            return new Parsed(new AnalysisResult(source, AnalysisResult.Status.PARTIAL, AnalysisResult.ParseMode.FULL, diagnostics), Collections.<Tag>emptyList());
        }
        CountingHandler handler=new CountingHandler(config.getTimeoutMillis(), config.getMaxEvents(), stop);
        try {
            if (stop.cancelled()) throw new Abort(true);
            SAXParserFactory factory=SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XMLReader reader=factory.newSAXParser().getXMLReader();
            disableFeature(reader, "http://xml.org/sax/features/external-general-entities");
            disableFeature(reader, "http://xml.org/sax/features/external-parameter-entities");
            disableFeature(reader, "http://apache.org/xml/features/nonvalidating/load-external-dtd");
            reader.setContentHandler(handler); reader.setErrorHandler(handler); reader.setEntityResolver(handler);
            reader.parse(new InputSource(new StringReader(text)));
            if (stop.cancelled()) throw new Abort(true);
        } catch (Abort abort) {
            if (!abort.cancelled) diagnostics.add(diagnostic("XML_ANALYSIS_LIMIT", abort.getMessage(), Diagnostic.Severity.INFO, text, 0, 0));
            return new Parsed(new AnalysisResult(source, abort.cancelled ? AnalysisResult.Status.CANCELLED : AnalysisResult.Status.PARTIAL, AnalysisResult.ParseMode.FULL, diagnostics), Collections.<Tag>emptyList());
        } catch (SAXParseException syntax) {
            int end=offset(text, syntax.getLineNumber(), syntax.getColumnNumber());
            int start=Math.max(0, end == text.length() ? end-1 : end);
            diagnostics.add(diagnostic("XML_SYNTAX_ERROR", syntax.getMessage(), Diagnostic.Severity.ERROR, text, start, Math.min(text.length(), start+1)));
            return new Parsed(new AnalysisResult(source, AnalysisResult.Status.COMPLETE, AnalysisResult.ParseMode.FULL, diagnostics), Collections.<Tag>emptyList());
        } catch (Exception failure) {
            diagnostics.add(diagnostic("XML_PARSER_FAILURE", "XML parser could not complete: " + failure.getMessage(), Diagnostic.Severity.INFO, text, 0, 0));
            return new Parsed(new AnalysisResult(source, AnalysisResult.Status.PARTIAL, AnalysisResult.ParseMode.FULL, diagnostics), Collections.<Tag>emptyList());
        }
        List<Tag> tags=scanTags(text, config.getMaxEvents());
        if (tags == null) {
            diagnostics.add(diagnostic("XML_ANALYSIS_LIMIT", "XML tag budget exceeded.", Diagnostic.Severity.INFO, text, 0, 0));
            return new Parsed(new AnalysisResult(source, AnalysisResult.Status.PARTIAL, AnalysisResult.ParseMode.FULL, diagnostics), Collections.<Tag>emptyList());
        }
        if (isLayout(source.getId())) {
            for (Tag tag : tags) for (Attribute attr : tag.attributes) {
                if ((attr.name.equals("android:layout_width") || attr.name.equals("android:layout_height")) && !validDimension(attr.value)) {
                    if (diagnostics.size() >= config.getMaxDiagnostics())
                        return new Parsed(new AnalysisResult(source, AnalysisResult.Status.PARTIAL, AnalysisResult.ParseMode.FULL, diagnostics), tags);
                    diagnostics.add(diagnostic("XML_INVALID_DIMENSION", "Invalid layout dimension: " + attr.value, Diagnostic.Severity.ERROR, text, attr.start, attr.end));
                }
            }
        }
        return new Parsed(new AnalysisResult(source, AnalysisResult.Status.COMPLETE, AnalysisResult.ParseMode.FULL, diagnostics), tags);
    }
    private static void disableFeature(XMLReader reader, String name) {
        try { reader.setFeature(name, false); } catch (SAXException ignored) { /* DTD rejection and EntityResolver still apply. */ }
    }
    static Diagnostic diagnostic(String code, String message, Diagnostic.Severity severity, String text, int start, int end) {
        return new Diagnostic(code, message == null ? code : message, severity, new LineIndex(text).range(start,end), Collections.<QuickFix>emptyList());
    }
    private static int offset(String text, int line, int column) {
        int pos=0, current=1;
        while (pos<text.length() && current<line) if (text.charAt(pos++)=='\n') current++;
        return Math.max(0, Math.min(text.length(), pos+Math.max(0,column-1)));
    }
    private static boolean isLayout(String path) { return Pattern.compile("(?:^|/)res/layout(?:-[^/]+)?/[^/]+\\.xml$").matcher(path).find(); }
    private static boolean validDimension(String value) {
        if (value.startsWith("@") || value.startsWith("?")) return true;
        if (value.equals("match_parent") || value.equals("fill_parent") || value.equals("wrap_content")) return true;
        return value.matches("-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:dp|dip|sp|px|pt|mm|in)");
    }
    /** Token positions are used only after SAX has accepted the entire document. */
    private static List<Tag> scanTags(String text, int limit) {
        ArrayList<Tag> tags=new ArrayList<>();
        int i=0, n=text.length(), count=0;
        while (i<n) {
            int p=text.indexOf('<', i); if (p<0) break;
            if (text.startsWith("<!--",p)) { i=text.indexOf("-->",p+4)+3; continue; }
            if (text.startsWith("<![CDATA[",p)) { i=text.indexOf("]]>",p+9)+3; continue; }
            if (text.startsWith("<?",p)) { i=text.indexOf("?>",p+2)+2; continue; }
            if (text.startsWith("</",p)) { i=text.indexOf('>',p+2)+1; continue; }
            int cursor=p+1, nameStart=cursor;
            while (cursor<n && !Character.isWhitespace(text.charAt(cursor)) && text.charAt(cursor)!='/' && text.charAt(cursor)!='>') cursor++;
            String name=text.substring(nameStart,cursor);
            ArrayList<Attribute> attrs=new ArrayList<>();
            while (cursor<n) {
                while (cursor<n && Character.isWhitespace(text.charAt(cursor))) cursor++;
                if (cursor>=n || text.charAt(cursor)=='>' || text.charAt(cursor)=='/') break;
                int a=cursor;
                while (cursor<n && !Character.isWhitespace(text.charAt(cursor)) && text.charAt(cursor)!='=') cursor++;
                String key=text.substring(a,cursor);
                while (cursor<n && (Character.isWhitespace(text.charAt(cursor)) || text.charAt(cursor)=='=')) cursor++;
                if (cursor>=n) break;
                char quote=text.charAt(cursor++); int valueStart=cursor;
                while (cursor<n && text.charAt(cursor)!=quote) cursor++;
                int valueEnd=cursor; if (cursor<n) cursor++;
                attrs.add(new Attribute(key,text.substring(valueStart,valueEnd),valueStart,valueEnd));
                if (++count>limit) return null;
            }
            int end=text.indexOf('>',cursor); if (end<0) break;
            tags.add(new Tag(name,p,end+1,attrs));
            if (++count>limit) return null;
            i=end+1;
        }
        return tags;
    }
}
