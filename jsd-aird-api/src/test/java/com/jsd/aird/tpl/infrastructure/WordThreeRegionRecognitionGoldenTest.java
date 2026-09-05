package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.tpl.application.RuleBasedRecognitionEngine;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.junit.jupiter.api.Test;

class WordThreeRegionRecognitionGoldenTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DocxStructureParser parser = new DocxStructureParser(objectMapper);
    private final RuleBasedRecognitionEngine engine = new RuleBasedRecognitionEngine(
            objectMapper, new JsonCanonicalizer(objectMapper));

    @Test
    void recognizesBasicInformationAndRowDetails() throws Exception {
        var batch = recognize("Word真实测试_基本信息与按行明细.docx");

        assertThat(regionTypes(batch)).containsExactlyInAnyOrder(
                "FORM_REGION", "ROW_TABLE", "FORM_REGION");
        assertThat(fields(batch, "SCALAR")).hasSize(10);
        assertThat(fields(batch, "REPEAT_FIELD")).hasSize(6);
        assertThat(fields(batch, "REPEAT_FIELD"))
                .extracting(suggestion -> suggestion.payload().path("fieldName").asText())
                .contains("序号");
        assertThat(fields(batch, "REPEAT_FIELD"))
                .allSatisfy(suggestion -> assertThat(suggestion.payload().path("suggestionLevel").asText())
                        .isEqualTo("CHILD"));
        assertThat(batch.qualityIssues()).isEmpty();
    }

    @Test
    void recognizesColumnDetailsAndNestedFormWhileIgnoringOuterContainer() throws Exception {
        var batch = recognize("Word真实测试_按列明细与嵌套表格_源.docx");

        assertThat(regionTypes(batch)).containsExactlyInAnyOrder(
                "FORM_REGION", "COLUMN_TABLE", "FORM_REGION");
        assertThat(fields(batch, "SCALAR")).hasSize(8);
        assertThat(fields(batch, "REPEAT_FIELD")).hasSize(9);
        assertThat(fields(batch, "REPEAT_FIELD"))
                .extracting(suggestion -> suggestion.payload().path("fieldName").asText())
                .contains("测试树脂样品或配方", "实验编号");
        assertThat(fields(batch, "REPEAT_FIELD"))
                .allSatisfy(suggestion -> assertThat(suggestion.payload().path("suggestionLevel").asText())
                        .isEqualTo("CHILD"));
        assertThat(batch.qualityIssues()).isEmpty();
    }

    private RecognitionModelClient.RecognitionBatch recognize(String name) throws Exception {
        var path = Path.of("..", "docs", "word-import-smoke", name).toAbsolutePath().normalize();
        assertThat(path).exists();
        try (InputStream input = Files.newInputStream(path)) {
            var parsed = parser.parse(input);
            return engine.recognize(TemplateFormat.DOCX, name, parsed.structureSummary());
        }
    }

    private java.util.List<String> regionTypes(RecognitionModelClient.RecognitionBatch batch) {
        return batch.suggestions().stream()
                .filter(suggestion -> "SCALAR_FIELD".equals(suggestion.suggestionType()))
                .collect(Collectors.toMap(
                        suggestion -> suggestion.payload().path("regionId").asText(),
                        suggestion -> suggestion.payload().path("blockType").asText(),
                        (left, right) -> left))
                .values().stream().toList();
    }

    private java.util.List<RecognitionModelClient.ModelSuggestion> fields(
            RecognitionModelClient.RecognitionBatch batch, String mappingKind
    ) {
        return batch.suggestions().stream()
                .filter(suggestion -> "SCALAR_FIELD".equals(suggestion.suggestionType()))
                .filter(suggestion -> mappingKind.equals(suggestion.payload().path("mappingKind").asText()))
                .toList();
    }
}
