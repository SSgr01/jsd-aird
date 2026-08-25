package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.application.command.ProjectCommands;
import com.jsd.aird.mdm.application.port.ProjectRepository;
import com.jsd.aird.mdm.application.query.ProjectQuery;
import com.jsd.aird.mdm.domain.model.Project;
import com.jsd.aird.mdm.domain.model.ProjectPriority;
import com.jsd.aird.mdm.domain.model.ProjectStatus;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    static final String OPERATOR = "system";

    private final ProjectRepository repository;

    public ProjectService(ProjectRepository repository) {
        this.repository = repository;
    }

    public List<Project> search(ProjectQuery query) {
        return repository.findPage(query);
    }

    public long count(ProjectQuery query) {
        return repository.count(query);
    }

    public byte[] exportXlsx(ProjectQuery query) {
        var total = repository.count(query);
        var projects = new java.util.ArrayList<Project>();
        var pageSize = 200;
        var pages = (int) Math.ceil((double) total / pageSize);
        for (var page = 1; page <= pages; page++) {
            var pageQuery = new ProjectQuery(query.keyword(), query.owner(), query.priority(), query.status(),
                query.startDateFrom(), query.startDateTo(), query.partnerId(), page, pageSize);
            projects.addAll(repository.findPage(pageQuery));
        }
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("项目列表");
            var headerStyle = workbook.createCellStyle();
            var headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            var headers = new String[]{"项目编号", "项目名称", "客户", "负责人", "开始日期", "结束日期", "优先级", "状态", "团队人数"};
            var header = sheet.createRow(0);
            for (var column = 0; column < headers.length; column++) {
                var cell = header.createCell(column);
                cell.setCellValue(headers[column]);
                cell.setCellStyle(headerStyle);
            }
            for (var index = 0; index < projects.size(); index++) {
                var project = projects.get(index);
                var row = sheet.createRow(index + 1);
                setCell(row, 0, project.projectCode());
                setCell(row, 1, project.name());
                setCell(row, 2, project.partnerName());
                setCell(row, 3, project.owner());
                setCell(row, 4, project.startDate());
                setCell(row, 5, project.endDate());
                setCell(row, 6, project.priority());
                setCell(row, 7, project.status());
                setCell(row, 8, project.teamSize());
            }
            for (var column = 0; column < headers.length; column++) sheet.autoSizeColumn(column);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.INTERNAL_ERROR, "项目列表 Excel 导出失败");
        }
    }

    private static void setCell(Row row, int column, Object value) {
        var cell = row.createCell(column);
        if (value == null) return;
        if (value instanceof Number number) cell.setCellValue(number.doubleValue());
        else cell.setCellValue(String.valueOf(value));
    }

    public Project get(UUID id) {
        return repository.findById(id)
            .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "项目不存在"));
    }

    @Transactional
    public ProjectCommands.Created create(ProjectCommands.SaveProject command) {
        validate(command);
        var code = resolveCode(command.projectCode());
        if (repository.existsByProjectCode(code, null))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "项目编号已存在：" + code);

        var now = Instant.now();
        var project = new Project(UUID.randomUUID(), code, command.name().trim(), command.partnerId(),
            trim(command.partnerName()), trim(command.owner()), command.startDate(), command.endDate(),
            command.priority() == null ? ProjectPriority.MEDIUM : command.priority(),
            command.status() == null ? ProjectStatus.NOT_STARTED : command.status(),
            command.teamSize() == null ? 0 : Math.max(command.teamSize(), 0),
            command.background(), command.customFields(), command.teamMembers(), 0, now, now);
        repository.insert(project, OPERATOR);
        return new ProjectCommands.Created(project.id(), 0);
    }

    @Transactional
    public void update(UUID id, ProjectCommands.SaveProject command) {
        validate(command);
        if (command.projectCode() == null || command.projectCode().isBlank())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "项目编号不能为空");
        var current = get(id);
        var code = command.projectCode().trim();
        if (repository.existsByProjectCode(code, id))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "项目编号已存在：" + code);

        var project = new Project(id, code, command.name().trim(), command.partnerId(),
            trim(command.partnerName()), trim(command.owner()), command.startDate(), command.endDate(),
            command.priority() == null ? current.priority() : command.priority(),
            command.status() == null ? current.status() : command.status(),
            command.teamSize() == null ? current.teamSize() : Math.max(command.teamSize(), 0),
            command.background(), command.customFields(),
            command.teamMembers() == null ? current.teamMembers() : command.teamMembers(),
            current.version(), current.createdAt(), Instant.now());

        var version = command.version() == null ? current.version() : command.version();
        if (!repository.update(project, version, OPERATOR)) conflict();
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.softDelete(id, OPERATOR))
            throw new ApiException(ApiErrorCode.NOT_FOUND, "项目不存在");
    }

    @Transactional
    public List<ProjectCommands.Created> copy(List<UUID> ids) {
        if (ids == null || ids.isEmpty())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "请选择需要复制的项目");

        var sources = repository.findAllByIds(ids);
        if (sources.isEmpty())
            throw new ApiException(ApiErrorCode.NOT_FOUND, "项目不存在");

        var year = LocalDate.now().getYear();
        var now = Instant.now();
        return sources.stream().map(source -> {
            var copy = new Project(UUID.randomUUID(), repository.nextProjectCode(year),
                truncate(source.name() + "（副本）"), source.partnerId(), source.partnerName(), source.owner(),
                source.startDate(), source.endDate(), source.priority(), ProjectStatus.NOT_STARTED,
                source.teamSize(), source.background(), source.customFields(), source.teamMembers(), 0, now, now);
            repository.insert(copy, OPERATOR);
            return new ProjectCommands.Created(copy.id(), 0);
        }).toList();
    }

    private String resolveCode(String projectCode) {
        return projectCode == null || projectCode.isBlank()
            ? repository.nextProjectCode(LocalDate.now().getYear())
            : projectCode.trim();
    }

    private static void validate(ProjectCommands.SaveProject command) {
        if (command == null || command.name() == null || command.name().isBlank())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "项目名称不能为空");
        if (command.startDate() != null && command.endDate() != null
            && command.endDate().isBefore(command.startDate()))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "结束日期不能早于开始日期");
    }

    private static String truncate(String value) {
        return value.length() > 300 ? value.substring(0, 300) : value;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static void conflict() {
        throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "数据已被他人修改，请刷新后重试");
    }
}
