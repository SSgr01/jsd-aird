package com.jsd.aird.mfg.ingest.infrastructure;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.ingest.application.port.InstanceIngestRepository;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInstanceIngestRepository implements InstanceIngestRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcInstanceIngestRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insert(NewJob job) {
        jdbcTemplate.update("""
                INSERT INTO mfg.production_ingest_job (
                    id, organization_id, production_order_id, requested_template_version_id,
                    source_type, status, created_by
                ) VALUES (?, ?, ?, ?, ?, 'QUEUED', ?)
                """, job.id(), job.organizationId(), job.productionOrderId(),
                job.requestedTemplateVersionId(), job.sourceType(), job.actorId());
        var order = 0;
        for (var fileId : job.sourceFileIds()) {
            jdbcTemplate.update("""
                    INSERT INTO mfg.production_ingest_source (ingest_job_id, file_object_id, page_order)
                    VALUES (?, ?, ?)
                    """, job.id(), fileId, order++);
        }
    }

    @Override
    public Optional<Job> find(UUID organizationId, UUID orderId, UUID jobId) {
        return jdbcTemplate.query("""
                        SELECT id, organization_id, production_order_id, requested_template_version_id,
                               selected_template_version_id, source_type, match_mode, status,
                               template_match_score, result_version, result_jsonb, error_message,
                               created_at, updated_at
                        FROM mfg.production_ingest_job
                        WHERE id = ? AND organization_id = ? AND production_order_id = ?
                        """,
                (rs, rowNum) -> {
                    var score = rs.getBigDecimal("template_match_score");
                    return new Job(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("production_order_id", UUID.class),
                        rs.getObject("requested_template_version_id", UUID.class),
                        rs.getObject("selected_template_version_id", UUID.class),
                        rs.getString("source_type"), rs.getString("match_mode"),
                        rs.getString("status"), score == null ? null : score.doubleValue(),
                        rs.getInt("result_version"), parseNullable(rs.getString("result_jsonb")),
                        rs.getString("error_message"), sources(jobId), items(jobId),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant());
                },
                jobId, organizationId, orderId).stream().findFirst();
    }

    @Override
    public void markProcessing(UUID jobId) {
        jdbcTemplate.update("""
                UPDATE mfg.production_ingest_job
                SET status = 'PROCESSING', updated_at = now(), error_message = NULL
                WHERE id = ? AND status = 'QUEUED'
                """, jobId);
    }

    @Override
    public void saveResult(
            UUID jobId,
            UUID selectedTemplateVersionId,
            String matchMode,
            double score,
            JsonNode result,
            List<NewItem> items
    ) {
        jdbcTemplate.update("DELETE FROM mfg.production_ingest_item WHERE ingest_job_id = ?", jobId);
        if (!items.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO mfg.production_ingest_item (
                        id, ingest_job_id, item_key, item_kind, binding_id, field_code,
                        data_path, record_key, record_index, raw_value_jsonb,
                        normalized_value_jsonb, source_locator_jsonb, confidence, review_status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, items, items.size(), (statement, item) -> {
                statement.setObject(1, item.id());
                statement.setObject(2, jobId);
                statement.setString(3, item.itemKey());
                statement.setString(4, item.itemKind());
                statement.setString(5, item.bindingId());
                statement.setString(6, item.fieldCode());
                statement.setString(7, item.dataPath());
                statement.setString(8, item.recordKey());
                if (item.recordIndex() == null) statement.setNull(9, Types.INTEGER);
                else statement.setInt(9, item.recordIndex());
                statement.setObject(10, pgJson(item.rawValue()));
                statement.setObject(11, pgJson(item.normalizedValue()));
                statement.setObject(12, pgJson(item.sourceLocator()));
                statement.setDouble(13, item.confidence());
                statement.setString(14, item.reviewStatus());
            });
        }
        jdbcTemplate.update("""
                UPDATE mfg.production_ingest_job
                SET selected_template_version_id = ?, match_mode = ?, template_match_score = ?,
                    result_jsonb = ?, result_version = result_version + 1,
                    status = 'REVIEW_REQUIRED', updated_at = now(), error_message = NULL
                WHERE id = ? AND status = 'PROCESSING'
                """, selectedTemplateVersionId, matchMode, score, pgJson(result), jobId);
    }

    @Override
    public void markFailed(UUID jobId, String message) {
        jdbcTemplate.update("""
                UPDATE mfg.production_ingest_job
                SET status = 'FAILED', error_message = ?, updated_at = now()
                WHERE id = ? AND status IN ('QUEUED', 'PROCESSING')
                """, message, jobId);
    }

    @Override
    public int confirm(
            UUID organizationId, UUID orderId, UUID jobId, int resultVersion, JsonNode confirmedData
    ) {
        var updated = jdbcTemplate.update("""
                UPDATE mfg.production_ingest_job
                SET status = 'CONFIRMED', confirmed_at = now(), updated_at = now(),
                    result_jsonb = jsonb_set(result_jsonb, '{confirmedData}', ?, true)
                WHERE id = ? AND organization_id = ? AND production_order_id = ?
                  AND status = 'REVIEW_REQUIRED' AND result_version = ?
                """, pgJson(confirmedData), jobId, organizationId, orderId, resultVersion);
        if (updated > 0) {
            jdbcTemplate.update("""
                    UPDATE mfg.production_ingest_item
                    SET review_status = 'CONFIRMED',
                        user_value_jsonb = normalized_value_jsonb,
                        updated_at = now()
                    WHERE ingest_job_id = ? AND review_status IN ('EXTRACTED', 'NEEDS_REVIEW')
                    """, jobId);
        }
        return updated;
    }

    @Override
    public int cancel(UUID organizationId, UUID orderId, UUID jobId) {
        return jdbcTemplate.update("""
                UPDATE mfg.production_ingest_job
                SET status = 'CANCELLED', updated_at = now()
                WHERE id = ? AND organization_id = ? AND production_order_id = ?
                  AND status IN ('QUEUED', 'PROCESSING', 'REVIEW_REQUIRED', 'FAILED')
                """, jobId, organizationId, orderId);
    }

    private List<UUID> sources(UUID jobId) {
        return jdbcTemplate.queryForList("""
                SELECT file_object_id FROM mfg.production_ingest_source
                WHERE ingest_job_id = ? ORDER BY page_order
                """, UUID.class, jobId);
    }

    private List<Item> items(UUID jobId) {
        return jdbcTemplate.query("""
                SELECT id, item_key, item_kind, binding_id, field_code, data_path,
                       record_key, record_index, raw_value_jsonb, normalized_value_jsonb,
                       user_value_jsonb, source_locator_jsonb, confidence, review_status
                FROM mfg.production_ingest_item
                WHERE ingest_job_id = ? ORDER BY item_kind, record_index NULLS FIRST, item_key
                """, (rs, rowNum) -> new Item(
                rs.getObject("id", UUID.class), rs.getString("item_key"),
                rs.getString("item_kind"), rs.getString("binding_id"),
                rs.getString("field_code"), rs.getString("data_path"),
                rs.getString("record_key"), (Integer) rs.getObject("record_index"),
                parseNullable(rs.getString("raw_value_jsonb")),
                parseNullable(rs.getString("normalized_value_jsonb")),
                parseNullable(rs.getString("user_value_jsonb")),
                parse(rs.getString("source_locator_jsonb")),
                rs.getDouble("confidence"), rs.getString("review_status")), jobId);
    }

    private PGobject pgJson(JsonNode value) {
        try {
            var result = new PGobject();
            result.setType("jsonb");
            result.setValue(value == null ? "null" : objectMapper.writeValueAsString(value));
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to serialize JSONB", exception);
        }
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to parse JSONB", exception);
        }
    }

    private JsonNode parseNullable(String value) {
        return value == null ? null : parse(value);
    }
}
