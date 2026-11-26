package com.jsd.aird.data.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.Test;

class DataWorkbookServiceTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void preparedLaboratoryWorkspaceKeepsAllSheetsWithoutReadingStagedValueCopies(boolean legacyDraft) throws Exception {
        var json=new ObjectMapper();var organization=UUID.randomUUID();var id=UUID.randomUUID();var version=UUID.randomUUID();var file=UUID.randomUUID();
        var repository=org.mockito.Mockito.mock(DataRepository.class);
        var templates=org.mockito.Mockito.mock(TemplateDataImportFacade.class);
        var files=org.mockito.Mockito.mock(com.jsd.aird.ops.application.port.FileObjectRepository.class);
        var storage=org.mockito.Mockito.mock(com.jsd.aird.ops.application.port.ObjectStorage.class);
        var job=new DataRepository.Job(id,file,"hash","large.xlsx","XLSX",version,"WAITING_CONFIRM",100,"READY","v1",null,java.time.Instant.EPOCH,java.time.Instant.EPOCH);
        var snapshot=json.readTree("""
                {"sheets":{"s":{"id":"s","custom":{"labTemplate":{"version":1}},"cellData":{"0":{"0":{"f":"=Other!A1"}}}},"other":{"id":"other","name":"Other","cellData":{"0":{"0":{"v":42}}}}}}
                """);
        var instance=json.createObjectNode().put("version",2);
        if(legacyDraft) instance.putObject("model").putArray("fields").addObject().put("id","first-sheet-field");
        var schema=json.createObjectNode();schema.putObject("x-jsd-lab-template");
        var repairedInstance=instance.deepCopy().put("labMultiSheet",true);
        repairedInstance.withObject("/model").withArray("/fields").addObject().put("id","second-sheet-field");
        schema.set("x-jsd-workbook-instance",repairedInstance);
        var definition=new TemplateDataImportFacade.DataTemplateDefinition(version,version,"TEST","测试模板",null,1,"XLSX",schema,json.createArrayNode(),List.of(),9,7,"contract",json.createObjectNode());
        org.mockito.Mockito.when(repository.findJob(organization,id)).thenReturn(java.util.Optional.of(job));
        org.mockito.Mockito.when(repository.findWorkbookDraft(organization,id)).thenReturn(java.util.Optional.of(new DataRepository.WorkbookDraft(snapshot,instance,json.createArrayNode())));
        org.mockito.Mockito.when(templates.getVersion(organization,version)).thenReturn(definition);
        org.mockito.Mockito.when(templates.prepareWorkbookReview(org.mockito.ArgumentMatchers.eq(organization),org.mockito.ArgumentMatchers.eq(version),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new TemplateDataImportFacade.ImportExecution(definition,List.of(),null,json.createArrayNode(),snapshot.deepCopy()));
        org.mockito.Mockito.when(files.find(organization,file)).thenReturn(java.util.Optional.of(org.mockito.Mockito.mock(com.jsd.aird.ops.application.port.FileObjectRepository.FileObject.class)));
        var reader=new DataWorkbookService(repository,files,storage,null,templates,json);
        com.jsd.aird.shared.security.ActorContext.set(new com.jsd.aird.shared.security.Actor(organization,UUID.randomUUID(),"tester"));
        try {
            var result=reader.workspace(id);
            assertThat(result.snapshot().path("sheets").size()).isEqualTo(2);
            assertThat(result.snapshot().at("/sheets/s/cellData/0/0/f").asText()).isEqualTo("=Other!A1");
            assertThat(result.fieldResultsSource()).isEqualTo("SNAPSHOT");
            if(legacyDraft) {
                var restored=com.jsd.aird.shared.excel.WorkbookStructure.instance(result.snapshot());
                assertThat(restored.path("labMultiSheet").asBoolean()).isTrue();
                assertThat(restored.path("model").path("fields")).hasSize(2);
                org.mockito.Mockito.verify(templates).prepareWorkbookReview(org.mockito.ArgumentMatchers.eq(organization),org.mockito.ArgumentMatchers.eq(version),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            }
            assertThat(json.valueToTree(result).has("templateInstance")).isFalse();
            org.mockito.Mockito.verify(repository,org.mockito.Mockito.never()).listRows(organization,id);
            org.mockito.Mockito.verify(repository,org.mockito.Mockito.never()).listMappings(organization,id);
            org.mockito.Mockito.verify(templates,org.mockito.Mockito.never()).describePreparedWorkbook(org.mockito.ArgumentMatchers.eq(organization),org.mockito.ArgumentMatchers.eq(version),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verifyNoInteractions(storage);
        } finally { com.jsd.aird.shared.security.ActorContext.clear(); }
    }

    @Test
    void fieldResultsUseCurrentRevisionAndNeverOpenTheSourceWorkbook() {
        var organization=UUID.randomUUID();var id=UUID.randomUUID();var rowId=UUID.randomUUID();
        var repository=org.mockito.Mockito.mock(DataRepository.class);var templates=org.mockito.Mockito.mock(TemplateDataImportFacade.class);
        var storage=org.mockito.Mockito.mock(com.jsd.aird.ops.application.port.ObjectStorage.class);
        var job=new DataRepository.Job(id,UUID.randomUUID(),"hash","source.xlsx","XLSX",UUID.randomUUID(),"WAITING_CONFIRM",100,"READY","v1",null,java.time.Instant.EPOCH,java.time.Instant.EPOCH);
        var json=new ObjectMapper();var values=json.createObjectNode().put("A","sample");
        var row=new DataRepository.Row(rowId,"s",1,values,values,json.createObjectNode(),"VALID");
        org.mockito.Mockito.when(repository.findJob(organization,id)).thenReturn(java.util.Optional.of(job));
        org.mockito.Mockito.when(repository.listRowsForSheet(organization,id,"s")).thenReturn(List.of(row));
        var service=new DataWorkbookService(repository,null,storage,null,templates,json);
        com.jsd.aird.shared.security.ActorContext.set(new com.jsd.aird.shared.security.Actor(organization,UUID.randomUUID(),"tester"));
        try {
            var result=service.fieldResults(id,job.getRevision(),"s",rowId.toString());
            assertThat(result.fields()).hasSize(1);
            assertThat(result.fields().getFirst().effectiveValue().asText()).isEqualTo("sample");
            org.assertj.core.api.Assertions.assertThatThrownBy(()->service.fieldResults(id,"stale","s",rowId.toString())).isInstanceOf(com.jsd.aird.shared.error.ApiException.class);
            org.mockito.Mockito.verifyNoInteractions(storage);
            org.mockito.Mockito.verify(repository,org.mockito.Mockito.never()).listRows(organization,id);
        } finally { com.jsd.aird.shared.security.ActorContext.clear(); }
    }

    @Test
    void buildsConfirmationOnlyFromAuditedCompletedSource() throws Exception {
        var json=new ObjectMapper();var organization=UUID.randomUUID();var id=UUID.randomUUID();var file=UUID.randomUUID();
        var repository=org.mockito.Mockito.mock(DataRepository.class);
        var files=org.mockito.Mockito.mock(com.jsd.aird.ops.application.port.FileObjectRepository.class);
        var templates=org.mockito.Mockito.mock(TemplateDataImportFacade.class);
        var now=java.time.Instant.now();
        var job=new DataRepository.Job(id,file,"sha","source.xlsx","XLSX",UUID.randomUUID(),null,"COMPLETED",100,"COMPLETED",null,null,now,now);
        var snapshot=json.readTree("{\"sheets\":{\"s\":{\"cellData\":{\"0\":{\"0\":{\"v\":\"reviewed\"}}}}}}");
        org.mockito.Mockito.when(repository.findJob(organization,id)).thenReturn(java.util.Optional.of(job));
        org.mockito.Mockito.when(repository.findWorkbookDraft(organization,id)).thenReturn(java.util.Optional.of(new DataRepository.WorkbookDraft(snapshot,json.createObjectNode(),json.createArrayNode())));
        org.mockito.Mockito.when(files.find(organization,file)).thenReturn(java.util.Optional.of(org.mockito.Mockito.mock(com.jsd.aird.ops.application.port.FileObjectRepository.FileObject.class)));
        var reader=new DataWorkbookService(repository,files,null,null,templates,json);
        assertThat(reader.sourceSnapshotForExperiment(organization,id).path("importConfirmation").isMissingNode()).isTrue();
        var proof=json.createObjectNode().put("importJobId",id.toString()).put("revision","audit-id").put("confirmedBy",UUID.randomUUID().toString()).put("confirmedAt",now.toString());
        org.mockito.Mockito.when(repository.importConfirmation(organization,id)).thenReturn(java.util.Optional.of(proof));
        var result=reader.sourceSnapshotForExperiment(organization,id);
        assertThat(result.at("/importConfirmation/dataHash").asText()).isEqualTo(new com.jsd.aird.shared.json.JsonCanonicalizer(json).hash(
                com.jsd.aird.shared.excel.WorkbookReviewContent.workbook(json,snapshot)));
        assertThat(snapshot.has("importConfirmation")).isFalse();
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataWorkbookService service = new DataWorkbookService(
            null, null, null, null, null, objectMapper);

    @Test
    void rendersStagedPlainValuesWithMappedBusinessNamesAndSourceAddresses() {
        var sourceKey = "b_058394f7605b53cd9ffa84c021d82";
        var bindingId = UUID.randomUUID().toString();
        var detail = objectMapper.createObjectNode()
                .put("bindingId", bindingId)
                .put("dataPath", "/material/productName")
                .put("labelPath", "AUTO.TABLE.COLUMN_8EB421AE78")
                .put("ragEligible", true);
        var mapping = new DataRepository.Mapping(UUID.randomUUID(), "sheet-1", sourceKey, "产品名称",
                "MATERIAL.PRODUCT_NAME", "产品名称", "MAP", "TEXT", null, null,
                detail, "MATCHED");

        var raw = objectMapper.createObjectNode().put(sourceKey, "测试树脂 A");
        var source = objectMapper.createObjectNode();
        source.putObject("cells").putObject(sourceKey)
                .put("sheetId", "sheet-1").put("sheetName", "原料数据")
                .put("bindingId", bindingId).put("valuePath", "/material/productName")
                .put("labelPath", "AUTO.TABLE.COLUMN_8EB421AE78")
                .put("cellAddress", "B2");
        var row = new DataRepository.Row(UUID.randomUUID(), "sheet-1", 2, raw, raw.deepCopy(),
                raw.deepCopy(), "STAGED", source);
        var sheet = new DataRepository.Sheet(UUID.randomUUID(), "sheet-1", "原料数据", 0, true,
                List.of(1), 2, 3, objectMapper.createObjectNode(), "PENDING");

        var fields = service.importFields(List.of(row), List.of(mapping), List.of(sheet));

        assertThat(fields).hasSize(1);
        var field = fields.getFirst();
        assertThat(field.fieldName()).isEqualTo("产品名称");
        assertThat(field.labelPath()).isEmpty();
        assertThat(field.fieldCode()).isEqualTo("MATERIAL.PRODUCT_NAME");
        assertThat(field.bindingId()).isEqualTo(bindingId);
        assertThat(field.valuePath()).isEqualTo("/material/productName");
        assertThat(field.address()).isEqualTo("B2");
        assertThat(field.rawValue().asText()).isEqualTo("测试树脂 A");
        assertThat(field.normalizedValue().asText()).isEqualTo("测试树脂 A");
        assertThat(field.effectiveValue().asText()).isEqualTo("测试树脂 A");
        // Plain staged values can be previewed, but corrections require validated binding metadata.
        assertThat(field.editable()).isFalse();
    }

    @Test
    void compilesUniqueTemplateDefinitionsIncludingEmptyFieldsAndKeepsComponentsSeparate() {
        var fields = List.of(
                new TemplateDataImportFacade.FieldDefinition("MATERIAL.NAME", "产品名称", "TEXT", null,
                        true, true, List.of(), "/material/name"),
                new TemplateDataImportFacade.FieldDefinition("MATERIAL.BATCH", "批号", "TEXT", null,
                        false, false, List.of(), "/material/batch")
        );
        var bindings = List.of(
                binding("region-a", "binding-name-a", "MATERIAL.NAME", "基本信息 > 产品名称", "B2:B200"),
                binding("region-a", "binding-batch", "MATERIAL.BATCH", "基本信息 > 批号", "C2:C200"),
                binding("region-b", "binding-name-b", "MATERIAL.NAME", "复检信息 > 产品名称", "E2:E200")
        );
        var definition = new TemplateDataImportFacade.DataTemplateDefinition(
                UUID.randomUUID(), UUID.randomUUID(), "TPL-1", "原料模板", null, 1, "XLSX",
                objectMapper.createObjectNode(), objectMapper.createArrayNode(), fields,
                7, 7, "hash", objectMapper.createObjectNode());

        var result = service.fieldDefinitions(definition, bindings, List.of());

        assertThat(result).hasSize(3);
        assertThat(result).filteredOn(item -> "产品名称".equals(item.displayName())).hasSize(2);
        assertThat(result).anySatisfy(item -> {
            assertThat(item.bindingId()).isEqualTo("binding-batch");
            assertThat(item.displayName()).isEqualTo("批号");
        });
        assertThat(result).extracting(DataWorkbookService.FieldDefinitionView::componentId)
                .containsExactly("region-a", "region-a", "region-b");
    }

    @Test
    void hidesTableContractCodesFromCustomerFieldGroups() {
        var fields = List.of(new TemplateDataImportFacade.FieldDefinition(
                "TABLE.COLUMN_1234", "原料名称", "TEXT", null,
                false, false, List.of(), "/material/name"));
        var definition = new TemplateDataImportFacade.DataTemplateDefinition(
                UUID.randomUUID(), UUID.randomUUID(), "TPL-2", "原料模板", null, 1, "XLSX",
                objectMapper.createObjectNode(), objectMapper.createArrayNode(), fields,
                7, 7, "hash", objectMapper.createObjectNode());
        var binding = binding("ingredient-region", "binding-name", "TABLE.COLUMN_1234",
                "TABLE.COLUMN_1234", "B2:B20");

        var result = service.fieldDefinitions(definition, List.of(binding), List.of());

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.displayName()).isEqualTo("原料名称");
            assertThat(item.labelPath()).isEmpty();
            assertThat(item.groupPath()).isEmpty();
        });
    }

