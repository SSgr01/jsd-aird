package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.tpl.application.RuleBasedRecognitionEngine;
import com.jsd.aird.tpl.application.TemplateFileNormalizationService;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class LegacyDocLibreOfficeGoldenTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DocxStructureParser parser = new DocxStructureParser(objectMapper);
    private final RuleBasedRecognitionEngine engine = new RuleBasedRecognitionEngine(
            objectMapper, new JsonCanonicalizer(objectMapper));

    @Test
    void convertsLegacyDocWithoutChangingRegionsFieldsOrChineseText() throws Exception {
        var executable = Path.of("C:/Program Files/LibreOffice/program/soffice.com");
        Assumptions.assumeTrue(Files.isRegularFile(executable), "本机未安装 LibreOffice，跳过真实 DOC 转换");
        var fixtureRoot = Path.of("..", "docs", "word-import-smoke").toAbsolutePath().normalize();
        var legacy = fixtureRoot.resolve("Word真实测试_按列明细与嵌套表格.doc");
        var reference = fixtureRoot.resolve("Word真实测试_按列明细与嵌套表格_源.docx");
        assertThat(legacy).exists();
        assertThat(reference).exists();

        var normalizer = new TemplateFileNormalizationService(
                mock(FileStorageFacade.class), executable.toString(), Duration.ofSeconds(90));
        var converted = normalizer.normalize(
                legacy.getFileName().toString(), "application/msword", Files.readAllBytes(legacy));
        var convertedParse = parser.parse(new ByteArrayInputStream(converted.normalizedBytes()));
        RecognitionModelClient.RecognitionBatch convertedBatch = engine.recognize(
                TemplateFormat.DOCX, converted.normalizedName(), convertedParse.structureSummary());
        RecognitionModelClient.RecognitionBatch referenceBatch;
        try (var input = Files.newInputStream(reference)) {
            var referenceParse = parser.parse(input);
            referenceBatch = engine.recognize(TemplateFormat.DOCX, reference.getFileName().toString(),
                    referenceParse.structureSummary());
        }

        assertThat(converted.normalizationStatus()).isEqualTo("NORMALIZED");
        assertThat(convertedParse.structureSummary().path("documentIR").path("tables")).hasSize(4);
        assertThat(regionTypes(convertedBatch)).containsExactlyInAnyOrderElementsOf(regionTypes(referenceBatch));
        assertThat(fields(convertedBatch, "SCALAR")).hasSize(8);
        assertThat(fields(convertedBatch, "REPEAT_FIELD")).hasSize(9);
        assertThat(fields(convertedBatch, "REPEAT_FIELD"))
                .extracting(item -> item.payload().path("fieldName").asText())
                .contains("测试树脂样品或配方", "实验编号", "固含", "粘度");
        assertThat(convertedBatch.qualityIssues()).isEmpty();
        assertThat(convertedParse.structureSummary().path("documentIR").path("text").asText())
                .contains("树脂样品综合测评记录", "测试树脂样品或配方", "附注与审批")
                .doesNotContain("??", "\u0007");
    }

    private java.util.List<String> regionTypes(RecognitionModelClient.RecognitionBatch batch) {
        return batch.suggestions().stream()
                .filter(item -> "SCALAR_FIELD".equals(item.suggestionType()))
                .collect(Collectors.toMap(
                        item -> item.payload().path("regionId").asText(),
                        item -> item.payload().path("blockType").asText(),
                        (left, right) -> left))
                .values().stream().sorted().toList();
    }

    private java.util.List<RecognitionModelClient.ModelSuggestion> fields(
            RecognitionModelClient.RecognitionBatch batch, String mappingKind
    ) {
        return batch.suggestions().stream()
                .filter(item -> "SCALAR_FIELD".equals(item.suggestionType()))
                .filter(item -> mappingKind.equals(item.payload().path("mappingKind").asText()))
                .toList();
    }
}
