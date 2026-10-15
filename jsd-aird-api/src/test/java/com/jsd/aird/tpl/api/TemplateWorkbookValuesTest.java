package com.jsd.aird.tpl.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TemplateWorkbookValuesTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void clearsTemplateExampleValuesButPreservesFormulasAndPublishedSnapshot() throws Exception {
        var original = json.readTree("""
                {"sheets":{"sheet-1":{"name":"测试结果","cellData":{"1":{"0":{"v":"30"},"1":{"v":"=A2*2","f":"=A2*2"}}}}}}
                """);
        var input = binding("viscosity", "A2", "INPUT");
        var formula = binding("hardness", "B2", "FORMULA");

        var draft = TemplateWorkbookValues.blankCopy(original, List.of(input, formula));
        assertFalse(draft.path("sheets").path("sheet-1").path("cellData").path("1").path("0").has("v"));
        assertEquals("=A2*2", draft.path("sheets").path("sheet-1").path("cellData").path("1").path("1").path("v").asText());
        assertEquals("30", original.path("sheets").path("sheet-1").path("cellData").path("1").path("0").path("v").asText());

        TemplateWorkbookValues.writeInput(draft, input, "35");
        assertEquals("35", draft.path("sheets").path("sheet-1").path("cellData").path("1").path("0").path("v").asText());
    }

    @Test
    void preservesReportLabelsAndOnlyClearsEditableCells() throws Exception {
        var original = json.readTree("""
                {"sheets":{"sheet-1":{"name":"应用测试报告","cellData":{
                  "4":{"1":{"v":"施工方式：喷涂"}},
                  "12":{"2":{"v":"粘度"},"3":{"v":"示例值"}}
                }}}}
                """);
        var regionLocator = json.createObjectNode().put("sheetId", "sheet-1").put("address", "B5:D13");
        var inlineLocator = json.createObjectNode().put("sheetId", "sheet-1").put("address", "B5")
                .put("labelRange", "B5").put("valueMode", "INLINE_TEXT")
                .put("valuePart", "AFTER_DELIMITER");
        var viscosityLocator = json.createObjectNode().put("sheetId", "sheet-1").put("address", "C13:D13")
                .put("labelRange", "C13").put("valueRange", "C13:D13");
        var region = new TemplateDataImportFacade.ImportBinding("region", "", "", "REPEAT_REGION",
                null, null, 0, 0, 0, null, regionLocator, false, false, true,
                "INPUT", "OBJECT", "");
        var inline = new TemplateDataImportFacade.ImportBinding("method", "method", "/method", "SCALAR",
                null, null, 0, 0, 0, null, inlineLocator, true, false, true,
                "INPUT", "TEXT", "");
        var viscosity = new TemplateDataImportFacade.ImportBinding("viscosity", "viscosity", "/viscosity",
                "REPEAT_FIELD", null, null, 0, 0, 0, null, viscosityLocator, true, false, true,
                "INPUT", "NUMBER", "");

        var draft = TemplateWorkbookValues.blankCopy(original, List.of(region, inline, viscosity));
        var cells = draft.path("sheets").path("sheet-1").path("cellData");
        assertEquals("施工方式：", cells.path("4").path("1").path("v").asText());
        assertEquals("粘度", cells.path("12").path("2").path("v").asText());
        assertFalse(cells.path("12").path("3").has("v"));

        TemplateWorkbookValues.writeInput(draft, inline, "刷涂");
        TemplateWorkbookValues.writeInput(draft, viscosity, "1250");
        assertEquals("施工方式：刷涂", cells.path("4").path("1").path("v").asText());
        assertEquals("粘度", cells.path("12").path("2").path("v").asText());
        assertEquals("1250", cells.path("12").path("3").path("v").asText());
    }

    private TemplateDataImportFacade.ImportBinding binding(String fieldCode, String address, String source) {
        var locator = json.createObjectNode().put("sheetId", "sheet-1").put("address", address);
        return new TemplateDataImportFacade.ImportBinding(fieldCode, fieldCode, "/" + fieldCode,
                "SCALAR", null, null, 0, 0, 0, null, locator, true, false, true,
                source, "NUMBER", "", null, "FEATURE", true);
    }
}
