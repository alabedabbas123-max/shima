package com.shima.analyzer.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicitly scoped resource snapshots, not AAPT's merged R table. */
final class ResourceIndex {
    private static final Pattern FILE=Pattern.compile("(?:^|/)res/([^/]+)/([^/]+)$");
    private static final Pattern RESOURCE_NAME=Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern IDENTIFIER=Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern NEW_ID=Pattern.compile("@\\+id/([A-Za-z_][A-Za-z0-9_]*)");
    private static final Set<String> FILE_TYPES=new HashSet<>(Arrays.asList("layout","drawable","mipmap","menu","xml","raw","anim","animator","font","navigation"));
    static final Set<String> SUPPORTED=new HashSet<>(Arrays.asList("id","layout","drawable","mipmap","menu","xml","raw","anim","animator","font","navigation","string","color","dimen","bool","integer","array","plurals","style"));
    private final Map<String,Set<String>> names=new HashMap<>();
    final Map<String,XmlAnalyzer.Parsed> xml=new LinkedHashMap<>();
    final Map<String,ResourceFile> files=new LinkedHashMap<>();
    boolean complete;

    ResourceIndex(WorkspaceSnapshot workspace, AnalysisConfig config, XmlAnalyzer.Stop stop) {
        complete=workspace.areResourcesComplete();
        for (ResourceFile file : workspace.getResources()) {
            files.put(file.getPath(),file);
            Matcher match=FILE.matcher(file.getPath());
            if (!match.find()) { complete=false; continue; }
            String directory=match.group(1), basename=match.group(2);
            int dash=directory.indexOf('-');
            String type=dash<0 ? directory : directory.substring(0,dash);
            if (FILE_TYPES.contains(type)) {
                int dot=basename.indexOf('.'); String name=dot<0 ? basename : basename.substring(0,dot);
                add(type,name);
            }
            if (!basename.toLowerCase(java.util.Locale.US).endsWith(".xml")) continue;
            if (file.getText()==null) { complete=false; continue; }
            SourceFile source=new SourceFile(file.getPath(),Language.XML,file.getText());
            XmlAnalyzer.Parsed parsed=XmlAnalyzer.inspect(source,config,stop);
            xml.put(file.getPath(),parsed);
            if (!parsed.complete()) { complete=false; continue; }
            for (XmlAnalyzer.Tag tag : parsed.tags) {
                for (XmlAnalyzer.Attribute attr : tag.attributes) {
                    if (attr.value.contains("&") && (attr.name.equals("name") || attr.value.startsWith("@+id/"))) complete=false;
                    Matcher id=NEW_ID.matcher(attr.value);
                    if (id.matches()) add("id",id.group(1));
                }
                if (type.equals("values")) {
                    String name=tag.attribute("name"), itemType=tag.name;
                    if (itemType.equals("item")) itemType=tag.attribute("type");
                    if ("string-array".equals(itemType) || "integer-array".equals(itemType)) itemType="array";
                    if ("style".equals(itemType) && name != null) name=name.replace('.','_');
                    if (SUPPORTED.contains(itemType) && !FILE_TYPES.contains(itemType)) add(itemType,name);
                }
            }
        }
    }
    private void add(String type, String name) {
        if (name==null || !(type.equals("id") || type.equals("style") ? IDENTIFIER : RESOURCE_NAME).matcher(name).matches()) return;
        Set<String> group=names.get(type); if (group==null) { group=new HashSet<>(); names.put(type,group); }
        group.add(name);
    }
    boolean contains(String type, String name) {
        Set<String> group=names.get(type); return group!=null && group.contains(name);
    }
    WorkspaceFix createIdFix(String name, String sourceId, String sourceText, WorkspaceSnapshot snapshot) {
        if (!IDENTIFIER.matcher(name).matches()) return null;
        String path=snapshot.getIdsFile();
        if (path.equals(sourceId)) return null;
        for (SourceFile source : snapshot.getSources()) if (path.equals(source.getId())) return null;
        ResourceFile target=files.get(path);
        String original=target==null ? null : target.getText();
        if (target!=null && original==null) return null;
        String replacement; int offset;
        if (original==null) {
            offset=0; replacement="<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <item type=\"id\" name=\""+name+"\" />\n</resources>\n";
        } else {
            XmlAnalyzer.Parsed parsed=xml.get(path);
            if (parsed==null || !parsed.complete() || parsed.tags.isEmpty() || !parsed.tags.get(0).name.equals("resources")) return null;
            Matcher close=Pattern.compile("</resources\\s*>").matcher(original); int start=-1;
            while (close.find()) start=close.start();
            if (start<0) return null;
            String newline=original.contains("\r\n") ? "\r\n" : "\n";
            int line=original.lastIndexOf('\n',start-1)+1;
            String indent=original.substring(line,start);
            if (indent.trim().isEmpty()) { offset=line; replacement=indent+"    <item type=\"id\" name=\""+name+"\" />"+newline; }
            else { offset=start; replacement=newline+"    <item type=\"id\" name=\""+name+"\" />"+newline; }
        }
        LinkedHashMap<String,String> guards=new LinkedHashMap<>();
        guards.put(sourceId,sourceText); guards.put(path,original);
        LinkedHashMap<String,List<TextEdit>> changes=new LinkedHashMap<>();
        changes.put(path,Collections.singletonList(new TextEdit(offset,offset,replacement)));
        return new WorkspaceFix("Create @id/"+name+" in "+path,guards,changes);
    }
}
