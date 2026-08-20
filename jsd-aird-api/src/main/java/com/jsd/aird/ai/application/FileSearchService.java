package com.jsd.aird.ai.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        var dataFilesById = new LinkedHashMap<UUID, DataSourceFileSearchFacade.SourceFileMatch>();
        for (var term : searchTerms(command.query())) {
            dataSources.searchSourceFiles(organizationId, term, safe(command.dataCategoryIds()), limit)
                    .forEach(file -> dataFilesById.merge(file.fileObjectId(), file, this::mergeDataFile));
        }
        dataFilesById.values().stream().map(this::dataFile).forEach(candidates::add);
        var files = candidates.stream()
                .sorted(Comparator.comparingDouble(this::bestScore).reversed()
                        .thenComparing(FileResult::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit).toList();
        return new FileSearchResponse(files);
    }

    private FileResult knowledgeFile(KnowledgeFileSearchFacade.FileMatch file) {
        return new FileResult(file.fileObjectId(), file.logicalDocumentId(), file.fileVersionId(), "KNOWLEDGE",
                file.title(), file.originalName(), file.contentType(), file.size(), file.version(), file.tags(),
                file.updatedAt(),
                file.hits().stream().map(item -> new Hit(item.hitId(), item.snippet(), item.score(),
                        new SourceAnchor(item.pageNo(), item.sheetName(), item.cellRange(), item.paragraphId(),
                                item.bbox(), item.startTimeMs(), item.endTimeMs(), item.section(), null, null))).toList());
    }

    private FileResult dataFile(DataSourceFileSearchFacade.SourceFileMatch file) {
        return new FileResult(file.fileObjectId(), null, file.importJobId(), "DATA_CENTER", file.originalName(),
                file.originalName(), file.contentType(), file.size(), 1, List.of(), file.updatedAt(),
                file.hits().stream().map(item -> new Hit(item.hitId(), item.snippet(), item.score(),
                        new SourceAnchor(null, item.sheetName(), item.cellAddress(), null, List.of(), null, null,
                                "SOURCE_CELL", item.rowNumber(), item.columnName()))).toList());
    }

    private double bestScore(FileResult file) {
        var best = file.hits().stream().mapToDouble(Hit::score).max().orElse(0);
        return best + Math.min(0.05, file.hits().size() * 0.005);
    }

    private DataSourceFileSearchFacade.SourceFileMatch mergeDataFile(
            DataSourceFileSearchFacade.SourceFileMatch left,
            DataSourceFileSearchFacade.SourceFileMatch right) {
        var hits = new LinkedHashMap<UUID, DataSourceFileSearchFacade.Hit>();
        left.hits().forEach(hit -> hits.put(hit.hitId(), hit));
        right.hits().forEach(hit -> hits.putIfAbsent(hit.hitId(), hit));
        var merged = hits.values().stream()
                .sorted(Comparator.comparingDouble(DataSourceFileSearchFacade.Hit::score).reversed())
                .limit(10).toList();
        return new DataSourceFileSearchFacade.SourceFileMatch(left.fileObjectId(), left.importJobId(),
                left.originalName(), left.contentType(), left.size(),
                left.updatedAt().isAfter(right.updatedAt()) ? left.updatedAt() : right.updatedAt(), merged);
    }

    private List<String> searchTerms(String query) {
        var terms = new LinkedHashSet<String>();
        terms.add(query.trim());
        for (var term : query.split("[\\s,，、；;：:()（）]+")) {
            if (term.length() >= 2) terms.add(term);
        }
        return terms.stream().limit(12).toList();
    }

    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }

    public record SearchCommand(String query, int limit, List<UUID> scopeIds,
                                List<UUID> knowledgeCategoryIds, List<UUID> dataCategoryIds) { }
    public record FileSearchResponse(List<FileResult> files) { }
    public record FileResult(UUID fileObjectId, UUID logicalDocumentId, UUID fileVersionId, String sourceModule,
                             String title, String originalName, String contentType, long size, int version,
                             List<String> tags, Instant updatedAt,
                             List<Hit> hits) { }
    public record Hit(UUID id, String snippet, double score, SourceAnchor anchor) { }
    public record SourceAnchor(Integer pageNo, String sheetName, String cellRange, String paragraphId,
                               List<Double> bbox, Long startTimeMs, Long endTimeMs, String section,
                               Integer rowNumber, String columnName) { }
}
