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
    void linksLayoutRowsLeftToRightWithoutStealingTheNextRowLabel() {
        var appearance = source(1, List.of(0.154, 0.608, 0.235, 0.608, 0.235, 0.625, 0.154, 0.625));
        var appearanceValue = source(1, List.of(0.497, 0.608, 0.630, 0.608, 0.630, 0.627, 0.497, 0.627));
        var viscosity = source(1, List.of(0.154, 0.627, 0.342, 0.627, 0.342, 0.644, 0.154, 0.644));
        var viscosityValue = source(1, List.of(0.499, 0.628, 0.593, 0.628, 0.593, 0.644, 0.499, 0.644));
        var solids = source(1, List.of(0.154, 0.646, 0.356, 0.646, 0.356, 0.663, 0.154, 0.663));
        var solidsValue = source(1, List.of(0.499, 0.646, 0.560, 0.646, 0.560, 0.663, 0.499, 0.663));
        var path = List.of("技术指标");
        var nodes = List.of(
                node("外观", "paragraph", path, appearance.sourceNodeKey(), Map.of()),
                node("乳白半透明液体", "paragraph", path, appearanceValue.sourceNodeKey(), Map.of()),
                node("粘度 （25℃/cps）", "paragraph", path, viscosity.sourceNodeKey(), Map.of()),
                node("400-700cps", "paragraph", path, viscosityValue.sourceNodeKey(), Map.of()),
                node("固含 （120℃×1h）", "paragraph", path, solids.sourceNodeKey(), Map.of()),
                node("85 %", "paragraph", path, solidsValue.sourceNodeKey(), Map.of()));

        var children = chunker.chunk("UA-1117", nodes,
                        List.of(appearance, appearanceValue, viscosity, viscosityValue, solids, solidsValue), List.of())
                .stream().filter(value -> value.role().equals("CHILD")).toList();

        assertThat(children).hasSize(1);
        assertThat(children.getFirst().content())
                .contains("外观：乳白半透明液体", "粘度 （25℃/cps）：400-700cps", "固含 （120℃×1h）：85 %")
                .doesNotContain("乳白半透明液体：粘度", "400-700cps：固含");
        assertThat(children.getFirst().relations()).hasSize(3);
    }

    @Test
    void keepsShortMeasurementParagraphsWholeButAggregatesThem() {
        var first = source(2, List.of(0.1, 0.2, 0.8, 0.2, 0.8, 0.3, 0.1, 0.3));
        var second = source(2, List.of(0.1, 0.32, 0.8, 0.32, 0.8, 0.42, 0.1, 0.42));
        var third = source(2, List.of(0.1, 0.44, 0.8, 0.44, 0.8, 0.54, 0.1, 0.54));
        var path = List.of("实验方法");
        var nodes = List.of(
                node("溶液浓度为 2 mg/mL，温度为 25 ℃。", "paragraph", path, first.sourceNodeKey(), Map.of()),
                node("交联液浓度为 1 mol/L，处理时间为 10 s。", "paragraph", path, second.sourceNodeKey(), Map.of()),
                node("样品厚度为 2 mm，照射能量为 800 mJ/cm²。", "paragraph", path, third.sourceNodeKey(), Map.of()));

        var children = chunker.chunk("通用实验文档", nodes, List.of(first, second, third), List.of()).stream()
                .filter(value -> value.role().equals("CHILD")).toList();

        assertThat(children).singleElement().satisfies(child -> {
            assertThat(child.content()).contains("2 mg/mL", "1 mol/L", "800 mJ/cm²");
            assertThat(child.sourceNodeKeys()).containsExactly(first.sourceNodeKey(), second.sourceNodeKey(),
                    third.sourceNodeKey());
        });
    }

    @Test
    void doesNotResurrectFilteredPageFurnitureWhenLinkingFields() {
        var body = source(2, List.of(0.10, 0.20, 0.80, 0.20, 0.80, 0.35, 0.10, 0.35));
        var pageNumber = source(2, List.of(0.10, 0.970, 0.13, 0.970, 0.13, 0.985, 0.10, 0.985));
        var footer = source(2, List.of(0.50, 0.970, 0.75, 0.970, 0.75, 0.985, 0.50, 0.985));
        var path = List.of("Article");
        var nodes = List.of(
                node("The experiment used a stable cross-linking process.", "paragraph", path,
                        body.sourceNodeKey(), Map.of()),
                node("2", "paragraph", path, pageNumber.sourceNodeKey(), Map.of()),
                node("wileyonlinelibrary.com", "paragraph", path, footer.sourceNodeKey(), Map.of()));

        var children = chunker.chunk("Generic paper", nodes, List.of(body, pageNumber, footer), List.of()).stream()
                .filter(value -> value.role().equals("CHILD")).toList();

        assertThat(children).singleElement().satisfies(child -> {
            assertThat(child.content()).contains("stable cross-linking process")
                    .doesNotContain("wileyonlinelibrary.com");
            assertThat(child.sourceNodeKeys()).containsExactly(body.sourceNodeKey());
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

    @Test
    void rejoinsADanglingCrossPageParagraphAcrossHeaderAndImageAndKeepsItStandalone() {
        var tailSource = source(2, List.of(0.50, 0.86, 0.91, 0.86, 0.91, 0.92, 0.50, 0.92));
        var headerSource = source(3, List.of(0.01, 0.10, 0.03, 0.10, 0.03, 0.25, 0.01, 0.25));
        var imageSource = source(3, List.of(0.20, 0.10, 0.80, 0.10, 0.80, 0.54, 0.20, 0.54));
        var continuationSource = source(3, List.of(0.08, 0.62, 0.49, 0.62, 0.49, 0.91, 0.08, 0.91));
        var path = List.of("Article");
        var nodes = List.of(
                node("Slide 1 was prepared using", "paragraph", path, tailSource.sourceNodeKey(), Map.of()),
                node("COMMUNICATION", "paragraph", path, headerSource.sourceNodeKey(), Map.of()),
                node("Figure caption", "image", path, imageSource.sourceNodeKey(), Map.of()),
                node("a solution (1 mol L−1). The sample concentration was 2 mg mL−1 and the process took ≈10 s.",
                        "paragraph", path, continuationSource.sourceNodeKey(), Map.of()));

        var children = chunker.chunk("Generic paper", nodes,
                        List.of(tailSource, headerSource, imageSource, continuationSource), List.of()).stream()
                .filter(value -> value.role().equals("CHILD")).toList();

        var method = children.stream().filter(value -> value.content().contains("prepared using")).findFirst().orElseThrow();
        assertThat(method.content()).contains("prepared using a solution", "1 mol L−1", "2 mg mL−1", "≈10 s")
                .doesNotContain("COMMUNICATION", "Figure caption");
        assertThat(method.sourceNodeKeys()).containsExactly(tailSource.sourceNodeKey(), continuationSource.sourceNodeKey());
        assertThat(method.primaryAnchor().path("page").asInt()).isEqualTo(3);
        assertThat(method.firstPage()).isEqualTo(3);
        assertThat(method.content()).contains("页码：2-3");
        assertThat(children).anySatisfy(value -> assertThat(value.content()).contains("Figure caption"));
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
