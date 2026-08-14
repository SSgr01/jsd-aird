package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.application.command.ProjectStageCommands;
import com.jsd.aird.mdm.application.port.ProjectRepository;
import com.jsd.aird.mdm.application.port.ProjectStageRepository;
import com.jsd.aird.mdm.application.query.ProjectStageQuery;
import com.jsd.aird.mdm.domain.model.ProjectStage;
import com.jsd.aird.mdm.domain.model.ProjectStatus;
import com.jsd.aird.mdm.domain.model.StageStatus;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectStageService {
    static final String OPERATOR = "system";

    private final ProjectRepository projects;
    private final ProjectStageRepository stages;

    public ProjectStageService(ProjectRepository projects, ProjectStageRepository stages) {
        this.projects = projects;
        this.stages = stages;
    }

    public List<ProjectStage> listByProject(UUID projectId) {
        requireProject(projectId);
        return stages.findByProject(projectId);
    }

    public ProjectStage get(UUID id) {
        return stages.findById(id).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "阶段不存在或已删除"));
    }

    public PageResponse<ProjectStage> search(ProjectStageQuery query) {
        var items = stages.findPage(query);
        long total = stages.count(query);
        long pages = (total + query.size() - 1) / query.size();
        return new PageResponse<>(items, query.page(), query.size(), total, pages);
    }

    @Transactional
    public ProjectStage create(UUID projectId, ProjectStageCommands.Create command) {
        var project = requireProject(projectId);
        if (project.status() == ProjectStatus.COMPLETED) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "已完成项目不能新增阶段");
        }
        String name = validateName(command.name());
        validateDates(command.plannedStart(), command.plannedEnd());
        StageStatus status = command.status() == null ? StageStatus.PENDING : command.status();
        if (status == StageStatus.COMPLETED) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "新建阶段不能直接设为已完成");
        }
        if (stages.existsActiveName(projectId, name, null)) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "同一项目中已存在同名阶段");
        }
        Instant now = Instant.now();
        int orderNo = stages.nextOrderNo(projectId);
        String stageCode = clean(command.stageCode());
        if (stageCode == null) {
            stageCode = project.projectCode() + "-STG-" + String.format("%03d", orderNo);
        }
        var stage = new ProjectStage(UUID.randomUUID(), projectId, project.projectCode(), project.name(), stageCode,
            name, orderNo, status, clean(command.owner()), clean(command.description()),
            command.plannedStart(), command.plannedEnd(), status == StageStatus.IN_PROGRESS ? now : null,
            null, 0, 0, 0, now, now);
        stages.insert(stage, OPERATOR);
        return stage;
    }

    @Transactional
    public ProjectStage update(UUID id, ProjectStageCommands.Update command) {
        ProjectStage current = get(id);
        String name = validateName(command.name());
        validateDates(command.plannedStart(), command.plannedEnd());
        StageStatus target = command.status() == null ? current.status() : command.status();
        if (stages.existsActiveName(current.projectId(), name, id)) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "同一项目中已存在同名阶段");
        }
        if (target == StageStatus.COMPLETED && current.openTaskCount() > 0) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,
                "阶段仍有 " + current.openTaskCount() + " 个未完成任务，不能完成");
        }
        if (current.status() == StageStatus.COMPLETED && target != StageStatus.COMPLETED && clean(command.transitionReason()) == null) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "重新打开已完成阶段必须填写原因");
        }
        Instant now = Instant.now();
        Instant actualStart = current.actualStart();
        Instant actualEnd = current.actualEnd();
        if (target == StageStatus.IN_PROGRESS && actualStart == null) actualStart = now;
        if (target == StageStatus.COMPLETED && actualEnd == null) actualEnd = now;
        if (current.status() == StageStatus.COMPLETED && target != StageStatus.COMPLETED) actualEnd = null;
        var updated = new ProjectStage(current.id(), current.projectId(), current.projectCode(), current.projectName(),
            current.stageCode(), name, current.orderNo(), target, clean(command.owner()), clean(command.description()),
            command.plannedStart(), command.plannedEnd(), actualStart, actualEnd, current.taskCount(),
            current.openTaskCount(), current.version() + 1, current.createdAt(), now);
        if (!stages.update(updated, command.version(), OPERATOR)) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "阶段已被其他用户修改，请刷新后重试");
        }
        return updated;
    }

    @Transactional
    public ProjectStage delete(UUID id, long version) {
        ProjectStage stage = get(id);
        if (stage.taskCount() > 0 || stage.openTaskCount() > 0) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "阶段下存在任务，不能删除");
        }
        if (!stages.softDelete(id, version, OPERATOR)) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "阶段已被其他用户修改，请刷新后重试");
        }
        return stage;
    }

    @Transactional
    public List<ProjectStage> reorder(UUID projectId, ProjectStageCommands.Reorder command) {
        List<ProjectStage> current = stages.findByProject(projectId);
        if (command.items().size() != current.size()) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "排序请求必须包含项目下全部有效阶段");
        }
        var byId = current.stream().collect(java.util.stream.Collectors.toMap(ProjectStage::id, it -> it));
        var seen = new HashSet<UUID>();
        List<ProjectStage> ordered = command.items().stream().map(item -> {
            ProjectStage stage = byId.get(item.id());
            if (stage == null || !seen.add(item.id())) {
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "排序请求包含无效或重复阶段");
            }
            if (stage.version() != item.version()) {
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "阶段排序版本已变化，请刷新后重试");
            }
            return stage;
        }).toList();
        try {
            stages.reorder(projectId, ordered, OPERATOR);
        } catch (IllegalStateException exception) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "阶段排序发生并发冲突，请刷新后重试");
        }
        return stages.findByProject(projectId);
    }

    private com.jsd.aird.mdm.domain.model.Project requireProject(UUID id) {
        return projects.findById(id).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "项目不存在或已删除"));
    }

    private static String validateName(String value) {
        String name = clean(value);
        if (name == null) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "阶段名称不能为空");
        if (name.length() > 120) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "阶段名称最多 120 个字符");
        return name;
    }

    private static void validateDates(java.time.LocalDate start, java.time.LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "计划结束日期不能早于开始日期");
        }
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
