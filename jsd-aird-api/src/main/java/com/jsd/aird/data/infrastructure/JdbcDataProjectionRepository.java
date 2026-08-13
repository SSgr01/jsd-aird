package com.jsd.aird.data.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.data.application.port.DataProjectionRepository;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDataProjectionRepository implements DataProjectionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcDataProjectionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ProjectionResult project(UUID organizationId, UUID importJobId, UUID actorId,
                                    UUID templateVersionId, List<UUID> revisionIds,
                                    List<TemplateDataImportFacade.ImportBinding> bindings) {
        var revisionFilter = revisionIds == null || revisionIds.isEmpty()
                ? ""
                : " AND r.id IN (" + String.join(",", java.util.Collections.nCopies(revisionIds.size(), "?")) + ")";
        var revisionArgs = new ArrayList<Object>();
        revisionArgs.add(organizationId);
        revisionArgs.add(importJobId);
        if (revisionIds != null) revisionArgs.addAll(revisionIds);
        var revisions = jdbc.query("""
                SELECT r.id, r.asset_id, r.revision_no, r.raw_data_jsonb,
                       r.normalized_data_jsonb, r.corrected_data_jsonb, a.asset_key
                FROM data.data_asset_revision r
                JOIN data.data_asset a ON a.id = r.asset_id
                WHERE a.organization_id = ? AND r.import_job_id = ?
                  AND r.publication_status = 'PUBLISHED'
                """ + revisionFilter + """
                ORDER BY r.revision_no, r.id
                """, this::revision, revisionArgs.toArray());
        if (revisions.isEmpty()) return new ProjectionResult(null, 0, 0, 0);

        retireDatasets(organizationId, importJobId, null);
        jdbc.update("DELETE FROM data.data_record WHERE organization_id = ? AND import_job_id = ?",
                organizationId, importJobId);

        var datasetId = UUID.randomUUID();
        var sourceRevisionIds = objectMapper.createArrayNode();
        revisions.forEach(item -> sourceRevisionIds.add(item.id().toString()));
        var schema = objectMapper.createObjectNode()
                .put("projection", "LONG_TABLE")
                .put("projectionVersion", "v1")
                .put("templateVersionId", templateVersionId.toString());
        var contract = jdbc.query("""
                SELECT import_contract_version, contract_hash
                FROM data.import_job WHERE organization_id = ? AND id = ?
                """, (rs, rowNum) -> new ContractRef((Integer) rs.getObject(1), rs.getString(2)),
                organizationId, importJobId).stream().findFirst().orElse(new ContractRef(null, null));
        if (contract.version() != null) {
            schema.put("importContractVersion", contract.version())
                    .put("contractHash", contract.hash())
                    .put("approvalPolicy", "DISABLED");
        }
        jdbc.update("""
                INSERT INTO ai.training_dataset (
                    id, organization_id, import_job_id, template_version_id, projection_version,
                    name, status, schema_jsonb, quality_summary_jsonb, source_revision_ids_jsonb, created_by,
                    import_contract_version, contract_hash, approval_policy
                ) VALUES (?, ?, ?, ?, 'v1', ?, 'DRAFT', ?, '{}'::jsonb, ?, ?, ?, ?, ?)
                """, datasetId, organizationId, importJobId, templateVersionId,
                "导入批次长表候选 - " + importJobId, pgJson(schema), pgJson(sourceRevisionIds), actorId,
                contract.version(), contract.hash(), contract.version() == null ? "LEGACY" : "DISABLED");

        int recordCount = 0;
        int longValueCount = 0;
        int eligibleRecordCount = 0;
        var bindingsByField = bindings.stream().filter(item -> item.fieldCode() != null && !item.fieldCode().isBlank())
                .collect(java.util.stream.Collectors.toMap(TemplateDataImportFacade.ImportBinding::fieldCode,
                        item -> item, (left, right) -> left));
        var bindingsById = bindings.stream().filter(item -> item.bindingId() != null && !item.bindingId().isBlank())
                .collect(java.util.stream.Collectors.toMap(TemplateDataImportFacade.ImportBinding::bindingId,
                        item -> item, (left, right) -> left));
        for (var item : revisions) {
            var anchors = anchors(item.id());
            var firstAnchor = primaryAnchor(anchors, bindingsByField);
            var recordId = UUID.randomUUID();
            var recordKey = item.assetKey() + ":V" + item.revisionNo();
            var effective = effective(item.normalized(), item.corrected());
            jdbc.update("""
                    INSERT INTO data.data_record (
                        id, organization_id, import_job_id, asset_id, revision_id, record_key, record_index,
                        sheet_id, sheet_name, source_row_number, raw_data_jsonb, normalized_data_jsonb,
                        corrected_data_jsonb, effective_data_jsonb, quality_status, synthetic_key
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'VALID', false)
                    """, recordId, organizationId, importJobId, item.assetId(), item.id(), recordKey,
                    item.revisionNo(), firstAnchor == null ? null : firstAnchor.sheetId(),
                    firstAnchor == null ? null : firstAnchor.sheetName(),
                    firstAnchor == null ? null : firstAnchor.rowNumber(), pgJson(item.raw()),
                    pgJson(item.normalized()), pgJson(item.corrected()), pgJson(effective));

            var dimensions = objectMapper.createObjectNode();
            var measures = objectMapper.createObjectNode();
            var eligible = true;
            var recordValueCount = 0;
            var values = effective.fields();
            while (values.hasNext()) {
                var entry = values.next();
                var fieldCode = entry.getValue().path("fieldCode").asText(entry.getKey().split("@", 2)[0]);
                var binding = bindingsById.get(entry.getValue().path("bindingId").asText(""));
                if (binding == null) binding = bindingsByField.get(fieldCode);
                if (binding != null) {
                    dimensions.put(entry.getKey() + ".mappingKind", binding.mappingKind());
                    if (binding.repeatAxis() != null && !binding.repeatAxis().isBlank()) {
                        dimensions.put(entry.getKey() + ".repeatAxis", binding.repeatAxis());
                    }
                }
                var dataPath = binding == null || binding.dataPath().isBlank()
                        ? entry.getValue().path("dataPath").asText("/" + escape(entry.getKey()))
                        : binding.dataPath();
                var effectiveValue = effectiveValue(entry.getValue());
                if (binding == null && dataPath.startsWith("/dimensions/")) {
                    dimensions.put(dataPath.substring("/dimensions/".length()), effectiveValue.asText(""));
                }
                var dimensionOnly = binding == null && dataPath.startsWith("/dimensions/");
                var result = appendValues(organizationId, recordId, fieldCode, dataPath,
                        entry.getValue(), anchors.getOrDefault(entry.getKey(), List.of()),
                        dimensionOnly ? objectMapper.createObjectNode() : measures,
                        binding == null || binding.trainingEligible(), binding);
                recordValueCount += result.count();
                longValueCount += result.count();
                var role = binding == null ? "FEATURE" : binding.trainingRole();
                var requiredForTraining = binding != null && binding.required()
                        && ("FEATURE".equalsIgnoreCase(role) || "TARGET".equalsIgnoreCase(role));
                if (requiredForTraining) eligible &= result.eligible();
            }
            eligible &= recordValueCount > 0;
            if (eligible) eligibleRecordCount++;
            var source = objectMapper.createObjectNode();
            source.put("assetId", item.assetId().toString())
                    .put("revisionId", item.id().toString())
                    .put("revisionNo", item.revisionNo());
            source.set("sourceAnchor", firstAnchor == null ? objectMapper.nullNode() : anchorJson(firstAnchor));
            source.set("sourceAnchors", anchorArray(anchors));
            jdbc.update("""
                    INSERT INTO ai.training_dataset_record (
                        id, dataset_id, source_record_id, record_key, dimensions_jsonb, measures_jsonb,
                        source_jsonb, training_eligible, exclusion_reason
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), datasetId, recordId, recordKey, pgJson(dimensions), pgJson(measures), pgJson(source),
                    eligible, eligible ? null : "存在空值、公式值或不可训练字段");
            recordCount++;
        }
        var quality = objectMapper.createObjectNode()
                .put("recordCount", recordCount)
                .put("longValueCount", longValueCount)
                .put("eligibleRecordCount", eligibleRecordCount)
                .put("status", "DRAFT");
        jdbc.update("""
                UPDATE ai.training_dataset
                SET quality_summary_jsonb = ?, updated_at = now()
                WHERE id = ? AND organization_id = ?
                """, pgJson(quality), datasetId, organizationId);
        jdbc.update("""
                UPDATE data.import_job SET latest_training_dataset_id = ?, updated_at = now()
                WHERE id = ? AND organization_id = ?
                """, datasetId, importJobId, organizationId);
        return new ProjectionResult(datasetId, recordCount, longValueCount, eligibleRecordCount);
    }

    @Override
    public Optional<TrainingDataset> findLatestDataset(UUID organizationId, UUID importJobId) {
        return jdbc.query("""
                SELECT d.id, d.import_job_id, d.template_version_id, d.projection_version,
                       d.name, d.status, d.schema_jsonb, d.quality_summary_jsonb,
                       d.source_revision_ids_jsonb,
                       coalesce((d.quality_summary_jsonb->>'recordCount')::int, 0) AS record_count,
                       coalesce((d.quality_summary_jsonb->>'eligibleRecordCount')::int, 0) AS eligible_count
                FROM ai.training_dataset d
                WHERE d.organization_id = ? AND d.import_job_id = ?
                ORDER BY d.created_at DESC LIMIT 1
                """, this::dataset, organizationId, importJobId).stream().findFirst();
    }

    @Override
    public Optional<TrainingDataset> findDataset(UUID organizationId, UUID datasetId) {
        return jdbc.query("""
                SELECT d.id, d.import_job_id, d.template_version_id, d.projection_version,
                       d.name, d.status, d.schema_jsonb, d.quality_summary_jsonb,
                       d.source_revision_ids_jsonb,
                       coalesce((d.quality_summary_jsonb->>'recordCount')::int, 0) AS record_count,
                       coalesce((d.quality_summary_jsonb->>'eligibleRecordCount')::int, 0) AS eligible_count
                FROM ai.training_dataset d
                WHERE d.organization_id = ? AND d.id = ?
                """, this::dataset, organizationId, datasetId).stream().findFirst();
    }

    @Override
    public List<LongTableRow> previewRows(UUID organizationId, UUID importJobId, int limit) {
        return jdbc.query("""
                SELECT r.record_key, r.dimensions_jsonb, r.measures_jsonb, r.source_jsonb,
                       r.training_eligible, r.exclusion_reason
                FROM ai.training_dataset_record r
                JOIN ai.training_dataset d ON d.id = r.dataset_id
                WHERE d.organization_id = ? AND d.import_job_id = ?
                  AND d.created_at = (
                      SELECT max(previous.created_at)
                      FROM ai.training_dataset previous
                      WHERE previous.organization_id = d.organization_id
                        AND previous.import_job_id = d.import_job_id
                  )
                ORDER BY r.created_at, r.record_key
                LIMIT ?
                """, (rs, rowNum) -> new LongTableRow(
                rs.getString("record_key"), parse(rs.getString("dimensions_jsonb")),
                parse(rs.getString("measures_jsonb")), parse(rs.getString("source_jsonb")),
                rs.getBoolean("training_eligible"), rs.getString("exclusion_reason")),
                organizationId, importJobId, Math.min(100, Math.max(1, limit)));
    }

    @Override
    public void retireDatasets(UUID organizationId, UUID importJobId, UUID exceptDatasetId) {
        if (exceptDatasetId == null) {
            jdbc.update("""
                    UPDATE ai.training_dataset SET status = 'RETIRED', updated_at = now()
                    WHERE organization_id = ? AND import_job_id = ? AND status <> 'RETIRED'
                    """, organizationId, importJobId);
        } else {
            jdbc.update("""
                    UPDATE ai.training_dataset SET status = 'RETIRED', updated_at = now()
                    WHERE organization_id = ? AND import_job_id = ? AND id <> ? AND status <> 'RETIRED'
                    """, organizationId, importJobId, exceptDatasetId);
        }
    }

    @Override
    public void updateDatasetStatus(UUID organizationId, UUID datasetId, String status, UUID actorId) {
        jdbc.update("""
                UPDATE ai.training_dataset
                SET status = ?, approved_by = CASE WHEN ? = 'APPROVED' THEN ? ELSE approved_by END,
                    approved_at = CASE WHEN ? = 'APPROVED' THEN now() ELSE approved_at END,
                    updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, status, status, actorId, status, organizationId, datasetId);
    }

    private Revision revision(ResultSet rs, int rowNum) throws SQLException {
        return new Revision(rs.getObject("id", UUID.class), rs.getObject("asset_id", UUID.class),
                rs.getInt("revision_no"), parse(rs.getString("raw_data_jsonb")),
                parse(rs.getString("normalized_data_jsonb")), parse(rs.getString("corrected_data_jsonb")),
                rs.getString("asset_key"));
    }

    private TrainingDataset dataset(ResultSet rs, int rowNum) throws SQLException {
        return new TrainingDataset(rs.getObject("id", UUID.class), rs.getObject("import_job_id", UUID.class),
                rs.getObject("template_version_id", UUID.class), rs.getString("projection_version"),
                rs.getString("name"), rs.getString("status"), parse(rs.getString("schema_jsonb")),
                parse(rs.getString("quality_summary_jsonb")), parse(rs.getString("source_revision_ids_jsonb")),
                rs.getInt("record_count"), rs.getInt("eligible_count"));
    }

    private Map<String, List<Anchor>> anchors(UUID revisionId) {
        var result = new LinkedHashMap<String, List<Anchor>>();
        jdbc.query("""
                SELECT id, field_code, sheet_id, sheet_name, row_number, column_name, cell_address
                FROM data.source_anchor WHERE asset_revision_id = ? ORDER BY row_number, column_number
                """, (rs, rowNum) -> {
            var anchor = new Anchor(rs.getObject("id", UUID.class), rs.getString("field_code"),
                    rs.getString("sheet_id"), rs.getString("sheet_name"),
                    (Integer) rs.getObject("row_number"), rs.getString("column_name"),
                    rs.getString("cell_address"));
            result.computeIfAbsent(anchor.fieldCode(), ignored -> new ArrayList<>()).add(anchor);
            return anchor;
        }, revisionId);
        return result;
    }

    private AppendResult appendValues(UUID organizationId, UUID recordId, String fieldCode, String dataPath,
                                      JsonNode node, List<Anchor> anchors, ObjectNode measures,
                                      boolean bindingTrainingEligible,
                                      TemplateDataImportFacade.ImportBinding binding) {
        if (node != null && node.isArray()) {
            int count = 0;
            boolean eligible = true;
            for (int i = 0; i < node.size(); i++) {
                var child = appendValues(organizationId, recordId, fieldCode, dataPath + "/" + i,
                        node.get(i), anchors, measures, bindingTrainingEligible, binding);
                count += child.count();
                eligible &= child.eligible();
            }
            return new AppendResult(count, eligible);
        }
        var wrapper = node != null && node.isObject() && node.has("normalizedValue") ? node : null;
        var value = wrapper == null ? node : effectiveValue(wrapper);
        var eligible = value != null && !value.isNull() && !value.asText("").isBlank();
        eligible &= bindingTrainingEligible;
        if (wrapper != null && wrapper.has("trainingEligible")) eligible &= wrapper.path("trainingEligible").asBoolean(true);
        var reason = eligible ? null : "空值或不可训练字段";
        var wrapperValueSource = wrapper == null ? "" : wrapper.path("valueSource").asText("");
        var valueSource = !wrapperValueSource.isBlank() ? wrapperValueSource.toUpperCase(java.util.Locale.ROOT)
                : binding == null || binding.valueSource() == null || binding.valueSource().isBlank()
                ? "INPUT" : binding.valueSource().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("INPUT", "FORMULA", "DERIVED", "STATIC").contains(valueSource)) valueSource = "INPUT";
        var calculationSource = wrapper == null ? null : wrapper.path("calculationSource").asText(null);
        var calculationStatus = wrapper == null ? null : wrapper.path("calculationStatus").asText(null);
        var formulaTrustStatus = wrapper == null ? null : wrapper.path("formulaTrustStatus").asText(null);
        if ("FORMULA".equals(valueSource)) {
            if (calculationSource == null || calculationSource.isBlank()) calculationSource = "MISSING";
            if (calculationStatus == null || calculationStatus.isBlank()) calculationStatus = "FAILED";
            if (formulaTrustStatus == null || formulaTrustStatus.isBlank()) {
                formulaTrustStatus = "VALID".equals(calculationStatus) ? "TRUSTED_RECALCULATED"
                        : "STALE_POSSIBLE".equals(calculationStatus) ? "UNVERIFIED_CACHE" : "MISSING_RESULT";
            }
            eligible &= "VALID".equals(calculationStatus);
            if (!eligible) reason = "公式计算结果缺失或不可信";
        } else {
            calculationSource = null;
            calculationStatus = null;
            formulaTrustStatus = "NOT_APPLICABLE";
        }
        var anchor = anchors.isEmpty() ? null : anchors.getFirst();
        var anchorId = anchor == null ? null : anchor.id();
        var effectiveBindingId = binding == null || binding.bindingId() == null || binding.bindingId().isBlank()
                ? fieldCode : binding.bindingId();
        var labelPath = binding == null ? wrapper == null ? null : wrapper.path("labelPath").asText(null) : binding.labelPath();
        var ragEligible = binding == null ? wrapper == null || wrapper.path("ragEligible").asBoolean(true) : binding.ragEligible();
        jdbc.update("""
                INSERT INTO data.data_value (
                    id, organization_id, record_id, field_code, data_path, value_jsonb, value_text,
                    normalized_unit, source_anchor_id, training_eligible, exclusion_reason
                    , binding_id, value_path, label_path, rag_eligible, value_source, calculation_source, calculation_status,
                    calculation_trust_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (record_id, binding_id, value_path)
                    WHERE binding_id IS NOT NULL AND value_path IS NOT NULL
                DO UPDATE SET
                    value_jsonb = EXCLUDED.value_jsonb, value_text = EXCLUDED.value_text,
                    normalized_unit = EXCLUDED.normalized_unit, source_anchor_id = EXCLUDED.source_anchor_id,
                    training_eligible = EXCLUDED.training_eligible, exclusion_reason = EXCLUDED.exclusion_reason,
                    binding_id = EXCLUDED.binding_id, value_path = EXCLUDED.value_path,
                    label_path = EXCLUDED.label_path, rag_eligible = EXCLUDED.rag_eligible,
                    value_source = EXCLUDED.value_source, calculation_source = EXCLUDED.calculation_source,
                    calculation_status = EXCLUDED.calculation_status,
                    calculation_trust_status = EXCLUDED.calculation_trust_status
                """, UUID.randomUUID(), organizationId, recordId, fieldCode, dataPath,
                pgJson(value == null ? objectMapper.nullNode() : value),
                value == null || value.isContainerNode() ? null : value.asText(),
                wrapper == null ? null : wrapper.path("normalizedUnit").asText(null), anchorId,
                eligible, reason, effectiveBindingId, dataPath,
                labelPath, ragEligible, valueSource, calculationSource, calculationStatus, formulaTrustStatus);
        if (!"FORMULA".equals(valueSource) || "VALID".equals(calculationStatus)) {
            measures.set(dataPath, value == null ? objectMapper.nullNode() : value.deepCopy());
        }
        return new AppendResult(1, eligible);
    }

    private JsonNode effective(JsonNode normalized, JsonNode corrected) {
        ObjectNode result = normalized == null || !normalized.isObject()
                ? objectMapper.createObjectNode() : (ObjectNode) normalized.deepCopy();
        if (corrected != null && corrected.isObject()) {
            var fields = corrected.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                var correctedValue = entry.getValue();
                if (correctedValue != null && !correctedValue.isNull()
                        && !correctedValue.path("normalizedValue").asText("").isBlank()) {
                    result.set(entry.getKey(), correctedValue.deepCopy());
                }
            }
        }
        return result;
    }

    private JsonNode effectiveValue(JsonNode wrapper) {
        if (wrapper.has("correctedValue") && !wrapper.path("correctedValue").isNull()) return wrapper.path("correctedValue");
        if (wrapper.has("normalizedValue")) return wrapper.path("normalizedValue");
        return wrapper.path("rawValue");
    }

    private ObjectNode anchorJson(Anchor anchor) {
        return objectMapper.createObjectNode().put("id", anchor.id().toString())
                .put("fieldCode", anchor.fieldCode())
                .put("sheetId", anchor.sheetId()).put("sheetName", anchor.sheetName())
                .put("rowNumber", anchor.rowNumber() == null ? 0 : anchor.rowNumber())
                .put("columnName", anchor.columnName()).put("address", anchor.address());
    }

    private ArrayNode anchorArray(Map<String, List<Anchor>> anchors) {
        var result = objectMapper.createArrayNode();
        anchors.values().stream().flatMap(List::stream).forEach(anchor -> result.add(anchorJson(anchor)));
        return result;
    }

    private Anchor primaryAnchor(Map<String, List<Anchor>> anchors,
                                 Map<String, TemplateDataImportFacade.ImportBinding> bindingsByField) {
        var structured = anchors.entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith("DATA.DIMENSION."))
                .filter(entry -> {
                    var binding = bindingsByField.get(entry.getKey());
                    if (binding == null || binding.mappingKind() == null) return false;
                    var kind = binding.mappingKind().toUpperCase(java.util.Locale.ROOT);
                    return kind.contains("REPEAT") || kind.contains("MATRIX") || kind.contains("TABLE");
                })
                .flatMap(entry -> entry.getValue().stream())
                .findFirst();
        if (structured.isPresent()) return structured.get();
        return anchors.entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith("DATA.DIMENSION."))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElseGet(() -> anchors.values().stream().flatMap(List::stream).findFirst().orElse(null));
    }

    private String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private PGobject pgJson(JsonNode value) {
        try {
            var result = new PGobject();
            result.setType("jsonb");
            result.setValue(objectMapper.writeValueAsString(value == null ? objectMapper.nullNode() : value));
            return result;
        } catch (JsonProcessingException | java.sql.SQLException exception) {
            throw new IllegalStateException("无法序列化投影 JSON", exception);
        }
    }

    private JsonNode parse(String value) {
        if (value == null) return objectMapper.createObjectNode();
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid JSONB", exception); }
    }

    private record Revision(UUID id, UUID assetId, int revisionNo, JsonNode raw, JsonNode normalized, JsonNode corrected,
                            String assetKey) {}
    private record Anchor(UUID id, String fieldCode, String sheetId, String sheetName, Integer rowNumber,
                          String columnName, String address) {}
    private record AppendResult(int count, boolean eligible) {}
    private record ContractRef(Integer version, String hash) {}
}
