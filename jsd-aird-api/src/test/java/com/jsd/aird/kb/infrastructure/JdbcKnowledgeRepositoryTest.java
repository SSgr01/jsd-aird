package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.kb.application.port.KnowledgeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcKnowledgeRepositoryTest {

    @Test
    void writesParentsBeforeChildrenAndIndexesOnlyChildTerms() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new JdbcKnowledgeRepository(jdbc);
        var parseRunId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(UUID.class), any())).thenReturn(parseRunId);
        var parent = chunk("parent:section", null, "PARENT", 0, List.of(), List.of(), List.of());
        var reviewIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        var sourceIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        var child = chunk("child:section:0", parent.chunkKey(), "CHILD", 1,
                reviewIds, sourceIds, List.of(new KnowledgeRepository.TermFrequency("乙酸乙酯", 2)));

        repository.replaceChunks(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(parent, child));

        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), args.capture());
        var inserts = java.util.stream.IntStream.range(0, sql.getAllValues().size())
                .filter(index -> sql.getAllValues().get(index).contains("INSERT INTO kb.document_chunk"))
                .mapToObj(index -> args.getAllValues().get(index)).toList();
        assertThat(inserts).hasSize(2);
        assertThat(inserts.get(0)[20]).isEqualTo("PARENT");
        assertThat(inserts.get(0)[10]).isNull();
        assertThat(inserts.get(1)[20]).isEqualTo("CHILD");
        assertThat(inserts.get(1)[10]).isEqualTo(inserts.get(0)[0]);
        assertThat(inserts.get(1)[22]).isEqualTo(json(reviewIds));
        assertThat(inserts.get(1)[23]).isEqualTo(json(sourceIds));
        assertThat(sql.getAllValues().stream().filter(value -> value.contains("INSERT INTO kb.chunk_term"))).hasSize(1);
    }

    @Test
    void usesCurrentAnalyzerVersionAndExcludesParentsFromStatisticsAndEmbedding() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new JdbcKnowledgeRepository(jdbc);

        repository.bm25Search(UUID.randomUUID(), List.of(
                new KnowledgeRepository.AnalyzedTerm("material-smartcn-v2", "chemical"),
                new KnowledgeRepository.AnalyzedTerm("material-smartcn-v2", "cacl2")),
                false, List.of(), 10);
        repository.rebuildTermStats(UUID.randomUUID());
        repository.chunksForEmbedding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class));
        var querySql = String.join("\n", sql.getAllValues());
        assertThat(querySql).contains("unnest(?::text[], ?::text[])",
                "q.analyzer_version = c.analyzer_version", "s.analyzer_version = c.analyzer_version",
                "c.chunk_role = 'CHILD'");

        var updateSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(updateSql.capture(), any(Object[].class));
        assertThat(String.join("\n", updateSql.getAllValues())).contains("GROUP BY c.analyzer_version",
                "GROUP BY c.analyzer_version, t.term", "c.chunk_role = 'CHILD'");
    }

    @Test
    void selectsTheLatestReviewRevisionStatusForDocumentWorkflow() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new JdbcKnowledgeRepository(jdbc);

        repository.listDocuments(UUID.randomUUID(), null, null, null, null,
                null, null, 1, 20);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class));
        assertThat(sql.getValue()).contains(
                "latest_rr.status AS review_revision_status",
                "LEFT JOIN LATERAL",
                "ORDER BY rr.revision_no DESC",
                "LIMIT 1");
    }

    @Test
    void batchesAllVariantsAndHydratesRankedChunksWithThreeDatabaseCalls() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new JdbcKnowledgeRepository(jdbc);
        var organizationId = UUID.randomUUID();
        var chunkIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        repository.batchBm25Rank(organizationId, List.of(
                new KnowledgeRepository.AnalyzedQuery(0, "first", List.of(
                        new KnowledgeRepository.AnalyzedTerm("material-smartcn-v2", "first"))),
                new KnowledgeRepository.AnalyzedQuery(1, "second", List.of(
                        new KnowledgeRepository.AnalyzedTerm("material-smartcn-v2", "second")))),
                true, List.of(UUID.randomUUID()), 48);
        repository.batchVectorRank(organizationId, List.of(
                new KnowledgeRepository.VectorQuery(0, "first", "[0.1,0.2]"),
                new KnowledgeRepository.VectorQuery(1, "second", "[0.2,0.1]")),
                true, List.of(UUID.randomUUID()), 48, 2);
        repository.loadSearchRows(organizationId, chunkIds);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class));
        assertThat(sql.getAllValues().get(0)).contains(
                "unnest(?::int[], ?::text[], ?::text[])",
                "PARTITION BY query_ordinal", "rank_no <= ?", "c.chunk_role = 'CHILD'");
        assertThat(sql.getAllValues().get(1)).contains(
                "unnest(?::int[], ?::text[])", "CROSS JOIN LATERAL",
                "PARTITION BY query_ordinal", "c.embedding IS NOT NULL");
        assertThat(sql.getAllValues().get(2)).contains(
                "c.source_anchors_jsonb", "c.review_node_ids_jsonb", "c.source_node_keys_jsonb",
                "c.id IN (", "?,?)");
    }

    private KnowledgeRepository.ChunkWrite chunk(String key, String parent, String role, int number,
                                                   List<UUID> reviewIds, List<UUID> sourceIds,
                                                   List<KnowledgeRepository.TermFrequency> terms) {
        return new KnowledgeRepository.ChunkWrite(key, parent, role, number, 1, "paragraph", "内容", null,
                role.equals("CHILD") ? 1 : 0, 1, "material-smartcn-v2", null, terms, List.of("参数"),
                reviewIds, sourceIds, "{\"kind\":\"page\"}", "[{\"kind\":\"page\"}]", "[]",
                null, null, null, List.of(), null, null);
    }

    private String json(List<UUID> values) {
        return "[\"" + String.join("\",\"", values.stream().map(UUID::toString).toList()) + "\"]";
    }
}
