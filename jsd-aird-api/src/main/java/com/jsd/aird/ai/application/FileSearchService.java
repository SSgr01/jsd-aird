package com.jsd.aird.ai.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeFileSearchFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FileSearchService {

    private final KnowledgeFileSearchFacade knowledge;
    private final DataSourceFileSearchFacade dataSources;

    public FileSearchService(KnowledgeFileSearchFacade knowledge, DataSourceFileSearchFacade dataSources) {
        this.knowledge = knowledge;
        this.dataSources = dataSources;
    }

    public FileSearchResponse search(UUID organizationId, SearchCommand command) {
        if (!StringUtils.hasText(command.query())) throw new ApiException(ApiErrorCode.BAD_REQUEST, "检索关键词不能为空");
        var limit = Math.min(50, Math.max(1, command.limit()));
        var candidates = new ArrayList<FileResult>();
        knowledge.searchFiles(organizationId, command.query().trim(), safe(command.scopeIds()),
                        safe(command.knowledgeCategoryIds()), limit)
                .stream().map(this::knowledgeFile).forEach(candidates::add);
        dataSources.searchSourceFiles(organizationId, command.query().trim(), safe(command.dataCategoryIds()), limit)
                .stream().map(this::dataFile).forEach(candidates::add);
        var files = candidates.stream()
                .sorted(Comparator.comparingDouble(this::bestScore).reversed()
                        .thenComparing(FileResult::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit).toList();
        return new FileSearchResponse(files);
    }

    private FileResult knowledgeFile(KnowledgeFileSearchFacade.FileMatch file) {
        return new FileResult(file.fileObjectId(), file.logicalDocumentId(), file.fileVersionId(), "KNOWLEDGE",
                file.title(), file.originalName(), file.contentType(), file.size(), file.version(), file.tags(),
                file.relatedObjects().stream().map(item -> new RelatedObject(item.id(), item.objectType(),
                        item.externalId(), item.name())).toList(), file.updatedAt(),
                file.hits().stream().map(item -> new Hit(item.hitId(), item.snippet(), item.score(),
                        new SourceAnchor(item.pageNo(), item.sheetName(), item.cellRange(), item.paragraphId(),
                                item.bbox(), item.startTimeMs(), item.endTimeMs(), item.section(), null, null))).toList());
    }

    private FileResult dataFile(DataSourceFileSearchFacade.SourceFileMatch file) {
        return new FileResult(file.fileObjectId(), null, file.importJobId(), "DATA_CENTER", file.originalName(),
                file.originalName(), file.contentType(), file.size(), 1, List.of(), List.of(), file.updatedAt(),
                file.hits().stream().map(item -> new Hit(item.hitId(), item.snippet(), item.score(),
                        new SourceAnchor(null, item.sheetName(), item.cellAddress(), null, List.of(), null, null,
                                "SOURCE_CELL", item.rowNumber(), item.columnName()))).toList());
    }

    private double bestScore(FileResult file) {
        return file.hits().stream().mapToDouble(Hit::score).max().orElse(0);
    }

    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }

    public record SearchCommand(String query, int limit, List<UUID> scopeIds,
                                List<UUID> knowledgeCategoryIds, List<UUID> dataCategoryIds) { }
    public record FileSearchResponse(List<FileResult> files) { }
    public record FileResult(UUID fileObjectId, UUID logicalDocumentId, UUID fileVersionId, String sourceModule,
                             String title, String originalName, String contentType, long size, int version,
                             List<String> tags, List<RelatedObject> relatedObjects, Instant updatedAt,
                             List<Hit> hits) { }
    public record RelatedObject(UUID id, String objectType, String externalId, String name) { }
    public record Hit(UUID id, String snippet, double score, SourceAnchor anchor) { }
    public record SourceAnchor(Integer pageNo, String sheetName, String cellRange, String paragraphId,
                               List<Double> bbox, Long startTimeMs, Long endTimeMs, String section,
                               Integer rowNumber, String columnName) { }
}
