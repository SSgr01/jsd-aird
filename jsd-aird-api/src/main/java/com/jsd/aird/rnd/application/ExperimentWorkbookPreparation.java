package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.excel.WorkbookStructure;
import com.jsd.aird.shared.lab.LabWorkbook;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import java.util.UUID;

/** Creation and editing must project the same published structure. */
final class ExperimentWorkbookPreparation {
    private ExperimentWorkbookPreparation() {}
    static JsonNode prepare(ObjectMapper json, TemplateDataImportFacade templates, UUID organizationId,
                            UUID templateVersionId, JsonNode baseline, JsonNode source, UUID experimentId) {
        var working=LabWorkbook.prepare(json,source,baseline,experimentId.toString());
        if(templateVersionId!=null && templates!=null) {
            var definition=templates.getVersion(organizationId,templateVersionId);
            if(definition!=null) {
                WorkbookStructure.seed(json,working,definition.schema(),definition.mappings(),experimentId.toString());
                working=LabWorkbook.prepare(json,working,baseline,experimentId.toString());
            }
        }
        return working;
    }
}
