package com.jsd.aird.ai.rnd.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.catalog.CatalogContracts.*;

@Repository
public class CatalogRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CatalogRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public JdbcTemplate jdbc() { return jdbc; }

    public Optional<ImportSummary> findImportByHash(String hash) {
        return jdbc.query("SELECT * FROM tpl.standard_field_catalog_import WHERE source_sha256=?",
                this::importRow, hash).stream().findFirst();
    }

    public Optional<ImportSummary> findImport(UUID id) {
        return jdbc.query("SELECT * FROM tpl.standard_field_catalog_import WHERE id=?",
                this::importRow, id).stream().findFirst();
    }

    public int nextVersion() {
        Integer value = jdbc.queryForObject("SELECT coalesce(max(catalog_version),0)+1 FROM tpl.standard_field_catalog_import", Integer.class);
        return value == null ? 1 : value;
    }

    public void insertImport(UUID id, UUID organizationId, UUID fileId, String sha, String name,
                             int version, String parserVersion, int rowCount, int proposalCount,
                             JsonNode summary, UUID userId) {
        jdbc.update("""
                INSERT INTO tpl.standard_field_catalog_import
                  (id,organization_id,source_file_id,source_sha256,source_name,catalog_version,
                   parser_version,status,source_row_count,proposal_count,summary_jsonb,created_by)
                VALUES(?,?,?,?,?,?,'PARSED',?,?,?,?::jsonb,?)
                """, id, organizationId, fileId, sha, name, version, parserVersion,
                rowCount, proposalCount, summary == null ? "{}" : summary.toString(), userId);
    }

    public void insertProposal(UUID id, UUID importId, int sourceRow, int ordinal, String semanticKey,
                               String rawItem, String rawMethod, String rawExample, String rawFactors,
                               String atomicName, String code, String valueType, String unit,
                               UUID standardFieldId, String standardFieldCode, JsonNode qualifiers,
                               JsonNode aliases, JsonNode factorSuggestions, JsonNode evidence,
                               int confidence, String changeStatus, UUID previousProposalId, JsonNode diff) {
        jdbc.update("""
                INSERT INTO tpl.standard_field_catalog_proposal
                  (id,import_id,source_row,atomic_ordinal,semantic_key,raw_item_name,raw_test_method,
                   raw_example_result,raw_influence_factors,atomic_name,suggested_code,
                   suggested_value_type,suggested_unit,standard_field_id,standard_field_code,
                   qualifier_jsonb,aliases_jsonb,factor_suggestions_jsonb,evidence_jsonb,confidence,
                   change_status,previous_proposal_id,diff_jsonb)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?::jsonb)
                """, id, importId, sourceRow, ordinal, semanticKey, rawItem, rawMethod, rawExample,
                rawFactors, atomicName, code, valueType, unit, standardFieldId, standardFieldCode,
                json(qualifiers), json(aliases), json(factorSuggestions), json(evidence), confidence,
                changeStatus == null ? "NEW" : changeStatus, previousProposalId, json(diff));
    }

    public Optional<ProposalView> latestProposalForSemantic(String semanticKey) {
        return jdbc.query("""
                SELECT p.* FROM tpl.standard_field_catalog_proposal p
                JOIN tpl.standard_field_catalog_import i ON i.id=p.import_id
                WHERE p.semantic_key=? ORDER BY i.catalog_version DESC, p.id DESC LIMIT 1
                """, this::proposalRow, semanticKey).stream().findFirst();
    }

    public List<ImportSummary> latestImports(int limit) {
        return jdbc.query("SELECT * FROM tpl.standard_field_catalog_import ORDER BY catalog_version DESC,id LIMIT ?",
                this::importRow, Math.max(1, Math.min(20, limit)));
    }

    public ProposalPage proposals(UUID importId, String keyword, String reviewStatus, int page, int size) {
        var where = new StringBuilder(" WHERE import_id=?");
        var args = new ArrayList<Object>(); args.add(importId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (atomic_name ILIKE ? OR raw_item_name ILIKE ? OR raw_test_method ILIKE ?)");
            String value = "%" + keyword.strip() + "%"; args.add(value); args.add(value); args.add(value);
        }
        if (reviewStatus != null && !reviewStatus.isBlank()) { where.append(" AND review_status=?"); args.add(reviewStatus.strip().toUpperCase()); }
        Long total = jdbc.queryForObject("SELECT count(*) FROM tpl.standard_field_catalog_proposal" + where,
                Long.class, args.toArray());
        var queryArgs = new ArrayList<>(args); queryArgs.add(size); queryArgs.add(Math.max(0, (page - 1) * size));
        var rows = jdbc.query("SELECT * FROM tpl.standard_field_catalog_proposal" + where
                + " ORDER BY source_row,atomic_ordinal,id LIMIT ? OFFSET ?", this::proposalRow, queryArgs.toArray());
        long count = total == null ? 0 : total;
        return new ProposalPage(rows, page, size, count == 0 ? 0 : (count + size - 1) / size, count);
    }

    private ImportSummary importRow(ResultSet rs, int ignored) throws SQLException {
        return new ImportSummary(rs.getObject("id", UUID.class), rs.getString("source_name"),
                rs.getString("source_sha256"), rs.getInt("catalog_version"), rs.getString("parser_version"),
                rs.getString("status"), rs.getInt("source_row_count"), rs.getInt("proposal_count"),
                json(rs,"summary_jsonb"), instant(rs,"created_at"));
    }

    private ProposalView proposalRow(ResultSet rs, int ignored) throws SQLException {
        return new ProposalView(rs.getObject("id", UUID.class), rs.getObject("import_id", UUID.class),
                rs.getInt("source_row"), rs.getInt("atomic_ordinal"), rs.getString("semantic_key"),
                rs.getString("raw_item_name"), rs.getString("raw_test_method"), rs.getString("raw_example_result"),
                rs.getString("raw_influence_factors"), rs.getString("atomic_name"), rs.getString("suggested_code"),
                rs.getString("suggested_value_type"), rs.getString("suggested_unit"),
                rs.getObject("standard_field_id", UUID.class), rs.getString("standard_field_code"),
                json(rs,"qualifier_jsonb"), strings(rs,"aliases_jsonb"), strings(rs,"factor_suggestions_jsonb"),
                json(rs,"evidence_jsonb"), rs.getInt("confidence"), rs.getString("review_status"),
                json(rs,"application_jsonb"), instant(rs,"created_at"), rs.getString("change_status"),
                rs.getObject("previous_proposal_id", UUID.class), json(rs,"diff_jsonb"));
    }

    private JsonNode json(ResultSet rs, String column) throws SQLException {
        try { return mapper.readTree(rs.getString(column)); }
        catch (Exception e) { throw new SQLException("目录JSON解析失败: " + column, e); }
    }

    private List<String> strings(ResultSet rs, String column) throws SQLException {
        try { return mapper.readValue(rs.getString(column), new TypeReference<>() { }); }
        catch (Exception e) { throw new SQLException("目录数组解析失败: " + column, e); }
    }

    private String json(JsonNode node) { return node == null || node.isNull() ? "{}" : node.toString(); }
    private static Instant instant(ResultSet rs, String column) throws SQLException { var v=rs.getTimestamp(column); return v==null?null:v.toInstant(); }
}
