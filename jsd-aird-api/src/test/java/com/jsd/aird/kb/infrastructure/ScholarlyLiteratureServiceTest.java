package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.kb.api.ScholarlyLiteratureFacade.Unavailable;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScholarlyLiteratureServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAlexClient client = mock(OpenAlexClient.class);
    private final ScholarlyLiteratureService service = new ScholarlyLiteratureService(client, mapper);
    private ObjectNode work() throws Exception {
        return (ObjectNode) mapper.readTree("""
            {"id":"https://openalex.org/W123","doi":"https://doi.org/10.1234/example","title":"Example study",
             "is_retracted":false,"has_content":{"pdf":true},"open_access":{"is_oa":true},
             "content_urls":{"pdf":"https://content.openalex.org/works/W123.pdf"},
             "best_oa_location":{"license":"cc-by"},
             "abstract_inverted_index":{"Second":[1],"First":[0]},
             "referenced_works":["https://openalex.org/W456","https://openalex.org/W789"],
             "authorships":[{"author":{"display_name":"Author"},"institutions":[{"display_name":"Institute"}]}]}
            """);
    }
    @Test void doiLookupIsFreeAndReturnsBibliographyNotEvidence() throws Exception {
        when(client.get(eq("/works/https://doi.org/10.1234/example"), eq(Map.of()), eq("singleton"), any())).thenReturn(work());
        var result = service.lookup(mapper.createObjectNode().put("query", "https://doi.org/10.1234/example"));
        assertThat(result.path("results").get(0).path("abstract").asText()).isEqualTo("First Second");
        assertThat(result.path("results").get(0).path("authors").get(0).path("institutions").get(0).asText()).isEqualTo("Institute");
        assertThat(result.path("metadata_only").asBoolean()).isTrue();
        assertThat(result.path("result").path("message").path("items").get(0).path("DOI").asText()).isEqualTo("10.1234/example");
    }
    @Test void semanticSearchUsesPagePaginationAndStopsAtFifty() throws Exception {
        var raw = mapper.createObjectNode(); raw.putObject("meta").put("count", 50);
        var items = raw.putArray("results"); for (int index = 0; index < 20; index++) items.add(work());
        when(client.get(eq("/works"), anyMap(), eq("semantic"), any())).thenAnswer(call -> {
            Map<String, String> params = call.getArgument(1);
            assertThat(params).containsEntry("page", "2").doesNotContainKey("cursor"); return raw;
        });
        var args = mapper.createObjectNode().put("query", "hydrogel").put("mode", "semantic").put("limit", 20).put("cursor", "semantic:2:20");
        assertThat(service.search(args).path("next_cursor").asText()).isEqualTo("semantic:3:20");
        assertThatThrownBy(() -> service.search(args.deepCopy().put("cursor", "semantic:4:20"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(args.deepCopy().put("limit", 10))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void referencesAndCitationsUseDifferentFiltersAndKeepTheirCursors() throws Exception {
        when(client.get(eq("/works/W123"), anyMap(), eq("singleton"), any())).thenReturn(work());
        var raw = mapper.createObjectNode(); raw.putObject("meta").put("count", 1).put("next_cursor", "next"); raw.putArray("results").add(work());
        when(client.get(eq("/works"), anyMap(), eq("list"), any())).thenReturn(raw);
        var args = mapper.createObjectNode().put("work_id", "W123").put("limit", 1);
        assertThat(service.expand(args).path("next_cursor").asText()).isEqualTo("1");
        verify(client).get(eq("/works"), argThat(params -> "openalex_id:W456".equals(params.get("filter"))), eq("list"), any());
        assertThat(service.expand(args.put("direction", "citations")).path("next_cursor").asText()).isEqualTo("next");
        verify(client).get(eq("/works"), argThat(params -> "cites:W123".equals(params.get("filter"))), eq("list"), any());
    }
    @Test void fullTextRequiresLicenseOfficialContentAndValidPdfAndCarriesAnImmutableHash() throws Exception {
        var record = work();
        when(client.get(eq("/works/W123"), anyMap(), eq("singleton"), any())).thenReturn(record);
        var bytes = "%PDF-1.7\nfixture".getBytes(StandardCharsets.US_ASCII);
        when(client.pdf("W123")).thenReturn(bytes);
        var downloaded = service.download("W123");
        assertThat(downloaded.name()).isEqualTo("Example study.pdf");
        assertThat(downloaded.provenance().path("sha256").asText()).hasSize(64);
        assertThat(downloaded.provenance().path("license").asText()).isEqualTo("cc-by");
        record.put("is_retracted", true);
        assertThatThrownBy(() -> service.download("W123")).isInstanceOfSatisfying(Unavailable.class, e -> assertThat(e.code()).isEqualTo("OPENALEX_WORK_RETRACTED"));
        record.put("is_retracted", false); ((ObjectNode) record.path("best_oa_location")).put("license", "unknown");
        assertThatThrownBy(() -> service.download("W123")).hasMessage("OPENALEX_REUSABLE_LICENSE_NOT_CONFIRMED");
        ((ObjectNode) record.path("best_oa_location")).put("license", "cc-by");
        ((ObjectNode) record.path("content_urls")).put("pdf", "http://localhost/private");
        assertThatThrownBy(() -> service.download("W123")).hasMessage("OPENALEX_FULLTEXT_UNAVAILABLE");
        verify(client, times(1)).pdf("W123");
    }
    @Test void quotaFailuresAndMalformedRecordsNeverBecomeSuccessfulMetadata() throws Exception {
        when(client.get(anyString(), anyMap(), anyString(), any())).thenThrow(new Unavailable("OPENALEX_FREE_BUDGET_EXHAUSTED", 100));
        assertThat(service.search(mapper.createObjectNode().put("query", "polymer")).path("error").asText()).isEqualTo("OPENALEX_FREE_BUDGET_EXHAUSTED");
        doReturn(mapper.createObjectNode()).when(client).get(anyString(), anyMap(), anyString(), any());
        assertThat(service.search(mapper.createObjectNode().put("query", "polymer")).path("error").asText()).isEqualTo("OPENALEX_INVALID_RESPONSE");
    }
}
