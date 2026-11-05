package com.jsd.aird.rnd.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jsd.aird.rnd.application.port.QuotationReviewRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotationReviewService {
    private final QuotationService quotations;
    private final QuotationReviewRepository reviews;

    public QuotationReviewService(QuotationService quotations, QuotationReviewRepository reviews) {
        this.quotations = quotations;
        this.reviews = reviews;
    }

    public View get(UUID id) {
        var detail = quotations.detail(id);
        var actor = ActorContext.required();
        var review = reviews.ensure(actor.organizationId(), id);
        return view(id, detail.versionNo(), review);
    }

    @Transactional
    public View submit(UUID id, int quotationVersion, long reviewVersion) {
        var actor = ActorContext.required();
        quotations.detail(id);
        var current = reviews.ensure(actor.organizationId(), id);
        var detail = quotations.detail(id);
        requireVersions(detail.versionNo(), quotationVersion, current.lockVersion(), reviewVersion);
        if (!current.status().equals("NOT_SUBMITTED") && !current.status().equals("REJECTED")) {
            throw new ApiException(ApiErrorCode.REVIEW_STATE_CONFLICT, "当前报价记录不允许提交审核");
        }
        return view(id, quotationVersion, reviews.transition(actor.organizationId(), id,
                reviewVersion, current.status(), "SUBMITTED", quotationVersion,
                actor.userId(), null));
    }

    @Transactional
    public View approve(UUID id, int quotationVersion, long reviewVersion, String comment) {
        return decide(id, quotationVersion, reviewVersion, "APPROVED",
                comment == null || comment.isBlank() ? null : comment.trim());
    }

    @Transactional
    public View reject(UUID id, int quotationVersion, long reviewVersion, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ApiErrorCode.REVIEW_REASON_REQUIRED, "驳回原因不能为空");
        }
        return decide(id, quotationVersion, reviewVersion, "REJECTED", reason.trim());
    }

    private View decide(UUID id, int quotationVersion, long reviewVersion,
                        String nextStatus, String comment) {
        var actor = ActorContext.required();
        quotations.detail(id);
        var current = reviews.ensure(actor.organizationId(), id);
        var detail = quotations.detail(id);
        requireVersions(detail.versionNo(), quotationVersion, current.lockVersion(), reviewVersion);
        if (!"SUBMITTED".equals(current.status())) {
            throw new ApiException(ApiErrorCode.REVIEW_STATE_CONFLICT, "只有待审核报价可以通过或驳回");
        }
        return view(id, quotationVersion, reviews.transition(actor.organizationId(), id,
                reviewVersion, "SUBMITTED", nextStatus, quotationVersion,
                actor.userId(), comment));
    }

    private void requireVersions(int actualQuotationVersion, int quotationVersion,
                                 long actualReviewVersion, long reviewVersion) {
        if (actualQuotationVersion != quotationVersion || actualReviewVersion != reviewVersion) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                    "报价内容或审核状态已变化，请刷新后重试");
        }
    }

    private View view(UUID id, int quotationVersion, QuotationReviewRepository.Review review) {
        var organizationId = ActorContext.required().organizationId();
        return new View(id, quotationVersion, review.status(), review.submittedBy(),
                review.submittedAt(), review.reviewedBy(), review.reviewedAt(),
                review.comment(), review.lockVersion(), reviews.events(organizationId, id));
    }

    public record View(UUID quotationId, int quotationVersion, String status,
                       UUID submittedBy, Instant submittedAt, UUID reviewedBy,
                       Instant reviewedAt, String comment, long lockVersion,
                       List<QuotationReviewRepository.Event> events) { }
}
