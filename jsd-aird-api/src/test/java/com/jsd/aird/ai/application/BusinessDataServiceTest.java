package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessDataServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final UUID org = id(100), otherOrg = id(200);
    private final Map<UUID,BusinessRecordView> records = new LinkedHashMap<>();
    private final Set<UUID> visible = new HashSet<>();
    private final BusinessExistingData existing = mock(BusinessExistingData.class);
    private BusinessDataService service;
    private BusinessReadPort reader;
    private BusinessDataAccess policy;
    private static UUID id(long n) { return new UUID(0, n); }
    private ObjectNode object(String value) throws Exception { return (ObjectNode)json.readTree(value); }
    @BeforeEach void setup() {
        ActorContext.set(new Actor(org, id(101), "isolated-acceptance"));
        reader = new BusinessReadPort() {
            public Set<String> resourceTypes() { return Set.of("CUSTOMER", "INVENTORY_BALANCE"); }
            public Set<String> filters(String type) { return Set.of("query", "scope"); }
            public Page references(String type, ObjectNode filter, int page, int size) {
                var selected = records.values().stream().filter(row -> type.equals(row.ref().path("type").asText())).toList();
                return new Page(selected.stream().skip((long)(page - 1) * size).limit(size).map(BusinessRecordView::ref).toList(), (long)page * size < selected.size());
            }
            public BusinessRecordView read(ObjectNode ref) { return records.get(BusinessViews.id(ref)); }
        };
        policy = new BusinessDataAccess() {
            public Set<String> resourceTypes() { return reader.resourceTypes(); }
            public boolean allowed(Actor actor, String type, UUID id, String operation, JsonNode context) {
                return org.equals(actor.organizationId()) && (id == null || visible.contains(id));
            }
        };
        service = new BusinessDataService(List.of(reader), List.of(policy), existing, json);
    }
    @AfterEach void clear() { ActorContext.clear(); }
    private BusinessRecordView add(int n, String type, String title, ObjectNode body) {
        var row = BusinessViews.view(type, id(n), null, title, body, "/inventory/query", List.of());
        records.put(id(n), row); visible.add(id(n)); return row;
    }
    @Test void permissionIntegrationIsClosedByDefault() throws Exception {
        var closed = new BusinessDataService(List.of(reader), List.of(), existing, json);
        assertThatThrownBy(() -> closed.invoke("business_search", object("{\"type\":\"CUSTOMER\"}")))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.DATA_SCOPE_DENIED));
        assertThat(closed.catalog().path("resource_types").toString()).contains("\"available\":false");
        verify(existing, never()).invoke(anyString(), any());
    }
    @Test void explicitLocalAccessConnectsTheRegisteredReaderWithoutReplacingDomainPolicies() throws Exception {
        ActorContext.set(new Actor(org, id(101), "local-admin", "SYSTEM_ADMIN"));
        var row = add(1, "CUSTOMER", "客户", object("{\"name\":\"客户\"}"));
        var local = new LocalBusinessAccess(List.of(reader));
        var connected = new BusinessDataService(List.of(reader), List.of(), existing, json);
        connected.setLocalAccess(local);
        assertThat(connected.invoke("business_search", object("{\"type\":\"CUSTOMER\"}")).path("total").asInt()).isEqualTo(1);
        assertThat(connected.invoke("business_read", json.createObjectNode().set("ref", row.ref())).path("candidates").size()).isEqualTo(1);
        assertThat(connected.allowed("CUSTOMER", id(1), "WRITE", row.ref())).isFalse();
        service.setLocalAccess(local);
        visible.clear();
        assertThat(service.allowed("CUSTOMER", id(1), "READ", row.ref())).isFalse();
        assertThatThrownBy(() -> service.invoke("business_read", json.createObjectNode().set("ref", row.ref()))).isInstanceOf(ApiException.class);
        ActorContext.clear();
        assertThatThrownBy(() -> connected.catalog()).isInstanceOf(RuntimeException.class);
    }
    @Test void authorizationPrecedesPaginationCountsAndAggregation() throws Exception {
        add(1, "INVENTORY_BALANCE", "授权零库存", object("{\"quantityKg\":0,\"scope\":\"RND\"}"));
        add(2, "INVENTORY_BALANCE", "另一组织秘密产品", object("{\"quantityKg\":99999,\"scope\":\"RND\"}")); visible.remove(id(2));
        add(3, "INVENTORY_BALANCE", "授权产品", object("{\"quantityKg\":1.25,\"scope\":\"RND\"}"));
        var page = service.invoke("business_search", object("{\"type\":\"INVENTORY_BALANCE\",\"limit\":1,\"offset\":1}"));
        assertThat(page.path("total").asInt()).isEqualTo(2);
        assertThat(page.path("candidates").path(0).path("text").asText()).contains("授权产品");
        assertThat(page.toString()).doesNotContain("秘密", "99999");
        var sum = service.invoke("business_query", object("{\"type\":\"INVENTORY_BALANCE\",\"metrics\":[{\"field\":\"quantityKg\",\"op\":\"sum\",\"as\":\"kg\"}]}"));
        assertThat(sum.path("rows").path(0).path("kg").decimalValue()).isEqualByComparingTo(new BigDecimal("1.25"));
        ActorContext.set(new Actor(otherOrg, id(201), "another-organization"));
        assertThatThrownBy(() -> service.invoke("business_read", json.createObjectNode().set("ref", records.get(id(1)).ref()))).isInstanceOf(ApiException.class);
    }
    @Test void missingZeroAndTextAreDifferentAndMixedScopesCannotBeSummed() throws Exception {
        add(1, "INVENTORY_BALANCE", "零", object("{\"quantityKg\":0,\"scope\":\"RND\"}"));
        add(2, "INVENTORY_BALANCE", "缺失", object("{\"quantityKg\":null,\"scope\":\"RND\"}"));
        var query = object("{\"type\":\"INVENTORY_BALANCE\",\"metrics\":[{\"field\":\"quantityKg\",\"op\":\"avg\"},{\"field\":\"quantityKg\",\"op\":\"count\"}]}");
        var result = service.invoke("business_query", query);
        assertThat(result.path("rows").path(0).path("avg_quantityKg").isNumber()).isTrue();
        assertThat(result.path("rows").path(0).path("count_quantityKg").asInt()).isEqualTo(1);
        add(3, "INVENTORY_BALANCE", "生产库存", object("{\"quantityKg\":2,\"scope\":\"PRODUCTION\"}"));
        assertThatThrownBy(() -> service.invoke("business_query", query)).hasMessageContaining("分组");
        query.putArray("group_by").add("scope");
        assertThat(service.invoke("business_query", query).path("rows").size()).isEqualTo(2);
        add(4, "INVENTORY_BALANCE", "未测", object("{\"quantityKg\":\"NOT_MEASURED\",\"scope\":\"RND\"}"));
        assertThatThrownBy(() -> service.invoke("business_query", query)).hasMessageContaining("不能当作数值");
    }
    @Test void datasetUsesOneReplayableEvidenceSnapshotAndKeepsNumericTypes() throws Exception {
        add(1, "INVENTORY_BALANCE", "产品", object("{\"quantityKg\":12.50,\"scope\":\"RND\",\"unknownNewField\":null}"));
        var args = object("{\"type\":\"INVENTORY_BALANCE\",\"output\":\"file\"}");
        var response = service.invoke("business_query", args);
        assertThat(response.path("candidates").size()).isEqualTo(1);
        byte[] bytes = Base64.getDecoder().decode(response.path("dataset").path("content_base64").asText());
        assertThat(BusinessViews.hash(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(response.path("dataset").path("sha256").asText());
        assertThat(json.readTree(bytes).path("rows").path(0).path("quantityKg").isNumber()).isTrue();
        var source = response.path("candidates").path(0).path("source");
        service.validateSource(source, "12.5");
        add(1, "INVENTORY_BALANCE", "产品", object("{\"quantityKg\":11,\"scope\":\"RND\",\"unknownNewField\":null}"));
        assertThatThrownBy(() -> service.validateSource(source, null)).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.VERSION_CONFLICT));
    }
    @Test void snapshotAndDownloadReauthorizeAfterRevocation() throws Exception {
        var row = add(1, "CUSTOMER", "隔离客户", object("{\"name\":\"隔离客户\",\"value\":0}"));
        var source = service.candidate(row).path("source"); service.validateSource(source, "隔离客户");
        visible.clear();
        assertThatThrownBy(() -> service.validateSource(source, "隔离客户")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.requireExport(source)).isInstanceOf(ApiException.class);
    }
    @Test void linksReadActualIdsAndHideUnauthorizedTargetsWithSameName() throws Exception {
        var parent = add(1, "CUSTOMER", "客户", object("{\"name\":\"客户\"}"));
        add(2, "INVENTORY_BALANCE", "同名材料", object("{\"quantityKg\":1}"));
        add(3, "INVENTORY_BALANCE", "同名材料", object("{\"quantityKg\":1000}")); visible.remove(id(3));
        records.put(id(1), new BusinessRecordView(parent.ref(), parent.title(), parent.facts(), List.of(
            BusinessViews.link("明确关联", "INVENTORY_BALANCE", id(2)), BusinessViews.link("明确关联", "INVENTORY_BALANCE", id(3))), parent.path()));
        var args = json.createObjectNode().put("relation", "明确关联"); args.set("ref", parent.ref());
        var linked = service.invoke("business_links", args);
        assertThat(linked.path("candidates").size()).isEqualTo(2);
        assertThat(linked.toString()).doesNotContain(id(3).toString(), "1000");
        assertThat(linked.path("candidates").path(1).path("ref").path("id").asText()).isEqualTo(id(2).toString());
    }
    @Test void unknownFiltersAndFieldsFailInsteadOfReturningAnUnrestrictedAnswer() throws Exception {
        add(1, "CUSTOMER", "客户", object("{\"value\":1}"));
        assertThatThrownBy(() -> service.invoke("business_search", object("{\"type\":\"CUSTOMER\",\"filters\":{\"made_up_scope\":\"all\"}}")))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getMessage()).contains("不支持");
                assertThat(json.valueToTree(error.detail()).path("rule").asText()).isEqualTo("unsupported_filter");
            });
        assertThatThrownBy(() -> service.invoke("business_query", object("{\"type\":\"CUSTOMER\",\"fields\":[\"nonexistent\"]}"))).hasMessageContaining("未知字段");
    }
    @Test void linkedExperimentReadsItsPinnedOverviewEvenWhenTheDirectoryHasZeroSamples() throws Exception {
        var project = BusinessViews.ref("PROJECT", id(80)).put("revision", "1");
        var experiment = BusinessViews.ref("EXPERIMENT", id(81)).put("revision", "fixed-version").put("version_id", "fixed-version");
        var origin = json.createObjectNode(); origin.putArray("candidates").addObject().set("ref", project);
        var directory = json.createObjectNode().put("next_offset", 20);
        directory.putArray("candidates").addObject().put("text", "sample_count=0").set("ref", experiment);
        var full = json.createObjectNode();
        full.putArray("candidates").addObject().put("text", "背景保留 166.8 g + 139.0 g + 9.0 g；本次未实测").set("ref", experiment);
        when(existing.available("EXPERIMENT")).thenReturn(true);
        when(existing.invoke(eq("business_search"), any())).thenReturn(directory);
        when(existing.invoke(eq("business_read"), any())).thenAnswer(call ->
            call.getArgument(1, ObjectNode.class).path("ref").path("type").asText().equals("PROJECT") ? origin : full);
        var args = json.createObjectNode().put("relation", "实验"); args.set("ref", project);
        var result = service.invoke("business_links", args);
        assertThat(result.path("candidates").path(1).path("text").asText()).contains("166.8 g", "本次未实测");
        assertThat(result.path("candidates").path(1).path("ref")).isEqualTo(experiment);
        assertThat(result.path("next_offset").asInt()).isEqualTo(20);
        doThrow(new ApiException(ApiErrorCode.DATA_SCOPE_DENIED)).when(existing)
            .invoke(eq("business_read"), argThat(query -> query != null && query.path("ref").equals(experiment)));
        assertThatThrownBy(() -> service.invoke("business_links", args)).isInstanceOf(ApiException.class);
    }
    @Test void displayKeepsBusinessCountsButDoesNotTurnProjectDocumentsIntoFormulaMaterials() throws Exception {
        var facts = object("{\"materialCount\":0,\"experimentCount\":1,\"status\":\"NOT_STARTED\",\"organizationId\":\"internal-org\",\"futureBusinessField\":\"保留新业务值\"}");
        String evidence = BusinessViews.markdown("项目", facts);
        String readable = BusinessViews.modelMarkdown("项目", facts);
        assertThat(readable).contains("关联资料数: 0", "关联实验数: 1", "未开始", "不是配方物料数", "保留新业务值")
            .doesNotContain("materialCount", "NOT_STARTED", "organizationId", "internal-org");
        assertThat(BusinessViews.markdown("项目", facts)).isEqualTo(evidence);
        assertThat(facts.path("materialCount").asInt()).isZero();
    }
    @Test void batchRelationsUseRecordedVersionAndRecheckUpstreamAccess() throws Exception {
        var ref=BusinessViews.ref("EXPERIMENT",id(81)).put("version_id",id(82).toString());
        var origin=json.createObjectNode();origin.putArray("candidates").addObject().set("ref",ref);
        var batches=origin.putArray("batch_sources");
        batches.addObject().put("sourceExperimentId",id(83).toString()).put("sourceVersionId",id(84).toString()).put("sourceSampleKey","upstream-a").put("targetColumn",2);
        batches.addObject().put("sourceExperimentId",id(83).toString()).put("sourceVersionId",id(84).toString()).put("sourceSampleKey","upstream-b").put("targetColumn",3);
        when(existing.invoke(eq("business_read"),any())).thenAnswer(call->{
            var target=call.getArgument(1,ObjectNode.class).path("ref");
            if(target.path("id").asText().equals(id(81).toString()))return origin;
            assertThat(target.path("version_id").asText()).isEqualTo(id(84).toString());
            var found=json.createObjectNode();found.putArray("candidates").addObject().put("text","上游批次正文");return found;
        });
        var args=json.createObjectNode().put("relation","来源实验");args.set("ref",ref);
        var result=service.invoke("business_links",args);
        assertThat(result.path("candidates")).hasSize(2);
        assertThat(result.path("candidates").path(1).path("sample_associations")).hasSize(2);
        doThrow(new ApiException(ApiErrorCode.DATA_SCOPE_DENIED)).when(existing).invoke(eq("business_read"),argThat(a->a!=null&&a.path("ref").path("id").asText().equals(id(83).toString())));
        assertThat(service.invoke("business_links",args).path("candidates")).hasSize(1);
    }
    @Test void markdownPreservesUnknownFieldsEscapesTablesAndRemovesEditorProtocol() throws Exception {
        var facts = BusinessViews.clean(object("{\"zero\":0,\"missing\":null,\"newField\":\"未测\",\"allowedActions\":[\"DELETE\"],\"workbookSnapshot\":{},\"rows\":[{\"sample\":\"A|B\",\"value\":0}]}"));
        String text = BusinessViews.markdown("隔离样本", facts);
        assertThat(text).contains("zero: 0", "missing: 缺失", "newField: 未测", "A\\|B").doesNotContain("allowedActions", "workbookSnapshot", "DELETE");
    }
}
