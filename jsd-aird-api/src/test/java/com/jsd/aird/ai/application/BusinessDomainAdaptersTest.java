package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.data.application.BusinessStructuredReader;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.mdm.api.*;
import com.jsd.aird.mdm.application.port.*;
import com.jsd.aird.mdm.application.service.BusinessCustomerReader;
import com.jsd.aird.mfg.application.BusinessManufacturingReader;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import com.jsd.aird.mfg.inventory.application.port.InventoryRepository;
import com.jsd.aird.mfg.inventory.domain.InventoryModels;
import com.jsd.aird.mfg.upload.application.port.ProductionUploadRepository;
import com.jsd.aird.quality.application.BusinessQualityReader;
import com.jsd.aird.quality.application.port.QualityDataStore;
import com.jsd.aird.rnd.application.BusinessResearchTestReader;
import com.jsd.aird.rnd.application.port.ResearchTestRepository;
import com.jsd.aird.rnd.domain.ResearchTestModels;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import com.jsd.aird.spc.application.*;
import com.jsd.aird.spc.application.port.SpectrumRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Domain readers run against isolated repository fixtures; no production writes or model calls. */
class BusinessDomainAdaptersTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Actor actor = new Actor(id(100), id(101), "isolated-business-acceptance");
    private final BusinessExistingData existing = mock(BusinessExistingData.class);
    private final InventoryRepository inventory = mock(InventoryRepository.class);
    private final ProductionOrderRepository orders = mock(ProductionOrderRepository.class);
    private final ProductionUploadRepository uploads = mock(ProductionUploadRepository.class);
    private static UUID id(long n) { return new UUID(0, n); }
    private ObjectNode object(String value) throws Exception { return (ObjectNode)json.readTree(value); }
    @BeforeEach void setup() { ActorContext.set(actor); }
    @AfterEach void clear() { ActorContext.clear(); }
    private BusinessDataService service(BusinessReadPort... readers) {
        var types = new HashSet<String>(); for (var reader : readers) types.addAll(reader.resourceTypes());
        var policy = new BusinessDataAccess() {
            public Set<String> resourceTypes() { return types; }
            public boolean allowed(Actor a, String type, UUID target, String op, JsonNode context) { return actor.organizationId().equals(a.organizationId()); }
        };
        return new BusinessDataService(List.of(readers), List.of(policy), existing, json);
    }
    private ObjectNode read(BusinessDataService service, ObjectNode ref) { return service.invoke("business_read", json.createObjectNode().set("ref", ref)); }
    private ObjectNode links(BusinessDataService service, ObjectNode ref, String name) {
        var args = json.createObjectNode().put("relation", name); args.set("ref", ref); return service.invoke("business_links", args);
    }
    private BusinessManufacturingReader manufacturing() { return new BusinessManufacturingReader(inventory, orders, uploads, json); }
    private ObjectNode workbook(int value) throws Exception {
        return object("{\"sheets\":{\"s1\":{\"name\":\"生产明细\",\"cellData\":{\"0\":{\"0\":{\"v\":\"实际投料kg\"},\"1\":{\"v\":"+value+"}},\"1\":{\"0\":{\"v\":\"新增检测项\"},\"1\":{\"v\":0}}}}}}");
    }

    @Test void customerProjectRelationshipKeepsNativePaginationAndPermissionPath() throws Exception {
        var customers = mock(BusinessPartnerRepository.class);
        var customer = new BusinessPartner(id(1), "ISO-C01", "隔离客户", null, null, PartnerStatus.ACTIVE, null, List.of(), 1, Instant.EPOCH, Instant.EPOCH);
        when(customers.findById(id(1))).thenReturn(Optional.of(customer));
        var reader = new BusinessCustomerReader(customers, mock(PartnerContactRepository.class), mock(CommunicationRecordRepository.class), mock(CustomerRequirementRepository.class), json);
        var page = object("{\"candidates\":[{\"text\":\"真实客户关联项目\",\"ref\":{\"type\":\"PROJECT\",\"id\":\""+id(20)+"\"}}],\"next_offset\":40}");
        when(existing.invoke(eq("business_search"), any())).thenReturn(page);
        var service = service(reader); var ref = read(service, BusinessViews.ref("CUSTOMER", id(1))).path("candidates").path(0).path("ref");
        var args = json.createObjectNode().put("relation", "关联项目").put("offset", 20).put("limit", 20); args.set("ref", ref);
        var result = service.invoke("business_links", args);
        assertThat(result.path("candidates").size()).isEqualTo(2);
        assertThat(result.path("next_offset").asInt()).isEqualTo(40);
        verify(existing).invoke(eq("business_search"), argThat(a -> a.path("offset").asInt()==20 && a.path("filters").path("customer_id").asText().equals(id(1).toString())));
        assertThat(service.candidate(reader.read((ObjectNode)ref)).path("source").path("anchor").path("path").asText()).isEqualTo("/partners/"+id(1));
    }

    private QualityDataStore.RecordView quality(UUID id, String type, String number, ObjectNode data) {
        return new QualityDataStore.RecordView(id, type, null, "隔离分类", number, number, data, null, null,
            null, null, null, null, null, null, "ALL", json.createObjectNode(), 1, Instant.EPOCH, Instant.EPOCH);
    }
    @Test void shipmentCoaTestAndStandardFollowExactBusinessNumbers() throws Exception {
        var shipment = mock(InventoryModels.Shipment.class);
        when(shipment.id()).thenReturn(id(1)); when(shipment.shipmentNo()).thenReturn("ISO-S01");
        when(shipment.productId()).thenReturn(id(10)); when(shipment.scope()).thenReturn(InventoryModels.Scope.PRODUCTION);
        when(shipment.coaNo()).thenReturn("COA-EXACT");
        when(inventory.findShipment(actor.organizationId(), id(1))).thenReturn(Optional.of(shipment));
        var repo = mock(QualityDataStore.class);
        var coa = quality(id(2), "coa", "COA-EXACT", object("{\"internalRecordNo\":\"TEST-EXACT\",\"result\":0}"));
        var record = quality(id(3), "record", "TEST-EXACT", object("{\"standardNo\":\"STD-EXACT\",\"method\":\"隔离方法\",\"temperature\":25}"));
        var standard = quality(id(4), "standard", "STD-EXACT", object("{\"limit\":1,\"unit\":\"MPa\"}"));
        for (var row : List.of(coa, record, standard)) {
            when(repo.record(actor.organizationId(), actor.role(), row.id())).thenReturn(row);
            when(repo.recordByBusinessNo(actor.organizationId(), actor.role(), row.businessType(), row.businessNo())).thenReturn(Optional.of(row));
        }
        var service = service(manufacturing(), new BusinessQualityReader(repo, json));
        var coaResult = links(service, BusinessViews.ref("SHIPMENT", id(1)), "出货COA");
        var coaRef = (ObjectNode)coaResult.path("candidates").path(1).path("ref");
        assertThat(coaRef.path("id").asText()).isEqualTo(id(2).toString());
        var testResult = links(service, coaRef, "COA对应测试记录");
        var stdResult = links(service, (ObjectNode)testResult.path("candidates").path(1).path("ref"), "采用标准");
        assertThat(stdResult.path("candidates").path(1).path("ref").path("id").asText()).isEqualTo(id(4).toString());
        assertThat(stdResult.toString()).contains("MPa", "/quality/records/");
        verify(repo).recordByBusinessNo(actor.organizationId(), actor.role(), "coa", "COA-EXACT");
    }

    @Test void productionPathsReadSavedRevisionsAndKeepUnconfirmedValuesSeparate() throws Exception {
        var order = mock(ProductionOrderRepository.ProductionWorkspace.class);
        when(order.orderNo()).thenReturn("ISO-P01"); when(order.data()).thenReturn(object("{\"kg\":999}"));
        when(orders.findWorkspace(actor.organizationId(), id(1))).thenReturn(Optional.of(order));
        when(orders.findRevision(actor.organizationId(), id(1), id(11))).thenReturn(Optional.of(new ProductionOrderRepository.RecordRevision(id(11),id(1),1,"SUBMITTED",null,null,object("{\"kg\":5}"),null,null,null,null,null)));
        var upload = mock(ProductionUploadRepository.UploadView.class);
        when(upload.productionName()).thenReturn("隔离上传生产单"); when(upload.workbookSnapshot()).thenReturn(workbook(999));
        when(uploads.find(actor.organizationId(), id(2))).thenReturn(Optional.of(upload));
        when(uploads.versions(actor.organizationId(), id(2))).thenReturn(List.of(new ProductionUploadRepository.VersionView(id(12),id(2),1,workbook(7),1,"PUBLISHED","验收",Instant.EPOCH)));
        when(uploads.listRecognitionFields(actor.organizationId(),id(2))).thenReturn(List.of(new ProductionUploadRepository.RecognitionFieldView(id(13),"mass","SCALAR","mass","mass","mass",null,json.getNodeFactory().textNode("识别候选8"),json.getNodeFactory().numberNode(8),json.createObjectNode(),0.5,"PENDING")));
        var service = service(manufacturing());
        var oldOrder = read(service,BusinessViews.ref("PRODUCTION_ORDER",id(1)).put("version_id",id(11).toString()));
        assertThat(oldOrder.path("candidates").path(0).path("text").asText()).contains("kg: 5").doesNotContain("999");
        assertThat(oldOrder.toString()).contains("/production-orders/", "/workspace");
        var oldUpload = read(service,BusinessViews.ref("PRODUCTION_UPLOAD",id(2)).put("version_id",id(12).toString()));
        assertThat(oldUpload.path("candidates").path(0).path("text").asText()).contains("B1", "7", "新增检测项").doesNotContain("999", "识别候选8", "export");
        var current = read(service,BusinessViews.ref("PRODUCTION_UPLOAD",id(2)));
        assertThat(current.toString()).contains("PENDING", "识别候选8", "不能作为正式生产事实", "/production-orders/uploads/");
    }

    @Test void researchTestUsesFixedDocumentContentAndPreservesUnknownBusinessFields() throws Exception {
        var repo = mock(ResearchTestRepository.class); var summary = mock(ResearchTestModels.Summary.class);
        when(summary.name()).thenReturn("隔离测试标准"); when(summary.recordType()).thenReturn(ResearchTestModels.Type.STANDARD);
        var current = object("{\"newBusinessField\":0,\"documentSnapshot\":{\"body\":{\"dataStream\":\"当前999\"},\"styles\":{\"editor\":true}}}");
        when(repo.detail(actor.organizationId(),id(1))).thenReturn(Optional.of(new ResearchTestModels.Detail(summary,null,null,null,null,current,null,null,null,List.of())));
        var historical = object("{\"newBusinessField\":0,\"documentSnapshot\":{\"body\":{\"dataStream\":\"标准正文25°C\\r拉伸强度1MPa\"},\"styles\":{\"editor\":true}}}");
        var version = new ResearchTestModels.Version(id(2),1,"PUBLISHED",null,null,null,historical,null,null,null,null,null,null,null,Instant.EPOCH);
        when(repo.versions(actor.organizationId(),id(1))).thenReturn(List.of(version));
        var result=read(service(new BusinessResearchTestReader(repo,json)),BusinessViews.ref("RESEARCH_TEST",id(1)).put("version_id",id(2).toString()));
        String text=result.path("candidates").path(0).path("text").asText();
        assertThat(text).contains("25°C", "1MPa", "newBusinessField: 0").doesNotContain("999", "styles", "documentSnapshot");
        assertThat(result.toString()).contains("/research-test/standards/");
    }

    @Test void structuredRowsKeepRawNormalizedCorrectedAndExclusionState() throws Exception {
        var repo=mock(DataRepository.class); var job=mock(DataRepository.Job.class); var row=mock(DataRepository.Row.class);
        when(repo.findJob(actor.organizationId(),id(1))).thenReturn(Optional.of(job));
        when(row.id()).thenReturn(id(2)); when(row.rowNumber()).thenReturn(3);
        when(row.rawValues()).thenReturn(object("{\"mass\":\"0.0\",\"newField\":\"未测\"}"));
        when(row.normalizedValues()).thenReturn(object("{\"mass\":0,\"newField\":null}"));
        when(row.correctedValues()).thenReturn(object("{\"mass\":0.1}")); when(row.excluded()).thenReturn(true); when(row.exclusionReason()).thenReturn("隔离样本排除");
        when(repo.listRows(actor.organizationId(),id(1))).thenReturn(List.of(row));
        var reader=new BusinessStructuredReader(repo,json); var result=reader.read(BusinessViews.ref("STRUCTURED_RECORD",id(2)).put("import_job_id",id(1).toString()));
        assertThat(result.facts().path("normalizedValues").path("mass").isNumber()).isTrue();
        assertThat(result.facts().path("normalizedValues").path("newField").isNull()).isTrue();
        assertThat(result.facts().path("excluded").asBoolean()).isTrue();
        assertThat(result.facts().path("correctedValues").path("mass").asDouble()).isEqualTo(0.1);
        assertThat(result.path()).isEqualTo("/data/import-jobs/"+id(1));
        assertThatThrownBy(()->reader.read(BusinessViews.ref("STRUCTURED_RECORD",id(2)).put("import_job_id",id(9).toString()))).isInstanceOf(ApiException.class);
    }

    @Test void spectrumOriginalChecksHashAndUsesTheNativeFileBridge() throws Exception {
        var repo=mock(SpectrumRepository.class); var files=mock(SpectrumService.class);
        byte[] bytes="%PDF-1.7 isolated spectrum".getBytes(StandardCharsets.UTF_8); String hash=BusinessViews.hash(bytes);
        var chart=new SpectrumRepository.ChartRow(id(1),actor.organizationId(),id(2),"ISO","隔离图谱",id(3),"样品A","isolated.pdf","application/pdf",bytes.length,hash,"A","ISO-B01","25°C", "{}",1,"READY",Instant.EPOCH,Instant.EPOCH);
        when(repo.findChart(actor.organizationId(),id(1))).thenReturn(Optional.of(chart));
        when(files.openChart(id(1))).thenAnswer(call->new SpectrumService.StoredChartFile(chart,new com.jsd.aird.ops.api.FileStorageFacade.StoredFile(id(3),"isolated.pdf","application/pdf",bytes.length,hash,new ByteArrayInputStream(bytes))));
        var reader=new BusinessSpectrumReader(repo,files,json); var service=service(reader);
        var args=json.createObjectNode().put("include_file",true); args.set("ref",BusinessViews.ref("SPECTRUM",id(1)));
        var result=service.invoke("business_read",args);
        assertThat(Base64.getDecoder().decode(result.path("business_file").path("content_base64").asText())).isEqualTo(bytes);
        assertThat(result.toString()).contains("原始数值曲线", "/api/v1/spc/charts/", "/spectrum/view?chartId=");
        when(files.openChart(id(1))).thenAnswer(call->new SpectrumService.StoredChartFile(chart,new com.jsd.aird.ops.api.FileStorageFacade.StoredFile(id(3),"isolated.pdf","application/pdf",bytes.length,hash,new ByteArrayInputStream("tampered".getBytes(StandardCharsets.UTF_8)))));
        assertThatThrownBy(()->service.invoke("business_read",args)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.errorCode()).isEqualTo(ApiErrorCode.VERSION_CONFLICT));
    }

    @Test void unavailablePermissionIntegrationNeverReadsTheDomainRepository() {
        var customer=mock(BusinessPartnerRepository.class);
        var reader=new BusinessCustomerReader(customer,mock(PartnerContactRepository.class),mock(CommunicationRecordRepository.class),mock(CustomerRequirementRepository.class),json);
        var closed=new BusinessDataService(List.of(reader),List.of(),existing,json);
        assertThatThrownBy(()->read(closed,BusinessViews.ref("CUSTOMER",id(1)))).isInstanceOf(ApiException.class);
        verifyNoInteractions(customer);
    }
}
