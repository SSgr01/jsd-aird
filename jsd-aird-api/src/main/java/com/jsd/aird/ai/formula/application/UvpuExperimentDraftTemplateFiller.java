package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.api.TemplateDataImportFacade.PublishedExperimentTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Fills one UV/PU candidate into the first record column of a published experiment template copy. */
@Component
public class UvpuExperimentDraftTemplateFiller {
    private final ObjectMapper json;
    private final UvpuAnalysisProfile analysisProfile;

    public UvpuExperimentDraftTemplateFiller(ObjectMapper json, UvpuAnalysisProfile analysisProfile) {
        this.json = json;
        this.analysisProfile = analysisProfile;
    }

    public JsonNode fill(PublishedExperimentTemplate template, ObjectNode editModel, JsonNode process,
                         JsonNode context, String ownerName, LocalDate plannedDate, int candidateNo,
                         String instanceId) {
        var snapshot = template.snapshot().deepCopy();
        if (!(snapshot instanceof ObjectNode workbook) || !workbook.path("sheets").isObject()) {
            throw new IllegalArgumentException("已发布实验模板不是可编辑的Excel工作簿快照");
        }
        var contract = template.importContract();
        var bindings = bindings(contract);
        var projection = firstFormulaProjection(contract)
                .orElseThrow(() -> new IllegalArgumentException("实验模板缺少配方矩阵投影"));
        var componentId = projection.path("componentId").asText();
        var sheetId = sheetId(contract, componentId)
                .orElseThrow(() -> new IllegalArgumentException("配方矩阵未关联有效Sheet"));

        putSemantic(workbook, bindings, sheetId, "BASIC", "TITLE", text(editModel, "title"));
        putSemantic(workbook, bindings, sheetId, "BASIC", "PURPOSE", text(editModel, "purpose"));
        putSemantic(workbook, bindings, sheetId, "BASIC", "OWNER", ownerName);
        putSemantic(workbook, bindings, sheetId, "BASIC", "EXPERIMENT_DATE", plannedDate.toString());
        putIdentity(workbook, contract, bindings, componentId, "AI候选-" + String.format(Locale.ROOT, "%02d", candidateNo));

        putByLabel(workbook, bindings, sheetId, "PROCESS", "温度",
                displayNumber(fact(process, context, "temperatureC"), "℃"));
        putByLabel(workbook, bindings, sheetId, "PROCESS", "湿度",
                displayNumber(fact(process, context, "humidityRhPct"), "%RH"));
        putByLabel(workbook, bindings, sheetId, "TEST", "测试用素材",
                substrateText(fact(process, context, "substrate")));
        putByLabel(workbook, bindings, sheetId, "PROCESS", "施工方式", applicationText(
                fact(process, context, "applicationMethod"), fact(process, context, "applicatorSpecUm")));
        putByLabel(workbook, bindings, sheetId, "PROCESS", "固化", curingText(fact(process, context, "curingSource"),
                fact(process, context, "uvaIntensityMwCm2"), fact(process, context, "uvEnergyMjCm2")));
        putByLabel(workbook, bindings, sheetId, "PROCESS", "涂料固含",
                percentageValue(fact(process, context, "coatingSolidsPct")));

        fillFormula(workbook, projection, sheetId, editModel.path("formulaItems"));
        clearTestValues(workbook, bindings, sheetId);
        workbook.put("id", "ai-experiment-draft-" + instanceId);
        workbook.put("name", text(editModel, "title"));
        return workbook;
    }

    private void fillFormula(ObjectNode workbook, JsonNode projection, String sheetId, JsonNode formulaItems) {
        var labels = Range.parse(projection.path("labelRange").asText())
                .orElseThrow(() -> new IllegalArgumentException("配方材料区域无效"));
        var values = Range.parse(projection.path("valueRange").asText())
                .orElseThrow(() -> new IllegalArgumentException("配方数值区域无效"));
        if (labels.rowCount() != values.rowCount()) {
            throw new IllegalArgumentException("配方材料区域与数值区域行数不一致");
        }
        var items = new ArrayList<JsonNode>();
        var byCode = new LinkedHashMap<String, JsonNode>();
        if (formulaItems != null && formulaItems.isArray()) {
            formulaItems.forEach(item -> {
                items.add(item);
                byCode.put(normalize(item.path("materialCode").asText()), item);
            });
        }
        var blankTemplate = true;
        for (var offset = 0; offset < labels.rowCount(); offset++) {
            if (!cellText(workbook, sheetId, labels.startRow() + offset, labels.startColumn()).isBlank()) {
                blankTemplate = false;
                break;
            }
        }
        if (blankTemplate) {
            if (items.size() > labels.rowCount()) {
                throw new IllegalArgumentException("候选配方材料数量超过实验模板配方行数");
            }
            for (var offset = 0; offset < labels.rowCount(); offset++) {
                var item = offset < items.size() ? items.get(offset) : null;
                var materialLabel = item == null ? null : item.path("materialName")
                        .asText(item.path("materialCode").asText(""));
                putCell(workbook, sheetId, labels.startRow() + offset, labels.startColumn(), materialLabel);
                putCell(workbook, sheetId, values.startRow() + offset, values.startColumn(),
                        item == null || !item.path("ratio").isNumber() ? null : item.path("ratio"));
            }
            return;
        }
        for (var offset = 0; offset < labels.rowCount(); offset++) {
            var label = cellText(workbook, sheetId, labels.startRow() + offset, labels.startColumn());
            var material = analysisProfile.material(label, label).orElse(null);
            var code = material == null ? normalize(label) : normalize(material.code());
            var item = byCode.get(code);
            putCell(workbook, sheetId, values.startRow() + offset, values.startColumn(),
                    item == null || !item.path("ratio").isNumber() ? null : item.path("ratio"));
        }
    }

