package com.jsd.aird.spc.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.shared.json.RevisionToken;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpectrumMethodPresetService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuthorizationService authorization;
    private final com.jsd.aird.ops.application.port.AuditLogFacade audit;
    public SpectrumMethodPresetService(JdbcTemplate jdbc, ObjectMapper json, AuthorizationService authorization, com.jsd.aird.ops.application.port.AuditLogFacade audit) {
        this.jdbc = jdbc; this.json = json; this.authorization = authorization; this.audit = audit;
    }
    public record Preset(UUID id, String name, String methodType, int version, boolean active, JsonNode snapshot) {
        public String getRevision() { return RevisionToken.of(id, name, methodType, version, active, snapshot); }
    }
    public record Catalog(JsonNode methods, List<Preset> presets, boolean canManage) {}
    public record Command(String name, JsonNode snapshot, Boolean active, String revision) {}
    public Catalog list() {
        requireRead(); var actor = ActorContext.required();
        var manager = canManage();
        var rows = jdbc.query("SELECT * FROM spc.method_preset WHERE organization_id=?" + (manager ? "" : " AND active=true") + " ORDER BY name,id",
                (rs, n) -> row(rs), actor.organizationId());
        return new Catalog(SpectrumTestMethods.schema(), rows, manager);
    }
    @Transactional
    public Preset create(Command command) {
        requireManage(); var actor = ActorContext.required();
        var snapshot = presetSnapshot(command.snapshot());
        var name = name(command.name()); var id = UUID.randomUUID();
        jdbc.update("INSERT INTO spc.method_preset(id,organization_id,name,method_type,snapshot,created_by,active) VALUES(?,?,?,?,?::jsonb,?,?)",
                id, actor.organizationId(), name, snapshot.path("methodType").asText(), snapshot.toString(), actor.userId(), command.active() == null || command.active());
        var result = find(id, false);
        audit.append(actor.organizationId(), actor.userId(), "SPECTRUM_METHOD_PRESET_CREATED", "SPECTRUM_METHOD_PRESET", id, json.valueToTree(result));
        return result;
    }
    @Transactional
    public Preset update(UUID id, Command command) {
        requireManage(); var current = find(id, true); RevisionToken.require(command.revision(), current.getRevision());
        var snapshot = presetSnapshot(command.snapshot());
        jdbc.update("UPDATE spc.method_preset SET name=?,method_type=?,snapshot=?::jsonb,active=?,version=version+1,updated_at=now() WHERE organization_id=? AND id=?",
                name(command.name()), snapshot.path("methodType").asText(), snapshot.toString(), command.active() == null ? current.active() : command.active(), ActorContext.required().organizationId(), id);
        var result = find(id, false); var actor = ActorContext.required();
        audit.append(actor.organizationId(), actor.userId(), "SPECTRUM_METHOD_PRESET_UPDATED", "SPECTRUM_METHOD_PRESET", id, json.valueToTree(result));
        return result;
    }
    private JsonNode presetSnapshot(JsonNode value) {
        var snap = SpectrumTestMethods.snapshot(value);
        var method = SpectrumTestMethods.method(snap.path("methodType").asText());
        // Variable sample values must never become defaults for the next test.
        for (var field : method.path("fields")) if (field.path("variable").asBoolean())
            ((com.fasterxml.jackson.databind.node.ObjectNode)snap.path("conditions")).remove(field.path("key").asText());
        snap.remove(List.of("presetId", "presetName", "presetRevision", "legacyText"));
        return snap;
    }
    private Preset find(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM spc.method_preset WHERE organization_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> row(rs), ActorContext.required().organizationId(), id).stream().findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "方法预设不存在"));
    }
    private Preset row(java.sql.ResultSet rs) throws java.sql.SQLException {
        try { return new Preset(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("method_type"), rs.getInt("version"), rs.getBoolean("active"), json.readTree(rs.getString("snapshot"))); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private void requireRead() {
        var actor = ActorContext.required();
        for (var code : List.of("spectrum.upload.view", "spectrum.upload.create", "spectrum.view.view", "spectrum.view.edit"))
            if (authorization.check(new PermissionCheck(actor.organizationId(), actor.userId(), code, "SPECTRUM", null, "READ")).allowed()) return;
        throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "当前用户没有图谱权限");
    }
    private boolean canManage() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM iam.app_user WHERE organization_id=? AND id=? AND dictionary_admin=true AND status='ACTIVE')",
                Boolean.class, ActorContext.required().organizationId(), ActorContext.required().userId()));
    }
    private void requireManage() { requireRead(); if (!canManage()) throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "仅字典管理员可以维护共享预设"); }
    private String name(String value) {
        if (value == null || value.isBlank() || value.length() > 160) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "预设名称不能为空，最多160字");
        return value.trim();
    }
}
