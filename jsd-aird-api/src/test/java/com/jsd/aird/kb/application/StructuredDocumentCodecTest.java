package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.StructuredDocumentCodec;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.infrastructure.QwenDocumentParsingConverter;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;

class StructuredDocumentCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StructuredDocumentCodec codec = new StructuredDocumentCodec(objectMapper);

    @Test
    void buildsSemanticListsAndProjectsOnlyIncludedReviewNodes() {
        var initial = codec.initialize(List.of(
                block("heading-1", "检测报告", Map.of("level", 1)),
                block("list-item", "批号 LOT-1", Map.of("ordered", false)),
                block("list-item", "黏度 120", Map.of("ordered", false)),
                block("paragraph", "人工确认内容", Map.of())
        ));
        var content = initial.confirmedDocument().path("content");

        assertThat(content.get(0).path("type").asText()).isEqualTo("heading");
        assertThat(content.get(0).path("attrs").path("level").asInt()).isEqualTo(1);
        assertThat(content.get(1).path("type").asText()).isEqualTo("bulletList");
        assertThat(content.get(1).path("content")).hasSize(2);

        var excluded = UUID.fromString(content.get(1).path("content").get(0)
                .path("attrs").path("reviewNodeId").asText());
        var projection = codec.project(initial.confirmedDocument(), List.of(excluded));

        assertThat(projection.confirmedText()).contains("检测报告", "黏度 120", "人工确认内容")
                .doesNotContain("批号 LOT-1");
        assertThat(projection.nodes()).allSatisfy(node -> assertThat(node.sourceNodeKeys()).isNotEmpty());
    }

    @Test
    void allowsUserContentWithoutSourceAndRejectsDuplicateReviewIdentity() {
        var document = objectMapper.createObjectNode().put("type", "doc");
        var content = document.putArray("content");
        var id = UUID.randomUUID().toString();
        for (var text : List.of("A", "B")) {
            var paragraph = content.addObject().put("type", "paragraph");
            paragraph.putObject("attrs").put("reviewNodeId", id).put("origin", "user")
                    .putArray("sourceNodeKeys");
            paragraph.putArray("content").addObject().put("type", "text").put("text", text);
        }

        assertThatThrownBy(() -> codec.validate(document))
                .isInstanceOf(ApiException.class).hasMessageContaining("重复节点标识");
    }

    @Test
    void writesOcrCellSpansAndHeadersIntoTiptapTableNodes() {
        var blocks = new QwenDocumentParsingConverter().convert("""
                \\begin{tabular}{ccccc}
                 & A & B & C & D \\\\
                1 & \\multicolumn{4}{c}{材料基础信息} \\\\
                2 & 物料名称 & \\multicolumn{3}{l}{TEST-TPL-丙烯酸树脂} \\\\
                3 & 状态 & \\multicolumn{3}{l}{合格} \\\\
                \\end{tabular}
                """, 1);

        var table = codec.initialize(blocks).confirmedDocument().path("content").get(0);
        assertThat(table.path("type").asText()).isEqualTo("table");
        assertThat(table.path("content")).hasSize(3);
        var title = table.path("content").get(0).path("content").get(0);
        assertThat(title.path("type").asText()).isEqualTo("tableHeader");
        assertThat(title.path("attrs").path("colspan").asInt()).isEqualTo(4);
        var value = table.path("content").get(1).path("content").get(1);
        assertThat(value.path("type").asText()).isEqualTo("tableCell");
        assertThat(value.path("attrs").path("colspan").asInt()).isEqualTo(3);
    }

    private DocumentParser.TextBlock block(String section, String text, Map<String, Object> attributes) {
        return new DocumentParser.TextBlock(null, section, text, null, null, null, List.of(), null,
                null, null, attributes);
    }
}
