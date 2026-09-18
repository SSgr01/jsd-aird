package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.api.PageResponse;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.modeling.ModelingContracts.*;

@Repository
public class JdbcModelingConfigurationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcModelingConfigurationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public JdbcTemplate jdbc() { return jdbc; }

    public PageResponse<TargetSummary> targets(UUID organizationId, String keyword, String status,
                                               String valueType, String category, int page, int size) {
        var where = new StringBuilder(" WHERE t.organization_id = ?");
        var args = new ArrayList<Object>(); args.add(organizationId);
        filter(where, args, "t.status", status);
        filter(where, args, "t.value_type", valueType);
        filter(where, args, "t.performance_project", category);
        if (text(keyword)) {
            where.append(" AND (lower(t.name) LIKE lower(?) OR lower(t.target_code) LIKE lower(?))");
            args.add("%" + keyword.strip() + "%"); args.add("%" + keyword.strip() + "%");
        }
        var total = jdbc.queryForObject("SELECT count(*) FROM ai.prediction_target t" + where, Long.class, args.toArray());
        var queryArgs = new ArrayList<>(args);
        queryArgs.add(size); queryArgs.add((page - 1) * size);
        var rows = jdbc.query("""
                SELECT t.*, tv.version_no current_version_no, s.name current_scheme_name,
                       (SELECT mv.id FROM ai.model_version mv WHERE mv.organization_id=t.organization_id
                        AND mv.target_id=t.id AND mv.status='ACTIVE' LIMIT 1) active_model_id,
                       (SELECT CASE WHEN er.status='SUCCEEDED' THEN 'COMPLETED' ELSE er.status END FROM ai.eligibility_evaluation_run er
                        WHERE er.organization_id=t.organization_id AND er.target_id=t.id
                          AND (er.target_version_id=t.current_version_id OR t.current_version_id IS NULL)
                          AND (er.input_scheme_id=t.current_input_scheme_id OR t.current_input_scheme_id IS NULL)
                        ORDER BY er.created_at DESC LIMIT 1) eligibility_status
                FROM ai.prediction_target t
                LEFT JOIN ai.target_version tv ON tv.id=t.current_version_id AND tv.organization_id=t.organization_id
                LEFT JOIN ai.input_scheme s ON s.id=t.current_input_scheme_id AND s.organization_id=t.organization_id
                """ + where + " ORDER BY t.updated_at DESC, t.id LIMIT ? OFFSET ?", this::target, queryArgs.toArray());
        long count = total == null ? 0 : total;
        return new PageResponse<>(rows, page, size, count, count == 0 ? 0 : (count + size - 1) / size);
    }

    public Optional<TargetSummary> target(UUID organizationId, UUID id) {
        return jdbc.query("""
                SELECT t.*, tv.version_no current_version_no, s.name current_scheme_name,
                       (SELECT mv.id FROM ai.model_version mv WHERE mv.organization_id=t.organization_id
                        AND mv.target_id=t.id AND mv.status='ACTIVE' LIMIT 1) active_model_id,
                       (SELECT CASE WHEN er.status='SUCCEEDED' THEN 'COMPLETED' ELSE er.status END FROM ai.eligibility_evaluation_run er
                        WHERE er.organization_id=t.organization_id AND er.target_id=t.id
                          AND (er.target_version_id=t.current_version_id OR t.current_version_id IS NULL)
                          AND (er.input_scheme_id=t.current_input_scheme_id OR t.current_input_scheme_id IS NULL)
                        ORDER BY er.created_at DESC LIMIT 1) eligibility_status
                FROM ai.prediction_target t
                LEFT JOIN ai.target_version tv ON tv.id=t.current_version_id AND tv.organization_id=t.organization_id
                LEFT JOIN ai.input_scheme s ON s.id=t.current_input_scheme_id AND s.organization_id=t.organization_id
                WHERE t.organization_id=? AND t.id=?
                """, this::target, organizationId, id).stream().findFirst();
    }

    public List<TargetVersionView> targetVersions(UUID organizationId, UUID targetId) {
        return jdbc.query("""
                SELECT * FROM ai.target_version WHERE organization_id=? AND target_id=?
                ORDER BY version_no DESC, id
                """, this::targetVersion, organizationId, targetId);
    }

    public Optional<TargetVersionView> targetVersion(UUID organizationId, UUID id) {
        return jdbc.query("SELECT * FROM ai.target_version WHERE organization_id=? AND id=?",
                this::targetVersion, organizationId, id).stream().findFirst();
    }

    public List<SourceMappingView> sourceMappings(UUID organizationId, UUID targetId) {
        return jdbc.query("""
                SELECT * FROM ai.source_mapping_version WHERE organization_id=? AND target_id=?
                ORDER BY source_type, version_no DESC, id
                """, this::sourceMapping, organizationId, targetId);
    }

    public Optional<SourceMappingView> sourceMapping(UUID organizationId, UUID id) {
        return jdbc.query("SELECT * FROM ai.source_mapping_version WHERE organization_id=? AND id=?",
                this::sourceMapping, organizationId, id).stream().findFirst();
    }

    public PageResponse<InputFieldSummary> inputFields(UUID organizationId, String keyword, String status,
                                                       String valueType, int page, int size) {
        var where = new StringBuilder(" WHERE f.organization_id = ?");
        var args = new ArrayList<Object>(); args.add(organizationId);
        filter(where, args, "f.status", status); filter(where, args, "f.value_type", valueType);
        if (text(keyword)) {
            where.append(" AND (lower(f.name) LIKE lower(?) OR lower(f.field_code) LIKE lower(?))");
            args.add("%" + keyword.strip() + "%"); args.add("%" + keyword.strip() + "%");
        }
        var total = jdbc.queryForObject("SELECT count(*) FROM ai.input_field f" + where, Long.class, args.toArray());
        var queryArgs = new ArrayList<>(args); queryArgs.add(size); queryArgs.add((page - 1) * size);
        var rows = jdbc.query("""
                SELECT f.*, fv.version_no current_version_no, d.dictionary_code standard_field_code,
                       d.display_name standard_field_name,d.default_unit standard_field_unit,fv.unit
                FROM ai.input_field f
                LEFT JOIN ai.input_field_version fv ON fv.id=f.current_version_id AND fv.organization_id=f.organization_id
                LEFT JOIN tpl.standard_field_dictionary d ON d.id=fv.standard_field_dictionary_id
                """ + where + " ORDER BY f.updated_at DESC, f.id LIMIT ? OFFSET ?", this::inputField, queryArgs.toArray());
        long count = total == null ? 0 : total;
        return new PageResponse<>(rows, page, size, count, count == 0 ? 0 : (count + size - 1) / size);
    }

    public Optional<InputFieldSummary> inputField(UUID organizationId, UUID id) {
        return jdbc.query("""
                SELECT f.*, fv.version_no current_version_no, d.dictionary_code standard_field_code,
                       d.display_name standard_field_name,d.default_unit standard_field_unit,fv.unit
                FROM ai.input_field f
                LEFT JOIN ai.input_field_version fv ON fv.id=f.current_version_id AND fv.organization_id=f.organization_id
                LEFT JOIN tpl.standard_field_dictionary d ON d.id=fv.standard_field_dictionary_id
                WHERE f.organization_id=? AND f.id=?
                """, this::inputField, organizationId, id).stream().findFirst();
    }

    public List<InputFieldVersionView> inputFieldVersions(UUID organizationId, UUID inputFieldId) {
        return jdbc.query("""
                SELECT fv.*, d.dictionary_code standard_field_code,d.display_name standard_field_name,
                       d.default_unit standard_field_unit
                FROM ai.input_field_version fv LEFT JOIN tpl.standard_field_dictionary d ON d.id=fv.standard_field_dictionary_id
                WHERE fv.organization_id=? AND fv.input_field_id=? ORDER BY fv.version_no DESC, fv.id
                """, this::inputFieldVersion, organizationId, inputFieldId);
    }

    public Optional<InputFieldVersionView> inputFieldVersion(UUID organizationId, UUID id) {
        return jdbc.query("""
                SELECT fv.*, d.dictionary_code standard_field_code,d.display_name standard_field_name,
                       d.default_unit standard_field_unit
                FROM ai.input_field_version fv LEFT JOIN tpl.standard_field_dictionary d ON d.id=fv.standard_field_dictionary_id
                WHERE fv.organization_id=? AND fv.id=?
                """, this::inputFieldVersion, organizationId, id).stream().findFirst();
    }

    public List<InputSchemeView> inputSchemes(UUID organizationId, UUID targetId) {
        var schemes = jdbc.query("""
                SELECT s.*,d.dictionary_code material_dictionary_code FROM ai.input_scheme s
                LEFT JOIN ai.material_dictionary_version d ON d.organization_id=s.organization_id AND d.id=s.material_dictionary_version_id
                WHERE s.organization_id=? AND s.target_id=?
                ORDER BY s.version_no DESC, s.created_at DESC, s.id
                """, (rs, ignored) -> scheme(rs, List.of()), organizationId, targetId);
        return schemes.stream().map(s -> new InputSchemeView(s.id(), s.targetId(), s.targetVersionId(), s.materialDictionaryVersionId(), s.materialDictionaryCode(), s.code(),
                s.name(), s.version(), s.status(), schemeFields(organizationId, s.id()), s.preprocessing(),
                s.configHash(), s.revision(), s.frozenAt(), s.createdAt())).toList();
    }

    public Optional<InputSchemeView> inputScheme(UUID organizationId, UUID id) {
        return jdbc.query("""
                SELECT s.*,d.dictionary_code material_dictionary_code FROM ai.input_scheme s
                LEFT JOIN ai.material_dictionary_version d ON d.organization_id=s.organization_id AND d.id=s.material_dictionary_version_id
                WHERE s.organization_id=? AND s.id=?
                """,
                (rs, ignored) -> scheme(rs, schemeFields(organizationId, id)), organizationId, id).stream().findFirst();
    }

    public List<SchemeFieldView> schemeFields(UUID organizationId, UUID schemeId) {
        return jdbc.query("""
                SELECT sf.*, f.field_code, f.name field_name, fv.value_type, fv.unit, fv.availability_stage
                FROM ai.input_scheme_field sf
                JOIN ai.input_field_version fv ON fv.organization_id=sf.organization_id AND fv.id=sf.input_field_version_id
                JOIN ai.input_field f ON f.organization_id=fv.organization_id AND f.id=fv.input_field_id
                WHERE sf.organization_id=? AND sf.input_scheme_id=? ORDER BY sf.ordinal, sf.input_field_version_id
                """, (rs, ignored) -> new SchemeFieldView(uuid(rs,"input_field_version_id"), rs.getString("field_code"),
                rs.getString("field_name"), rs.getString("value_type"), rs.getString("unit"),
                rs.getString("availability_stage"), rs.getBoolean("required"), rs.getInt("ordinal"),
                json(rs,"override_jsonb")), organizationId, schemeId);
    }

    public List<TrainingPolicyView> trainingPolicies(UUID organizationId, UUID targetId) {
        return jdbc.query("""
                SELECT * FROM ai.modeling_policy_version WHERE organization_id=? AND kind='TRAINING' AND (?::uuid IS NULL OR target_id=?)
                ORDER BY target_id, version_no DESC, id
                """, this::policy, organizationId, targetId, targetId);
    }

    public Optional<TrainingPolicyView> trainingPolicy(UUID organizationId, UUID id) {
        return jdbc.query("SELECT * FROM ai.modeling_policy_version WHERE organization_id=? AND id=? AND kind='TRAINING'",
                this::policy, organizationId, id).stream().findFirst();
    }

    public List<ReferenceField> referenceFields(String keyword) {
        var pattern = text(keyword) ? "%" + keyword.strip() + "%" : "%";
        return jdbc.query("""
                SELECT id,dictionary_code,version_no,display_name,data_type,default_unit,group_code
                FROM tpl.standard_field_dictionary WHERE status='ACTIVE'
                  AND (display_name ILIKE ? OR dictionary_code ILIKE ?)
                ORDER BY display_name,dictionary_code,version_no DESC LIMIT 100
                """, (rs, ignored) -> new ReferenceField(uuid(rs,"id"), rs.getString("dictionary_code"),
                rs.getInt("version_no"), rs.getString("display_name"), rs.getString("data_type"),
                rs.getString("default_unit"), rs.getString("group_code")), pattern, pattern);
    }

    public List<MaterialReference> materials(String keyword) {
        var pattern = text(keyword) ? "%" + keyword.strip() + "%" : "%";
        return jdbc.query("""
                SELECT id,code,name,category,status FROM mdm.material
                WHERE status <> 'RETIRED' AND (code ILIKE ? OR name ILIKE ?)
                ORDER BY name,code,id LIMIT 100
                """, (rs, ignored) -> new MaterialReference(uuid(rs,"id"), rs.getString("code"),
                rs.getString("name"), rs.getString("category"), rs.getString("status")), pattern, pattern);
    }

    public List<MaterialAliasView> materialAliases(UUID organizationId, String keyword) {
        var pattern = text(keyword) ? "%" + keyword.strip() + "%" : "%";
        return jdbc.query("""
                SELECT a.*,m.code material_code,m.name material_name FROM mdm.material_alias a
                JOIN mdm.material m ON m.id=a.material_id
                WHERE a.organization_id=? AND (a.display_alias ILIKE ? OR m.code ILIKE ? OR m.name ILIKE ?)
                ORDER BY a.updated_at DESC,a.id LIMIT 200
                """, this::alias, organizationId, pattern, pattern, pattern);
    }

    public Optional<MaterialAliasView> materialAlias(UUID organizationId, UUID id) {
        return jdbc.query("""
                SELECT a.*,m.code material_code,m.name material_name FROM mdm.material_alias a
                JOIN mdm.material m ON m.id=a.material_id WHERE a.organization_id=? AND a.id=?
                """, this::alias, organizationId, id).stream().findFirst();
    }

    public List<MaterialDictionaryView> materialDictionaries(UUID organizationId) {
        return jdbc.query("""
                SELECT * FROM ai.material_dictionary_version WHERE organization_id=?
                ORDER BY dictionary_code,version_no DESC,id
                """, this::dictionary, organizationId);
    }

    public List<SampleFactRow> currentSampleFacts(UUID organizationId) {
        return jdbc.query("""
                SELECT ts.logical_sample_key,coalesce(ts.authority_source_type,ss.source_type) source_type,
                       sr.composition_jsonb,sr.process_jsonb,sr.conditions_jsonb,sr.facts_jsonb,sr.source_coordinates_jsonb
                FROM ai.training_sample ts
                JOIN ai.sample_revision sr ON sr.organization_id=ts.organization_id
                    AND sr.id=ts.current_sample_revision_id AND sr.status='CURRENT'
                JOIN ai.sample_source ss ON ss.organization_id=ts.organization_id
                    AND ss.id=sr.sample_source_id AND ss.status IN ('CURRENT','TAKEN_OVER')
                WHERE ts.organization_id=? AND ts.status IN ('ACTIVE','TAKEN_OVER')
                ORDER BY ts.logical_sample_key,ss.source_type
                """,(rs,n)->new SampleFactRow(rs.getString("logical_sample_key"),rs.getString("source_type"),
                json(rs,"composition_jsonb"),json(rs,"process_jsonb"),json(rs,"conditions_jsonb"),json(rs,"facts_jsonb"),json(rs,"source_coordinates_jsonb")),organizationId);
    }

    public record SampleFactRow(String logicalSampleKey, String sourceType, JsonNode composition,
                                JsonNode process, JsonNode conditions, JsonNode facts, JsonNode coordinates) { }

    public Optional<MaterialDictionaryView> materialDictionary(UUID organizationId, UUID id) {
        return jdbc.query("SELECT * FROM ai.material_dictionary_version WHERE organization_id=? AND id=?",
                this::dictionary, organizationId, id).stream().findFirst();
    }

    public Optional<Map<String, Object>> commandReceipt(UUID organizationId, String operation, String key) {
        return jdbc.query("""
                SELECT request_hash,response_jsonb FROM ai.configuration_command_receipt
                WHERE organization_id=? AND operation=? AND idempotency_key=?
                """, (rs, ignored) -> Map.<String,Object>of("requestHash", rs.getString("request_hash"),
                "response", json(rs,"response_jsonb")), organizationId, operation, key).stream().findFirst();
    }

    public void lockCommand(UUID organizationId, String operation, String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                (rs, ignored) -> rs.getObject(1), organizationId + ":" + operation + ":" + key);
    }

    public PGobject pg(JsonNode value) {
        try {
            var result = new PGobject(); result.setType("jsonb");
            result.setValue(value == null || value.isNull() ? "{}" : value.toString()); return result;
        } catch (SQLException exception) { throw new IllegalArgumentException(exception); }
    }

    public JsonNode json(ResultSet rs, String column) throws SQLException {
        try { var value=rs.getString(column); return value == null ? mapper.createObjectNode() : mapper.readTree(value); }
        catch (Exception exception) { throw new SQLException("JSON列解析失败: " + column, exception); }
    }

    public List<String> strings(ResultSet rs, String column) throws SQLException {
        try { return mapper.readValue(rs.getString(column), new TypeReference<>() { }); }
        catch (Exception exception) { throw new SQLException("JSON数组解析失败: " + column, exception); }
    }

    private TargetSummary target(ResultSet rs, int ignored) throws SQLException {
        return new TargetSummary(uuid(rs,"id"),rs.getString("target_code"),rs.getString("name"),
                rs.getString("performance_project"),rs.getString("value_type"),rs.getString("status"),
                uuid(rs,"current_version_id"),integer(rs,"current_version_no"),uuid(rs,"current_input_scheme_id"),
                rs.getString("current_scheme_name"),uuid(rs,"active_model_id"),
                Optional.ofNullable(rs.getString("eligibility_status")).orElse("NOT_EVALUATED"),
                uuid(rs,"active_model_id") == null ? "NOT_TRAINED" : "ACTIVE",rs.getLong("revision"),instant(rs,"updated_at"));
    }

    private TargetVersionView targetVersion(ResultSet rs, int ignored) throws SQLException {
        return new TargetVersionView(uuid(rs,"id"),uuid(rs,"target_id"),rs.getInt("version_no"),rs.getString("status"),
                rs.getString("value_type"),rs.getString("unit"),strings(rs,"classes_jsonb"),json(rs,"definition_jsonb"),
                json(rs,"observation_semantics_jsonb"),rs.getString("config_hash"),instant(rs,"published_at"),instant(rs,"created_at"),
                uuid(rs,"result_standard_field_dictionary_id"),uuid(rs,"catalog_proposal_id"));
    }

    private SourceMappingView sourceMapping(ResultSet rs, int ignored) throws SQLException {
        return new SourceMappingView(uuid(rs,"id"),uuid(rs,"target_id"),uuid(rs,"target_version_id"),
                rs.getString("source_type"),rs.getInt("version_no"),rs.getString("status"),json(rs,"mapping_jsonb"),
                rs.getString("mapping_hash"),instant(rs,"published_at"),instant(rs,"created_at"));
    }

    private InputFieldSummary inputField(ResultSet rs, int ignored) throws SQLException {
        return new InputFieldSummary(uuid(rs,"id"),rs.getString("field_code"),rs.getString("name"),
                rs.getString("value_type"),rs.getString("unit"),rs.getString("availability_stage"),
                rs.getString("status"),uuid(rs,"current_version_id"),integer(rs,"current_version_no"),
                rs.getString("standard_field_code"),rs.getString("standard_field_name"),rs.getString("standard_field_unit"),
                rs.getLong("revision"),instant(rs,"updated_at"));
    }

    private InputFieldVersionView inputFieldVersion(ResultSet rs, int ignored) throws SQLException {
        return new InputFieldVersionView(uuid(rs,"id"),uuid(rs,"input_field_id"),rs.getInt("version_no"),
                rs.getString("status"),rs.getString("value_type"),rs.getString("unit"),
                rs.getString("availability_stage"),uuid(rs,"standard_field_dictionary_id"),
                rs.getString("standard_field_code"),rs.getString("standard_field_name"),rs.getString("standard_field_unit"),
                json(rs,"definition_jsonb"),json(rs,"preprocessing_jsonb"),
                rs.getString("config_hash"),instant(rs,"published_at"),instant(rs,"created_at"));
    }

    private InputSchemeView scheme(ResultSet rs, List<SchemeFieldView> fields) throws SQLException {
        return new InputSchemeView(uuid(rs,"id"),uuid(rs,"target_id"),uuid(rs,"target_version_id"),
                uuid(rs,"material_dictionary_version_id"),rs.getString("material_dictionary_code"),
                rs.getString("scheme_code"),rs.getString("name"),rs.getInt("version_no"),rs.getString("status"),
                fields,json(rs,"preprocessing_jsonb"),rs.getString("config_hash"),rs.getLong("revision"),
                instant(rs,"frozen_at"),instant(rs,"created_at"));
    }

    private TrainingPolicyView policy(ResultSet rs, int ignored) throws SQLException {
        return new TrainingPolicyView(uuid(rs,"id"),uuid(rs,"target_id"),rs.getInt("version_no"),
                rs.getString("status"),json(rs,"qualification_jsonb"),json(rs,"validation_jsonb"),
                json(rs,"training_jsonb"),rs.getString("policy_hash"),instant(rs,"published_at"),instant(rs,"created_at"));
    }

    private MaterialAliasView alias(ResultSet rs, int ignored) throws SQLException {
        return new MaterialAliasView(uuid(rs,"id"),uuid(rs,"material_id"),rs.getString("material_code"),
                rs.getString("material_name"),rs.getString("display_alias"),rs.getString("normalized_alias"),
                rs.getString("status"),rs.getLong("revision"),instant(rs,"updated_at"));
    }

    private MaterialDictionaryView dictionary(ResultSet rs, int ignored) throws SQLException {
        List<MaterialDictionaryItem> items;
        try { items=mapper.convertValue(json(rs,"vocabulary_jsonb"),new TypeReference<>() { }); }
        catch (Exception exception) { throw new SQLException("材料字典解析失败",exception); }
        return new MaterialDictionaryView(uuid(rs,"id"),rs.getString("dictionary_code"),rs.getInt("version_no"),
                rs.getString("status"),items,json(rs,"encoder_jsonb"),rs.getString("dictionary_hash"),
                rs.getLong("revision"),instant(rs,"frozen_at"),instant(rs,"created_at"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException { return rs.getObject(column,UUID.class); }
    private static Integer integer(ResultSet rs, String column) throws SQLException { int v=rs.getInt(column); return rs.wasNull()?null:v; }
    private static Instant instant(ResultSet rs, String column) throws SQLException { var v=rs.getTimestamp(column); return v==null?null:v.toInstant(); }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static void filter(StringBuilder where, List<Object> args, String column, String value) {
        if (text(value)) { where.append(" AND ").append(column).append(" = ?"); args.add(value.strip().toUpperCase(java.util.Locale.ROOT)); }
    }
}
