package com.jsd.aird.data.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.Test;

class BindingLabelResolverTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BindingLabelResolver resolver = new BindingLabelResolver(mapper);

    @Test
    void keepsAllSixResultColumnsWhenLabelLocatorIsInsideAMergedTitle() {
        var binding = binding("experimentNo", "实验编号", "B2", "C2:H2", false);
        var rows = List.of(List.of("", "", "", "", "", "", "", ""),
                List.of("实验编号", "", "S1", "S2", "S3", "S4", "S5", "S6"));
        var layout = mapper.createObjectNode();
        layout.putArray("merges").add("A2:B2");
        var sheet = new TemplateDataImportFacade.ParsedSheet("sheet-1", "Sheet1", 0, 1, 2, 1, 8,
                List.of(1), 1, 2, rows, layout, "fixture");

        var resolved = resolver.resolve(List.of(binding), List.of(), List.of(sheet));

        assertThat(resolved.executableBindings()).hasSize(1);
        assertThat(resolved.executableBindings().getFirst().locator().path("valueRange").asText()).isEqualTo("C2:H2");
        assertThat(resolved.executableBindings().getFirst().locator().path("autoRelocated").asBoolean()).isFalse();
        assertThat(resolved.fieldResolutions().get(0).path("status").asText()).isEqualTo("ORIGINAL");
    }

    @Test
    void keepsOriginalLabelAndRelocatesUniqueLabelWithItsValueRange() {
        var viscosity = binding("viscosity", "粘度", "B2", "C2", true);
        var hardness = binding("hardness", "硬度", "B3", "C3", false);
        var sheet = sheet(List.of(List.of("", "", ""), List.of("", "粘度", "10"),
                List.of("", "其他", ""), List.of("", "", ""), List.of("硬度", "20", "")));

        var result = resolver.resolve(List.of(viscosity, hardness), fields(), List.of(sheet));

        assertThat(result.executableBindings()).hasSize(2);
        assertThat(result.executableBindings().get(0).locator().path("valueRange").asText()).isEqualTo("C2");
        assertThat(result.executableBindings().get(1).locator().path("labelRange").asText()).isEqualTo("A5");
        assertThat(result.executableBindings().get(1).locator().path("valueRange").asText()).isEqualTo("B5");
        assertThat(result.fieldResolutions().get(0).path("status").asText()).isEqualTo("ORIGINAL");
        assertThat(result.fieldResolutions().get(1).path("status").asText()).isEqualTo("RELOCATED");
    }

    @Test
    void doesNotReadViscosityWhenOriginalCellNowContainsHardness() {
        var viscosity = binding("viscosity", "粘度", "B2", "C2", true);
        var hardness = binding("hardness", "硬度", "B3", "C3", true);
        var sheet = sheet(List.of(List.of("", "", ""), List.of("", "硬度", "88"),
                List.of("", "粘度", "12")));

        var result = resolver.resolve(List.of(viscosity, hardness), fields(), List.of(sheet));

        assertThat(result.executableBindings()).extracting(item -> item.fieldCode()).containsExactly("viscosity", "hardness");
        assertThat(result.executableBindings().get(0).locator().path("valueRange").asText()).isEqualTo("C3");
        assertThat(result.executableBindings().get(1).locator().path("valueRange").asText()).isEqualTo("C2");
    }

    @Test
    void removesMissingAndAmbiguousBindingsFromExecution() {
        var viscosity = binding("viscosity", "粘度", "B2", "C2", true);
        var hardness = binding("hardness", "硬度", "B3", "C3", false);
        var sheet = sheet(List.of(List.of("粘度", "", "粘度"), List.of("", "其他", "")));

        var result = resolver.resolve(List.of(viscosity, hardness), fields(), List.of(sheet));

        assertThat(result.executableBindings()).isEmpty();
        assertThat(result.fieldResolutions()).extracting(node -> node.path("status").asText())
                .containsExactly("AMBIGUOUS", "MISSING_OPTIONAL");
    }

    @Test
    void matchesFieldAlias() {
        var viscosity = binding("viscosity", "粘度", "B2", "C2", true);
        var sheet = sheet(List.of(List.of("", "", ""), List.of("", "黏度", "10")));

        var result = resolver.resolve(List.of(viscosity), fields(), List.of(sheet));

        assertThat(result.executableBindings()).hasSize(1);
        assertThat(result.fieldResolutions().get(0).path("status").asText()).isEqualTo("ORIGINAL");
    }

    private TemplateDataImportFacade.ImportBinding binding(String code, String name, String label, String value,
                                                            boolean required) {
        var locator = mapper.createObjectNode().put("sheetId", "sheet-1").put("componentId", "measurements")
                .put("labelRange", label).put("valueRange", value);
        return new TemplateDataImportFacade.ImportBinding(code, code, "/" + code, "SCALAR", null, null,
                1, 1, 1, null, locator, required, false, true, "INPUT", "DECIMAL", null,
                name, List.of(name), "FEATURE", true, null, null);
    }

    private List<TemplateDataImportFacade.FieldDefinition> fields() {
        return List.of(new TemplateDataImportFacade.FieldDefinition("viscosity", "粘度", "DECIMAL", null,
                        true, false, List.of("黏度"), "/viscosity"),
                new TemplateDataImportFacade.FieldDefinition("hardness", "硬度", "DECIMAL", null,
                        false, false, List.of(), "/hardness"));
    }

    private TemplateDataImportFacade.ParsedSheet sheet(List<List<String>> rows) {
        return new TemplateDataImportFacade.ParsedSheet("sheet-1", "Sheet1", 0, 1, rows.size(), 1,
                rows.stream().mapToInt(List::size).max().orElse(1), List.of(1), 1, 2, rows);
    }
}
