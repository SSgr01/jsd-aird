package com.jsd.aird.mdm.infrastructure;

import com.jsd.aird.mdm.application.service.ProjectWorkService;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.mdm.infrastructure.persistence.ProjectWorkMapper;
import com.jsd.aird.shared.error.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProjectTaskConsistencyTest {
    @Test void missingOrNegativeTaskVersionCannotAdoptTheCurrentVersion() {
        var mapper=mock(ProjectWorkMapper.class);var service=new ProjectWorkService(mapper);
        for(Long version:new Long[]{null,-1L})assertThatThrownBy(()->service.updateTask(UUID.randomUUID(),new ProjectWorkService.TaskInput(UUID.randomUUID(),"edit",null,null,null,null,version)))
                .isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).errorCode()).isEqualTo(ApiErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(mapper);
    }
    @Test void failedTaskCasCannotMoveAnyChildRecords() {
        var mapper=mock(ProjectWorkMapper.class);var service=new ProjectWorkService(mapper);var id=UUID.randomUUID();var project=UUID.randomUUID();var target=UUID.randomUUID();
        when(mapper.task(id)).thenReturn(Optional.of(new ProjectTask(id,"TASK",project,UUID.randomUUID(),"original",null,ProjectPriority.MEDIUM,null,"PENDING",0,1,Instant.EPOCH,Instant.EPOCH)));
        when(mapper.stageBelongs(target,project)).thenReturn(true);
        assertThatThrownBy(()->service.updateTask(id,new ProjectWorkService.TaskInput(target,"stale",null,null,null,null,0L))).isInstanceOf(ApiException.class);
        verify(mapper).updateTask(argThat(task->task.version()==0));
        verify(mapper,never()).moveTaskExperiments(any(),any());
        verify(mapper,never()).moveTaskProductionUploads(any(),any());
        verify(mapper,never()).moveTaskResourceLinks(any(),any());
    }
}
