package com.jsd.aird.tpl.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.tpl.application.port.StandardFieldRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class JdbcStandardFieldRepository implements StandardFieldRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcStandardFieldRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<StandardField> search(String keyword, String valueType) {
        var normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        var normalizedValueType = StringUtils.hasText(valueType) ? valueType.trim() : null;
        var sql = new StringBuilder("""
                SELECT id, dictionary_code, version_no, display_name, data_type,
                       ui_type, group_code, default_unit, description
                FROM tpl.standard_field_dictionary
                WHERE status = 'ACTIVE'
                """);
        var arguments = new ArrayList<Object>();
        if (normalizedKeyword != null) {
            sql.append("""
                    AND (lower(display_name) LIKE lower(?)
                         OR lower(dictionary_code) LIKE lower(?)
                         OR EXISTS (
                             SELECT 1 FROM tpl.standard_field_alias a
                             WHERE a.dictionary_id = tpl.standard_field_dictionary.id
                               AND lower(a.alias) LIKE lower(?)
                         ))
                    """);
            var pattern = "%" + normalizedKeyword + "%";
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (normalizedValueType != null) {
            sql.append(" AND data_type = ?\n");
            arguments.add(normalizedValueType);
        }
        sql.append("ORDER BY display_name, dictionary_code, version_no DESC LIMIT 100");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapStandardField(rs), arguments.toArray());
    }

    @Override
    public Optional<StandardField> find(UUID id) {
        return queryOne("""
                SELECT id, dictionary_code, version_no, display_name, data_type,
                       ui_type, group_code, default_unit, description
                FROM tpl.standard_field_dictionary
                WHERE id = ? AND status = 'ACTIVE'
                """, id);
    }

    @Override
    public Optional<StandardField> findActive(String fieldCode, int version, UUID id) {
        return jdbcTemplate.query("""
                SELECT id, dictionary_code, version_no, display_name, data_type,
                       ui_type, group_code, default_unit, description
                FROM tpl.standard_field_dictionary
                WHERE id = ? AND dictionary_code = ? AND version_no = ? AND status = 'ACTIVE'
                """, (rs, rowNum) -> mapStandardField(rs), id, fieldCode, version).stream().findFirst();
    }

    @Override
    public boolean isDictionaryAdmin(UUID organizationId, UUID userId) {
        var count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM iam.app_user
                WHERE id = ? AND organization_id = ? AND dictionary_admin = true
                """, Long.class, userId, organizationId);
        return count != null && count > 0;
    }

    @Override
    public boolean belongsToOrganization(UUID organizationId, UUID templateVersionId) {
        var count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tpl.template_version tv
                JOIN tpl.template t ON t.id = tv.template_id
                WHERE tv.id = ? AND t.organization_id = ?
                """, Long.class, templateVersionId, organizationId);
        return count != null && count > 0;
    }

    @Override
    public Request insertRequest(RequestDraft request) {
        jdbcTemplate.update("""
                INSERT INTO tpl.standard_field_request (
                    id, organization_id, template_version_id, field_id, display_name,
                    data_type, ui_type, group_code, description, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, request.id(), request.organizationId(), request.templateVersionId(), request.fieldId(),
                request.displayName(), request.valueType(), request.uiType(), request.groupCode(),
                request.description(), request.createdBy());
        return findRequest(request.organizationId(), request.id()).orElseThrow();
    }

    @Override
    public Optional<Request> findRequest(UUID organizationId, UUID requestId) {
        return jdbcTemplate.query("""
                SELECT id, organization_id, template_version_id, field_id, display_name,
                       data_type, ui_type, group_code, description, status, proposed_field_code,
                       approved_dictionary_id, review_comment, created_by, created_at,
                       reviewed_by, reviewed_at
                FROM tpl.standard_field_request
                WHERE organization_id = ? AND id = ?
                """, (rs, rowNum) -> mapRequest(rs), organizationId, requestId).stream().findFirst();
    }

    @Override
    public List<Request> listRequests(UUID organizationId, String status) {
        var sql = new StringBuilder("""
                SELECT id, organization_id, template_version_id, field_id, display_name,
                       data_type, ui_type, group_code, description, status, proposed_field_code,
                       approved_dictionary_id, review_comment, created_by, created_at,
                       reviewed_by, reviewed_at
                FROM tpl.standard_field_request
                WHERE organization_id = ?
                """);
        var arguments = new ArrayList<Object>();
        arguments.add(organizationId);
        if (StringUtils.hasText(status)) {
            sql.append(" AND status = ?\n");
            arguments.add(status.trim());
        }
        sql.append("ORDER BY created_at DESC");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRequest(rs), arguments.toArray());
    }

    @Override
    public StandardField approveRequest(
            UUID organizationId, UUID requestId, UUID reviewerId, String fieldCode, String reviewComment
    ) {
        var request = findRequest(organizationId, requestId).orElseThrow();
        var dictionaryId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO tpl.standard_field_dictionary (
                    id, dictionary_code, version_no, display_name, data_type,
                    description, group_code, ui_type, status
                ) VALUES (?, ?, 1, ?, ?, ?, ?, ?, 'ACTIVE')
                """, dictionaryId, fieldCode, request.displayName(), request.valueType(),
                request.description(), request.groupCode(), request.uiType());
        jdbcTemplate.update("""
                UPDATE tpl.standard_field_request
                SET status = 'APPROVED', proposed_field_code = ?, approved_dictionary_id = ?,
                    review_comment = ?, reviewed_by = ?, reviewed_at = now()
                WHERE organization_id = ? AND id = ? AND status = 'PENDING'
                """, fieldCode, dictionaryId, reviewComment, reviewerId, organizationId, requestId);
        return find(dictionaryId).orElseThrow();
    }

    @Override
    public void rejectRequest(UUID organizationId, UUID requestId, UUID reviewerId, String reviewComment) {
        jdbcTemplate.update("""
                UPDATE tpl.standard_field_request
                SET status = 'REJECTED', review_comment = ?, reviewed_by = ?, reviewed_at = now()
                WHERE organization_id = ? AND id = ? AND status = 'PENDING'
                """, reviewComment, reviewerId, organizationId, requestId);
    }

    @Override
    public void backfillTemplateField(UUID organizationId, Request request, StandardField standardField) {
        if (request.templateVersionId() == null || !belongsToOrganization(organizationId, request.templateVersionId())) return;
        jdbcTemplate.update("""
                UPDATE tpl.template_version tv
                SET schema_jsonb = jsonb_set(
                    tv.schema_jsonb,
                    '{x-jsd-field-model,fields}',
                    COALESCE((
                        SELECT jsonb_agg(
                            CASE WHEN field->>'id' = ? OR field->>'fieldId' = ?
                                 THEN field || jsonb_build_object(
                                     'fieldCode', ?, 'standardFieldId', ?,
                                     'standardFieldVersion', 1, 'standardFieldName', ?,
                                     'fieldOrigin', 'STANDARD', 'standardSelectionStatus', 'CONFIRMED',
                                     'standardMatchStatus', 'CONFIRMED',
                                     'requiresStandardConfirmation', false, 'uiType', ?
                                 )
                                 ELSE field END
                        )
                        FROM jsonb_array_elements(tv.schema_jsonb->'x-jsd-field-model'->'fields') field
                    ), '[]'::jsonb), true
                ), updated_at = now()
                WHERE tv.id = ?
                  AND EXISTS (
                      SELECT 1 FROM tpl.template t
                      WHERE t.id = tv.template_id AND t.organization_id = ?
                  )
                """, request.fieldId(), request.fieldId(), standardField.fieldCode(), standardField.id(),
                standardField.displayName(), standardField.uiType(), request.templateVersionId(), organizationId);
        jdbcTemplate.update("""
                UPDATE tpl.template_mapping
                SET field_code = ?, diagnostic_jsonb = diagnostic_jsonb || jsonb_build_object(
                    'standardFieldId', ?, 'standardFieldVersion', 1,
                    'fieldOrigin', 'STANDARD'
                )
                WHERE template_version_id = ? AND field_id::text = ?
                """, standardField.fieldCode(), standardField.id(), request.templateVersionId(), request.fieldId());
    }

    private Optional<StandardField> queryOne(String sql, Object argument) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapStandardField(rs), argument).stream().findFirst();
    }

    private StandardField mapStandardField(ResultSet rs) throws SQLException {
        return new StandardField(
                rs.getObject("id", UUID.class), rs.getString("dictionary_code"),
                rs.getInt("version_no"), rs.getString("display_name"), rs.getString("data_type"),
                rs.getString("ui_type"), rs.getString("group_code"), rs.getString("default_unit"),
                rs.getString("description")
        );
    }

    private Request mapRequest(ResultSet rs) throws SQLException {
        return new Request(
                rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("template_version_id", UUID.class), rs.getString("field_id"),
                rs.getString("display_name"), rs.getString("data_type"), rs.getString("ui_type"),
                rs.getString("group_code"), rs.getString("description"), rs.getString("status"),
                rs.getString("proposed_field_code"), rs.getObject("approved_dictionary_id", UUID.class),
                rs.getString("review_comment"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getObject("reviewed_by", UUID.class),
                rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant()
        );
    }
}
