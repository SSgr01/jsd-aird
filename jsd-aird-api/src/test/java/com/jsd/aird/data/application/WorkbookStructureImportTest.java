package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.excel.WorkbookStructure;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.tpl.application.TemplateDataImportFacadeImpl;
import com.jsd.aird.tpl.application.StandardFieldService;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.infrastructure.GenericTabularStructureParser;
import com.jsd.aird.tpl.infrastructure.XlsxStructureParser;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.ByteArrayInputStream;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class WorkbookStructureImportTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void permitsSharedPlaceholderCodesForDistinctStructuralRegions() throws Exception {
        var snapshot=json.readTree("""
                {"sheets":{"s":{"id":"s","name":"明细","cellData":{}}}}
                """);
        var instance=json.readTree("""
                {"version":2,"model":{"fields":[
                  {"id":"r1","fieldCode":"AUTO.FIELD","displayRole":"REGION"},
                  {"id":"r2","fieldCode":"AUTO.FIELD","displayRole":"REGION"}]},
                 "mapping":[],"records":[],"items":[],"issues":[]}
                """);
        assertThatCode(() -> WorkbookStructure.validate(json,snapshot,instance)).doesNotThrowAnyException();
    }
    @Test void warnsAboutUnmappedContentInsideTwoRowRecords() throws Exception {
        var snapshot=json.readTree("""
                {"sheets":{"s":{"id":"s","name":"明细","cellData":{
                   "0":{"0":{"v":"已绑定"}},"1":{"0":{"v":"未绑定"}},
                   "2":{"0":{"v":"下一条"}},"3":{"0":{"v":"仍未绑定"}}}}}}
                """);
        var instance=json.readTree("""
                {"version":2,"model":{"fields":[
                  {"id":"f","bindingId":"f","fieldCode":"F","name":"字段","reviewStatus":"CONFIRMED"}]},
                 "mapping":[
                  {"bindingId":"r","mappingKind":"REPEAT_REGION","repeatAxis":"ROW","recordHeight":2,"recordStride":2,"locator":{"sheetId":"s","dataRange":"A1:A4"}},
                  {"bindingId":"f","parentBindingId":"r","mappingKind":"REPEAT_FIELD","bindingStatus":"VALID","locator":{"sheetId":"s","valueRange":"A1:A4"}}],
                 "records":[{"id":"1","regionId":"r","sheetId":"s","axis":"ROW","index":1,"size":2},{"id":"2","regionId":"r","sheetId":"s","axis":"ROW","index":3,"size":2}],"items":[],"issues":[]}
                """);
        var result=WorkbookStructure.validate(json,snapshot,instance);
        assertThat(result.path("issues").findValuesAsText("address")).containsExactlyInAnyOrder("A2","A4");
    }
    @Test void preservesLegacyDuplicateCodesWithDifferentPathsAndBindingIds() throws Exception {
        var snapshot=(ObjectNode)json.readTree("""
                {"sheets":{"s":{"id":"s","name":"明细","cellData":{}}}}
                """);
        var schema=json.readTree("""
                {"x-jsd-field-model":{"groups":[],"fields":[
                 {"id":"f1","bindingId":"b1","fieldCode":"PLATE","dataPath":"/rows/*/plate1","name":"板1","reviewStatus":"CONFIRMED"},
                 {"id":"f2","bindingId":"b2","fieldCode":"PLATE","dataPath":"/rows/*/plate2","name":"板2","reviewStatus":"CONFIRMED"}]}}
                """);
        var mapping=json.readTree("""
                [{"bindingId":"b1","fieldCode":"PLATE","dataPath":"/rows/*/plate1","mappingKind":"SCALAR","bindingStatus":"VALID","locator":{"sheetId":"s","valueRange":"A1"}},
                 {"bindingId":"b2","fieldCode":"PLATE","dataPath":"/rows/*/plate2","mappingKind":"SCALAR","bindingStatus":"VALID","locator":{"sheetId":"s","valueRange":"A2"}}]
                """);
        var instance=WorkbookStructure.seed(json,snapshot,schema,mapping,"fixture");
        var fields=instance.path("model").path("fields");
        assertThat(fields.get(0).path("fieldCode").asText()).isNotEqualTo(fields.get(1).path("fieldCode").asText());
        assertThat(fields.get(0).path("originalFieldCode").asText()).isEqualTo("PLATE");
        assertThat(instance.path("issues")).hasSize(2);
        assertThatCode(() -> WorkbookStructure.validate(json,snapshot,instance)).doesNotThrowAnyException();
    }
    @Test void expandsTwoColumnRecordsAcrossAnEmptyRecord() throws Exception {
        var snapshot=(ObjectNode)json.readTree("""
                {"sheetOrder":["s"],"sheets":{"s":{"id":"s","name":"明细","cellData":{
                "0":{"1":{"v":"S1"},"5":{"v":"S3"},"7":{"v":"合计"}},
                "1":{"2":{"v":"10"},"6":{"v":"30"}}}}}}
                """);
        var schema=(ObjectNode)json.readTree("""
                {"type":"object","x-jsd-field-model":{"modelVersion":4,"groups":[{"id":"g","name":"明细","order":0}],"fields":[
                {"id":"code","bindingId":"code","fieldCode":"code","name":"编码","groupId":"g","valueType":"string","reviewStatus":"CONFIRMED"},
                {"id":"qty","bindingId":"qty","fieldCode":"qty","name":"用量","groupId":"g","valueType":"string","reviewStatus":"CONFIRMED"}],"blocks":[],"semanticAnnotations":[]}}
                """);
        var mapping=json.createArrayNode();
        var root=mapping.addObject().put("bindingId","table").put("componentId","table").put("dataPath","/records").put("mappingKind","REPEAT_REGION").put("role","REPEAT_REGION").put("repeatAxis","COLUMN").put("recordHeight",1).put("recordWidth",2).put("recordStride",2).put("bindingStatus","VALID");
        root.withObject("/locator").put("sheetId","s").put("sheetName","明细").put("dataRange","B1:E2").put("range","B1:E2");
        for(var key:List.of("code","qty")) {
            var b=mapping.addObject().put("bindingId",key).put("fieldCode",key).put("componentId","table").put("parentBindingId","table").put("dataPath","/records/*/"+key).put("mappingKind","REPEAT_FIELD").put("repeatAxis","COLUMN").put("recordWidth",2).put("recordStride",2).put("bindingStatus","VALID");
            b.withObject("/locator").put("sheetId","s").put("sheetName","明细").put("valueRange",key.equals("code")?"B1:D1":"C2:E2");
        }
        var instance=WorkbookStructure.seed(json,snapshot,schema,mapping,"columns");
        var org0=UUID.randomUUID();var version0=UUID.randomUUID();var fileId=UUID.randomUUID();
        var repo0=mock(TemplateRepository.class);var workspace0=mock(TemplateRepository.TemplateWorkspace.class);
        when(repo0.findWorkspace(org0,version0)).thenReturn(Optional.of(workspace0));
        when(workspace0.schema()).thenReturn(schema);when(workspace0.mapping()).thenReturn(mapping);when(workspace0.templateCode()).thenReturn("ORDINARY");
        var files=mock(FileObjectRepository.class);var file=mock(FileObjectRepository.FileObject.class);
        when(files.find(org0,fileId)).thenReturn(Optional.of(file));when(file.originalName()).thenReturn("export.xlsx");when(file.objectKey()).thenReturn("export");
        var storage=mock(ObjectStorage.class);var exporter0=new SnapshotWorkbookExporter(json);
        var exported=exporter0.export(snapshot,json.createArrayNode(),json.createObjectNode(),null).content();
        when(storage.get("export")).thenReturn(new ObjectStorage.StoredObject(new ByteArrayInputStream(exported),exported.length,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        var facade0=new TemplateDataImportFacadeImpl(repo0,files,storage,new GenericTabularStructureParser(),mock(StandardFieldService.class),exporter0,json,new XlsxStructureParser(json));
        var imported=facade0.prepareImport(org0,version0,fileId);
        assertThat(imported.definition().schema().path("x-jsd-workbook-instance").path("records")).hasSize(3);
        WorkbookStructure.expandImportedRegions(json,snapshot,instance,"columns");
        assertThat(instance.path("mapping").get(0).path("locator").path("dataRange").asText()).isEqualTo("B1:G2");
        assertThat(instance.path("records")).hasSize(3);
        var org=UUID.randomUUID();var version=UUID.randomUUID();var repo=mock(TemplateRepository.class);var workspace=mock(TemplateRepository.TemplateWorkspace.class);
        when(repo.findWorkspace(org,version)).thenReturn(Optional.of(workspace));when(workspace.schema()).thenReturn(schema);when(workspace.mapping()).thenReturn(mapping);when(workspace.templateCode()).thenReturn("ORDINARY");
        var facade=new TemplateDataImportFacadeImpl(repo,mock(FileObjectRepository.class),mock(ObjectStorage.class),new GenericTabularStructureParser(),mock(StandardFieldService.class),new SnapshotWorkbookExporter(json),json,new XlsxStructureParser(json));
        var execution=facade.prepareWorkbookImport(org,version,snapshot,instance);var sheet=execution.parsed().sheets().getFirst();
        var result=new StructuredDataExtractor(json).extract(sheet,execution.definition().fields(),execution.bindings(),1,sheet.lastRow(),8,execution.definition().importContract()).orElseThrow();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(1).rawValues().toString()).contains("S3").contains("30").doesNotContain("合计");
    }
    @Test void expandsTwoRowRecordsAcrossAnEmptyRecordAndStopsAtTotal() throws Exception {
        var snapshot=(ObjectNode)json.readTree("""
                {"sheetOrder":["s"],"sheets":{"s":{"id":"s","name":"明细","cellData":{
                "0":{"0":{"v":"编码"},"1":{"v":"用量"}},"1":{"0":{"v":"MAT-1"}},"2":{"1":{"v":"10"}},
                "5":{"0":{"v":"MAT-3"}},"6":{"1":{"v":"30"}},"7":{"0":{"v":"合计"},"1":{"v":"40"}},"8":{"0":{"v":"其他区内容"}}}}}}
                """);
        var schema=(ObjectNode)json.readTree("""
                {"type":"object","x-jsd-field-model":{"modelVersion":4,"groups":[{"id":"g","name":"明细","order":0}],"fields":[
                {"id":"code","bindingId":"code","fieldCode":"code","name":"编码","groupId":"g","valueType":"string","reviewStatus":"CONFIRMED"},
                {"id":"qty","bindingId":"qty","fieldCode":"qty","name":"用量","groupId":"g","valueType":"string","reviewStatus":"CONFIRMED"}],"blocks":[],"semanticAnnotations":[]}}
                """);
        var mapping=json.createArrayNode();
        var root=mapping.addObject().put("bindingId","table").put("componentId","table").put("dataPath","/records").put("mappingKind","REPEAT_REGION").put("role","REPEAT_REGION").put("repeatAxis","ROW").put("recordHeight",2).put("recordWidth",1).put("recordStride",2).put("bindingStatus","VALID");
        root.withObject("/locator").put("sheetId","s").put("sheetName","明细").put("dataRange","A2:B5").put("range","A1:B5").put("logicalInputRange","A1:B5").put("valueRange","A1:B5").put("recordRange","A1:B5");
        for(var key:List.of("code","qty")) {
            var b=mapping.addObject().put("bindingId",key).put("fieldCode",key).put("componentId","table").put("parentBindingId","table").put("dataPath","/records/*/"+key).put("mappingKind","REPEAT_FIELD").put("repeatAxis","ROW").put("recordHeight",2).put("recordStride",2).put("bindingStatus","VALID");
            b.withObject("/locator").put("sheetId","s").put("sheetName","明细").put("valueRange",key.equals("code")?"A2:A4":"B3:B5");
        }
        var instance=WorkbookStructure.seed(json,snapshot,schema,mapping,"input");
        WorkbookStructure.expandImportedRegions(json,snapshot,instance,"input");
        assertThat(instance.path("mapping").get(0).path("locator").path("dataRange").asText()).isEqualTo("A2:B7");
        assertThat(instance.path("mapping").get(0).path("locator").path("logicalInputRange").asText()).isEqualTo("A1:B7");
        assertThat(instance.path("records")).hasSize(3);
        var org=UUID.randomUUID();var version=UUID.randomUUID();var repo=mock(TemplateRepository.class);var workspace=mock(TemplateRepository.TemplateWorkspace.class);
        when(repo.findWorkspace(org,version)).thenReturn(Optional.of(workspace));when(workspace.schema()).thenReturn(schema);when(workspace.mapping()).thenReturn(mapping);when(workspace.templateCode()).thenReturn("ORDINARY");
        var facade=new TemplateDataImportFacadeImpl(repo,mock(FileObjectRepository.class),mock(ObjectStorage.class),new GenericTabularStructureParser(),mock(StandardFieldService.class),new SnapshotWorkbookExporter(json),json,new XlsxStructureParser(json));
        var execution=facade.prepareWorkbookImport(org,version,snapshot,instance);var sheet=execution.parsed().sheets().getFirst();
        var result=new StructuredDataExtractor(json).extract(sheet,execution.definition().fields(),execution.bindings(),1,sheet.lastRow(),8,execution.definition().importContract()).orElseThrow();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(1).rawValues().toString()).contains("MAT-3").contains("30").doesNotContain("其他区内容");
    }
    @Test void singleFileInstanceKeepsAllTenColumnRecordsAndCommonLocalText()throws Exception {
        var snapshot=(ObjectNode)json.readTree("""
                {"id":"comparison","sheetOrder":["s"],"sheets":{"s":{"id":"s","name":"对照","rowCount":20,"columnCount":12,"cellData":{}}}}
                """);
        var cells=snapshot.withObject("/sheets/s/cellData");
        cells.withObject("/0").putObject("0").put("v","本次说明");cells.withObject("/0").putObject("1").put("v","核对页新增文字");
        cells.withObject("/2").putObject("0").put("v","样品编号");cells.withObject("/3").putObject("0").put("v","测试结果");
        for(int c=1;c<=10;c++){cells.withObject("/2").putObject(""+c).put("v","S"+c);cells.withObject("/3").putObject(""+c).put("v",c-1);}
        var schema=(ObjectNode)json.readTree("""
                {"type":"object","x-jsd-experiment-import":{"templateUsage":"EXPERIMENT_DATA","recordMode":"SINGLE_FILE","identities":[],"listProjections":[]},
                "x-jsd-field-model":{"modelVersion":4,"groups":[{"id":"g","name":"对照","order":0}],"fields":[
                {"id":"sample","bindingId":"sample","fieldCode":"sample","name":"样品编号","groupId":"g","kind":"SCALAR","reviewStatus":"CONFIRMED","experimentField":{"domain":"OTHER","field":"DYNAMIC_VALUE"}},
                {"id":"value","bindingId":"value","fieldCode":"value","name":"测试结果","groupId":"g","kind":"SCALAR","reviewStatus":"CONFIRMED","experimentField":{"domain":"TEST","field":"VALUE"}},
                {"id":"note","bindingId":"note","fieldCode":"INSTANCE_LOCAL.note","fieldOrigin":"INSTANCE_LOCAL","name":"本次说明","groupId":"g","kind":"SCALAR","reviewStatus":"CONFIRMED","experimentField":{"domain":"OTHER","field":"DYNAMIC_VALUE"}}],"blocks":[],"semanticAnnotations":[]}}
                """);
        var mapping=json.createArrayNode();
        var root=mapping.addObject().put("bindingId","table").put("componentId","table").put("dataPath","/records").put("mappingKind","REPEAT_REGION").put("role","REPEAT_REGION").put("repeatAxis","COLUMN").put("bindingStatus","VALID");
        root.withObject("/locator").put("sheetId","s").put("sheetName","对照").put("dataRange","B3:K4").put("range","A3:K4").put("structureType","COLUMN_TABLE");
        for(var key:List.of("sample","value","note")){
            boolean common=key.equals("note");int row=key.equals("sample")?3:4;
            var b=mapping.addObject().put("bindingId",key).put("componentId",common?"common":"table").put("fieldCode",common?"INSTANCE_LOCAL.note":key).put("dataPath","/fields/"+key).put("mappingKind",common?"SCALAR":"REPEAT_FIELD").put("role","FIELD").put("bindingStatus","VALID");
            if(!common)b.put("repeatAxis","COLUMN").put("parentBindingId","table");
            b.set("experimentField",schema.path("x-jsd-field-model").path("fields").get(List.of("sample","value","note").indexOf(key)).path("experimentField"));
            b.withObject("/locator").put("sheetId","s").put("sheetName","对照").put("labelRange",common?"A1":"A"+row).put("valueRange",common?"B1":"B"+row+":K"+row).put("address",common?"B1":"B"+row+":K"+row).put("valueMode",common?"SCALAR":"ARRAY_ROW");
        }
        var instance=WorkbookStructure.seed(json,snapshot,schema,mapping,"ten");
        var org=UUID.randomUUID();var version=UUID.randomUUID();var repository=mock(TemplateRepository.class);var workspace=mock(TemplateRepository.TemplateWorkspace.class);
        when(repository.findWorkspace(org,version)).thenReturn(Optional.of(workspace));when(workspace.schema()).thenReturn(schema);
        var facade=new TemplateDataImportFacadeImpl(repository,mock(FileObjectRepository.class),mock(ObjectStorage.class),new GenericTabularStructureParser(),mock(StandardFieldService.class),new SnapshotWorkbookExporter(json),json,new XlsxStructureParser(json));
        var execution=facade.prepareWorkbookImport(org,version,snapshot,instance);var sheet=execution.parsed().sheets().getFirst();var extractor=new StructuredDataExtractor(json);
        assertThat(sheet.rows().toString()).contains("S1");assertThat(execution.bindings()).hasSizeGreaterThan(2);
        var candidate=extractor.extract(sheet,execution.definition().fields(),execution.bindings(),1,sheet.lastRow(),9,execution.definition().importContract());
        assertThat(candidate).as("sheet=%s bindings=%s",sheet.sheetName(),execution.bindings()).isPresent();
        var extracted=candidate.orElseThrow();
        var stable=extractor.applyInstanceIdentities(extracted,instance,"ten");
        assertThat(stable.rows()).hasSize(10);assertThat(stable.rows().stream().map(r->r.id()).distinct()).hasSize(10);
        assertThat(stable.rows()).allSatisfy(r->assertThat(r.rawValues().toString()).contains("核对页新增文字"));
        assertThat(stable.rows().getFirst().rawValues().toString()).contains("\"0\"");
    }
    @Test void ordinaryTemplateUsesTheSameExpandedInstanceAndRoundTripsLocalFields()throws Exception {
        var snapshot=(ObjectNode)json.readTree("""
                {"id":"ordinary","sheetOrder":["sheet-1"],"sheets":{"sheet-1":{"id":"sheet-1","name":"普通明细","rowCount":20,"columnCount":10,
                "cellData":{"1":{"0":{"v":"物料编码"},"1":{"v":"用量"},"2":{"v":"本次备注"}},"2":{"0":{"v":"MAT-01"},"1":{"v":"90g"},"2":{"v":"首次投料"}},"3":{"0":{"v":"MAT-02"},"1":{"v":0},"2":{"v":"保留零值"}}}}}}
                """);
        var schema=(ObjectNode)json.readTree("""
                {"type":"object","x-jsd-field-model":{"modelVersion":4,"groups":[{"id":"g","name":"明细","order":0}],"fields":[
                {"id":"code","bindingId":"code","fieldCode":"code","name":"物料编码","groupId":"g","kind":"SCALAR","valueType":"string","reviewStatus":"CONFIRMED"},
                {"id":"qty","bindingId":"qty","fieldCode":"qty","name":"用量","groupId":"g","kind":"SCALAR","valueType":"number","unit":"g","reviewStatus":"CONFIRMED"},
                {"id":"note","bindingId":"note","fieldCode":"INSTANCE_LOCAL.note","name":"本次备注","groupId":"g","kind":"SCALAR","fieldOrigin":"INSTANCE_LOCAL","valueType":"string","reviewStatus":"CONFIRMED"}],"blocks":[],"semanticAnnotations":[]}}
                """);
        var mapping=json.createArrayNode();
        var root=mapping.addObject().put("bindingId","table").put("componentId","table").put("dataPath","/records").put("mappingKind","REPEAT_REGION").put("role","REPEAT_REGION").put("repeatAxis","ROW").put("bindingStatus","VALID");
        root.withObject("/locator").put("sheetId","sheet-1").put("sheetName","普通明细").put("dataRange","A3:C4").put("range","A2:C4").put("structureType","ROW_TABLE");
        for(var key:List.of("code","qty","note")) {
            int column=List.of("code","qty","note").indexOf(key);String letter=""+(char)('A'+column);
            var binding=mapping.addObject().put("bindingId",key).put("componentId","table").put("parentBindingId","table").put("fieldCode",key.equals("note")?"INSTANCE_LOCAL.note":key).put("dataPath","/records/*/"+key).put("mappingKind","REPEAT_FIELD").put("role","FIELD").put("repeatAxis","ROW").put("bindingStatus","VALID");
            binding.withObject("/locator").put("sheetId","sheet-1").put("sheetName","普通明细").put("labelRange",letter+"2").put("address",letter+"3:"+letter+"4").put("valueRange",letter+"3:"+letter+"4");
        }
        var instance=WorkbookStructure.seed(json,snapshot,schema,mapping,"ordinary");
        var org=UUID.randomUUID();var version=UUID.randomUUID();var repository=mock(TemplateRepository.class);var workspace=mock(TemplateRepository.TemplateWorkspace.class);
        var legacySchema=json.createObjectNode();legacySchema.putObject("properties").putObject("note").put("fieldCode","INSTANCE_LOCAL.note").put("name","新字段");schema.fields().forEachRemaining(e->legacySchema.set(e.getKey(),e.getValue()));
        when(repository.findWorkspace(org,version)).thenReturn(Optional.of(workspace));when(workspace.schema()).thenReturn(legacySchema);when(workspace.templateCode()).thenReturn("ORDINARY");
        var exporter=new SnapshotWorkbookExporter(json);var parser=new XlsxStructureParser(json);
        var facade=new TemplateDataImportFacadeImpl(repository,mock(FileObjectRepository.class),mock(ObjectStorage.class),new GenericTabularStructureParser(),mock(StandardFieldService.class),exporter,json,parser);
        var execution=facade.prepareWorkbookImport(org,version,snapshot,instance);
        assertThat(execution.definition().fields().stream().map(f->f.fieldCode()).toList()).contains("INSTANCE_LOCAL.note");
        assertThat(execution.definition().fields().stream().filter(f->f.fieldCode().equals("INSTANCE_LOCAL.note")).findFirst().orElseThrow().displayName()).isEqualTo("本次备注");
        var sheet=execution.parsed().sheets().getFirst();var extractor=new StructuredDataExtractor(json);
        var extracted=extractor.extract(sheet,execution.definition().fields(),execution.bindings(),1,sheet.lastRow(),8,execution.definition().importContract()).orElseThrow();
        var stable=extractor.applyInstanceIdentities(extracted,instance,"import");
        assertThat(stable.rows()).hasSize(2);assertThat(stable.rows().get(1).rawValues().toString()).contains("保留零值").contains("0");
        assertThat(stable.rows().getFirst().sourceMetadata().path("recordKey").asText()).startsWith("INSTANCE:");
        assertThat(extractor.applyInstanceIdentities(extracted,instance,"import").rows().getFirst().id()).isEqualTo(stable.rows().getFirst().id());
        var dataRepository=mock(com.jsd.aird.data.application.port.DataRepository.class);
        var dataTemplates=mock(com.jsd.aird.tpl.api.TemplateDataImportFacade.class);var importId=UUID.randomUUID();
        var job=new com.jsd.aird.data.application.port.DataRepository.Job(importId,UUID.randomUUID(),"hash","ordinary.xlsx","XLSX",version,null,"PARSING",0,"PARSING","tester",null,java.time.Instant.now(),java.time.Instant.now());
        when(dataRepository.findJob(org,importId)).thenReturn(Optional.of(job));
        when(dataRepository.findWorkbookDraft(org,importId)).thenReturn(Optional.of(new com.jsd.aird.data.application.port.DataRepository.WorkbookDraft(snapshot,instance,json.createArrayNode())));
        when(dataRepository.listSheets(org,importId)).thenReturn(List.of(new com.jsd.aird.data.application.port.DataRepository.Sheet(UUID.randomUUID(),sheet.sheetId(),sheet.sheetName(),0,true,List.of(2),3,3,json.createObjectNode(),"CONFIRMED")));
        when(dataTemplates.getVersion(org,version)).thenReturn(execution.definition());
        when(dataTemplates.prepareWorkbookImport(eq(org),eq(version),any(),any())).thenReturn(execution);
        new DataImportService(dataRepository,mock(FileObjectRepository.class),dataTemplates,json,mock(com.jsd.aird.ops.application.port.AuditLogFacade.class),mock(com.jsd.aird.ops.application.port.OpsAsyncFacade.class)).parseInternal(org,importId);
        verify(dataRepository).saveParsed(eq(importId),anyString(),anyList(),anyList(),argThat(rs->rs.size()==2),eq(importId));
        var bytes=exporter.export(snapshot,json.createArrayNode(),json.createObjectNode(),null).content();
        var restored=parser.parseInstance(new ByteArrayInputStream(bytes)).snapshot();
        assertThat(WorkbookStructure.instance(restored).path("records")).isEqualTo(instance.path("records"));
        assertThat(restored.path("sheets")).hasSize(1);
    }
}
