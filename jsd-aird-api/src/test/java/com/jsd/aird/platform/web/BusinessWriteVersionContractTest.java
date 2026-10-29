package com.jsd.aird.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.adapter.in.web.DataPublicationController;
import com.jsd.aird.data.application.DataPublicationService;
import com.jsd.aird.kb.adapter.in.web.KnowledgeController;
import com.jsd.aird.kb.application.KnowledgeService;
import com.jsd.aird.kb.application.KnowledgeUploadTaskService;
import com.jsd.aird.mfg.adapter.in.web.ProductionOrderController;
import com.jsd.aird.mfg.application.*;
import com.jsd.aird.quality.adapter.in.web.QualityDataController;
import com.jsd.aird.quality.application.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BusinessWriteVersionContractTest {
    private static final String ID=UUID.randomUUID().toString();
    private MockMvc mvc(Object controller){return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules())).build();}
    @Test void productionSubmitCancelAndDeleteRequireAnExplicitNonNegativeVersion() throws Exception {
        var service=mock(ProductionOrderService.class);
        var mvc=mvc(new ProductionOrderController(service,mock(ProductionOfficeExportService.class)));
        for(String action:new String[]{"submit","cancel"}){
            mvc.perform(post("/api/v1/production-orders/"+ID+"/"+action).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnprocessableEntity());
            mvc.perform(post("/api/v1/production-orders/"+ID+"/"+action).contentType(MediaType.APPLICATION_JSON).content("{\"lockVersion\":-1}")).andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(delete("/api/v1/production-orders/"+ID)).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(service);
    }
    @Test void qualityDeletionPublicationAndLegacyBatchDeletesCannotOmitVersions() throws Exception {
        var service=mock(QualityDataService.class);var mvc=mvc(new QualityDataController(service,mock(QualityDataExportService.class)));
        mvc.perform(delete("/api/v1/quality/records/"+ID)).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/quality/records/"+ID+"/publish").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/quality/records/batch").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"coa\",\"categoryId\":\""+ID+"\",\"records\":[],\"deleteIds\":[\""+ID+"\"]}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/quality/records/delete").contentType(MediaType.APPLICATION_JSON).content("{\"records\":[{\"id\":\""+ID+"\"}]}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/quality/records/delete").contentType(MediaType.APPLICATION_JSON).content("{\"records\":[null]}")).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(service);
    }
    @Test void publicationStateActionsRequireTheRevisionSeenByTheUser() throws Exception {
        var service=mock(DataPublicationService.class);var mvc=mvc(new DataPublicationController(service));
        for(String action:new String[]{"confirm","withdraw","publish","disable","restore","revise"})mvc.perform(post("/api/v1/data/sources/"+ID+"/publication/"+action).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(service);
    }
    @Test void sourceMetadataAndCorrectionsRejectMissingClientRevisions() throws Exception {
        var service=mock(com.jsd.aird.data.application.DataImportService.class);
        var mvc=mvc(new com.jsd.aird.data.adapter.in.web.DataController(service,null,null,null,null,null));
        mvc.perform(put("/api/v1/data/import-jobs/"+ID+"/records/"+ID+"/values").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bindingId\":\"field\",\"valuePath\":\"\",\"correctedValue\":3,\"reason\":\"test\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/data/import-jobs/"+ID+"/mappings").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/api/v1/data/import-jobs/"+ID)).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(service);
    }
    @Test void projectRelationsRequireTheOriginalRelationSet() throws Exception {
        var service=mock(com.jsd.aird.core.api.ProjectResourceFacade.class);
        var mvc=mvc(new com.jsd.aird.core.adapter.in.web.ProjectResourceController(service,mock(com.jsd.aird.iam.api.AuthorizationService.class)));
        mvc.perform(put("/api/v1/project-resource-links/KNOWLEDGE_DOCUMENT/"+ID).contentType(MediaType.APPLICATION_JSON).content("{\"targets\":[]}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/project-resource-links/KNOWLEDGE_DOCUMENT/"+ID).contentType(MediaType.APPLICATION_JSON).content("{\"targets\":[],\"expectedTargets\":[null]}"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(service);
    }
    @Test void spectrumMetadataMovesAndDeletesCannotOmitTheOriginalRevision() throws Exception {
        var service=mock(com.jsd.aird.spc.application.SpectrumService.class);
        var mvc=mvc(new com.jsd.aird.spc.adapter.in.web.SpectrumController(service));
        mvc.perform(put("/api/v1/spc/categories/"+ID).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"new category\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/spc/charts/"+ID).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"new title\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/spc/charts/"+ID+"/category").contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":\""+ID+"\"}")).andExpect(status().isUnprocessableEntity());
        for(String resource:new String[]{"categories","charts"})mvc.perform(delete("/api/v1/spc/"+resource+"/"+ID)).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(service);
    }
    @Test void modelReleaseActionsRejectMissingNullAndNegativeRevisionsWithoutTreatingThemAsZero() throws Exception {
        var service=mock(com.jsd.aird.ai.rnd.modeling.ModelRegistryService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new com.jsd.aird.ai.rnd.modeling.ModelRegistryController(service))
                .setControllerAdvice(new com.jsd.aird.ai.rnd.api.AiRndExceptionHandler(),new GlobalExceptionHandler()).build();
        for(String action:new String[]{"pause","rollback"})for(String body:new String[]{"{\"reason\":\"test\"}","{\"expectedRevision\":null,\"reason\":\"test\"}","{\"expectedRevision\":-1,\"reason\":\"test\"}","{\"expectedRevision\":0,\"reason\":\" \"}"}) {
            mvc.perform(post("/api/v1/ai/rnd/models/"+ID+"/"+action).header("Idempotency-Key","test-key").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));
        }
        verifyNoInteractions(service);
    }
    @Test void modelReleaseRevisionZeroIsStillAnExplicitValidVersion() throws Exception {
        var service=mock(com.jsd.aird.ai.rnd.modeling.ModelRegistryService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new com.jsd.aird.ai.rnd.modeling.ModelRegistryController(service))
                .setControllerAdvice(new com.jsd.aird.ai.rnd.api.AiRndExceptionHandler(),new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/v1/ai/rnd/models/"+ID+"/pause").header("Idempotency-Key","test-key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":0,\"expectedActiveModelVersionId\":\""+ID+"\",\"reason\":\"test\"}"))
                .andExpect(status().isOk());
        verify(service).pause(UUID.fromString(ID),new com.jsd.aird.ai.rnd.modeling.ModelRegistryContracts.ModelActionCommand(0,UUID.fromString(ID),"test"),"test-key");
    }
}
