package com.jsd.aird.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.application.PrivateResearchSourceService;
import com.jsd.aird.shared.error.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivateNavigationTest {
    @Test void searchScansWholeDocumentAndReturnsReadOffsetsWithoutClaimingCoverage() {
        var mapper = new ObjectMapper();
        var id = UUID.randomUUID();
        var cid = UUID.randomUUID();
        var source = mapper.createObjectNode().put("id", id.toString()).put("conversation_id", cid.toString())
            .put("version_id", UUID.randomUUID().toString()).put("kind", "RUN_UPLOAD").put("name", "study.pdf").put("status", "READY");
        var nodes = source.putObject("parsed").putArray("nodes");
        for (int i=0; i<40; i++) {
            var node = nodes.addObject().put("key", "node-"+i).put("text", i==30 ? "4.4.3. Rheological Analysis" : i==31 ? "Frequency sweep 0.1–100 rad/s" : "Body text");
            node.putObject("anchor").put("page", i/10+1);
        }
        var service = spy(new PrivateResearchSourceService(new org.springframework.jdbc.core.JdbcTemplate(mock(javax.sql.DataSource.class)), mapper, null, null, null));
        doReturn(source).when(service).get(id);
        var args = mapper.createObjectNode().put("document_id", id.toString()).put("conversation_id", cid.toString());
        try {
            var result = service.search(args.deepCopy().put("query", "frequency"));
            assertThat(result.path("candidates").size()).isEqualTo(1);
            assertThat(result.path("candidates").get(0).path("node_offset").asInt()).isEqualTo(31);
            assertThat(result.path("trace").path("fulltext_coverage").asBoolean()).isFalse();
            assertThat(result.path("next_offset").isNull()).isTrue();
            assertThat(service.read(args.deepCopy().put("offset", 31)).path("candidates").get(0).path("text").asText()).contains("rad/s");
            assertThatThrownBy(() -> service.read(args.deepCopy().put("query", "frequency"))).isInstanceOf(ApiException.class);
            assertThat(service.outline(args).path("entries").toString()).contains("Rheological Analysis", "HEADING_CANDIDATE", "PAGE_START");
            assertThatThrownBy(() -> service.search(args.deepCopy().put("conversation_id", UUID.randomUUID().toString()).put("query", "frequency"))).isInstanceOf(ApiException.class);
        } finally { service.close(); }
    }
}
