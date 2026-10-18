package com.jsd.aird.kb.application;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.core.api.ProjectResourceFacade;
import com.jsd.aird.core.api.ProjectResourceFacade.ResourceType;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.domain.FileSafetyScanner;
import com.jsd.aird.kb.domain.LexicalAnalyzer;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeDocumentFilesTest {
    private final UUID org = UUID.randomUUID(), user = UUID.randomUUID(), id = UUID.randomUUID(), versionId = UUID.randomUUID();
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final KnowledgeGovernanceRepository governance = mock(KnowledgeGovernanceRepository.class);
    private final FileStorageFacade storage = mock(FileStorageFacade.class);
    private final ProjectResourceFacade projectResources = mock(ProjectResourceFacade.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private KnowledgeService service() {
        ActorContext.set(new Actor(org, user, "tester"));
        return new KnowledgeService(repository, governance, storage, mock(OpsAsyncFacade.class), mock(AuditLogFacade.class),
                mapper, new StructuredDocumentCodec(mapper), List.of(), mock(FileSafetyScanner.class),
                mock(ObjectProvider.class), List.of(), "embedding", 1024, Duration.ofMinutes(15),
                new BlockAwareChunker(mapper), mock(LexicalAnalyzer.class), new SystemParsingPolicy("AUTO", false),
                Runnable::run, projectResources);
    }

    @AfterEach void clearActor() { ActorContext.clear(); }

    @Test void exportsOnlyOriginalFilesAndPreservesNotFoundErrors() throws Exception {
        var doc = mock(KnowledgeRepository.DocumentRow.class);
        var version = mock(KnowledgeRepository.VersionRow.class);
        var fileId = UUID.randomUUID();
        when(doc.currentVersionId()).thenReturn(versionId);
        when(repository.findDocument(org, id)).thenReturn(Optional.of(doc));
        when(repository.findVersion(org, versionId)).thenReturn(Optional.of(version));
        when(version.originalName()).thenReturn("树脂检测.pdf"); when(version.fileObjectId()).thenReturn(fileId);
        when(storage.open(org, fileId)).thenReturn(new FileStorageFacade.StoredFile(fileId, "树脂检测.pdf", "application/pdf", 5, "hash", new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8))));
        var service = service();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(service.exportDocuments(List.of(id))))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("树脂检测.pdf");
            assertThat(zip.readAllBytes()).isEqualTo("bytes".getBytes(StandardCharsets.UTF_8));
            assertThat(zip.getNextEntry()).isNull();
        }
        when(repository.findDocument(org, id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.exportDocuments(List.of(id))).isInstanceOf(ApiException.class);
    }

    @Test void editsMetadataAndNormalizesTagsWithoutChangingTheFile() {
        var doc = mock(KnowledgeRepository.DocumentRow.class); var categoryId = UUID.randomUUID();
        when(doc.id()).thenReturn(id); when(doc.organizationId()).thenReturn(org); when(doc.currentVersionId()).thenReturn(versionId);
        when(repository.findDocument(org, id)).thenReturn(Optional.of(doc));
        when(repository.findCategory(org, categoryId)).thenReturn(Optional.of(new KnowledgeRepository.CategoryRow(categoryId, "EXTERNAL", "供应商资料", null, 0, 1)));
        var source = mapper.createObjectNode().put("description", "供应商检测资料").put("visibility", "项目组可见");
        service().updateDocument(id, new KnowledgeService.UpdateDocumentCommand(" 新名称 ", "EXTERNAL", categoryId, List.of(" UV ", "uv", "耐磨"), source, null));
        verify(governance).updateDraftMetadata(org, id, "新名称", "EXTERNAL", categoryId);
        verify(governance).replaceTags(org, user, id, List.of("UV", "耐磨"));
        verify(governance).updateSourceInfo(org, id, versionId, source);
        verify(repository, never()).assignCategory(any(), any(), any());
    }

    @Test void refusesToDeleteAnUnpublishedDocumentLinkedToAProject() {
        when(repository.findDocument(org, id)).thenReturn(Optional.of(mock(KnowledgeRepository.DocumentRow.class)));
        when(governance.hasPublication(org, id)).thenReturn(false);
        when(projectResources.hasLinks(any(Actor.class), eq(ResourceType.KNOWLEDGE_DOCUMENT), eq(id))).thenReturn(true);

        assertThatThrownBy(() -> service().deleteDocument(id))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已关联项目");
        verify(repository, never()).deleteDocument(org, id);
    }
}
