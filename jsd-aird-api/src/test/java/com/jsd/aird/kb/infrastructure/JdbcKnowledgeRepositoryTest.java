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
    void keepsAnalyzerVersionsIsolatedAndExcludesParentsFromStatisticsAndEmbedding() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new JdbcKnowledgeRepository(jdbc);

        repository.bm25Search(UUID.randomUUID(), List.of(
                new KnowledgeRepository.AnalyzedTerm("term-v1", "乙酸"),
                new KnowledgeRepository.AnalyzedTerm("material-smartcn-v1", "乙酸乙酯")),
                false, List.of(), List.of(), 10);
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

    private KnowledgeRepository.ChunkWrite chunk(String key, String parent, String role, int number,
                                                   List<UUID> reviewIds, List<UUID> sourceIds,
                                                   List<KnowledgeRepository.TermFrequency> terms) {
        return new KnowledgeRepository.ChunkWrite(key, parent, role, number, 1, "paragraph", "内容", null,
                role.equals("CHILD") ? 1 : 0, 1, "material-smartcn-v1", null, terms, List.of("参数"),
                reviewIds, sourceIds, "{\"kind\":\"page\"}", "[{\"kind\":\"page\"}]", "[]",
                null, null, null, List.of(), null, null);
    }

    private String json(List<UUID> values) {
        return "[\"" + String.join("\",\"", values.stream().map(UUID::toString).toList()) + "\"]";
    }
}
