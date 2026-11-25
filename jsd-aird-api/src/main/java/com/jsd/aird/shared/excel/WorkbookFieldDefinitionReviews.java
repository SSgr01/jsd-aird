package com.jsd.aird.shared.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reuse reviewed field types within one organization's exact template version.
 * This approves definitions only, never the values of another experiment. */
@Component
public class WorkbookFieldDefinitionReviews {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonCanonicalizer canonicalizer;
    private final java.util.concurrent.ConcurrentMap<String, java.util.Set<String>> historical = new java.util.concurrent.ConcurrentHashMap<>();

    public WorkbookFieldDefinitionReviews(JdbcTemplate jdbc, ObjectMapper json, JsonCanonicalizer canonicalizer) {
        this.jdbc = jdbc; this.json = json; this.canonicalizer = canonicalizer;
    }

    public void remember(UUID organizationId, UUID versionId, JsonNode instance, UUID actorId) {
        if (versionId == null) return;
        for (var field : instance.path("model").path("fields")) {
            if (!local(field) || !field.path("reviewStatus").asText().equals("CONFIRMED")
                    || field.path("experimentSemanticStatus").asText().equals("NEEDS_REVIEW")) continue;
            var definition = definition(field);
            jdbc.update("""
                    INSERT INTO data.workbook_field_definition_review
                    (organization_id,template_version_id,definition_hash,definition_jsonb,confirmed_by)
                    VALUES(?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING
                    """, organizationId, versionId, canonicalizer.hash(definition), definition.toString(), actorId);
        }
        historical.remove(organizationId+":"+versionId);
    }

    public boolean apply(UUID organizationId, UUID versionId, JsonNode instance) {
        if (versionId == null || !instance.isObject()) return false;
        var accepted = new HashSet<>(jdbc.queryForList("""
                SELECT definition_hash FROM data.workbook_field_definition_review
                WHERE organization_id=? AND template_version_id=?
                """, String.class, organizationId, versionId));
        for(var text:jdbc.queryForList("""
                SELECT definition_jsonb::text FROM data.workbook_field_definition_review
                WHERE organization_id=? AND template_version_id=?
                """,String.class,organizationId,versionId)) {
            try { accepted.add(canonicalizer.hash(definition(json.readTree(text)))); }
            catch(com.fasterxml.jackson.core.JsonProcessingException exception) {throw new IllegalStateException("已确认字段定义无法读取",exception);}
        }
        // Older files already contain explicit human-confirmed definitions.
        // Read only their definition metadata once per template scope, never values.
        accepted.addAll(historical.computeIfAbsent(organizationId + ":" + versionId,
                ignored -> historicalDefinitions(organizationId, versionId)));
        boolean changed = false;
        for (var field : instance.path("model").path("fields")) {
            if (!(field instanceof ObjectNode object) || !local(field)
                    || field.path("reviewStatus").asText().equals("CONFIRMED")
                       && !field.path("experimentSemanticStatus").asText().equals("NEEDS_REVIEW")) continue;
            if (!accepted.contains(canonicalizer.hash(definition(field)))) continue;
            object.put("reviewStatus", "CONFIRMED");
            if (field.path("experimentSemanticStatus").asText().equals("NEEDS_REVIEW"))
                object.put("experimentSemanticStatus", "CONFIRMED");
            changed = true;
        }
        return changed;
    }

    private java.util.Set<String> historicalDefinitions(UUID organizationId, UUID versionId) {
        var result = new HashSet<String>();
        var fields = jdbc.queryForList("""
                SELECT field::text FROM data.import_workbook_draft w
                JOIN data.import_job j ON j.id=w.import_job_id AND j.organization_id=w.organization_id
                CROSS JOIN LATERAL jsonb_array_elements(COALESCE(w.instance_jsonb->'model'->'fields','[]'::jsonb)) field
                WHERE w.organization_id=? AND j.template_version_id=?
                  AND field->>'fieldOrigin'='INSTANCE_LOCAL' AND field->>'reviewStatus'='CONFIRMED'
                  AND COALESCE(field->>'experimentSemanticStatus','')<>'NEEDS_REVIEW'
                UNION
                SELECT field::text FROM rnd.experiment e
                JOIN rnd.experiment_version v ON v.id=e.current_version_id
                CROSS JOIN LATERAL jsonb_each(COALESCE(v.edit_model_jsonb->'documentSnapshot'->'sheets','{}'::jsonb)) sheet
                CROSS JOIN LATERAL jsonb_array_elements(COALESCE(sheet.value->'custom'->'templateInstance'->'model'->'fields','[]'::jsonb)) field
                WHERE e.organization_id=? AND v.template_version_id=? AND e.deleted=false
                  AND field->>'fieldOrigin'='INSTANCE_LOCAL' AND field->>'reviewStatus'='CONFIRMED'
                  AND COALESCE(field->>'experimentSemanticStatus','')<>'NEEDS_REVIEW'
                """, String.class, organizationId, versionId, organizationId, versionId);
        for (var text : fields) {
            try { result.add(canonicalizer.hash(definition(json.readTree(text)))); }
            catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException("已确认字段定义无法读取", exception); }
        }
        return java.util.Set.copyOf(result);
    }

    private boolean local(JsonNode field) {
        return field.path("fieldOrigin").asText().equals("INSTANCE_LOCAL") && !field.path("name").asText().isBlank();
    }

    JsonNode definition(JsonNode field) {
        var result = json.createObjectNode();
        result.put("name", normalizedLabel(field.path("name").asText()));
        result.put("groupId",field.path("groupId").asText().replaceFirst("^s[0-9a-f]{16}\\.",""));
        result.put("valueType", field.path("valueType").asText("string"));
        result.put("unit", field.path("unit").asText().strip());
        result.put("required", field.path("required").asBoolean());
        for (var key : List.of("experimentField", "baseMetric", "testCondition"))
            result.set(key, field.path(key).isMissingNode() ? json.nullNode() : field.path(key));
        return result;
    }

    private static String normalizedLabel(String value) {
        return value.replaceAll("[\\s　]+", "").replace('：', ':').replace('（','(').replace('）',')');
    }
}
