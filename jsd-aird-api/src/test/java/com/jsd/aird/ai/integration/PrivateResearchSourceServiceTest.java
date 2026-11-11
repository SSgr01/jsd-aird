package com.jsd.aird.ai.integration;

import com.jsd.aird.ai.application.PrivateResearchSourceService;
import com.jsd.aird.ai.adapter.out.PrivateSourceFileReadPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.kb.application.PrivateDocumentParsingService;
import com.jsd.aird.kb.application.StructuredDocumentCodec;
import com.jsd.aird.kb.application.SystemParsingPolicy;
import com.jsd.aird.kb.infrastructure.BasicFileSafetyScanner;
import com.jsd.aird.kb.infrastructure.PlainTextDocumentParser;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.platform.web.IsolatedPostgres;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_DATASOURCE_URL", matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*")
class PrivateResearchSourceServiceTest {
    @Test void privateSourcesAreVersionBoundOwnerScopedAndIdempotent() throws Exception {
        var mapper = new ObjectMapper();
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "synthetic", "USER");
        ActorContext.set(actor);
        try (var database = new IsolatedPostgres(Map.of("ai.agent_private_source", "private_source", "ai.research_record", "research_record"))) {
            database.table("private_source", "id uuid PRIMARY KEY,organization_id uuid NOT NULL,owner_id uuid NOT NULL,"
                + "conversation_id uuid NOT NULL,version_id uuid NOT NULL,idempotency_key varchar(128) NOT NULL,request_hash char(64) NOT NULL,"
                + "payload jsonb NOT NULL,created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now(),"
                + "UNIQUE(organization_id,owner_id,idempotency_key)");
            database.table("research_record", "organization_id uuid,owner_id uuid,kind text,payload jsonb");
            var parser = new PrivateDocumentParsingService(List.of(new PlainTextDocumentParser()), new BasicFileSafetyScanner(),
                new SystemParsingPolicy("AUTO", false), new StructuredDocumentCodec(mapper), mapper);
            var service = new PrivateResearchSourceService(database.jdbc, mapper, new MemoryFiles(), parser, mock(AuthorizationService.class));
            try {
                var cid = UUID.randomUUID();
                var body = mapper.createObjectNode().put("conversation_id", cid.toString()).put("name", "synthetic.md")
                    .put("content_type", "text/markdown").put("key", "private:synthetic-1")
                    .put("base64", Base64.getEncoder().encodeToString("# Study\n\nModulus: 12 kPa.".getBytes(StandardCharsets.UTF_8)));
                var created = service.upload(body);
                var id = UUID.fromString(created.path("id").asText());
                await().pollInSameThread().atMost(Duration.ofSeconds(5)).until(() -> "READY".equals(service.metadata(id).path("status").asText()));
                assertThat(service.upload(body).path("id").asText()).isEqualTo(id.toString());
                assertThat(service.upload(body).has("parsed")).isFalse();
                var originalVersion = UUID.fromString(created.path("version_id").asText());
                try (var original = service.open(id, originalVersion)) {
                    assertThat(new String(original.stream().readAllBytes(), StandardCharsets.UTF_8)).contains("Modulus: 12 kPa.");
                }
                assertThatThrownBy(() -> service.open(id, UUID.randomUUID())).isInstanceOf(ApiException.class);
                var page = service.read(mapper.createObjectNode().put("document_id", id.toString()).put("conversation_id", cid.toString()));
                var candidate = page.path("candidates").get(1);
                service.validate(candidate.path("source"), "12 kPa");
                var repeated = mapper.createArrayNode();
                for (int i=0; i<300; i++) repeated.add(candidate.path("source"));
                var spy = org.mockito.Mockito.spy(service);
                spy.authorizeAll(repeated);
                org.mockito.Mockito.verify(spy, org.mockito.Mockito.times(1)).get(id);
                var repeatedEvidence = mapper.createObjectNode();
                for (int i=0; i<300; i++) repeatedEvidence.putObject("e"+i).set("source",candidate.path("source"));
                repeatedEvidence.properties().forEach(entry -> ((com.fasterxml.jackson.databind.node.ObjectNode)entry.getValue()).put("text","12 kPa"));
                org.mockito.Mockito.clearInvocations(spy);
                spy.validateEvidence(repeatedEvidence);
                org.mockito.Mockito.verify(spy, org.mockito.Mockito.times(1)).get(id);
                ((com.fasterxml.jackson.databind.node.ObjectNode)repeatedEvidence.path("e299")).put("text","a different result");
                assertThatThrownBy(() -> spy.validateEvidence(repeatedEvidence)).isInstanceOf(ApiException.class);
                var forged = candidate.path("source").deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) forged).put("publication_id", UUID.randomUUID().toString());
                assertThatThrownBy(() -> service.validate(forged, "12 kPa")).isInstanceOf(ApiException.class);
                assertThatThrownBy(() -> service.authorizeAll(mapper.createArrayNode().add(candidate.path("source")).add(forged)))
                    .isInstanceOf(ApiException.class);
                assertThatThrownBy(() -> service.read(mapper.createObjectNode().put("document_id", id.toString())
                    .put("conversation_id", UUID.randomUUID().toString()))).isInstanceOf(ApiException.class);
                ActorContext.set(new Actor(actor.organizationId(), UUID.randomUUID(), "other", "USER"));
                assertThatThrownBy(() -> service.get(id)).isInstanceOf(ApiException.class);
                assertThatThrownBy(() -> spy.validateEvidence(repeatedEvidence)).isInstanceOf(ApiException.class);
                var filePolicy = new PrivateSourceFileReadPolicy(database.jdbc);
                var versionId = UUID.fromString(created.path("version_id").asText());
                assertThatThrownBy(() -> filePolicy.requireRead(actor.organizationId(), ActorContext.required().userId(), versionId))
                    .isInstanceOf(ApiException.class);
                assertThatThrownBy(() -> filePolicy.requireRead(actor.organizationId(), null, versionId)).isInstanceOf(ApiException.class);
                filePolicy.requireRead(actor.organizationId(), actor.userId(), versionId);
                var derivedId = UUID.randomUUID();
                database.jdbc.update("INSERT INTO ai.research_record VALUES(?,?, 'artifact', ?::jsonb)",
                    actor.organizationId(), actor.userId(), mapper.createObjectNode().put("file_id", derivedId.toString()).toString());
                assertThatThrownBy(() -> filePolicy.requireRead(actor.organizationId(), ActorContext.required().userId(), derivedId))
                    .isInstanceOf(ApiException.class);
                filePolicy.requireRead(actor.organizationId(), actor.userId(), derivedId);
                ActorContext.set(actor);
                var changed = body.deepCopy().put("base64", Base64.getEncoder().encodeToString("Changed evidence".getBytes(StandardCharsets.UTF_8)));
                assertThatThrownBy(() -> service.upload(changed)).isInstanceOf(ApiException.class);
                for (var field : List.of("title", "url", "content_sha256")) {
                    var tampered = (com.fasterxml.jackson.databind.node.ObjectNode) candidate.path("source").deepCopy();
                    tampered.put(field, "forged");
                    assertThatThrownBy(() -> service.validate(tampered, "12 kPa")).isInstanceOf(ApiException.class);
                }
                database.jdbc.update("UPDATE ai.agent_private_source SET payload=jsonb_set(payload,'{status}','\"PARSING\"'),"
                    + "updated_at=now()-interval '31 minutes' WHERE id=?", id);
                var interrupted = service.metadata(id);
                assertThat(interrupted.path("status").asText()).isEqualTo("FAILED");
                assertThat(interrupted.path("error").asText()).isEqualTo("PRIVATE_SOURCE_PARSE_INTERRUPTED");
            } finally { service.close(); }
        } finally { ActorContext.clear(); }
    }

    private static class MemoryFiles implements FileStorageFacade {
        private final Map<UUID, byte[]> content = new ConcurrentHashMap<>();
        @Override public StagedFile stageFile(String name, String type, String kind, InputStream source) {
            try {
                var id = UUID.randomUUID(); var bytes = source.readAllBytes(); content.put(id, bytes);
                return new StagedFile(id, name, type, bytes.length, "synthetic", "STAGED");
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        @Override public StagedFile stageDerived(UUID organizationId, UUID actorId, String name, String type, String kind, InputStream source) {
            return stageFile(name, type, kind, source);
        }
        @Override public StoredFile open(UUID organizationId, UUID id) {
            var bytes = content.get(id);
            return new StoredFile(id, "synthetic.md", "text/markdown", bytes.length, "synthetic", new ByteArrayInputStream(bytes));
        }
        @Override public void activate(UUID id) { }
    }
}
