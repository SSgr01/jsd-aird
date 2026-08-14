package com.jsd.aird.data.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** File-search boundary. Results are immutable source files, never structured records. */
public interface DataSourceFileSearchFacade {

    List<SourceFileMatch> searchSourceFiles(UUID organizationId, String query, List<UUID> categoryIds, int limit);

    default List<SourceFileHit> search(UUID organizationId, String query, List<UUID> categoryIds, int limit) {
        return searchSourceFiles(organizationId, query, categoryIds, limit).stream()
                .flatMap(file -> file.hits().stream().map(hit -> new SourceFileHit(
                        hit.hitId(), file.fileObjectId(), file.importJobId(), hit.rowNumber(), hit.columnName(),
                        file.originalName(), hit.snippet(), hit.score(),
                        "DATA_CENTER:" + (hit.sheetName() == null ? "" : hit.sheetName())
                                + ":" + (hit.cellAddress() == null ? "" : hit.cellAddress()))))
                .toList();
    }

    record SourceFileMatch(UUID fileObjectId, UUID importJobId, String originalName, String contentType,
                           long size, Instant updatedAt, List<Hit> hits) { }

    record Hit(UUID hitId, String snippet, double score, String sheetName, Integer rowNumber,
               String columnName, String cellAddress) { }

    record SourceFileHit(UUID hitId, UUID fileObjectId, UUID importJobId, Integer rowNumber,
                         String columnName, String originalName, String content, double score,
                         String sourceLocator) { }
}
