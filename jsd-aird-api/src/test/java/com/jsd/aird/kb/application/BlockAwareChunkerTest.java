package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;

class BlockAwareChunkerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockAwareChunker chunker = new BlockAwareChunker(mapper);

    @Test
    void createsNonSearchableParentsAndChildrenWithAllEvidence() {
        var headingSource = source(1, List.of(0.05, 0.05, 0.40, 0.05, 0.40, 0.10, 0.05, 0.10));
        var textSource = source(1, List.of(0.05, 0.15, 0.80, 0.15, 0.80, 0.25, 0.05, 0.25));
        var heading = node("产品参数", "heading", List.of("产品参数"), headingSource.sourceNodeKey(), Map.of("level", 1));
        var paragraph = node("UA-1117 是高官能度树脂", "paragraph", List.of("产品参数"),
                textSource.sourceNodeKey(), Map.of());

        var drafts = chunker.chunk("UA-1117 TDS", List.of(heading, paragraph),
                List.of(headingSource, textSource), List.of());

        assertThat(drafts).extracting(BlockAwareChunker.ChunkDraft::role).containsExactly("PARENT", "CHILD");
        var parent = drafts.getFirst(); var child = drafts.getLast();
        assertThat(child.parentKey()).isEqualTo(parent.chunkKey());
        assertThat(child.content()).contains("文档：UA-1117 TDS", "章节：产品参数", "页码：1",
                "UA-1117 是高官能度树脂").doesNotContain("\n产品参数\n");
        assertThat(child.sourceNodeKeys()).containsExactly(textSource.sourceNodeKey());
        assertThat(child.anchors()).hasSize(1);
    }

    @Test
    void linksAFieldAndValueOnceWithBothAnchors() {
        var fieldSource = source(1, List.of(0.10, 0.20, 0.30, 0.20, 0.30, 0.25, 0.10, 0.25));
        var valueSource = source(1, List.of(0.32, 0.20, 0.60, 0.20, 0.60, 0.25, 0.32, 0.25));
        var field = node("粘度", "paragraph", List.of("技术指标"), fieldSource.sourceNodeKey(), Map.of());
        var value = node("400-700cps", "paragraph", List.of("技术指标"), valueSource.sourceNodeKey(), Map.of());

        var child = chunker.chunk("UA-1117", List.of(field, value), List.of(fieldSource, valueSource), List.of())
                .stream().filter(valueDraft -> valueDraft.role().equals("CHILD")).findFirst().orElseThrow();

        assertThat(child.content()).containsOnlyOnce("粘度：400-700cps");
        assertThat(child.sourceNodeKeys()).containsExactly(fieldSource.sourceNodeKey(), valueSource.sourceNodeKey());
        assertThat(child.anchors()).hasSize(2);
        assertThat(child.relations()).singleElement().satisfies(relation -> {
            assertThat(relation.path("type").asText()).isEqualTo("FIELD_VALUE");
            assertThat(relation.path("confidence").asDouble()).isGreaterThan(0.9);
        });
    }

    @Test
    void keepsAnOversizedSingleBlockButRejectsTheHardLimit() {
        var source = source(1, List.of());
        var allowed = node("树".repeat(1_300), "codeBlock", List.of(), source.sourceNodeKey(), Map.of());
        assertThat(chunker.chunk("代码", List.of(allowed), List.of(source), List.of()).stream()
                .filter(value -> value.role().equals("CHILD")).findFirst().orElseThrow().modelTokenLength())
                .isGreaterThan(BlockAwareChunker.MAX_TOKENS);

        var rejected = node("树".repeat(8_193), "codeBlock", List.of(), source.sourceNodeKey(), Map.of());
        assertThatThrownBy(() -> chunker.chunk("代码", List.of(rejected), List.of(source), List.of()))
                .isInstanceOf(ApiException.class).hasMessageContaining("8192");
    }

    @Test
    void keepsNormalChildrenWithinTheMaximumAndBoundsOverlap() {
        var source = source(1, List.of());
        var markers = List.of("甲", "乙", "丙", "丁");
        var nodes = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> node(markers.get(index).repeat(650) + "。", "paragraph", List.of("性能"),
                        source.sourceNodeKey(), Map.of()))
                .toList();

        var children = chunker.chunk("材料", nodes, List.of(source), List.of()).stream()
                .filter(value -> value.role().equals("CHILD")).toList();

        assertThat(children).hasSizeGreaterThan(2)
                .allSatisfy(value -> assertThat(value.modelTokenLength()).isLessThanOrEqualTo(1_200));
        assertThat(children.get(1).content()).contains("乙".repeat(650)).doesNotContain("甲".repeat(101));
    }

    private StructuredDocumentCodec.ProjectedNode node(String text, String type, List<String> path,
                                                        UUID sourceKey, Map<String, Object> attributes) {
        return new StructuredDocumentCodec.ProjectedNode(UUID.randomUUID(), List.of(sourceKey), text, type,
                path, attributes);
    }

    private KnowledgeGovernanceRepository.SourceNodeView source(int page, List<Double> polygon) {
        var key = UUID.randomUUID();
        var anchor = mapper.createObjectNode().put("version", 1).put("kind", polygon.isEmpty() ? "page" : "page_region")
                .put("page", page);
        if (!polygon.isEmpty()) anchor.set("polygon", mapper.valueToTree(polygon));
        return new KnowledgeGovernanceRepository.SourceNodeView(key, 0, "paragraph", "", anchor,
                mapper.createObjectNode());
    }
}
