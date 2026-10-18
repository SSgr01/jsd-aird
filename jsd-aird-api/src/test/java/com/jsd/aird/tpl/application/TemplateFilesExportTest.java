package com.jsd.aird.tpl.application;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionDecision;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.application.port.WordOoxmlPatcher;
import com.jsd.aird.tpl.domain.TemplateFormat;
import com.jsd.aird.tpl.domain.TemplateStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TemplateFilesExportTest {
    @Test void exportsFilesAndChecksEachTemplatePermission() throws Exception {
        var org = UUID.randomUUID(); var user = UUID.randomUUID(); var version = UUID.randomUUID(); var template = UUID.randomUUID();
        var repository = mock(TemplateRepository.class); var authorization = mock(AuthorizationService.class);
        var workspace = mock(TemplateRepository.TemplateWorkspace.class);
        when(workspace.templateId()).thenReturn(template); when(workspace.format()).thenReturn(TemplateFormat.XLSX); when(workspace.status()).thenReturn(TemplateStatus.DRAFT);
        when(repository.findWorkspace(org, version)).thenReturn(Optional.of(workspace));
        when(authorization.check(any())).thenReturn(new PermissionDecision(true, "template.export", "ALLOW", "ALL", "ROLE"));
        var mapper = new ObjectMapper();
        var service = spy(new TemplateOfficeExportService(repository, mock(FileObjectRepository.class), mock(ObjectStorage.class), mapper,
                new SnapshotWorkbookExporter(mapper), mock(WordOoxmlPatcher.class), authorization));
        doReturn(new TemplateOfficeExportService.Download("光学膜模板.xlsx", "application/octet-stream", new byte[]{1, 2, 3}, List.of()))
                .when(service).export(version, "XLSX", "DRAFT");
        ActorContext.set(new Actor(org, user, "tester"));
        try {
            try (var zip = new ZipInputStream(new ByteArrayInputStream(service.exportFiles(List.of(version))))) {
                assertThat(zip.getNextEntry().getName()).isEqualTo("光学膜模板.xlsx");
                assertThat(zip.readAllBytes()).isEqualTo(new byte[]{1, 2, 3});
                assertThat(zip.getNextEntry()).isNull();
            }
            when(authorization.check(any())).thenReturn(PermissionDecision.denied("template.export"));
            assertThatThrownBy(() -> service.exportFiles(List.of(version))).isInstanceOf(ApiException.class);
        } finally { ActorContext.clear(); }
    }
}
