package com.jsd.aird.ai.adapter.out;

import com.jsd.aird.ops.api.FileReadPolicy;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Protect private originals even when another route opens the underlying file-object ID. */
@Component
public class PrivateSourceFileReadPolicy implements FileReadPolicy {
    private final JdbcTemplate jdbc;
    public PrivateSourceFileReadPolicy(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void requireRead(UUID organizationId, UUID actorId, UUID fileId) {
        var owners = jdbc.queryForList("SELECT owner_id FROM ai.agent_private_source WHERE organization_id=? AND version_id=?",
            UUID.class, organizationId, fileId);
        if (owners.isEmpty()) owners = jdbc.queryForList("SELECT owner_id FROM ai.research_record WHERE organization_id=? "
            + "AND kind='artifact' AND payload->>'file_id'=?", UUID.class, organizationId, fileId.toString());
        if (!owners.isEmpty() && (actorId == null || owners.stream().noneMatch(actorId::equals)))
            throw new ApiException(ApiErrorCode.NOT_FOUND);
    }
}
