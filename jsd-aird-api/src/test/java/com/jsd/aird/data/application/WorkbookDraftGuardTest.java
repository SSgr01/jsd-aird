package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkbookDraftGuardTest {
    final ObjectMapper json=new ObjectMapper();
    final DataRepository repository=mock(DataRepository.class);
    final DataImportService service=new DataImportService(repository,mock(FileObjectRepository.class),mock(TemplateDataImportFacade.class),json,mock(AuditLogFacade.class),mock(OpsAsyncFacade.class));
    final UUID org=UUID.randomUUID(),id=UUID.randomUUID();
    DataRepository.Job job(){return new DataRepository.Job(id,UUID.randomUUID(),"hash","fixture.xlsx","XLSX",UUID.randomUUID(),null,"WAITING_CONFIRM",100,"WAITING_CONFIRM","test",null,Instant.parse("2026-10-25T00:00:00Z"),Instant.parse("2026-10-25T00:00:00Z"));}
    void actor(){ActorContext.set(new Actor(org,UUID.randomUUID(),"tester"));}
    @AfterEach void clear(){ActorContext.clear();}
    @Test void staleRevisionNeverWritesOrReparsesTheWorkbook(){
        actor();when(repository.findJobForUpdate(org,id)).thenReturn(Optional.of(job()));
        assertThatThrownBy(()->service.saveWorkbookDraft(id,"stale",json.createObjectNode(),json.createObjectNode(),json.createArrayNode())).isInstanceOf(ApiException.class);
        verify(repository,never()).saveWorkbookDraft(any(),any(),any(),any(),any(),any());
        verify(repository,never()).clearParsed(any(),any());
    }
    @Test void reviewLockPreventsSavingTheStructureAndValuesTogether(){
        actor();var job=job();when(repository.findJobForUpdate(org,id)).thenReturn(Optional.of(job));
        var guard=mock(DataReviewGuard.class);service.setReviewGuard(guard);
        doThrow(new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"审核期间不能修改")).when(guard).beforeEdit(org,id);
        assertThatThrownBy(()->service.saveWorkbookDraft(id,job.getRevision(),json.createObjectNode(),json.createObjectNode(),json.createArrayNode())).isInstanceOf(ApiException.class).hasMessageContaining("审核期间");
        verify(repository,never()).saveWorkbookDraft(any(),any(),any(),any(),any(),any());
    }
    @Test void experimentRouteCannotEditADataCenterOwnedJob(){
        actor();when(repository.findSourceOwner(org,id)).thenReturn(Optional.of("DATA_CENTER"));
        assertThatThrownBy(()->service.saveExperimentWorkbookDraft(id,"revision",json.createObjectNode(),json.createObjectNode(),json.createArrayNode())).isInstanceOf(ApiException.class);
        verify(repository,never()).saveWorkbookDraft(any(),any(),any(),any(),any(),any());
    }
}
