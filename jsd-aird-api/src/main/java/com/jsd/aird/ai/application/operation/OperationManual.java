package com.jsd.aird.ai.application.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.shared.security.Actor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** The packaged handbook is the source of truth; the database holds derived vectors only. */
@Component
public class OperationManual {
    private static final Pattern MARKER = Pattern.compile("(?m)^<!-- section: ([a-z0-9-]+) -->[ \\t]*$");
    private final String version;
    private final Map<String, Page> pages;
    private final List<Section> sections;
    private final List<Chunk> chunks;

    @org.springframework.beans.factory.annotation.Autowired
    public OperationManual(ObjectMapper mapper) throws IOException {
        this(mapper, read("operation-assistant/pages.json"), read("operation-assistant/operation-manual.md"));
    }

    OperationManual(ObjectMapper mapper, String manifestText, String handbook) throws IOException {
        manifestText = manifestText.replace("\r\n", "\n").replace('\r', '\n');
        handbook = handbook.replace("\r\n", "\n").replace('\r', '\n');
        var manifest = mapper.readValue(manifestText, Manifest.class);
        version = manifest.version() + ":" + hash(manifestText + "\n" + handbook);
        var pageMap = new LinkedHashMap<String, Page>();
        for (var page : manifest.pages()) {
            if (page.id() == null || !page.id().matches("[a-z0-9-]+") || page.title() == null
                    || page.permissions() == null || page.resourceType() == null
                    || page.path() == null || !page.path().matches("^/(?!/)[A-Za-z0-9/_-]+(?:\\?[A-Za-z0-9_=.&%-]+)?$")) {
                throw new IllegalArgumentException("操作手册页面配置无效");
            }
            if (pageMap.putIfAbsent(page.id(), page) != null) throw new IllegalArgumentException("重复页面编号: " + page.id());
            if (page.contextPatterns() != null) page.contextPatterns().forEach(Pattern::compile);
        }
        pages = Map.copyOf(pageMap);
        var bodies = new LinkedHashMap<String, String>();
        var matches = MARKER.matcher(handbook);
        String previous = null;
        int start = 0;
        while (matches.find()) {
            if (previous != null) bodies.put(previous, handbook.substring(start, matches.start()).strip());
            previous = matches.group(1);
            if (bodies.containsKey(previous)) throw new IllegalArgumentException("重复手册章节: " + previous);
            start = matches.end();
        }
        if (previous != null) bodies.put(previous, handbook.substring(start).strip());
        var loaded = new ArrayList<Section>();
        var prepared = new ArrayList<Chunk>();
        var seen = new java.util.HashSet<String>();
        for (var section : manifest.sections()) {
            var content = bodies.get(section.id());
            if (!seen.add(section.id()) || content == null || !content.startsWith("## " + section.title())
                    || section.pageIds() == null || section.pageIds().isEmpty()
                    || section.pageIds().stream().anyMatch(id -> !pages.containsKey(id))
                    || section.requiredPermissions() == null || section.keywords() == null || section.questions() == null) {
                throw new IllegalArgumentException("手册章节或页面关联无效: " + section.id());
            }
            var filled = new Section(section.id(), section.title(), section.pageIds(), section.requiredPermissions(),
                    section.keywords(), section.questions(), content);
            loaded.add(filled);
            int ordinal = 0;
            for (var part : split(content)) {
                var embeddingText = section.title() + "\n" + String.join(" ", section.keywords()) + "\n" + part;
                prepared.add(new Chunk(section.id() + "-" + ordinal++, section.id(), section.title(), part,
                        embeddingText, hash(embeddingText)));
            }
        }
        if (!seen.equals(bodies.keySet())) throw new IllegalArgumentException("手册正文存在未登记的章节");
        sections = List.copyOf(loaded);
        chunks = List.copyOf(prepared);
    }

    private static List<String> split(String content) {
        var result = new ArrayList<String>();
        var group = new StringBuilder();
        var paragraphs = content.split("\\n\\s*\\n");
        String context = paragraphs.length > 0 ? paragraphs[0] : "";
        for (var paragraph : paragraphs) {
            if (paragraph.startsWith("前置条件：")) context += "\n\n" + paragraph;
        }
        for (var paragraph : paragraphs) {
            // Never cut a numbered step list or a prerequisite halfway through.
            if (!group.isEmpty() && group.length() + paragraph.length() > 1200) {
                result.add(group.toString().strip());
                group = new StringBuilder(context).append("\n\n");
            }
            group.append(paragraph).append("\n\n");
        }
        if (!group.isEmpty()) result.add(group.toString().strip());
        return result;
    }

    public Access authorized(Actor actor, AuthorizationService authorization) {
        var decisions = new HashMap<String, Boolean>();
        var availablePages = new LinkedHashMap<String, Page>();
        for (var page : pages.values()) {
            if (page.permissions().isEmpty() || page.permissions().stream().anyMatch(code ->
                    allowed(actor, authorization, decisions, code, page.resourceType()))) {
                availablePages.put(page.id(), page);
            }
        }
        var availableSections = sections.stream().filter(section -> {
            var accessible = section.pageIds().stream().filter(availablePages::containsKey).toList();
            if (accessible.isEmpty()) return false;
            var resourceType = pages.get(accessible.getFirst()).resourceType();
            return section.requiredPermissions().stream().allMatch(code ->
                    allowed(actor, authorization, decisions, code, resourceType));
        }).toList();
        return new Access(availableSections, Map.copyOf(availablePages));
    }

    private boolean allowed(Actor actor, AuthorizationService auth, Map<String, Boolean> cache, String code, String type) {
        return cache.computeIfAbsent(type + ":" + code, ignored -> {
            var decision = auth.check(actor, code, type);
            return decision != null && decision.allowed();
        });
    }

    public String version() { return version; }
    public Map<String, Page> pages() { return pages; }
    public List<Section> sections() { return sections; }
    public List<Chunk> chunks() { return chunks; }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String read(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public record Manifest(String version, List<Page> pages, List<Section> sections) { }
    public record Page(String id, String title, String path, List<String> permissions,
                       String resourceType, List<String> contextPatterns) { }
    public record Section(String id, String title, List<String> pageIds, List<String> requiredPermissions,
                          List<String> keywords, List<String> questions, String content) { }
    public record Chunk(String id, String sectionId, String title, String content, String embeddingText, String contentHash) { }
    public record Access(List<Section> sections, Map<String, Page> pages) {
        public Set<String> sectionIds() { return sections.stream().map(Section::id).collect(java.util.stream.Collectors.toSet()); }
    }
}