    @Test
    void usesBusinessRegionNamesAndMergesScalarBindingsIntoBasicInformation() {
        var contract = objectMapper.createObjectNode();
        var components = contract.putArray("components");
        components.add(namedComponent("基本信息", "basic-info", "FORM_REGION", "binding-name", "binding-code"));
        components.add(component("formula-region", "REPEAT_REGION", "formula-region", "binding-material", "binding-ratio"));

        var mappings = objectMapper.createArrayNode();
        mappings.add(objectMapper.createObjectNode()
                .put("bindingId", "formula-region")
                .put("mappingKind", "REPEAT_REGION")
                .set("diagnostic", objectMapper.createObjectNode().put("groupName", "配方明细")));
        var definition = new TemplateDataImportFacade.DataTemplateDefinition(
                UUID.randomUUID(), UUID.randomUUID(), "TPL-3", "生产配方模板", null, 1, "XLSX",
                objectMapper.createObjectNode(), mappings, List.of(
                        new TemplateDataImportFacade.FieldDefinition("PRODUCT.NAME", "产品名称", "TEXT", null,
                                true, true, List.of(), "/product/name"),
                        new TemplateDataImportFacade.FieldDefinition("PRODUCT.CODE", "产品编码", "TEXT", null,
                                false, false, List.of(), "/product/code"),
                        new TemplateDataImportFacade.FieldDefinition("FORMULA.MATERIAL", "原料编号", "TEXT", null,
                                true, false, List.of(), "/formula/material"),
                        new TemplateDataImportFacade.FieldDefinition("FORMULA.RATIO", "配方比例", "NUMBER", "kg",
                                true, false, List.of(), "/formula/ratio")),
                7, 7, "hash", contract);
        var bindings = List.of(
                scalarBinding("basic-info", "binding-name", "PRODUCT.NAME"),
                scalarBinding("basic-info", "binding-code", "PRODUCT.CODE"),
                binding("formula-region", "binding-material", "FORMULA.MATERIAL", "配方明细 > 原料编号", "B7:B21"),
                binding("formula-region", "binding-ratio", "FORMULA.RATIO", "配方明细 > 配方比例", "C7:C21")
        );
        var definitions = service.fieldDefinitions(definition, bindings, List.of());
        var sheet = new DataRepository.Sheet(UUID.randomUUID(), "sheet-1", "生产（配方）任务单", 0,
                true, List.of(1), 2, 3, objectMapper.createObjectNode(), "CONFIRMED");

        var regions = service.workbookRegions(definition, List.of(sheet), definitions, List.of());

        assertThat(regions).extracting(DataWorkbookService.WorkbookRegion::name)
                .containsExactly("基本信息", "配方明细");
        assertThat(regions.getFirst().fieldCount()).isEqualTo(2);
    }

