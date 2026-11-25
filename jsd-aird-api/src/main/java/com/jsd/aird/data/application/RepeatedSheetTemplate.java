package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Instantiates a single-sheet experimental method independently on each source sheet. */
final class RepeatedSheetTemplate {
    static TemplateDataImportFacade.ImportExecution expand(ObjectMapper json,
            TemplateDataImportFacade.DataTemplateDefinition definition,
            List<TemplateDataImportFacade.ImportBinding> bindings,
            TemplateDataImportFacade.ParsedTabularFile parsed) {
        var contract = definition.importContract();
        if (parsed.sheets().size() < 2 || contract == null || !contract.path("components").isArray()
                || !"EXPERIMENT_DATA".equals(contract.path("templateUsage").asText()))
            return new TemplateDataImportFacade.ImportExecution(definition, bindings, parsed);
        var sourceSheets = new HashSet<String>();
        for (var component : contract.path("components")) sourceSheets.add(component.path("sheetId").asText());
        // A multi-sheet contract already has explicit roles; never repeat those roles blindly.
        if (sourceSheets.size() != 1) return new TemplateDataImportFacade.ImportExecution(definition, bindings, parsed);
        var expanded = (ObjectNode) contract.deepCopy();
        var components = expanded.putArray("components");
        var identities = expanded.withObject("experimentImport").putArray("identities");
        var projections = expanded.putArray("listProjections");
        var executable = new ArrayList<TemplateDataImportFacade.ImportBinding>();
        for (var sheet : parsed.sheets()) {
            for (var original : contract.path("experimentImport").path("identities")) {
                var identity = (ObjectNode) original.deepCopy();
                identity.put("componentId", original.path("componentId").asText() + "@" + sheet.sheetId());
                identity.put("sheetId", sheet.sheetId());
                identities.add(identity);
            }
            for (var original : contract.path("listProjections")) {
                var projection = (ObjectNode) original.deepCopy();
                projection.put("componentId", original.path("componentId").asText() + "@" + sheet.sheetId());
                projection.put("sheetId", sheet.sheetId());
                projections.add(projection);
            }
            for (var original : contract.path("components")) {
                var component = (ObjectNode) original.deepCopy();
                var originalId = original.path("componentId").asText();
                var id = originalId + "@" + sheet.sheetId();
                component.put("componentId", id).put("sheetId", sheet.sheetId()).put("sheetScoped", true);
                for (var binding : component.path("bindings")) {
                    var locator = ((ObjectNode) binding).withObject("locator");
                    locator.put("sheetId", sheet.sheetId()).put("sheetName", sheet.sheetName()).put("componentId", id);
                    scopeFormContext(locator, sheet.sheetId());
                }
                components.add(component);
                for (var b : bindings) {
                    var owner = b.locator().path("componentId").asText(
                            b.parentBindingId() == null || b.parentBindingId().isBlank() ? b.bindingId() : b.parentBindingId());
                    if (!originalId.equals(owner)) continue;
                    var locator = (ObjectNode) b.locator().deepCopy();
                    locator.put("sheetId", sheet.sheetId()).put("sheetName", sheet.sheetName()).put("componentId", id);
                    scopeFormContext(locator, sheet.sheetId());
                    executable.add(new TemplateDataImportFacade.ImportBinding(b.bindingId(), b.fieldCode(), b.dataPath(),
                            b.mappingKind(), b.parentBindingId(), b.repeatAxis(), b.recordHeight(), b.recordWidth(),
                            b.recordStride(), b.terminationRule(), locator, b.required(), b.identity(), b.trainingEligible(),
                            b.valueSource(), b.valueType(), b.unit(), b.labelPath(), b.labelPathSegments(),
                            b.trainingRole(), b.ragEligible(), b.experimentField(), b.targetPath()));
                }
            }
        }
        var d = definition;
        var result = new TemplateDataImportFacade.DataTemplateDefinition(d.templateId(), d.versionId(), d.templateCode(),
                d.name(), d.category(), d.versionNo(), d.format(), d.schema(), d.mappings(), d.fields(),
                d.importContractVersion(), d.layoutStructureVersion(), d.contractHash(), expanded);
        return new TemplateDataImportFacade.ImportExecution(result, List.copyOf(executable), parsed);
    }

    private static void scopeFormContext(ObjectNode locator, String sheetId) {
        var context = locator.path("formContextComponentId").asText();
        if (!context.isBlank()) locator.put("formContextComponentId", context + "@" + sheetId);
    }
}
