package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in; spends a small part of the free OpenAlex allowance, saves no files. */
@EnabledIfEnvironmentVariable(named="JSD_AIRD_OPENALEX_LIVE_TEST", matches="true")
class OpenAlexLiveTest {
    @Test void realDiscoveryDoiCitationExpansionSemanticPaginationAndReusablePdf() throws Exception {
        var mapper = new ObjectMapper();
        var client = new OpenAlexClient(mapper, System.getenv("JSD_AIRD_OPENALEX_API_KEY"));
        var service = new ScholarlyLiteratureService(client, mapper);
        var lookup = service.lookup(mapper.createObjectNode().put("query", "10.3389/fchem.2018.00449"));
        assertThat(lookup.has("error")).isFalse();
        assertThat(lookup.path("results").get(0).path("work_id").asText()).isEqualTo("W2895247957");
        var query = mapper.createObjectNode().put("query", "self healing hydrogels").put("limit", 2);
        var search = service.search(query);
        assertThat(search.has("error")).isFalse(); assertThat(search.path("results").size()).isEqualTo(2);
        assertThat(search.path("next_cursor").isTextual()).isTrue();
        assertThat(service.search(query.deepCopy().put("cursor", search.path("next_cursor").asText())).has("error")).isFalse();
        for (var direction : new String[]{"references", "citations"}) {
            var page = service.expand(mapper.createObjectNode().put("work_id", "W2895247957").put("direction", direction).put("limit", 2));
            assertThat(page.has("error")).isFalse(); assertThat(page.path("results").size()).isEqualTo(2);
        }
        var semantic = service.search(query.deepCopy().put("mode", "semantic"));
        assertThat(semantic.has("error")).isFalse();
        assertThat(semantic.path("next_cursor").asText()).isEqualTo("semantic:2:2");
        assertThat(service.search(query.deepCopy().put("mode", "semantic").put("cursor", semantic.path("next_cursor").asText())).has("error")).isFalse();
        var pdf = service.download("W2895247957");
        try (var document = Loader.loadPDF(pdf.content())) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            assertThat(new PDFTextStripper().getText(document)).containsIgnoringCase("self-healing");
        }
        assertThat(pdf.provenance().path("sha256").asText()).hasSize(64);
    }
}
