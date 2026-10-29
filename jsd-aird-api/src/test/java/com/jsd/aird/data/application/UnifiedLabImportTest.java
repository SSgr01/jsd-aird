package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.lab.LabWorkbook;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.tpl.application.StandardFieldService;
import com.jsd.aird.tpl.application.TemplateDataImportFacadeImpl;
import com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import com.jsd.aird.tpl.infrastructure.GenericTabularStructureParser;
import com.jsd.aird.tpl.infrastructure.XlsxStructureParser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UnifiedLabImportTest {
    @Test void importsAllSixSynthesisIdentitiesAndMergedTitleFieldsFromTheCustomerExample()throws Exception {
        var json=new ObjectMapper();var parser=new XlsxStructureParser(json);var exporter=new SnapshotWorkbookExporter(json);
        var pack=new UnifiedLabTemplateCatalog(json).compile(UnifiedLabTemplateCatalog.SYNTHESIS,
                parser.parseInstance(getClass().getResourceAsStream("/templates/unified/synthesis.xlsx"))).payload();
        var bytes=getClass().getResourceAsStream("/unified-lab/synthesis-example.xlsx").readAllBytes();
        var org=UUID.randomUUID();var version=UUID.randomUUID();var fileId=UUID.randomUUID();
        var repository=mock(TemplateRepository.class);var files=mock(FileObjectRepository.class);var storage=mock(ObjectStorage.class);
        var workspace=mock(TemplateRepository.TemplateWorkspace.class);
        when(repository.findWorkspace(org,version)).thenReturn(Optional.of(workspace));
        when(workspace.schema()).thenReturn(pack.path("schema"));when(workspace.mapping()).thenReturn(pack.path("mapping"));
        when(workspace.inlineSnapshot()).thenReturn(pack.path("snapshot"));when(workspace.templateCode()).thenReturn(UnifiedLabTemplateCatalog.SYNTHESIS);
        when(workspace.name()).thenReturn("合成实验");when(workspace.format()).thenReturn(TemplateFormat.XLSX);
        when(files.find(org,fileId)).thenReturn(Optional.of(new FileObjectRepository.FileObject(fileId,org,"fixture","input.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes.length,"hash","ACTIVE")));
        when(storage.get("fixture")).thenAnswer(call->new ObjectStorage.StoredObject(new ByteArrayInputStream(bytes),bytes.length,"application/octet-stream"));
        var facade=new TemplateDataImportFacadeImpl(repository,files,storage,new GenericTabularStructureParser(),mock(StandardFieldService.class),exporter,json,parser);
        var execution=facade.prepareImport(org,version,fileId);var sheet=execution.parsed().sheets().getFirst();
        var resolved=new BindingLabelResolver(json).resolve(execution.bindings(),execution.definition().fields(),execution.parsed().sheets());
        for(var code:List.of("plan.content","sample.experimentNo","best.choice"))
            assertThat(resolved.executableBindings().stream().filter(b->code.equals(b.fieldCode())).findFirst().orElseThrow().locator().path("valueRange").asText()).startsWith("C").contains(":H");
        var result=new StructuredDataExtractor(json).extract(sheet,execution.definition().fields(),resolved.executableBindings(),1,sheet.lastRow(),9,execution.definition().importContract()).orElseThrow();
        assertThat(result.rows()).hasSize(6);
        assertThat(result.rows().stream().map(row->row.sourceMetadata().path("sourceIdentity").asText()).toList())
                .containsExactly("SIM-SYN-001","SIM-SYN-002","SIM-SYN-003","SIM-SYN-004","SIM-SYN-005","SIM-SYN-006");
        assertThat(result.rows().getLast().rawValues().path("b_plancontent").asText()).contains("85℃").contains("平行实验2");
    }
    @Test void recomputesGramTotalsInsteadOfTrustingAnArrayFormulaCache()throws Exception {
        var json=new ObjectMapper();var exporter=new SnapshotWorkbookExporter(json);var parser=new XlsxStructureParser(json);
        var pack=new UnifiedLabTemplateCatalog(json).compile(UnifiedLabTemplateCatalog.TEST,
                parser.parseInstance(getClass().getResourceAsStream("/templates/unified/test.xlsx"))).payload();
        var source=pack.path("snapshot").deepCopy();var sheet=LabWorkbook.sheet(source);
        LabWorkbook.cell(sheet,0,1).put("v","Live import");LabWorkbook.cell(sheet,1,1).put("v","Tester");
        LabWorkbook.cell(sheet,1,3).put("v","2026-10-24");
        LabWorkbook.cell(sheet,51,1).put("v","MAT-001");LabWorkbook.cell(sheet,51,2).put("v","90g");
        LabWorkbook.cell(sheet,52,1).put("v","MAT-002");LabWorkbook.cell(sheet,52,2).put("v","10");
        LabWorkbook.cell(sheet,59,2).put("v",99999); // deliberately stale cache
        var bytes=exporter.export(source,json.createArrayNode(),json.createObjectNode(),null).content();
        var org=UUID.randomUUID();var version=UUID.randomUUID();var fileId=UUID.randomUUID();
        var repository=mock(TemplateRepository.class);var files=mock(FileObjectRepository.class);var storage=mock(ObjectStorage.class);
        var workspace=mock(TemplateRepository.TemplateWorkspace.class);
        when(repository.findWorkspace(org,version)).thenReturn(Optional.of(workspace));
        when(workspace.schema()).thenReturn(pack.path("schema"));when(workspace.inlineSnapshot()).thenReturn(pack.path("snapshot"));
        when(workspace.templateCode()).thenReturn(UnifiedLabTemplateCatalog.TEST);
        when(files.find(org,fileId)).thenReturn(Optional.of(new FileObjectRepository.FileObject(fileId,org,"fixture","input.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes.length,"hash","ACTIVE")));
        when(storage.get("fixture")).thenAnswer(call->new ObjectStorage.StoredObject(new ByteArrayInputStream(bytes),bytes.length,"application/octet-stream"));
        var facade=new TemplateDataImportFacadeImpl(repository,files,storage,new GenericTabularStructureParser(),mock(StandardFieldService.class),exporter,json,parser);
        var execution=facade.prepareImport(org,version,fileId);var parsed=execution.parsed().sheets().getFirst();
        assertThat(parsed.rows().get(59).get(2)).isEqualTo("100");
        var total=java.util.stream.StreamSupport.stream(parsed.layoutIr().path("cells").spliterator(),false)
                .filter(cell->cell.path("address").asText().equals("C60")).findFirst().orElseThrow();
        assertThat(total.path("calculationStatus").asText()).isEqualTo("VALID");
        assertThat(total.path("calculationSource").asText()).isEqualTo("UNIFIED_LAB_RECALCULATED");
        assertThat(parsed.rows().getFirst().get(1)).isEqualTo("Live import");
        var resolved=new BindingLabelResolver(json).resolve(execution.bindings(),execution.definition().fields(),execution.parsed().sheets());
        var extracted=new StructuredDataExtractor(json).extract(parsed,execution.definition().fields(),resolved.executableBindings(),1,parsed.lastRow(),9,execution.definition().importContract()).orElseThrow();
        assertThat(extracted.mappings().stream().map(mapping->mapping.fieldCode()).toList()).contains("basic.title","basic.owner","basic.date");
        assertThat(extracted.rows()).isNotEmpty();
        var experiments=mock(com.jsd.aird.rnd.application.port.ExperimentRepository.class);
        var category=UUID.randomUUID();
        when(experiments.categories(org,false)).thenReturn(List.of(new com.jsd.aird.rnd.domain.ExperimentModels.Category(category,"TEST","Test",null,true,1)));
        when(experiments.create(any())).thenReturn(mock(com.jsd.aird.rnd.domain.ExperimentModels.Summary.class));
        var drafts=new com.jsd.aird.rnd.application.ExperimentDraftApplicationFacade(experiments,
                new com.jsd.aird.rnd.application.ExperimentEditModelNormalizer(json),json,mock(com.jsd.aird.iam.api.AuthorizationService.class));
        drafts.createImportedDraft(new com.jsd.aird.rnd.api.ExperimentDraftFacade.ImportedDraftCommand(org,UUID.randomUUID(),"Tester","Live import",category,"EXCEL_IMPORT",null,null,null,"Tester",java.time.LocalDate.of(2026,10,24),version,"hash",pack.path("snapshot"),json.createObjectNode().set("documentSnapshot",source)));
        var created=ArgumentCaptor.forClass(com.jsd.aird.rnd.application.port.ExperimentRepository.Create.class);
        verify(experiments).create(created.capture());
        assertThat(created.getValue().editModel().path("sourceGroups")).hasSize(1);
        assertThat(created.getValue().editModel().path("formulaItems").get(0).path("actualQty").asInt()).isEqualTo(90);
        assertThat(created.getValue().editModel().path("templateInstance").path("issues")).isEmpty();
    }
    @Test void rebuildsV9LocationsForExpandedAndCompactFilesBeforeDataExtraction()throws Exception {
        var folder=System.getProperty("lab.browser.snapshots");
        org.junit.jupiter.api.Assumptions.assumeTrue(folder!=null,"Run the real Univer acceptance fixture first");
        var json=new ObjectMapper();var exporter=new SnapshotWorkbookExporter(json);
        for(var kind:List.of("test","synthesis")) {
            var source=json.readTree(java.nio.file.Path.of(folder,kind+"-expanded.json").toFile());
            var catalog=new UnifiedLabTemplateCatalog(json);var parser=new XlsxStructureParser(json);
            var code=kind.equals("test")?UnifiedLabTemplateCatalog.TEST:UnifiedLabTemplateCatalog.SYNTHESIS;
            var pack=catalog.compile(code,parser.parseInstance(getClass().getResourceAsStream("/templates/unified/"+kind+".xlsx"))).payload();
            for(boolean compact:List.of(false,true)) {
                var snapshot=compact?LabWorkbook.present(json,LabWorkbook.prepare(json,source,pack.path("snapshot"),"file")).snapshot():source;
                var bytes=exporter.export(snapshot,json.createArrayNode(),json.createObjectNode(),null).content();
                var org=UUID.randomUUID();var version=UUID.randomUUID();var fileId=UUID.randomUUID();
                var repository=mock(TemplateRepository.class);var files=mock(FileObjectRepository.class);var storage=mock(ObjectStorage.class);
                var workspace=mock(TemplateRepository.TemplateWorkspace.class);
                when(repository.findWorkspace(org,version)).thenReturn(Optional.of(workspace));
                when(workspace.schema()).thenReturn(pack.path("schema"));when(workspace.mapping()).thenReturn(pack.path("mapping"));when(workspace.inlineSnapshot()).thenReturn(pack.path("snapshot"));
                when(workspace.templateCode()).thenReturn(code);when(workspace.name()).thenReturn(kind);when(workspace.format()).thenReturn(TemplateFormat.XLSX);
                when(files.find(org,fileId)).thenReturn(Optional.of(new FileObjectRepository.FileObject(fileId,org,"fixture","input.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes.length,"hash","ACTIVE")));
                when(storage.get("fixture")).thenAnswer(call->new ObjectStorage.StoredObject(new ByteArrayInputStream(bytes),bytes.length,"application/octet-stream"));
                var facade=new TemplateDataImportFacadeImpl(repository,files,storage,new GenericTabularStructureParser(),mock(StandardFieldService.class),exporter,json,parser);
                var execution=facade.prepareImport(org,version,fileId);
                assertThat(execution.definition().importContractVersion()).isEqualTo(9);
                assertThat(execution.parsed().sheets()).hasSize(1);
                assertThat(execution.parsed().sheets().getFirst().lastColumn()).isEqualTo(10);
                assertThat(execution.bindings().stream().filter(b->"sample.experimentNo".equals(b.fieldCode())).findFirst().orElseThrow().locator().toString()).contains(":J");
                assertThat(execution.definition().fields().stream().anyMatch(f->f.fieldCode().startsWith("LOCAL_"))).isTrue();
            }
        }
    }
}
