package com.jsd.aird.platform.web;

import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.infrastructure.JdbcKnowledgeRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "JSD_AIRD_CONSISTENCY_PG_TEST", matches = "true")
class KnowledgeVectorRankingPostgresTest {
    private static IsolatedPostgres db;
    private static JdbcKnowledgeRepository repository;
    private final UUID org = UUID.randomUUID(), category = UUID.randomUUID();
    private record Document(UUID id, UUID version, UUID review, UUID publication, UUID file) { }

    @BeforeAll static void setup() {
        db = new IsolatedPostgres(Map.of(
                "kb.document_chunk", "chunk", "kb.document", "document",
                "kb.publication", "publication", "kb.document_version", "version",
                "kb.document_ai_grant", "grant", "ops.file_object", "file", "kb.document_chunk_embedding", "embedding"));
        db.table("document", "id uuid PRIMARY KEY,organization_id uuid,lifecycle_status text,current_publication_id uuid,category_id uuid");
        db.table("publication", "id uuid PRIMARY KEY,document_version_id uuid,review_revision_id uuid,status text,metadata_snapshot_jsonb jsonb DEFAULT '{}'::jsonb");
        db.table("version", "id uuid PRIMARY KEY,file_object_id uuid");
        db.table("file", "id uuid PRIMARY KEY,organization_id uuid,status text");
        db.table("grant", "document_id uuid PRIMARY KEY,status text");
        db.table("chunk", "id uuid PRIMARY KEY,document_id uuid,document_version_id uuid,review_revision_id uuid,chunk_role text,embedding vector,embedding_model text DEFAULT 'qwen3'");
        db.table("embedding","chunk_id uuid,organization_id uuid,embedding_model text,embedding_dimension int,embedding halfvec");
        repository = new JdbcKnowledgeRepository(db.jdbc);
    }
    @AfterAll static void cleanup() { if (db != null) db.close(); }
    @BeforeEach void clear() { db.clear(); }

