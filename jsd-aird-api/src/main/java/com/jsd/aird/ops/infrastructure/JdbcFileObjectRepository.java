package com.jsd.aird.ops.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.ops.application.port.FileObjectRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFileObjectRepository implements FileObjectRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcFileObjectRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(NewFileObject file) {
        jdbcTemplate.update("""
                        INSERT INTO ops.file_object (
                            id, organization_id, bucket, object_key, original_name,
                            content_type, size_bytes, sha256, status, created_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'STAGED', ?)
                        """,
                file.id(),
                file.organizationId(),
                file.bucket(),
                file.objectKey(),
                file.originalName(),
                file.contentType(),
                file.size(),
                file.sha256(),
                file.actorId()
        );
    }

    @Override
    public Optional<FileObject> find(UUID organizationId, UUID fileId) {
        return jdbcTemplate.query("""
                        SELECT id, organization_id, object_key, original_name, content_type,
                               size_bytes, sha256, status
                        FROM ops.file_object
                        WHERE id = ? AND organization_id = ?
                        """,
                (rs, rowNum) -> new FileObject(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getString("object_key"),
                        rs.getString("original_name"),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("sha256"),
                        rs.getString("status")
                ),
                fileId,
                organizationId
        ).stream().findFirst();
    }

    @Override
    public void activate(UUID fileId) {
        jdbcTemplate.update("""
                UPDATE ops.file_object
                SET status = 'ACTIVE', activated_at = now()
                WHERE id = ? AND status = 'STAGED'
                """, fileId);
    }

    @Override
    public int markExpiredStagedDeleted(Instant olderThan) {
        return jdbcTemplate.update("""
                UPDATE ops.file_object
                SET status = 'DELETED'
                WHERE status = 'STAGED' AND created_at < ?
                """, java.sql.Timestamp.from(olderThan));
    }
}
