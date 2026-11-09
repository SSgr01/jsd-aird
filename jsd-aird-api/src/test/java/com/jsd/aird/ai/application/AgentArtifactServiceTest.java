package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.api.FileStorageFacade;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

class AgentArtifactServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final AgentArtifactStore records = mock(AgentArtifactStore.class);
    final FileStorageFacade storage = mock(FileStorageFacade.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final AgentSourceAuthorization sources = mock(AgentSourceAuthorization.class);
    final AgentArtifactService service = new AgentArtifactService(records, storage, jdbc, mapper, sources);
    final UUID fileId = UUID.randomUUID();

    @BeforeEach void actor() {
        ActorContext.set(new Actor(UUID.randomUUID(), UUID.randomUUID(), "test"));
        when(storage.stageFile(anyString(), anyString(), anyString(), any()))
            .thenReturn(new FileStorageFacade.StagedFile(fileId, "file", "application/octet-stream", 2, "hash", "STAGED"));
        when(records.create(any(), anyString())).thenAnswer(call -> ((ObjectNode) call.getArgument(0)).deepCopy());
    }

    @AfterEach void clear() { ActorContext.clear(); }

    ObjectNode request(String name) {
        return mapper.createObjectNode().put("key", "a".repeat(64)).put("name", name)
            .put("run_id", UUID.randomUUID().toString()).put("conversation_id", UUID.randomUUID().toString())
            .put("base64", Base64.getEncoder().encodeToString("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @CsvSource({"note.txt,text/plain", "data.csv,text/csv", "data.json,application/json", "report.md,text/markdown"})
    void genericFilesNeedNoResearchTopicOrReport(String name, String mime) {
        var result = service.save(request(name));
        assertThat(result.path("name").asText()).isEqualTo(name);
        assertThat(result.has("topic_id")).isFalse();
        assertThat(result.path("source_refs").isEmpty()).isTrue();
        assertThat(result.has("execution_receipt")).isFalse();
        verify(records, never()).topic(any());
        verify(storage).stageFile(eq(name), eq(mime), eq("RESEARCH_ARTIFACT"), any());
        verify(storage).activate(fileId);
    }

    @Test void retryReusesFileAndChangedContentConflicts() {
        var input = request("data.json");
        var saved = service.save(input);
        var id = UUID.randomUUID();
        when(jdbc.queryForList(contains("SELECT id"), eq(UUID.class), any(), any(), any())).thenReturn(List.of(id));
        when(records.get(id)).thenReturn(saved);
        assertThat(service.save(input)).isEqualTo(saved);
        verify(storage, times(1)).stageFile(anyString(), anyString(), anyString(), any());
        var changed = input.deepCopy().put("base64", Base64.getEncoder().encodeToString("[]".getBytes()));
        assertThatThrownBy(() -> service.save(changed)).isInstanceOf(ApiException.class);
    }

    @Test void legacyTopicAndSourcePermissionsStillApplyBeforeDownload() throws Exception {
        var id = UUID.randomUUID();
        var record = mapper.createObjectNode().put("file_id", fileId.toString()).put("sha256", "old-hash");
        record.putArray("source_refs").addObject().put("kind", "KB_PUBLICATION");
        when(records.get(id)).thenReturn(record);
        when(storage.open(any(), eq(fileId))).thenAnswer(call ->
            new FileStorageFacade.StoredFile(fileId, "old.csv", "text/csv", 3, "old-hash", new ByteArrayInputStream(new byte[]{1,2,3})));
        try (var file = service.open(id)) {
            assertThat(file.stream().readAllBytes()).containsExactly(1, 2, 3);
        }
        assertThat(service.provenance(id).path("sha256").asText()).isEqualTo("old-hash");
        doThrow(new IllegalStateException("revoked")).when(sources).requireExport(any());
        assertThatThrownBy(() -> service.open(id)).hasMessage("revoked");
        verify(storage, times(2)).open(any(), eq(fileId));
    }

    @Test void unsupportedFormatsAndPathsDoNotReachStorage() {
        for (var name : List.of("../report.md", "report.exe", "report.tsv", "C:report.txt"))
            assertThatThrownBy(() -> service.save(request(name))).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }
}