    @Test
    void prefersPublishedContractBusinessNameOverPlaceholderSchemaFieldName() {
        var contract = objectMapper.createObjectNode();
        contract.putArray("components").add(namedComponent(
                "测试数据", "table-root", "COLUMN_TABLE", "field-result"));
        var schema = objectMapper.createObjectNode();
        schema.putObject("x-jsd-field-model").putArray("fields")
                .add(objectMapper.createObjectNode().put("fieldId", "root-field")
                        .put("fieldCode", "AUTO.REPEAT.REGION").put("name", "重复记录区域"));
        var mappings = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("bindingId", "table-root").put("fieldId", "root-field")
                .put("fieldCode", "AUTO.REPEAT.REGION").put("mappingKind", "COLUMN_TABLE"));
        var definition = new TemplateDataImportFacade.DataTemplateDefinition(
                UUID.randomUUID(), UUID.randomUUID(), "TPL-6", "测试报告", null, 1, "XLSX",
                schema, mappings, List.of(new TemplateDataImportFacade.FieldDefinition(
                        "RESULT", "测试结果", "TEXT", null, false, false, List.of(), "/result")),
                7, 7, "hash", contract);
        var definitions = service.fieldDefinitions(definition,
                List.of(binding("table-root", "field-result", "RESULT", "测试结果", "E5:N20")), List.of());
        var sheet = new DataRepository.Sheet(UUID.randomUUID(), "sheet-1", "测试报告", 0,
                true, List.of(1), 2, 3, objectMapper.createObjectNode(), "CONFIRMED");