    private void clearTestValues(ObjectNode workbook, List<Binding> bindings, String sheetId) {
        bindings.stream().filter(binding -> sheetId.equals(binding.sheetId()))
                .filter(binding -> "TEST".equals(binding.node().path("experimentField").path("domain").asText()))
                .filter(binding -> "VALUE".equals(binding.node().path("experimentField").path("field").asText()))
                .forEach(binding -> putBinding(workbook, binding, null));
    }

    private void putIdentity(ObjectNode workbook, JsonNode contract, List<Binding> bindings,
                             String componentId, Object value) {
        if (!contract.path("experimentImport").path("identities").isArray()) return;
        for (var identity : contract.path("experimentImport").path("identities")) {
            if (!componentId.equals(identity.path("componentId").asText())) continue;
            var bindingId = identity.path("bindingId").asText();
            bindings.stream().filter(item -> bindingId.equals(item.node().path("bindingId").asText()))
                    .findFirst().ifPresent(binding -> putBinding(workbook, binding, value));
            return;
        }
    }

    private void putSemantic(ObjectNode workbook, List<Binding> bindings, String sheetId,
                             String domain, String field, Object value) {
        bindings.stream().filter(binding -> sheetId.equals(binding.sheetId()))
                .filter(binding -> domain.equals(binding.node().path("experimentField").path("domain").asText()))
                .filter(binding -> field.equals(binding.node().path("experimentField").path("field").asText()))
                .findFirst().ifPresent(binding -> putBinding(workbook, binding, value));
    }

    private void putByLabel(ObjectNode workbook, List<Binding> bindings, String sheetId,
                            String domain, String labelPart, Object value) {
        if (value == null || value.toString().isBlank()) return;
        var normalizedPart = normalize(labelPart);
        bindings.stream().filter(binding -> sheetId.equals(binding.sheetId()))
                .filter(binding -> domain.equals(binding.node().path("experimentField").path("domain").asText()))
                .filter(binding -> normalize(binding.node().path("labelPath").asText(
                        binding.node().path("fieldCode").asText())).contains(normalizedPart))
                .findFirst().ifPresent(binding -> putBinding(workbook, binding, value));
    }

    private void putBinding(ObjectNode workbook, Binding binding, Object value) {
        address(binding.node().path("locator")).flatMap(Range::parse)
                .ifPresent(range -> putCell(workbook, binding.sheetId(), range.startRow(), range.startColumn(), value));
    }

    private List<Binding> bindings(JsonNode contract) {
        var result = new ArrayList<Binding>();
        if (contract == null || !contract.path("components").isArray()) return result;
        for (var component : contract.path("components")) {
            var sheetId = component.path("sheetId").asText();
            var componentId = component.path("componentId").asText();
            for (var binding : component.path("bindings")) {
                var effectiveSheetId = binding.path("locator").path("sheetId").asText(sheetId);
                result.add(new Binding(binding, effectiveSheetId, componentId));
            }
        }
        return List.copyOf(result);
    }

