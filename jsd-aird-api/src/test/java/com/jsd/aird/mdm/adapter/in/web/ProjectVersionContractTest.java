package com.jsd.aird.mdm.adapter.in.web;

import com.jsd.aird.mdm.application.service.*;
import com.jsd.aird.platform.web.GlobalExceptionHandler;
import com.jsd.aird.tpl.api.ProjectTemplateFacade;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectVersionContractTest {
    private final ProjectService service = mock(ProjectService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProjectController(service,
            mock(ProjectImportService.class), mock(ProjectAuditLogService.class), mock(ProjectTemplateFacade.class),
            mock(CustomerRequirementService.class))).setControllerAdvice(new GlobalExceptionHandler()).build();
    private final UUID id=UUID.randomUUID();

    @Test void deleteRequiresAVersionAndDoesNotRunWithoutIt() throws Exception {
        mvc.perform(delete("/api/v1/projects/"+id)).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(service);
    }
    @Test void negativeOrMalformedDeleteVersionDoesNotRun() throws Exception {
        mvc.perform(delete("/api/v1/projects/"+id).param("version","-1")).andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/api/v1/projects/"+id).param("version","bad")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void versionZeroIsAValidExpectedVersion() throws Exception {
        mvc.perform(delete("/api/v1/projects/"+id).param("version","0")).andExpect(status().isOk());
        verify(service).delete(id,0);
    }
    @Test void negativeUpdateVersionIsRejectedByBodyValidation() throws Exception {
        mvc.perform(put("/api/v1/projects/"+id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"项目\",\"projectCode\":\"P-1\",\"version\":-1}"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(service);
    }
}
