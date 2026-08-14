package com.jsd.aird.data.application;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.data.application.port.DataProjectionRepository;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;

@Service
public class DataProjectionService {

    private final DataProjectionRepository repository;
    private final DataImportService imports;
    private final TemplateDataImportFacade templates;

    public DataProjectionService(DataProjectionRepository repository, DataImportService imports,
                                 TemplateDataImportFacade templates) {
        this.repository = repository;
        this.imports = imports;
        this.templates = templates;
    }

    public DataImportService.ProjectionSummary project(UUID importJobId) {
        var actor = ActorContext.required();
        var job = imports.get(importJobId);
        var result = repository.project(actor.organizationId(), importJobId, actor.userId(), job.templateVersionId(),
                List.of(), templates.getBindings(actor.organizationId(), job.templateVersionId()));
        return new DataImportService.ProjectionSummary(result.datasetId(), "DRAFT", result.recordCount(),
                result.longValueCount(), result.eligibleRecordCount());
    }

    public void projectInternal(UUID organizationId, UUID actorId, UUID importJobId, UUID templateVersionId,
                                List<UUID> recordIds) {
        repository.project(organizationId, importJobId, actorId, templateVersionId, recordIds,
                templates.getBindings(organizationId, templateVersionId));
    }

    public DataProjectionRepository.TrainingDataset latest(UUID importJobId) {
        var actor = ActorContext.required();
        return repository.findLatestDataset(actor.organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "该导入任务尚未生成长表数据集"));
    }

    public LongTablePreview longTablePreview(UUID importJobId, int limit) {
        var actor = ActorContext.required();
        var dataset = repository.findLatestDataset(actor.organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "该导入任务尚未生成长表数据集"));
        return new LongTablePreview(dataset, repository.previewRows(actor.organizationId(), importJobId, limit));
    }

    public DataProjectionRepository.TrainingDataset dataset(UUID datasetId) {
        var actor = ActorContext.required();
        return repository.findDataset(actor.organizationId(), datasetId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "训练数据集不存在"));
    }

    public record LongTablePreview(DataProjectionRepository.TrainingDataset dataset,
                                   List<DataProjectionRepository.LongTableRow> rows) {}

    public void updateStatus(UUID datasetId, String status) {
        var actor = ActorContext.required();
        if (!List.of("DRAFT", "REVIEWING", "APPROVED", "RETIRED").contains(status)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的训练数据集状态：" + status);
        }
        var existing = repository.findDataset(actor.organizationId(), datasetId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "训练数据集不存在"));
        if ("APPROVED".equals(status)
                && "DISABLED".equals(existing.schema().path("approvalPolicy").asText(""))) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "V2 导入生成的数据集仅为草稿，本阶段不允许审核或训练消费");
        }
        if ("APPROVED".equals(status) && !List.of("DRAFT", "REVIEWING").contains(existing.status())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "当前训练数据集状态不可审核通过");
        }
        repository.updateDatasetStatus(actor.organizationId(), datasetId, status, actor.userId());
    }
}
