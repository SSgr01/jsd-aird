package com.jsd.aird.mfg.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProductionOrderService {

    private final ProductionOrderRepository repository;
    private final JsonCanonicalizer canonicalizer;
    private final ObjectMapper objectMapper;

    public ProductionOrderService(
            ProductionOrderRepository repository,
            JsonCanonicalizer canonicalizer,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.canonicalizer = canonicalizer;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProductionOrderRepository.ProductionWorkspace create(CreateCommand command) {
        var actor = ActorContext.required();
        var template = repository.findPublishedTemplate(actor.organizationId(), command.templateVersionId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorCode.NOT_FOUND,
                        "只能从当前组织的已发布模板创建生产单"
                ));
        var orderId = UUID.randomUUID();
        var data = objectMapper.createObjectNode();
        var schemaHash = canonicalizer.hash(template.schema());
        var mappingHash = canonicalizer.hash(template.mapping());
        var dataHash = canonicalizer.hash(data);
        var workspaceHash = canonicalizer.workspaceHash(
                orderId.toString(),
                template.schema(),
                template.mapping(),
                data,
                template.snapshotHash(),
                template.editorAppVersion(),
                template.pluginManifestHash()
        );
        repository.insert(new ProductionOrderRepository.NewProductionOrder(
                orderId,
                actor.organizationId(),
                command.orderNo().trim(),
                template.versionId(),
                command.productId(),
                command.quantity(),
                command.unitCode(),
                command.plannedDate(),
                command.ownerId(),
                template.schema().deepCopy(),
                template.mapping().deepCopy(),
                data,
                template.snapshotFileId(),
                template.snapshotHash(),
                template.snapshotKind(),
                template.editorAppVersion(),
                template.pluginManifestHash(),
                template.snapshotFormatVersion(),
                schemaHash,
                mappingHash,
                dataHash,
                workspaceHash,
                actor.userId()
        ));
        return get(orderId);
    }

    public ProductionOrderRepository.ProductionWorkspace get(UUID orderId) {
        return repository.findWorkspace(ActorContext.required().organizationId(), orderId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "生产单不存在"));
    }

    public List<ProductionOrderRepository.ProductionOrderListItem> list() {
        return repository.list(ActorContext.required().organizationId());
    }

    @Transactional
    public void cancel(UUID orderId) {
        var actor = ActorContext.required();
        if (repository.cancel(actor.organizationId(), orderId) == 0) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "只有草稿生产单可以取消");
        }
        repository.appendOutbox(
                "PRODUCTION_ORDER",
                orderId,
                "PRODUCTION_ORDER_CANCELLED",
                objectMapper.createObjectNode().put("orderId", orderId.toString())
        );
    }

    @Transactional
    public SaveResult save(UUID orderId, SaveCommand command) {
        var actor = ActorContext.required();
        var current = get(orderId);
        if (!"DRAFT".equals(current.status())) {
            throw new ApiException(ApiErrorCode.TEMPLATE_VERSION_IMMUTABLE, "只有草稿生产单可以编辑");
        }
        if (!current.workspaceHash().equals(command.baseWorkspaceHash())) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }
        validateInstanceSchema(command.schema());
        var reconciliationRequired = validateMappings(command.mapping());
        validateBindingValues(command.bindingValues());
        validateSnapshot(command.snapshotFileId(), command.snapshotHash(), false);

        var schemaHash = canonicalizer.hash(command.schema());
        var mappingHash = canonicalizer.hash(command.mapping());
        var dataHash = canonicalizer.hash(command.data());
        var pluginHash = canonicalizer.hashText(command.pluginManifest());
        var workspaceHash = canonicalizer.workspaceHash(
                orderId.toString(),
                command.schema(),
                command.mapping(),
                command.data(),
                command.snapshotHash(),
                command.editorAppVersion(),
                pluginHash
        );
        var updated = repository.updateDraft(new ProductionOrderRepository.DraftUpdate(
                actor.organizationId(),
                orderId,
                command.lockVersion(),
                command.schema(),
                command.mapping(),
                command.data(),
                command.snapshotFileId(),
                command.snapshotHash(),
                command.editorAppVersion(),
                pluginHash,
                command.snapshotFormatVersion(),
                schemaHash,
                mappingHash,
                dataHash,
                workspaceHash
        ));
        if (updated == 0) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }
        repository.appendOutbox(
                "FILE_OBJECT",
                command.snapshotFileId(),
                "FILE_ACTIVATION_REQUESTED",
                objectMapper.createObjectNode().put("fileId", command.snapshotFileId().toString())
        );
        return new SaveResult(command.lockVersion() + 1, workspaceHash, reconciliationRequired);
    }

    @Transactional
    public UUID submit(UUID orderId) {
        var actor = ActorContext.required();
        var current = get(orderId);
        if (!"DRAFT".equals(current.status())) {
            throw new ApiException(ApiErrorCode.TEMPLATE_VERSION_IMMUTABLE, "生产单已提交");
        }
        if (current.reconciliationRequired()) {
            throw new ApiException(ApiErrorCode.MAPPING_RECONCILIATION_REQUIRED);
        }
        validateSnapshot(current.snapshotFileId(), current.snapshotHash(), true);
        var revisionId = UUID.randomUUID();
        var core = objectMapper.createObjectNode()
                .put("orderNo", current.orderNo())
                .put("quantity", current.quantity())
                .put("unitCode", current.unitCode())
                .put("plannedDate", current.plannedDate() == null ? null : current.plannedDate().toString());
        repository.submit(new ProductionOrderRepository.SubmitRevision(
                revisionId,
                actor.organizationId(),
                orderId,
                core,
                current.schema(),
                current.mapping(),
                current.data(),
                current.snapshotFileId(),
                current.snapshotHash(),
                current.schemaHash(),
                current.mappingHash(),
                current.dataHash(),
                current.workspaceHash(),
                actor.userId()
        ));
        repository.appendOutbox(
                "RECORD_REVISION",
                revisionId,
                "PROJECTION_REBUILD_REQUESTED",
                objectMapper.createObjectNode().put("revisionId", revisionId.toString())
        );
        return revisionId;
    }

    private void validateInstanceSchema(JsonNode schema) {
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())) {
            throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "实例 Schema 根节点必须是 object");
        }
        scanQueryable(schema, false);
    }

    private void scanQueryable(JsonNode node, boolean localAncestor) {
        if (node == null) {
            return;
        }
        var fieldCode = node.path("x-field-code").asText("");
        var local = localAncestor || fieldCode.startsWith("LOCAL.");
        if (local && node.path("x-queryable").asBoolean(false)) {
            throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "LOCAL 字段不能启用 x-queryable");
        }
        node.fields().forEachRemaining(field -> scanQueryable(field.getValue(), local));
    }

    private boolean validateMappings(JsonNode mapping) {
        if (mapping == null || !mapping.isArray()) {
            throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "实例 Mapping 必须是数组");
        }
        var ids = new HashSet<String>();
        var reconciliation = false;
        for (JsonNode binding : mapping) {
            if (!ids.add(binding.path("bindingId").asText())) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "实例 bindingId 必须唯一");
            }
            var hasPosition = StringUtils.hasText(binding.path("markerId").asText())
                    || StringUtils.hasText(binding.path("locator").path("address").asText())
                    || StringUtils.hasText(binding.path("locator").path("range").asText());
            reconciliation |= !"VALID".equals(binding.path("bindingStatus").asText("VALID"))
                    && hasPosition;
        }
        return reconciliation;
    }

    private void validateBindingValues(List<BindingValuePair> pairs) {
        for (BindingValuePair pair : pairs == null ? List.<BindingValuePair>of() : pairs) {
            if (!canonicalizer.hash(pair.dataValue()).equals(canonicalizer.hash(pair.editorValue()))) {
                throw new ApiException(
                        ApiErrorCode.WORKBOOK_DATA_DIVERGED,
                        "字段 " + pair.dataPath() + " 的编辑器值与 JSONB 值不一致"
                );
            }
        }
    }

    private void validateSnapshot(UUID fileId, String expectedHash, boolean requireActive) {
        if (fileId == null || !StringUtils.hasText(expectedHash)) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED);
        }
        var file = repository.findFile(ActorContext.required().organizationId(), fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY));
        if (!file.sha256().equals(expectedHash)) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "快照哈希不一致");
        }
        if (requireActive && !"ACTIVE".equals(file.status())) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "快照尚未由 Outbox 激活");
        }
    }

    public record CreateCommand(
            String orderNo,
            UUID templateVersionId,
            UUID productId,
            BigDecimal quantity,
            String unitCode,
            LocalDate plannedDate,
            UUID ownerId
    ) {
    }

    public record BindingValuePair(String dataPath, JsonNode dataValue, JsonNode editorValue) {
    }

    public record SaveCommand(
            long lockVersion,
            String baseWorkspaceHash,
            JsonNode schema,
            JsonNode mapping,
            JsonNode data,
            UUID snapshotFileId,
            String snapshotHash,
            String editorAppVersion,
            String pluginManifest,
            int snapshotFormatVersion,
            List<BindingValuePair> bindingValues
    ) {
    }

    public record SaveResult(long lockVersion, String workspaceHash, boolean reconciliationRequired) {
    }
}
