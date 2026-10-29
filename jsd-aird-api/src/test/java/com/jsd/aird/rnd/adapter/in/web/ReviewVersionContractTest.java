package com.jsd.aird.rnd.adapter.in.web;

import com.jsd.aird.platform.web.GlobalExceptionHandler;
import com.jsd.aird.rnd.application.*;
import com.jsd.aird.tpl.adapter.in.web.TemplateController;
import com.jsd.aird.tpl.application.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReviewVersionContractTest {
    @Test void reportApprovalAndRevisionRequireNonNegativeVersions() throws Exception {
        var service=mock(ResearchTestService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ResearchTestController(service,mock(ResearchTestExportService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for(String endpoint:new String[]{"approve","submit-review","return","archive","versions"}){
            for(String body:new String[]{"{}","{\"revision\":-1,\"reason\":\"r\"}"})
                mvc.perform(post("/api/v1/comprehensive-reports/"+UUID.randomUUID()+"/"+endpoint)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnprocessableEntity());
        }
        verifyNoInteractions(service);
    }
    @Test void templateApprovalRequiresBothVersions() throws Exception {
        var review=mock(TemplateVersionReviewService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new TemplateController(mock(TemplateWorkspaceService.class),
                mock(TemplateRecognitionReviewService.class),mock(TemplateOfficeExportService.class),review))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for(String endpoint:new String[]{"approve","reject","submit"}){
            for(String body:new String[]{"{}","{\"workspaceVersion\":12,\"reason\":\"r\"}","{\"workspaceVersion\":12,\"reviewVersion\":-1,\"reason\":\"r\"}"})
                mvc.perform(post("/api/v1/template-versions/"+UUID.randomUUID()+"/review/"+endpoint)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnprocessableEntity());
        }
        verifyNoInteractions(review);
    }
}
