package com.jsd.aird.data.application;

import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** All editable data paths serialize with confirmation/review on the import job. */
@Component
public class DataReviewGuard {
    private final JdbcTemplate jdbc;
    public DataReviewGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean locked(UUID organizationId, UUID jobId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM data.publication WHERE organization_id=? AND import_job_id=? AND status IN ('PENDING_REVIEW','APPROVED','PUBLISHED','DISABLED'))",
                Boolean.class, organizationId, jobId));
    }

    public void assertNotFreeform(UUID organizationId, UUID jobId) {
        var modes = jdbc.query("SELECT source_owner,recognition_mode FROM data.import_job WHERE organization_id=? AND id=?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, organizationId, jobId);
        if (!modes.isEmpty() && "DATA_CENTER".equals(modes.getFirst()[0])
                && "FREEFORM".equals(modes.getFirst()[1])) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                    "历史自由识别记录已设为只读，只能查看和下载原文件");
        }
    }

    public void beforeEdit(UUID organizationId, UUID jobId) {
        var jobs = jdbc.query("SELECT source_owner,status,recognition_mode FROM data.import_job WHERE organization_id=? AND id=? FOR UPDATE",
                (rs, n) -> new String[]{rs.getString(1),rs.getString(2),rs.getString(3)}, organizationId, jobId);
        if (jobs.isEmpty() || !"DATA_CENTER".equals(jobs.getFirst()[0])) return;
        if ("FREEFORM".equals(jobs.getFirst()[2])) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                    "历史自由识别记录已设为只读，只能查看和下载原文件");
        }
        var states = jdbc.query("SELECT status FROM data.publication WHERE organization_id=? AND import_job_id=?",
                (rs, n) -> rs.getString(1), organizationId, jobId);
        if (states.contains("PENDING_REVIEW")) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                "数据已送审，审核期间不能修改；请先撤回送审");
        if (states.contains("APPROVED")) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                "数据已审核通过，请先撤回修改，再重新提交审核");
        if (states.contains("PUBLISHED") || states.contains("DISABLED")) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                "已发布数据只读，请先创建修订草稿");
        if (states.isEmpty() && "COMPLETED".equals(jobs.getFirst()[1])) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                "历史已入库数据只读，请先创建修订草稿");
        jdbc.update("""
                UPDATE data.publication SET status='DRAFT',confirmed_snapshot_id=NULL,updated_at=now()
                WHERE organization_id=? AND import_job_id=? AND status='CONFIRMED'
                """, organizationId, jobId);
    }
}