    private Document document(UUID organization) {
        var d = new Document(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        db.jdbc.update("INSERT INTO kb.document VALUES(?,?,'ACTIVE',?,?)", d.id(), organization, d.publication(), category);
        db.jdbc.update("INSERT INTO kb.publication(id,document_version_id,review_revision_id,status) VALUES(?,?,?,'CURRENT')", d.publication(), d.version(), d.review());
        db.jdbc.update("INSERT INTO kb.document_version VALUES(?,?)", d.version(), d.file());
        db.jdbc.update("INSERT INTO ops.file_object VALUES(?,?,'ACTIVE')", d.file(), organization);
        db.jdbc.update("INSERT INTO kb.document_ai_grant VALUES(?,'APPROVED')", d.id());
        return d;
    }
    private UUID chunk(Document d, UUID id, String vector) {
        db.jdbc.update("INSERT INTO kb.document_chunk(id,document_id,document_version_id,review_revision_id,chunk_role,embedding) VALUES(?,?,?,?,'CHILD',CAST(? AS vector))", id, d.id(), d.version(), d.review(), vector);
        db.jdbc.update("INSERT INTO kb.document_chunk_embedding SELECT c.id,d.organization_id,'qwen3',vector_dims(c.embedding),c.embedding::halfvec FROM kb.document_chunk c JOIN kb.document d ON d.id=c.document_id WHERE c.id=?",id);
        return id;
    }
    private List<KnowledgeRepository.VectorQuery> queries() {
        return List.of(new KnowledgeRepository.VectorQuery(0, "x", "[1,0]", "qwen3"),
                new KnowledgeRepository.VectorQuery(1, "y", "[0,1]", "qwen3"));
    }

    @Test void batchesExactCosineRankingAndBreaksTiesByChunkId() {
        var d = document(org);
        var first = chunk(d, new UUID(0, 1), "[1,0]");
        var second = chunk(d, new UUID(0, 2), "[1,0]");
        var third = chunk(d, new UUID(0, 3), "[0,1]");
        var ranks = repository.batchVectorRank(org, queries(), true, List.of(category), Set.of(d.id()), 2, 2);
        assertThat(ranks).extracting(KnowledgeRepository.RankedChunk::chunkId).containsExactly(first, second, third, first);
        assertThat(ranks).extracting(KnowledgeRepository.RankedChunk::queryOrdinal).containsExactly(0, 0, 1, 1);
        assertThat(ranks).extracting(KnowledgeRepository.RankedChunk::rank).containsExactly(1, 2, 1, 2);
        assertThat(ranks).extracting(KnowledgeRepository.RankedChunk::score).containsExactly(1.0, 1.0, 1.0, 0.0);
    }

    @Test void excludesIneligibleDocumentsAndNoncurrentOrIncompatibleChunksBeforeLimit() {
        var good = document(org);
        var allowed = chunk(good, new UUID(0, 100), "[0,1]");
        var candidates = new java.util.HashSet<UUID>();
        candidates.add(good.id());
        for (int i = 0; i < 7; i++) {
            var d = document(i == 0 ? UUID.randomUUID() : org);
            chunk(d, UUID.randomUUID(), "[1,0]");
            if (i != 6) candidates.add(d.id());
            switch (i) {
                case 1 -> db.jdbc.update("UPDATE kb.document_ai_grant SET status='REVOKED' WHERE document_id=?", d.id());
                case 2 -> db.jdbc.update("UPDATE ops.file_object SET status='DELETED' WHERE id=?", d.file());
                case 3 -> db.jdbc.update("UPDATE kb.publication SET status='SUPERSEDED' WHERE id=?", d.publication());
                case 4 -> db.jdbc.update("UPDATE kb.document SET lifecycle_status='DELETED' WHERE id=?", d.id());
                case 5 -> db.jdbc.update("UPDATE ops.file_object SET organization_id=? WHERE id=?", UUID.randomUUID(), d.file());
                default -> { }
            }
        }
        var wrongCategory = document(org);
        candidates.add(wrongCategory.id());
        chunk(wrongCategory, UUID.randomUUID(), "[1,0]");
        db.jdbc.update("UPDATE kb.publication SET metadata_snapshot_jsonb=jsonb_build_object('categoryId',?::text) WHERE id=?", UUID.randomUUID().toString(), wrongCategory.publication());
        for (String defect : List.of("parent", "dimension", "version", "review", "null", "old-model", "unknown-model")) {
            var id = chunk(good, UUID.randomUUID(), defect.equals("dimension") ? "[1,0,0]" : defect.equals("null") ? null : "[1,0]");
            switch (defect) {
                case "parent" -> db.jdbc.update("UPDATE kb.document_chunk SET chunk_role='PARENT' WHERE id=?", id);
                case "version" -> db.jdbc.update("UPDATE kb.document_chunk SET document_version_id=? WHERE id=?", UUID.randomUUID(), id);
                case "review" -> db.jdbc.update("UPDATE kb.document_chunk SET review_revision_id=? WHERE id=?", UUID.randomUUID(), id);
                case "old-model" -> db.jdbc.update("UPDATE kb.document_chunk_embedding SET embedding_model='old-model' WHERE chunk_id=?", id);
                case "unknown-model" -> db.jdbc.update("UPDATE kb.document_chunk_embedding SET embedding_model=NULL WHERE chunk_id=?", id);
                default -> { }
            }
        }
        var ranks = repository.batchVectorRank(org, queries(), true, List.of(category), candidates, 1, 2);
        assertThat(ranks).extracting(KnowledgeRepository.RankedChunk::chunkId).containsExactly(allowed, allowed);
    }

    @Test void preservesUnfilteredScopeAndOptionalGrantPolicy() {
        var d = document(org);
        var id = chunk(d, UUID.randomUUID(), "[1,0]");
        db.jdbc.update("UPDATE kb.document_ai_grant SET status='REVOKED' WHERE document_id=?", d.id());
        assertThat(repository.batchVectorRank(org, queries(), true, List.of(), null, 48, 2)).isEmpty();
        assertThat(repository.batchVectorRank(org, queries(), false, List.of(), null, 48, 2))
                .extracting(KnowledgeRepository.RankedChunk::chunkId).containsExactly(id, id);
        assertThat(repository.batchVectorRank(org, queries(), false, List.of(), Set.of(), 48, 2)).isEmpty();
    }
}
