package com.jsd.aird.data.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcDataSourceFileSearchRepositoryTest {

    @Test
    void aggregatesAcrossFilesOnlyForExplicitSameRecordComparisons() {
        var comparison = new DataSourceFileSearchFacade.DataDetailQuery(
                DataSourceFileSearchFacade.DataQueryMode.FIELD_LOOKUP, "",
                DataSourceFileSearchFacade.DataFileScope.AUTO, List.of("G-6489"), List.of("附着力", "拉伸率"),
                DataSourceFileSearchFacade.RecordTermMatch.ALL, DataSourceFileSearchFacade.EvidenceFilter.ANY,
                5, 5, 50, DataSourceFileSearchFacade.ContextMode.COMPARABLE, 32, 5, "COMPARE");
        var ordinaryLookup = new DataSourceFileSearchFacade.DataDetailQuery(
                DataSourceFileSearchFacade.DataQueryMode.FIELD_LOOKUP, "",
                DataSourceFileSearchFacade.DataFileScope.AUTO, List.of("G-6489"), List.of("附着力"),
                DataSourceFileSearchFacade.RecordTermMatch.ALL, DataSourceFileSearchFacade.EvidenceFilter.ANY,
                1, 5, 50, DataSourceFileSearchFacade.ContextMode.MINIMAL, 32, 1, "VALUE");

        assertThat(JdbcDataSourceFileSearchRepository.shouldAggregateRecordLookup(comparison, 2)).isTrue();
        assertThat(JdbcDataSourceFileSearchRepository.shouldAggregateRecordLookup(comparison, 11)).isFalse();
        assertThat(JdbcDataSourceFileSearchRepository.shouldAggregateRecordLookup(ordinaryLookup, 2)).isFalse();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void searchesProjectedEffectiveValuesWithFieldMetadataAndPermissionScope() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        var categoryId = UUID.randomUUID();
        var permittedCategoryId = UUID.randomUUID();
        var scope = new DataSourceFileSearchFacade.AccessScope("CATEGORY", UUID.randomUUID(),
                Set.of(permittedCategoryId));

        new JdbcDataSourceFileSearchRepository(jdbc).searchSourceFiles(UUID.randomUUID(), "密度",
                List.of(categoryId), scope, 12);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("FROM ai.structured_retrieval_projection p")
                .contains("p.source_owner = 'DATA_CENTER'")
                .contains("p.data_category_id IN (?)")
                .contains("p.experiment_category_id IN (?)")
                .contains("p.experiment_title")
                .contains("p.experiment_purpose")
                .contains("p.project_name")
                .doesNotContain("staging_row", "raw_values_jsonb");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void selectedDataCategoryIncludesAuthorizedExperimentSourcesWithoutUncategorizedToken() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        var selectedCategory = UUID.randomUUID();

        new JdbcDataSourceFileSearchRepository(jdbc).searchSourceFiles(UUID.randomUUID(), "树脂",
                List.of(selectedCategory), DataSourceFileSearchFacade.AccessScope.all(), 10);

        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("p.source_owner = 'EXPERIMENT'")
                .contains("p.source_owner = 'DATA_CENTER' AND (p.data_category_id IN (?)")
                .contains("p.experiment_title");
        assertThat(java.util.Arrays.asList(args.getValue())).contains(selectedCategory)
                .doesNotContain(DataSourceFileSearchFacade.UNCATEGORIZED_SCOPE_ID);
        assertThat(args.getValue()).hasSize((int) sql.getValue().chars().filter(value -> value == '?').count());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void returnsProjectedFieldValueAndItsCompleteSourceAnchor() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var importJobId = UUID.randomUUID();
        var fileId = UUID.randomUUID();
        var valueId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            var rs = mock(ResultSet.class);
            when(rs.getObject("import_job_id", UUID.class)).thenReturn(importJobId);
            when(rs.getObject("file_object_id", UUID.class)).thenReturn(fileId);
            when(rs.getString("source_file_name")).thenReturn("检测报告.xlsx");
            when(rs.getString("original_name")).thenReturn("检测报告.xlsx");
            when(rs.getString("content_type")).thenReturn("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            when(rs.getLong("size_bytes")).thenReturn(100L);
            when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-09-10T10:00:00Z")));
            when(rs.getString("record_key")).thenReturn("REC-001");
            when(rs.getString("sheet_name")).thenReturn("Sheet1");
            when(rs.getObject("source_row_number")).thenReturn(12);
            when(rs.getString("column_name")).thenReturn("C");
            when(rs.getString("cell_address")).thenReturn("C12");
            when(rs.getObject("value_id", UUID.class)).thenReturn(valueId);
            when(rs.getString("field_code")).thenReturn("density");
            when(rs.getString("field_name")).thenReturn("密度");
            when(rs.getString("field_value")).thenReturn("1.08");
            when(rs.getString("normalized_unit")).thenReturn("g/cm³");
            when(rs.getString("value_type")).thenReturn("NUMBER");
            when(rs.getDouble("score")).thenReturn(3.0);
            when(rs.getString("experiment_no")).thenReturn("EXP-001");
            when(rs.getString("experiment_title")).thenReturn("阳离子树脂性能实验");
            when(rs.getString("experiment_purpose")).thenReturn("比较树脂配方性能");
            when(rs.getString("experiment_category_name")).thenReturn("应用测试");
            when(rs.getString("project_name")).thenReturn("阳离子树脂项目");
            return List.of(mapper.mapRow(rs, 0));
        });

        var result = new JdbcDataSourceFileSearchRepository(jdbc).searchSourceFiles(UUID.randomUUID(), "REC-001",
                List.of(UUID.randomUUID()), DataSourceFileSearchFacade.AccessScope.all(), 10);

        assertThat(result).singleElement().satisfies(file -> {
            assertThat(file.importJobId()).isEqualTo(importJobId);
            assertThat(file.fileObjectId()).isEqualTo(fileId);
            assertThat(file.hits()).singleElement().satisfies(hit -> {
                assertThat(hit.hitId()).isEqualTo(valueId);
                assertThat(hit.recordKey()).isEqualTo("REC-001");
                assertThat(hit.fieldCode()).isEqualTo("density");
                assertThat(hit.fieldName()).isEqualTo("密度");
                assertThat(hit.fieldValue()).isEqualTo("1.08");
                assertThat(hit.unit()).isEqualTo("g/cm³");
                assertThat(hit.sheetName()).isEqualTo("Sheet1");
                assertThat(hit.cellAddress()).isEqualTo("C12");
                assertThat(hit.snippet()).contains("实验编号=EXP-001", "实验名称=阳离子树脂性能实验",
                        "实验目的=比较树脂配方性能", "项目=阳离子树脂项目");
            });
        });
    }

    @Test
    void emptySelectedScopeCannotReachTheDatabase() {
        var jdbc = mock(JdbcTemplate.class);
        var scope = new DataSourceFileSearchFacade.AccessScope("SELECTED", null, Set.of());

        var result = new JdbcDataSourceFileSearchRepository(jdbc).searchSourceFiles(UUID.randomUUID(), "REC-001",
                List.of(UUID.randomUUID()), scope, 10);

        assertThat(result).isEmpty();
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void anOverviewWithoutAFileNameDoesNotReachTheDatabase() {
        var jdbc = mock(JdbcTemplate.class);
        var query = new DataSourceFileSearchFacade.DataDetailQuery(
                DataSourceFileSearchFacade.DataQueryMode.FILE_OVERVIEW, "",
                DataSourceFileSearchFacade.DataFileScope.AUTO, List.of(), List.of(), 5, 5, 50);

        var result = new JdbcDataSourceFileSearchRepository(jdbc).queryDetails(
                UUID.randomUUID(), query, List.of(UUID.randomUUID()), DataSourceFileSearchFacade.AccessScope.all());

        assertThat(result.resolution()).isEqualTo(DataSourceFileSearchFacade.DataResolution.FILE_REQUIRED);
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void automaticLookupUsesLatestAuthorizedFilesAndExactValueAssociations() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        var query = new DataSourceFileSearchFacade.DataDetailQuery(
                DataSourceFileSearchFacade.DataQueryMode.FIELD_LOOKUP, "",
                DataSourceFileSearchFacade.DataFileScope.AUTO, List.of("SJ-230水洗后"), List.of("粘度"),
                5, 5, 50);

        new JdbcDataSourceFileSearchRepository(jdbc).queryDetails(
                UUID.randomUUID(), query, List.of(UUID.randomUUID()), DataSourceFileSearchFacade.AccessScope.all());

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("FROM ai.structured_retrieval_projection p")
                .contains("record_match.record_key ILIKE")
                .contains("record_match.record_id = p.record_id")
                .contains("p.data_category_id");
        assertThat(sql.getAllValues()).anyMatch(value -> value.contains("p.field_name ILIKE"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void comparisonLookupMatchesAnyRequestedRecordInsteadOfRequiringOneRowToContainBoth() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        var query = new DataSourceFileSearchFacade.DataDetailQuery(
                DataSourceFileSearchFacade.DataQueryMode.FIELD_LOOKUP, "",
                DataSourceFileSearchFacade.DataFileScope.AUTO, List.of("APP-001", "APP-002"), List.of("粘度"),
                DataSourceFileSearchFacade.RecordTermMatch.ANY, 5, 5, 50);

        new JdbcDataSourceFileSearchRepository(jdbc).queryDetails(
                UUID.randomUUID(), query, List.of(UUID.randomUUID()), DataSourceFileSearchFacade.AccessScope.all());

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getAllValues()).anyMatch(value -> value.contains(") OR EXISTS (SELECT 1")
                && value.contains("record_match.record_id = p.record_id"));
        assertThat(sql.getAllValues()).anyMatch(value -> value.contains("0+")
                && value.contains("regexp_replace"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void assistantUncategorizedScopeIncludesNullCategoryWithoutBindingTheScopeToken() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        var categorized = UUID.randomUUID();
        var query = new DataSourceFileSearchFacade.DataDetailQuery(
                DataSourceFileSearchFacade.DataQueryMode.FIELD_LOOKUP, "",
                DataSourceFileSearchFacade.DataFileScope.AUTO, List.of(), List.of("外观"), 5, 5, 50);

        new JdbcDataSourceFileSearchRepository(jdbc).queryDetails(UUID.randomUUID(), query,
                List.of(categorized, JdbcDataSourceFileSearchRepository.UNCATEGORIZED_SCOPE_ID),
                DataSourceFileSearchFacade.AccessScope.all());

        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("p.data_category_id IN (?)")
                .contains("p.data_category_id IS NULL")
                .contains("p.source_owner = 'EXPERIMENT'");
        assertThat(java.util.Arrays.asList(args.getValue())).contains(categorized)
                .doesNotContain(JdbcDataSourceFileSearchRepository.UNCATEGORIZED_SCOPE_ID);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void confirmedDataEvidenceFilterExcludesUnconfirmedAndExperimentSources() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        var query = new DataSourceFileSearchFacade.DataDetailQuery(
                DataSourceFileSearchFacade.DataQueryMode.FIELD_LOOKUP, "",
                DataSourceFileSearchFacade.DataFileScope.AUTO, List.of("APP-UV-0001"), List.of("粘度"),
                DataSourceFileSearchFacade.RecordTermMatch.ALL,
                DataSourceFileSearchFacade.EvidenceFilter.CONFIRMED_DATA, 5, 5, 50,
                DataSourceFileSearchFacade.ContextMode.MINIMAL, 32, 5, "VALUE");

        new JdbcDataSourceFileSearchRepository(jdbc).queryDetails(UUID.randomUUID(), query,
                List.of(UUID.randomUUID(), JdbcDataSourceFileSearchRepository.UNCATEGORIZED_SCOPE_ID),
                DataSourceFileSearchFacade.AccessScope.all());

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getAllValues()).allMatch(value -> value.contains("data.confirmed_submission_head")
                && value.contains("p.source_owner = 'DATA_CENTER'")
                && value.contains("s.status = 'CONFIRMED'"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void experimentOnlyScopeUsesTheLinkedExperimentAndNeverFallsBackToImportOwner() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        var actorId = UUID.randomUUID();
        var scope = new DataSourceFileSearchFacade.RetrievalAccessScope(
                DataSourceFileSearchFacade.AccessScope.denied(),
                new DataSourceFileSearchFacade.AccessScope("SELF", actorId, Set.of()));

        new JdbcDataSourceFileSearchRepository(jdbc).searchSourceFiles(UUID.randomUUID(), "陈琪",
                List.of(JdbcDataSourceFileSearchRepository.UNCATEGORIZED_SCOPE_ID), scope, 10);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("p.source_owner = 'DATA_CENTER' AND (1 = 0)")
                .contains("p.source_owner = 'EXPERIMENT'")
                .contains("p.experiment_created_by = ? OR p.experiment_owner_id = ?")
                .doesNotContain("rnd.experiment_import_job");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void experimentTopicSearchIncludesAnchoredSiblingFieldsFromTheMatchedRecord() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var organizationId = UUID.randomUUID();
        var importJobId = UUID.randomUUID();
        var fileId = UUID.randomUUID();
        var recordId = UUID.randomUUID();
        var match = projectedRow(importJobId, fileId, UUID.randomUUID(), recordId,
                "合计", "100.55", "涂料配方 > 实验配方 > 合计", "D16", 2.3);
        var formula = projectedRow(importJobId, fileId, UUID.randomUUID(), recordId,
                "UA-145", "48.45", "涂料配方 > 实验配方 > UA-145", "D11", 4.0);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            var sql = (String) invocation.getArgument(0);
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(sql.contains("ranked_context") ? formula : match, 0));
        });

        var repository = new JdbcDataSourceFileSearchRepository(jdbc);
        var matched = repository.search(organizationId, "实验配方", List.of(UUID.randomUUID()),
                DataSourceFileSearchFacade.RetrievalAccessScope.all(), 10);
        var contexts = repository.expandExperimentContexts(organizationId, matched, List.of("实验配方"),
                DataSourceFileSearchFacade.RetrievalAccessScope.all());
        repository.expandExperimentContexts(organizationId, matched, List.of("粘度"),
                DataSourceFileSearchFacade.RetrievalAccessScope.all());

        assertThat(matched).singleElement().satisfies(hit -> {
            assertThat(hit.sourceOwner()).isEqualTo("EXPERIMENT");
            assertThat(hit.fieldValue()).isEqualTo("100.55");
        });
        assertThat(contexts).singleElement().satisfies(hit -> {
            assertThat(hit.fieldName()).isEqualTo("UA-145");
            assertThat(hit.fieldValue()).isEqualTo("48.45");
            assertThat(hit.content()).contains("实验编号=EXP-001", "记录=G-6488", "48.45");
            assertThat(hit.cellAddress()).isEqualTo("D11");
            assertThat(hit.sourceLocator()).endsWith(":Sheet1:D11");
        });
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(3)).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getAllValues().get(1)).contains("req.record_key = p.record_key")
                .contains("p.source_owner = 'EXPERIMENT'")
                .contains("p.semantic_domain = 'FORMULA'")
                .contains("context_rank <= 24");
        var compositionOrder = sql.getAllValues().get(1).substring(
                sql.getAllValues().get(1).indexOf("row_number() OVER"));
        var ordinaryOrder = sql.getAllValues().get(2).substring(
                sql.getAllValues().get(2).indexOf("row_number() OVER"));
        assertThat(compositionOrder.indexOf("WHEN p.semantic_domain = 'FORMULA'"))
                .isLessThan(compositionOrder.indexOf("WHEN (p.field_name ILIKE"));
        assertThat(ordinaryOrder.indexOf("WHEN (p.field_name ILIKE"))
                .isLessThan(ordinaryOrder.indexOf("WHEN p.semantic_domain = 'FORMULA'"));
        assertThat(java.util.Arrays.asList(args.getAllValues().get(1)))
                .contains(organizationId, importJobId, "IMPORT:STRUCT:sheet-1:COLUMN:1");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void expandsEverySelectedDistinctRecordWithinTheSameImport() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        var organizationId = UUID.randomUUID();
        var importJobId = UUID.randomUUID();
        var matched = List.of("COLUMN:1", "COLUMN:2", "COLUMN:3", "COLUMN:4").stream()
                .map(key -> experimentSeed(importJobId, "IMPORT:STRUCT:sheet-1:" + key)).toList();

        new JdbcDataSourceFileSearchRepository(jdbc).expandExperimentContexts(organizationId, matched,
                List.of("配方"), DataSourceFileSearchFacade.RetrievalAccessScope.all());

        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(anyString(), any(RowMapper.class), args.capture());
        assertThat(java.util.Arrays.asList(args.getValue()))
                .contains(importJobId, "IMPORT:STRUCT:sheet-1:COLUMN:1", "IMPORT:STRUCT:sheet-1:COLUMN:2",
                        "IMPORT:STRUCT:sheet-1:COLUMN:3", "IMPORT:STRUCT:sheet-1:COLUMN:4");
    }

    private ResultSet projectedRow(UUID importJobId, UUID fileId, UUID valueId, UUID recordId,
                                   String fieldName, String fieldValue, String sourceLabel,
                                   String cellAddress, double score) throws Exception {
        var rs = mock(ResultSet.class);
        when(rs.getObject("import_job_id", UUID.class)).thenReturn(importJobId);
        when(rs.getObject("file_object_id", UUID.class)).thenReturn(fileId);
        when(rs.getString("original_name")).thenReturn("阳离子树脂应用性能实验-001.xlsx");
        when(rs.getString("content_type")).thenReturn("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        when(rs.getLong("size_bytes")).thenReturn(100L);
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-09-21T10:00:00Z")));
        when(rs.getString("source_owner")).thenReturn("EXPERIMENT");
        when(rs.getString("recognition_mode")).thenReturn("TEMPLATE_GUIDED");
        when(rs.getObject("experiment_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getObject("experiment_version_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getString("record_key")).thenReturn("IMPORT:STRUCT:sheet-1:COLUMN:1");
        when(rs.getString("sheet_name")).thenReturn("Sheet1");
        when(rs.getObject("source_row_number")).thenReturn(11);
        when(rs.getString("column_name")).thenReturn("D");
        when(rs.getString("cell_address")).thenReturn(cellAddress);
        when(rs.getObject("value_id", UUID.class)).thenReturn(valueId);
        when(rs.getString("field_code")).thenReturn(fieldName);
        when(rs.getString("field_name")).thenReturn(fieldName);
        when(rs.getString("field_value")).thenReturn(fieldValue);
        when(rs.getString("normalized_unit")).thenReturn(null);
        when(rs.getString("value_type")).thenReturn("TEXT");
        when(rs.getDouble("score")).thenReturn(score);
        when(rs.getString("experiment_no")).thenReturn("EXP-001");
        when(rs.getString("experiment_title")).thenReturn("阳离子树脂性能实验");
        when(rs.getString("experiment_purpose")).thenReturn("比较树脂配方性能");
        when(rs.getString("experiment_category_name")).thenReturn("应用测试");
        when(rs.getString("project_name")).thenReturn(null);
        when(rs.getString("experiment_stage_name")).thenReturn(null);
        when(rs.getString("experiment_task_name")).thenReturn(null);
        when(rs.getObject("record_id", UUID.class)).thenReturn(recordId);
        when(rs.getObject("record_index", Integer.class)).thenReturn(1);
        when(rs.getString("value_path")).thenReturn(fieldName);
        when(rs.getString("source_label")).thenReturn(sourceLabel);
        when(rs.getString("semantic_domain")).thenReturn("FORMULA");
        when(rs.getString("semantic_field")).thenReturn("COMPONENT");
        when(rs.getString("experiment_status")).thenReturn("COMPLETED");
        when(rs.getString("display_record")).thenReturn("G-6488");
        return rs;
    }

    private DataSourceFileSearchFacade.SourceFileHit experimentSeed(UUID importJobId, String recordKey) {
        return new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(), UUID.randomUUID(), importJobId,
                1, "A", "实验.xlsx", "", 1d, "EXPERIMENT:Sheet1:A1", recordKey,
                "field", "字段", "值", "", "TEXT", "Sheet1", "A1", "EXPERIMENT",
                "TEMPLATE_GUIDED", UUID.randomUUID(), UUID.randomUUID(), "COMPLETED", "字段", "OTHER",
                "BASIC", null, "", "", null, "TARGET");
    }
}
