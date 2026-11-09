package com.jsd.aird.ai.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.application.PrivateResearchSourceService;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.kb.api.PrivateDocumentParsingFacade;
import com.jsd.aird.kb.api.ScholarlyLiteratureFacade;
import com.jsd.aird.ops.api.FileStorageFacade;
import com.jsd.aird.platform.web.IsolatedPostgres;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_DATASOURCE_URL", matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*")
class ScholarlyAcquisitionTest {
    @Test void acquisitionIsReservedBeforeDownloadOwnerScopedAndUnknownOutcomesNeverReplay() throws Exception {
        var mapper = new ObjectMapper();
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "synthetic", "USER");
        ActorContext.set(actor);
        try (var database = new IsolatedPostgres(Map.of("ai.agent_private_source", "private_source"))) {
            database.table("private_source", "id uuid PRIMARY KEY,organization_id uuid NOT NULL,owner_id uuid NOT NULL,"
                + "conversation_id uuid NOT NULL,version_id uuid NOT NULL,idempotency_key varchar(128) NOT NULL,request_hash char(64) NOT NULL,"
                + "payload jsonb NOT NULL,created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now(),"
                + "UNIQUE(organization_id,owner_id,idempotency_key)");
            var files = mock(FileStorageFacade.class);
            var parser = mock(PrivateDocumentParsingFacade.class);
            var scholarly = mock(ScholarlyLiteratureFacade.class);
            var bytes = "%PDF-1.7\nfixture".getBytes(StandardCharsets.US_ASCII);
            var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var version = UUID.randomUUID();
            when(files.stageFile(anyString(), anyString(), anyString(), any())).thenReturn(
                new FileStorageFacade.StagedFile(version, "study.pdf", "application/pdf", bytes.length, sha, "STAGED"));
            var parsed = mapper.createObjectNode(); parsed.putArray("nodes").addObject().put("key", "node-1").put("text", "Original evidence.");
            when(parser.parse(any(), any(), any(), anyString(), anyString(), anyLong(), any())).thenReturn(parsed);
            var started = new CountDownLatch(1); var release = new CountDownLatch(1);
            when(scholarly.download("W123")).thenAnswer(call -> {
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test download timed out");
                return new ScholarlyLiteratureFacade.FullText("study.pdf", bytes, mapper.createObjectNode().put("sha256", sha));
            });
            when(scholarly.download("W456")).thenThrow(new ScholarlyLiteratureFacade.Unavailable("OPENALEX_REQUEST_OUTCOME_UNKNOWN", 0));
            var service = new PrivateResearchSourceService(database.jdbc, mapper, files, parser, mock(AuthorizationService.class));
            try {
                var cid = UUID.randomUUID();
                var args = mapper.createObjectNode().put("conversation_id", cid.toString()).put("work_id", "W123");
                var first = service.acquire(args, scholarly);
                assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(first.path("status").asText()).isEqualTo("ACQUIRING");
                assertThat(service.acquire(args, scholarly).path("id")).isEqualTo(first.path("id"));
                assertThat(service.acquisitionStatus(args).path("status").asText()).isEqualTo("ACQUIRING");
                verifyNoInteractions(files, parser);
                release.countDown();
                await().pollInSameThread().atMost(Duration.ofSeconds(5)).until(() -> "READY".equals(service.acquisitionStatus(args).path("status").asText()));
                var ready = service.acquisitionStatus(args);
                assertThat(ready.path("version_id").asText()).isEqualTo(version.toString());
                assertThat(ready.path("sha256").asText()).isEqualTo(sha);
                assertThat(ready.has("parsed")).isFalse();
                var id = UUID.fromString(ready.path("id").asText());
                var candidate = service.read(mapper.createObjectNode().put("document_id", id.toString()).put("conversation_id", cid.toString())).path("candidates").get(0);
                service.validate(candidate.path("source"), "Original evidence.");
                verify(scholarly, times(1)).download("W123");
                ActorContext.set(new Actor(actor.organizationId(), UUID.randomUUID(), "other", "USER"));
                assertThat(service.acquisitionStatus(args).path("status").asText()).isEqualTo("NOT_STARTED");
                assertThatThrownBy(() -> service.get(id)).isInstanceOf(ApiException.class);
                ActorContext.set(new Actor(UUID.randomUUID(), actor.userId(), "other-org", "USER"));
                assertThat(service.acquisitionStatus(args).path("status").asText()).isEqualTo("NOT_STARTED");
                ActorContext.set(actor);
                var unknown = args.deepCopy().put("work_id", "W456");
                var pending = service.acquire(unknown, scholarly);
                await().pollInSameThread().atMost(Duration.ofSeconds(5)).until(() -> "UNKNOWN".equals(service.acquisitionStatus(unknown).path("status").asText()));
                assertThat(service.acquire(unknown, scholarly).path("id")).isEqualTo(pending.path("id"));
                verify(scholarly, times(1)).download("W456");
                var emptyConversation = args.deepCopy().put("conversation_id", UUID.randomUUID().toString());
                assertThat(service.acquisitionStatus(emptyConversation).path("status").asText()).isEqualTo("NOT_STARTED");
            } finally { release.countDown(); service.close(); }
        } finally { ActorContext.clear(); }
    }
}
