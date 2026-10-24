package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.application.command.ProjectCommands;
import com.jsd.aird.mdm.application.port.ProjectRepository;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.shared.error.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProjectConsistencyTest {
    private final ProjectRepository repository = mock(ProjectRepository.class);
    private final ProjectService service = new ProjectService(repository);
    private final UUID id = UUID.randomUUID();

    @Test void missingOrNegativeVersionCannotBypassUpdateProtection() {
        for (Long version : new Long[]{null, -1L}) {
            assertCode(() -> service.update(id, command(version)), ApiErrorCode.VALIDATION_ERROR);
        }
        verifyNoInteractions(repository);
    }

    @Test void staleUpdateIsRejectedInsteadOfUsingTheServerVersion() {
        when(repository.findById(id)).thenReturn(Optional.of(project(13, ProjectStatus.IN_PROGRESS)));
        when(repository.update(any(), eq(12L), anyString())).thenReturn(false);
        assertCode(() -> service.update(id, command(12L)), ApiErrorCode.RESOURCE_CONFLICT);
        verify(repository).update(any(), eq(12L), anyString());
    }

    @Test void missingAndConcurrentlyDeletedUpdatesAreNotFound() {
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertCode(() -> service.update(id, command(12L)), ApiErrorCode.NOT_FOUND);
        when(repository.findById(id)).thenReturn(Optional.of(project(12, ProjectStatus.IN_PROGRESS)), Optional.empty());
        assertCode(() -> service.update(id, command(12L)), ApiErrorCode.NOT_FOUND);
    }

    @Test void staleDeleteCannotDeleteANewerProject() {
        when(repository.findById(id)).thenReturn(Optional.of(project(13, ProjectStatus.IN_PROGRESS)));
        assertCode(() -> service.delete(id, 12), ApiErrorCode.RESOURCE_CONFLICT);
        verify(repository, never()).softDelete(any(), anyLong(), anyString());
    }

    @Test void latestBusinessStateIsCheckedBeforeDeletion() {
        when(repository.findById(id)).thenReturn(Optional.of(project(12, ProjectStatus.COMPLETED)));
        assertCode(() -> service.delete(id, 12), ApiErrorCode.RESOURCE_CONFLICT);
        verify(repository, never()).softDelete(any(), anyLong(), anyString());
    }

    @Test void deleteStillUsesCompareAndSwapAfterTheInitialRead() {
        when(repository.findById(id)).thenReturn(Optional.of(project(12, ProjectStatus.IN_PROGRESS)), Optional.of(project(13, ProjectStatus.IN_PROGRESS)));
        when(repository.softDelete(id, 12, "system")).thenReturn(false);
        assertCode(() -> service.delete(id, 12), ApiErrorCode.RESOURCE_CONFLICT);
        verify(repository).softDelete(id, 12, "system");
    }

    @Test void missingAndConcurrentlyDeletedRowsReturnNotFound() {
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertCode(() -> service.delete(id, 12), ApiErrorCode.NOT_FOUND);
        when(repository.findById(id)).thenReturn(Optional.of(project(12, ProjectStatus.IN_PROGRESS)), Optional.empty());
        assertCode(() -> service.delete(id, 12), ApiErrorCode.NOT_FOUND);
    }

    @Test void freshDeleteSucceedsOnce() {
        when(repository.findById(id)).thenReturn(Optional.of(project(12, ProjectStatus.IN_PROGRESS)));
        when(repository.softDelete(id, 12, "system")).thenReturn(true);
        service.delete(id, 12);
        verify(repository, times(1)).softDelete(id, 12, "system");
    }

    private Project project(long version, ProjectStatus status) {
        return new Project(id,"P-1","项目",null,null,"owner",null,null,ProjectPriority.MEDIUM,status,1,null,null,null,version,Instant.now(),Instant.now());
    }
    private ProjectCommands.SaveProject command(Long version) {
        return new ProjectCommands.SaveProject("P-1","修改",null,null,"owner",null,null,ProjectPriority.MEDIUM,ProjectStatus.IN_PROGRESS,1,null,null,null,version);
    }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ApiErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.errorCode()).isEqualTo(code));
    }
}
