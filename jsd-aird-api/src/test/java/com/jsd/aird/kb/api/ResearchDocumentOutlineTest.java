package com.jsd.aird.kb.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResearchDocumentOutlineTest {
    @Test
    void unnumberedScientificHeadingsLocateTheBodyWithoutClaimingCoverage() {
        var mapper = new ObjectMapper();
        var nodes = mapper.createArrayNode();
        for (String text : new String[]{"Paper title", "Experimental\u00a0Section", "Samples were prepared at 25 °C.",
            "Results\nand Discussion", "We discuss the results in this paragraph.", "Conclusions"}) {
            var node = nodes.addObject().put("key", "node-" + nodes.size()).put("text", text);
            node.putObject("anchor").put("page", 2);
        }
        var result = ResearchDocumentOutline.outline(mapper, nodes, 0);
        var entries = result.path("entries");
        assertEquals(4, entries.size());
        assertEquals("PAGE_START", entries.get(0).path("kind").asText());
        assertEquals(1, entries.get(1).path("node_offset").asInt());
        assertEquals("Experimental Section", entries.get(1).path("title").asText());
        assertEquals(3, entries.get(2).path("node_offset").asInt());
        assertEquals("Results and Discussion", entries.get(2).path("title").asText());
        assertEquals(5, entries.get(3).path("node_offset").asInt());
        assertFalse(result.path("trace").path("fulltext_coverage").asBoolean());
        assertTrue(result.path("next_offset").isNull());
    }
}
