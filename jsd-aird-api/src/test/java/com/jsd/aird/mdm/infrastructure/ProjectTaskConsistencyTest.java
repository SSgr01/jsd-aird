package com.jsd.aird.mdm.infrastructure;

import com.jsd.aird.mdm.application.service.ProjectWorkService;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.mdm.infrastructure.persistence.ProjectWorkMapper;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProjectTaskConsistencyTest {
    @AfterEach void clearActor() { ActorContext.clear(); }
    @Test void missingOrNegativeTaskVersionCannotAdoptTheCurrentVersion() {
        var mapper=mock(ProjectWorkMapper.class);var service=new ProjectWorkService(mapper);
        for(Long version:new Long[]{null,-1L})assertThatThrownBy(()->service.updateTask(UUID.randomUUID(),new ProjectWorkService.TaskInput(UUID.randomUUID(),"edit",null,null,null,null,null,version)))
                .isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).errorCode()).isEqualTo(ApiErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(mapper);
    }
    @Test void failedTaskCasCannotMoveAnyChildRecords() {
        ActorContext.set(ActorContext.developmentDefault());
        var mapper=mock(ProjectWorkMapper.class);var service=new ProjectWorkService(mapper);var id=UUID.randomUUID();var project=UUID.randomUUID();var target=UUID.randomUUID();
        when(mapper.task(id)).thenReturn(Optional.of(new ProjectTask(id,"TASK",project,UUID.randomUUID(),"original",null,ProjectPriority.MEDIUM,null,null,"PENDING",0,1,Instant.EPOCH,Instant.EPOCH)));
        when(mapper.stageBelongs(target,project)).thenReturn(true);
        assertThatThrownBy(()->service.updateTask(id,new ProjectWorkService.TaskInput(target,"stale",null,null,null,null,null,0L))).isInstanceOf(ApiException.class);
        verify(mapper).updateTask(argThat(task->task.version()==0), eq(ActorContext.required().userId().toString()));
        verify(mapper,never()).moveTaskExperiments(any(),any());
        verify(mapper,never()).moveTaskProductionUploads(any(),any());
        verify(mapper,never()).moveTaskResourceLinks(any(),any());
    }
    @Test void taskDescriptionIsSavedOnCreateAndUpdate() {
        ActorContext.set(ActorContext.developmentDefault());
        var mapper=mock(ProjectWorkMapper.class);var service=new ProjectWorkService(mapper);
        var project=UUID.randomUUID();var stage=UUID.randomUUID();
        when(mapper.stageBelongs(stage,project)).thenReturn(true);
        var created=service.createTask(project,new ProjectWorkService.TaskInput(stage,"任务",null,null,null,null,null,null,"  创建说明  "));
        assertThat(created.description()).isEqualTo("创建说明");
        verify(mapper).insertTask(argThat(task->"创建说明".equals(task.description())),anyString());

        when(mapper.task(created.id())).thenReturn(Optional.of(created));
        when(mapper.updateTask(any(),anyString())).thenReturn(1);
        service.updateTask(created.id(),new ProjectWorkService.TaskInput(stage,"任务",null,null,null,null,null,0L,"  编辑说明  "));
        verify(mapper).updateTask(argThat(task->"编辑说明".equals(task.description())),anyString());
    }
    @Test void selectedOwnerIsResolvedFromTheAllowedDepartmentTree() {
        ActorContext.set(ActorContext.developmentDefault());
        var mapper=mock(ProjectWorkMapper.class);var service=new ProjectWorkService(mapper);
        var project=UUID.randomUUID();var stage=UUID.randomUUID();var owner=UUID.randomUUID();
        when(mapper.stageBelongs(stage,project)).thenReturn(true);
        when(mapper.selectableOwnerName(ActorContext.required().organizationId(),ActorContext.required().userId(),owner))
            .thenReturn(Optional.of("实际姓名"));
        var task=service.createTask(project,new ProjectWorkService.TaskInput(stage,"任务","伪造姓名",null,null,null,null,null,null,owner));
        assertThat(task.owner()).isEqualTo("实际姓名");
        assertThat(task.ownerUserId()).isEqualTo(owner);

        var other=UUID.randomUUID();
        assertThatThrownBy(()->service.createTask(project,new ProjectWorkService.TaskInput(stage,"任务","其他人",null,null,null,null,null,null,other)))
            .isInstanceOf(ApiException.class);
        verify(mapper,times(1)).insertTask(any(),anyString());
    }
}
