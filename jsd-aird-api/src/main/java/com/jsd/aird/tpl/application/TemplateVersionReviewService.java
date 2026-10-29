package com.jsd.aird.tpl.application;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.application.port.TemplateVersionReviewRepository;
import com.jsd.aird.tpl.domain.TemplateStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateVersionReviewService {

    private final TemplateRepository templates;
    private final TemplateVersionReviewRepository reviews;

    public TemplateVersionReviewService(TemplateRepository templates, TemplateVersionReviewRepository reviews) {
        this.templates = templates;
        this.reviews = reviews;
    }

    public View get(UUID versionId) {
        var actor = ActorContext.required();
        var review = reviews.ensure(actor.organizationId(), versionId);
        return view(review, reviews.events(actor.organizationId(), versionId));
    }

    @Transactional
    public View submit(UUID versionId, long workspaceVersion, long reviewVersion, boolean dataCleanupConfirmed) {
        var actor = ActorContext.required();
        requireDraft(actor.organizationId(), versionId);
        var current = reviews.ensure(actor.organizationId(), versionId);
        requireVersions(actor.organizationId(), versionId, current, workspaceVersion, reviewVersion);
        if (!current.status().equals("NOT_SUBMITTED") && !current.status().equals("REJECTED")) {
            throw new ApiException(ApiErrorCode.REVIEW_STATE_CONFLICT, "当前版本不允许提交审核");
        }
        var workspace = templates.findWorkspace(actor.organizationId(), versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "模板版本不存在"));
        var experimentOrigin = workspace.origin() != null
                && workspace.origin().isObject()
                && "EXPERIMENT".equals(workspace.origin().path("type").asText());
        if (experimentOrigin && !dataCleanupConfirmed) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "实验来源模板提交审核前必须确认已清理实际实验数据");
        }
        var comment = experimentOrigin ? "已确认检查并清理实际实验数据" : null;
        var result = reviews.transition(actor.organizationId(), versionId, reviewVersion, current.status(), "SUBMITTED",
                actor.userId(), comment);
        if (experimentOrigin) {
            templates.appendAudit(actor.organizationId(), actor.userId(),
                    "TEMPLATE_DATA_CLEANUP_CONFIRMED", "TEMPLATE_VERSION", versionId,
                    workspace.origin().deepCopy());
        }
        return view(result, reviews.events(actor.organizationId(), versionId));
    }

    @Transactional
    public View approve(UUID versionId, long workspaceVersion, long reviewVersion, String comment) {
        var actor = ActorContext.required();
        requireDraft(actor.organizationId(), versionId);
        var current = reviews.ensure(actor.organizationId(), versionId);
        requireVersions(actor.organizationId(), versionId, current, workspaceVersion, reviewVersion);
        if (!current.status().equals("SUBMITTED")) {
            throw new ApiException(ApiErrorCode.REVIEW_STATE_CONFLICT, "只有待审核版本可以通过审核");
        }
        return view(reviews.transition(actor.organizationId(), versionId, reviewVersion, "SUBMITTED", "APPROVED",
                actor.userId(), blankToNull(comment)), reviews.events(actor.organizationId(), versionId));
    }

    @Transactional
    public View reject(UUID versionId, long workspaceVersion, long reviewVersion, String reason) {
        var actor = ActorContext.required();
        requireDraft(actor.organizationId(), versionId);
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ApiErrorCode.REVIEW_REASON_REQUIRED, "驳回原因不能为空");
        }
        var current = reviews.ensure(actor.organizationId(), versionId);
        requireVersions(actor.organizationId(), versionId, current, workspaceVersion, reviewVersion);
        if (!current.status().equals("SUBMITTED")) {
            throw new ApiException(ApiErrorCode.REVIEW_STATE_CONFLICT, "只有待审核版本可以驳回");
        }
        return view(reviews.transition(actor.organizationId(), versionId, reviewVersion, "SUBMITTED", "REJECTED",
                actor.userId(), reason.trim()), reviews.events(actor.organizationId(), versionId));
    }

    public void requireApproved(UUID organizationId, UUID versionId) {
        templates.lockWorkspace(organizationId, versionId);
        var workspace = templates.findWorkspace(organizationId, versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "模板版本不存在"));
        if (workspace.status() == TemplateStatus.PUBLISHED) return;
        var review = reviews.ensure(organizationId, versionId);
        if (!"APPROVED".equals(review.status())) {
            throw new ApiException(ApiErrorCode.REVIEW_REQUIRED, "模板必须审核通过后才能发布");
        }
    }

    private void requireDraft(UUID organizationId, UUID versionId) {
        templates.lockWorkspace(organizationId, versionId);
        var workspace = templates.findWorkspace(organizationId, versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "模板版本不存在"));
        if (workspace.status() != TemplateStatus.DRAFT) {
            throw new ApiException(ApiErrorCode.TEMPLATE_VERSION_IMMUTABLE);
        }
    }

    public void requireEditable(UUID organizationId, UUID versionId) {
        templates.lockWorkspace(organizationId, versionId);
        var review = reviews.ensure(organizationId, versionId);
        if ("SUBMITTED".equals(review.status()) || "APPROVED".equals(review.status())) {
            throw new ApiException(ApiErrorCode.REVIEW_STATE_CONFLICT, "待审核或已审核模板不可修改，请使用修订流程");
        }
    }

    private void requireVersions(UUID organizationId, UUID versionId,
                                 TemplateVersionReviewRepository.Review review, long workspaceVersion, long reviewVersion) {
        var workspace = templates.findWorkspace(organizationId, versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "模板版本不存在"));
        if (workspace.lockVersion() != workspaceVersion || review.lockVersion() != reviewVersion) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "模板内容或审核版本已变化，请核对最新版本后操作");
        }
    }

    private View view(TemplateVersionReviewRepository.Review review,
                      List<TemplateVersionReviewRepository.ReviewEvent> events) {
        return new View(review.versionId(), review.status(), review.submittedBy(), review.submittedAt(),
                review.reviewedBy(), review.reviewedAt(), review.comment(), events, review.lockVersion());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record View(UUID versionId, String status, UUID submittedBy, java.time.Instant submittedAt,
                       UUID reviewedBy, java.time.Instant reviewedAt, String comment,
                       List<TemplateVersionReviewRepository.ReviewEvent> events, long lockVersion) { }
}
