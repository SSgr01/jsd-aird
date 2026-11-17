package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.adapter.in.web.BusinessSourceController;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.ops.api.FileStorageFacade;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.*;
import com.jsd.aird.spc.application.*;
import com.jsd.aird.spc.application.port.SpectrumRepository;
import java.awt.Color;
import java.io.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpectrumWorkbenchIntegrationTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final UUID org = UUID.randomUUID(), chartId = UUID.randomUUID(), categoryId = UUID.randomUUID();
    private SpectrumRepository repo;
    private SpectrumService spectra;
    private BusinessDataService data;
    private SpectrumRepository.ChartRow chart;
    private byte[] original;
    private boolean allowed;

    @BeforeEach void setup() throws Exception {
        ActorContext.set(new Actor(org, UUID.randomUUID(), "spectrum-native-check")); allowed = true;
        repo = mock(SpectrumRepository.class); spectra = mock(SpectrumService.class);
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            for (var color : List.of(Color.RED, Color.BLUE)) {
                var page = new PDPage(new PDRectangle(300, 200)); pdf.addPage(page);
                try (var content = new PDPageContentStream(pdf, page)) {
                    content.setNonStrokingColor(color); content.addRect(0, 0, 300, 200); content.fill();
                }
            }
            pdf.save(bytes); original = bytes.toByteArray();
        }
        chart = row("样品A", BusinessViews.hash(original));
        when(repo.findChart(org, chartId)).thenAnswer(call -> Optional.of(chart));
        when(spectra.requireCategory(org, categoryId)).thenReturn(new SpectrumRepository.CategoryRow(categoryId,org,"IR","红外","","观察波数与峰形","[]",0,false,1));
        when(spectra.openChart(chartId)).thenAnswer(call -> new SpectrumService.StoredChartFile(chart,
            new FileStorageFacade.StoredFile(chart.fileObjectId(),"native.pdf","application/pdf",original.length,chart.sha256(),new ByteArrayInputStream(original))));
        var reader = new BusinessSpectrumReader(repo, spectra, json, new SpectrumPageRenderer(spectra));
        var access = new BusinessDataAccess() {
            public Set<String> resourceTypes() { return Set.of("SPECTRUM"); }
            public boolean allowed(Actor actor,String type,UUID id,String operation,com.fasterxml.jackson.databind.JsonNode context) { return allowed && org.equals(actor.organizationId()) && !"DOWNLOAD".equals(operation); }
        };
        data = new BusinessDataService(List.of(reader), List.of(access), mock(BusinessExistingData.class), json);
    }
    @AfterEach void clear() { ActorContext.clear(); }
    private SpectrumRepository.ChartRow row(String title, String sha) {
        return new SpectrumRepository.ChartRow(chartId,org,categoryId,"IR","红外",UUID.nameUUIDFromBytes("original".getBytes()),title,"native.pdf","application/pdf",original.length,sha,"A","B01","25°C","{\"concentration\":0}",2,"READY",Instant.EPOCH,Instant.EPOCH);
    }
    private ObjectNode ref() { return data.read(BusinessViews.ref("SPECTRUM",chartId)).ref(); }
    private ObjectNode readPage(ObjectNode ref, int page) {
        var args = json.createObjectNode().put("section","page").put("page",page).put("include_file",true); args.set("ref",ref);
        return data.invoke("business_read",args);
    }
    @Test void selectedOriginalPageReachesTheNativeImageBridgeWithItsOwnCitation() throws Exception {
        var reference = ref(); var result = readPage(reference,2);
        var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(result.path("business_file").path("content_base64").asText())));
        assertThat(new Color(image.getRGB(20,20))).isEqualTo(Color.BLUE);
        var candidate = result.path("candidates").path(0); var source = candidate.path("source");
        assertThat(source.path("anchor").path("page_no").asInt()).isEqualTo(2);
        assertThat(source.path("anchor").path("read_path").asText()).contains("/images/SPECTRUM/", "&page=2#page=2");
        assertThat(candidate.path("text").asText()).contains("样品A", "B01", "25°C", "观察波数与峰形", "concentration");
        data.validateSource(source, null);
        var controller = new BusinessSourceController(data,mock(BusinessComputeService.class),mock(AuthorizationService.class),json);
        var response = controller.image("SPECTRUM",chartId,reference.toString(),2);
        assertThat(response.getBody()).isEqualTo(Base64.getDecoder().decode(result.path("business_file").path("content_base64").asText()));
        assertThat(response.getHeaders().getFirst("X-Content-SHA256")).isEqualTo(result.path("business_file").path("sha256").asText());
        assertThatThrownBy(() -> controller.image("SPECTRUM",UUID.randomUUID(),reference.toString(),2)).isInstanceOf(ApiException.class);
    }
    @Test void invalidPagesChangedVersionsAndRevokedAccessNeverPublishAnImage() {
        var reference = ref();
        assertThatThrownBy(() -> readPage(reference,0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> readPage(reference,3)).isInstanceOf(ApiException.class);
        var pageSource = readPage(reference,1).path("candidates").path(0).path("source");
        chart = row("更新后的样品", chart.sha256());
        assertThatThrownBy(() -> readPage(reference,1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> data.validateSource(pageSource,null)).isInstanceOf(ApiException.class);
        var updated = ref(); original = "%PDF tampered".getBytes();
        assertThatThrownBy(() -> readPage(updated,1)).isInstanceOf(ApiException.class);
        allowed = false;
        assertThatThrownBy(() -> data.imagePageFile(updated,1)).isInstanceOf(ApiException.class);
    }
    @Test void workbenchContextPinsChosenPagesAndOriginalHash() {
        var host = new AgentHostService(mock(LiteratureResearchService.class),mock(com.jsd.aird.kb.api.ResearchKnowledgeFacade.class),
            mock(com.jsd.aird.mdm.api.AgentProjectFacade.class),mock(AgentSourceAuthorization.class),mock(AuthorizationService.class),json,mock(PrivateResearchSourceService.class));
        @SuppressWarnings("unchecked") var provider = (ObjectProvider<BusinessDataService>)mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(data); ReflectionTestUtils.setField(host,"businessData",provider);
        var selected = json.createObjectNode().put("kind","SPECTRUM").put("resource_id",chartId.toString()).put("version_id",chart.sha256());
        selected.putArray("pages").add(2); var request=json.createObjectNode(); request.set("ref",selected);
        var result = host.contextResource(request);
        assertThat(result.path("candidates").size()).isEqualTo(1);
        assertThat(result.path("ref")).isEqualTo(selected);
        assertThat(result.path("title").asText()).contains("第 2 页");
        assertThat(result.path("spectrum").path("sampleName").asText()).isEqualTo("A");
        assertThat(result.path("spectrum").path("testConditions").asText()).isEqualTo("25°C");
        assertThat(result.path("spectrum").has("fileObjectId")).isFalse();
        assertThat(result.path("spectrum").has("page_read_hint")).isFalse();
        selected.put("version_id","0".repeat(64));
        assertThatThrownBy(() -> host.contextResource(request)).isInstanceOf(ApiException.class);
    }
}
