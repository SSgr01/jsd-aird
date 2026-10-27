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
