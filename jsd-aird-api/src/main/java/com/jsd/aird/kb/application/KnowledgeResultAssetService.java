package com.jsd.aird.kb.application;

import java.util.UUID;

import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Opens parsed-result assets only after resolving their knowledge-document ownership. */
@Service
public class KnowledgeResultAssetService {

    private final JdbcTemplate jdbc;
    private final FileStorageFacade storage;

    public KnowledgeResultAssetService(JdbcTemplate jdbc, FileStorageFacade storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    public FileStorageFacade.StoredFile open(UUID organizationId, UUID assetFileId) {
        var allowed = jdbc.queryForObject("""
                SELECT count(*)
                FROM kb.document_result_asset a
                JOIN kb.document d ON d.id = a.document_id
                JOIN kb.document_version v ON v.id = a.document_version_id
                WHERE a.organization_id = ? AND a.asset_file_id = ?
                  AND d.lifecycle_status = 'ACTIVE'
                  AND v.status IN ('READY', 'PROCESSING')
                """, Long.class, organizationId, assetFileId);
        if (allowed == null || allowed == 0) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "图片资产不存在或当前无权访问");
        }
        return storage.open(organizationId, assetFileId);
    }
}
