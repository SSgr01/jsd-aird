package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.StructuredDocumentCodec;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MineruModernSchemaTest {
    @ParameterizedTest
    @ValueSource(strings={"standard-auto", "standard-ocr", "advanced-auto", "advanced-ocr"})
    void actualNinePagePaperPreservesReferencesAndScientificStructure(String sample) throws Exception {
        var json=new ObjectMapper();
        var path=Path.of(Objects.requireNonNull(getClass().getResource("/mineru/samples/"+sample+".zip")).toURI());
        var parsed=new MineruDocumentAdapter(json).parsePrecise(path,"paper.pdf",UUID.randomUUID());
        assertThat(parsed.metadata().get("pageCount")).isEqualTo(9);
        var refs=parsed.blocks().stream().filter(b -> "ref_text".equals(b.attributes().get("mineruType"))).toList();
        assertThat(refs).hasSize(20);
        assertThat(refs).allSatisfy(b -> assertThat(b.attributes().get("searchable")).isNotEqualTo(false));
        assertThat(refs.getLast().content()).contains("3212");
        assertThat(parsed.blocks().stream().filter(b -> "formula".equals(b.section()))).hasSize(2);
        assertThat(parsed.blocks().stream().filter(b -> "chart".equals(b.section()))).hasSize(7);
        assertThat(parsed.blocks().stream().map(b -> b.attributes().get("tableGroup")).filter(Objects::nonNull).distinct()).hasSize(4);
        assertThat(parsed.blocks()).allSatisfy(b -> {
            assertThat(b.pageNo()).isBetween(1,9);
            assertThat(b.bbox()).allSatisfy(v -> assertThat(v).isBetween(0d,1d));
            assertThat(b.content()).doesNotContain("\u0001");
        });
        assertThat(parsed.blocks().stream().filter(b -> b.section().equals("mineru-table-row"))
                .anyMatch(b -> b.attributes().get("cells").toString().contains("resultEntryPath"))).isTrue();
        if (sample.startsWith("standard")) {
            assertThat(refs.stream().filter(b -> "CONTIGUOUS_NUMBERED_REFERENCE".equals(b.attributes().get("recoveryReason")))).hasSize(9);
        } else {
            assertThat(parsed.blocks().stream().filter(b -> "chart".equals(b.section())))
                    .allSatisfy(b -> {
                        assertThat(b.attributes().get("derivedFromChart")).isEqualTo(true);
                        assertThat(b.attributes().get("extractedChartData")).asString().contains("|");
                        assertThat(b.content()).doesNotContain("|");
                    });
        }
    }
}
