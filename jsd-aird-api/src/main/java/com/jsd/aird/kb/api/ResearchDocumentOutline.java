package com.jsd.aird.kb.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Source-text navigation hints; detected headings are not a verified document structure. */
public final class ResearchDocumentOutline {
    private ResearchDocumentOutline() {}

    public static ObjectNode outline(ObjectMapper mapper, JsonNode nodes, int offset) {
        var result = mapper.createObjectNode();
        var entries = result.putArray("entries");
        String previousPage = "";
        int end = nodes.size();
        for (int i = offset; i < nodes.size(); i++) {
            var node = nodes.get(i);
            var text = node.path("text").asText().strip();
            var page = node.path("anchor").path("page").asText("");
            boolean pageStart = !page.isBlank() && !page.equals(previousPage);
            previousPage = page;
            boolean heading = text.length() <= 180 && text.matches("(?s)^(#{1,6}\\s+.+|\\d+(?:\\.\\d+)*\\.?\\s+\\p{L}.+|Abstract|References|摘要|参考文献)$");
            if (!pageStart && !heading) continue;
            int next = i + 1;
            while (next < nodes.size() && nodes.get(next).path("anchor").path("page").asText("").equals(page)) next++;
            var entry = entries.addObject().put("node_offset", i).put("end_offset_exclusive", next)
                .put("source_node_key", node.path("key").asText()).put("kind", heading ? "HEADING_CANDIDATE" : "PAGE_START")
                .put("title", heading ? text : "Page " + page);
            entry.set("anchor", node.path("anchor"));
            if (entries.size() == 40) { end = i + 1; break; }
        }
        result.putObject("trace").put("total_nodes", nodes.size()).put("fulltext_coverage", false);
        if (end < nodes.size()) result.put("next_offset", end); else result.putNull("next_offset");
        return result;
    }
}