        assertThat(service.workbookRegions(definition, List.of(sheet), definitions, List.of()))
                .singleElement().satisfies(region -> assertThat(region.name()).isEqualTo("测试数据"));
    }

    @Test
    void namesSignatureOnlyFormRegionForCustomers() {
        var contract = objectMapper.createObjectNode();
        contract.putArray("components").add(namedComponent(
                "基本信息", "signature-region", "FORM_REGION", "signature-binding"));
        var definition = new TemplateDataImportFacade.DataTemplateDefinition(
                UUID.randomUUID(), UUID.randomUUID(), "TPL-S", "不良联络单", null, 1, "XLSX",
                objectMapper.createObjectNode(), objectMapper.createArrayNode(), List.of(
                        new TemplateDataImportFacade.FieldDefinition("SIGNATURE", "签名/日期", "TEXT", null,
                                false, false, List.of(), "/signature")),
                7, 7, "hash", contract);
        var definitions = service.fieldDefinitions(definition,
                List.of(binding("signature-region", "signature-binding", "SIGNATURE", "签名/日期", "B6:F8")),
                List.of());
        var sheet = new DataRepository.Sheet(UUID.randomUUID(), "sheet-1", "不良联络单", 0,
                true, List.of(), 1, 1, objectMapper.createObjectNode(), "CONFIRMED");

        assertThat(service.workbookRegions(definition, List.of(sheet), definitions, List.of()))
                .singleElement().satisfies(region -> assertThat(region.name()).isEqualTo("处理与签字"));
    }

    @Test
    void removesDuplicateCandidateAtSameRangeInFavorOfBindingWithActualValue() {
        var definition = new TemplateDataImportFacade.DataTemplateDefinition(
                UUID.randomUUID(), UUID.randomUUID(), "TPL-D", "不良联络单", null, 1, "XLSX",
                objectMapper.createObjectNode(), objectMapper.createArrayNode(), List.of(
                        new TemplateDataImportFacade.FieldDefinition("DESCRIPTION", "不合格描述（品管部）", "TEXT", null,
                                false, false, List.of(), "/description"),
                        new TemplateDataImportFacade.FieldDefinition("SIGNATURE", "签名/日期", "TEXT", null,
                                false, false, List.of(), "/signature")),
                7, 7, "hash", objectMapper.createObjectNode());
        var business = binding("business-region", "business-binding", "DESCRIPTION", "不合格描述（品管部）", "B6:F8");
        var candidate = binding("signature-region", "signature-binding", "SIGNATURE", "签名/日期", "B6:F8");
        var value = new DataWorkbookService.FieldValue(UUID.randomUUID().toString(), "DESCRIPTION",
                "不合格描述（品管部）", "不合格描述（品管部）", "business-binding", "/description",
                "INPUT", "VALID", "TEXT", "", false, false, true, true,
                "sheet-1", "不良联络单", 6, "B6", null, null, null, null,
                objectMapper.getNodeFactory().textNode("粘度超标"), true, false, null,
                "business-region", "SCALAR", null, null, null, null, null, null);

        assertThat(service.fieldDefinitions(definition, List.of(business, candidate), List.of(value)))
                .extracting(DataWorkbookService.FieldDefinitionView::displayName)
                .containsExactly("不合格描述（品管部）");
    }

    @Test
    void omitsEmptyContractScaffoldingRegions() {
        var contract = objectMapper.createObjectNode();
        contract.putArray("components")
                .add(component("empty-parent", "FORM_REGION", "region-binding"))
                .add(namedComponent("配方明细", "detail-region", "ROW_TABLE", "binding-material"));
        var definition = new TemplateDataImportFacade.DataTemplateDefinition(
                UUID.randomUUID(), UUID.randomUUID(), "TPL-4", "生产配方模板", null, 1, "XLSX",
                objectMapper.createObjectNode(), objectMapper.createArrayNode(), List.of(
                        new TemplateDataImportFacade.FieldDefinition("FORMULA.MATERIAL", "原料编号", "TEXT", null,
                                true, false, List.of(), "/formula/material")),
                7, 7, "hash", contract);
        var definitions = service.fieldDefinitions(definition,
                List.of(binding("detail-region", "binding-material", "FORMULA.MATERIAL",
                        "配方明细 > 原料编号", "B7:B21")), List.of());
        var sheet = new DataRepository.Sheet(UUID.randomUUID(), "sheet-1", "生产（配方）任务单", 0,
                true, List.of(1), 2, 3, objectMapper.createObjectNode(), "CONFIRMED");

        var regions = service.workbookRegions(definition, List.of(sheet), definitions, List.of());

        assertThat(regions).singleElement().satisfies(region -> assertThat(region.name()).isEqualTo("配方明细"));
    }

    @Test
    void omitsDefinitionsThatHaveNoCustomerSourcePosition() {
        var contract = objectMapper.createObjectNode();
        contract.putArray("components").add(namedComponent(
                "未分区字段", "unassigned", "FORM_REGION", "missing-binding"));
        var definition = new TemplateDataImportFacade.DataTemplateDefinition(
                UUID.randomUUID(), UUID.randomUUID(), "TPL-5", "不良联络单", null, 1, "XLSX",
                objectMapper.createObjectNode(), objectMapper.createArrayNode(), List.of(
                        new TemplateDataImportFacade.FieldDefinition("SIGNATURE", "签名/日期", "TEXT", null,
                                false, false, List.of(), "/signature")),
                7, 7, "hash", contract);
        var locator = objectMapper.createObjectNode().put("componentId", "unassigned")
                .put("sheetId", "sheet-1");
        var binding = new TemplateDataImportFacade.ImportBinding("missing-binding", "SIGNATURE",
                "/signature", "SCALAR", "", "", 1, 1, 1,
                JsonNodeFactory.instance.objectNode(), locator, false, false, true,
                "INPUT", "TEXT", "", "签名/日期", "CONTEXT", false);
        var definitions = service.fieldDefinitions(definition, List.of(binding), List.of());
        var sheet = new DataRepository.Sheet(UUID.randomUUID(), "sheet-1", "不良联络单", 0,
                true, List.of(1), 2, 3, objectMapper.createObjectNode(), "CONFIRMED");

        assertThat(service.workbookRegions(definition, List.of(sheet), definitions, List.of())).isEmpty();
    }

    private com.fasterxml.jackson.databind.node.ObjectNode component(String id, String type, String... bindingIds) {
        var component = objectMapper.createObjectNode().put("componentId", id)
                .put("sheetId", "sheet-1").put("structureType", type);
        var bindings = component.putArray("bindings");
        for (var bindingId : bindingIds) bindings.add(objectMapper.createObjectNode().put("bindingId", bindingId));
        return component;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode namedComponent(
            String name, String id, String type, String... bindingIds
    ) {
        return component(id, type, bindingIds).put("name", name);
    }

    private TemplateDataImportFacade.ImportBinding scalarBinding(String componentId, String bindingId,
                                                                   String fieldCode) {
        var locator = objectMapper.createObjectNode().put("componentId", componentId)
                .put("sheetId", "sheet-1").put("valueRange", "A1");
        return new TemplateDataImportFacade.ImportBinding(bindingId, fieldCode, "/" + fieldCode,
                "SCALAR", "", "", 1, 1, 1, JsonNodeFactory.instance.objectNode(), locator,
                false, false, true, "INPUT", "TEXT", "", fieldCode, "FEATURE", true);
    }

    private TemplateDataImportFacade.ImportBinding binding(String componentId, String bindingId,
                                                            String fieldCode, String labelPath, String range) {
        var locator = objectMapper.createObjectNode().put("componentId", componentId)
                .put("sheetId", "sheet-1").put("valueRange", range);
        return new TemplateDataImportFacade.ImportBinding(bindingId, fieldCode, "/" + fieldCode,
                "REPEAT_FIELD", componentId + "-root", "ROW", 1, 1, 1,
                JsonNodeFactory.instance.objectNode(), locator, true, false, true,
                "INPUT", "TEXT", "", labelPath, "FEATURE", true);
    }
}
