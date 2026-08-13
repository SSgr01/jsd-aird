package com.jsd.aird.data.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** File-search boundary. Results are immutable source files, never structured data assets or records. */
public interface DataSourceFileSearchFacade {

    List<SourceFileMatch> searchSourceFiles(UUID organizationId, String query, List<UUID> categoryIds, int limit);

    record SourceFileMatch(UUID fileObjectId, UUID importJobId, String originalName, String contentType,
                           long size, Instant updatedAt, List<Hit> hits) { }

    record Hit(UUID hitId, String snippet, double score, String sheetName, Integer rowNumber,
               String columnName, String cellAddress) { }
}
