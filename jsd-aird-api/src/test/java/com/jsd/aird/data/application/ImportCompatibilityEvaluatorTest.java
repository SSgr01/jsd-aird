package com.jsd.aird.data.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.Test;

class ImportCompatibilityEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ImportCompatibilityEvaluator evaluator = new ImportCompatibilityEvaluator(mapper);

    @Test
    void doesNotTreatNinetyPercentAnchorCoverageAsExactConfirmation() {
        var bindings = mapper.createArrayNode();
        for (int i = 1; i <= 10; i++) bindings.addObject().put("labelPath", "字段" + i).put("valueSource", "INPUT");
        var contract = mapper.createObjectNode();
        contract.putArray("components").addObject()
                .put("componentId", "component-a").put("sheetId", "template-sheet")
                .put("range", "A1:J20").put("requiredComponent", true).set("bindings", bindings);
        var rows = new java.util.ArrayList<List<String>>();
        rows.add(java.util.stream.IntStream.rangeClosed(1, 9).mapToObj(i -> "字段" + i).toList());
        for (int i = 1; i < 20; i++) rows.add(List.of("值"));
        var sheet = new TemplateDataImportFacade.ParsedSheet("uploaded-sheet", "已重命名", 0,
                1, 20, 1, 10, List.of(1), 1, 2, rows);

        var result = evaluator.evaluate(contract, List.of(sheet));

        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.status()).isNotEqualTo("EXACT");
    }

    @Test
    void blocksFormulaRoleConflict() {
        var binding = mapper.createObjectNode().put("labelPath", "合计").put("valueSource", "FORMULA");
        binding.putObject("locator").put("valueRange", "C2:C2");
        var contract = mapper.createObjectNode();
        contract.putArray("components").addObject().put("componentId", "formula")
                .put("range", "A1:C2").put("requiredComponent", true)
                .set("bindings", mapper.createArrayNode().add(binding));
        var layout = mapper.createObjectNode();
        layout.putArray("cells").addObject().put("address", "C2").put("displayValue", "30").put("valueSource", "INPUT");
        var sheet = new TemplateDataImportFacade.ParsedSheet("sheet-1", "sheet-1", 0,
                1, 2, 1, 3, List.of(1), 1, 2,
                List.of(List.of("项目", "值", "合计"), List.of("a", "10", "30")), layout, "hash");

        assertThat(evaluator.evaluate(contract, List.of(sheet)).status()).isEqualTo("INCOMPATIBLE");
    }

    @Test
    void ignoresRegionOnlyScaffoldingAndAcceptsInlineLabelValues() {
        var scaffold = mapper.createObjectNode().put("mappingKind", "REPEAT_REGION")
                .put("labelPath", "基本信息区域");
        var inline = mapper.createObjectNode().put("fieldCode", "PACKAGING.MATERIAL")
                .put("mappingKind", "SCALAR").put("labelPath", "包装物料");
        inline.putObject("locator").put("valueMode", "INLINE").put("valueRange", "A2:C2");
        var contract = mapper.createObjectNode();
        contract.putArray("components")
                .addObject().put("componentId", "scaffold").put("range", "A1:C3")
                .set("bindings", mapper.createArrayNode().add(scaffold));
        contract.withArray("components")
                .addObject().put("componentId", "business").put("range", "A1:C3")
                .set("bindings", mapper.createArrayNode().add(inline));
        var sheet = new TemplateDataImportFacade.ParsedSheet("sheet-1", "生产单", 0,
                1, 3, 1, 3, List.of(), 1, 1,
                List.of(List.of("标题"), List.of("包装物料：纸桶"), List.of("")));

        var result = evaluator.evaluate(contract, List.of(sheet));

        assertThat(result.status()).isEqualTo("COMPATIBLE");
        assertThat(result.componentMatches()).hasSize(1);
        assertThat(result.componentMatches().get(0).path("componentId").asText()).isEqualTo("business");
    }
}