    private Optional<JsonNode> firstFormulaProjection(JsonNode contract) {
        if (contract != null && contract.path("listProjections").isArray()) {
            for (var item : contract.path("listProjections")) {
                if ("FORMULA".equals(item.path("domain").asText())) return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    private Optional<String> sheetId(JsonNode contract, String componentId) {
        if (contract != null && contract.path("components").isArray()) {
            for (var component : contract.path("components")) {
                if (componentId.equals(component.path("componentId").asText())) {
                    return Optional.ofNullable(component.path("sheetId").asText(null));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> address(JsonNode locator) {
        for (var value : List.of(locator.path("value").path("address").asText(""),
                locator.path("value").path("range").asText(""),
                locator.path("logicalInputRange").asText(""), locator.path("valueRange").asText(""),
                locator.path("address").asText(""), locator.path("range").asText(""))) {
            if (!value.isBlank()) return Optional.of(value);
        }
        return Optional.empty();
    }

    private void putCell(ObjectNode workbook, String sheetId, int row, int column, Object value) {
        var sheet = workbook.path("sheets").path(sheetId);
        if (!(sheet instanceof ObjectNode object)) return;
        var rowNode = (ObjectNode) ((ObjectNode) object.withObject("cellData")).withObject(String.valueOf(row - 1));
        var cell = (ObjectNode) rowNode.withObject(String.valueOf(column - 1));
        cell.remove(List.of("f", "si", "p"));
        if (value == null || value instanceof JsonNode node && (node.isNull() || node.isMissingNode())) {
            cell.remove("v");
        } else if (value instanceof JsonNode node) {
            cell.set("v", node.deepCopy());
        } else if (value instanceof BigDecimal decimal) {
            cell.put("v", decimal);
        } else if (value instanceof Number number) {
            cell.put("v", number.doubleValue());
        } else {
            cell.put("v", value.toString());
        }
    }

    private String cellText(ObjectNode workbook, String sheetId, int row, int column) {
        return workbook.path("sheets").path(sheetId).path("cellData")
                .path(String.valueOf(row - 1)).path(String.valueOf(column - 1)).path("v").asText("");
    }

    private JsonNode fact(JsonNode process, JsonNode context, String code) {
        if (process != null && process.path(code).isObject()) return process.path(code);
        if (context != null && context.path(code).isObject()) return context.path(code);
        return json.nullNode();
    }

    private Object percentageValue(JsonNode fact) {
        if (!fact.path("numericValue").isNumber()) return null;
        return fact.path("numericValue").decimalValue().movePointLeft(2);
    }

    private String displayNumber(JsonNode fact, String unit) {
        if (!fact.path("numericValue").isNumber()) return "";
        return number(fact.path("numericValue").decimalValue()) + unit;
    }

    private String substrateText(JsonNode fact) {
        var value = fact.path("textValue").asText("");
        var display = switch (value) {
            case "PET_100UM_OPTICAL" -> "100μm光学级PET膜";
            case "PET" -> "PET膜";
            case "PC" -> "PC膜";
            case "PMMA_PC" -> "PMMA/PC复合板";
            default -> cleanRaw(fact.path("rawValue").asText(""));
        };
        return display.isBlank() ? "" : "测试用素材：" + display;
    }

    private String applicationText(JsonNode method, JsonNode applicator) {
        var methodText = switch (method.path("textValue").asText("")) {
            case "WIRE_BAR_ROLL_COATING" -> "绕丝棒辊涂";
            default -> cleanRaw(method.path("rawValue").asText(""));
        };
        var spec = applicator.path("numericValue").isNumber()
                ? number(applicator.path("numericValue").decimalValue()) + "μm" : "";
        if (methodText.isBlank() && spec.isBlank()) return "";
        return "制膜施工方式：" + spec + methodText;
    }

    private String curingText(JsonNode source, JsonNode intensity, JsonNode energy) {
        var parts = new ArrayList<String>();
        var sourceText = switch (source.path("textValue").asText("")) {
            case "MERCURY_UV" -> "汞灯UV固化";
            default -> cleanRaw(source.path("rawValue").asText(""));
        };
        if (!sourceText.isBlank()) parts.add(sourceText);
        if (intensity.path("numericValue").isNumber()) {
            parts.add("UVA光强：" + number(intensity.path("numericValue").decimalValue()) + "mW/cm²");
        }
        if (energy.path("numericValue").isNumber()) {
            parts.add("UVA能量：" + number(energy.path("numericValue").decimalValue()) + "mJ/cm²");
        }
        return parts.isEmpty() ? "" : "固化条件：" + String.join("，", parts);
    }

    private String cleanRaw(String value) {
        if (value == null) return "";
        return value.replaceFirst("^[^：:]{1,12}[：:]", "")
                .replaceAll("[（(]本页.*?[）)]", "").strip();
    }

    private String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    private String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String normalize(String value) {
        return value == null ? "" : UvpuAnalysisProfile.normalize(value);
    }

    private record Binding(JsonNode node, String sheetId, String componentId) {}

    private record Range(int startRow, int endRow, int startColumn, int endColumn) {
        static Optional<Range> parse(String value) {
            if (value == null) return Optional.empty();
            var match = java.util.regex.Pattern.compile("^([A-Z]+)([0-9]+)(?::([A-Z]+)([0-9]+))?$",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value.replace("$", ""));
            if (!match.matches()) return Optional.empty();
            var startColumn = column(match.group(1));
            var endColumn = column(match.group(3) == null ? match.group(1) : match.group(3));
            var startRow = Integer.parseInt(match.group(2));
            var endRow = Integer.parseInt(match.group(4) == null ? match.group(2) : match.group(4));
            return Optional.of(new Range(startRow, endRow, startColumn, endColumn));
        }

        int rowCount() {
            return endRow - startRow + 1;
        }

        private static int column(String letters) {
            var result = 0;
            for (var item : letters.toUpperCase(Locale.ROOT).toCharArray()) result = result * 26 + item - 'A' + 1;
            return result;
        }
    }
}
