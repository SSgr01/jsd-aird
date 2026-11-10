package com.jsd.aird.rnd.application;

import java.util.UUID;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jsd.aird.rnd.application.port.QuotationRepository;
import com.jsd.aird.rnd.application.port.QuotationReviewRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 报价记录查询与删除。 */
@Service
public class QuotationService {
    private final QuotationRepository quotations;
    private final QuotationReviewRepository reviews;
    private final ObjectMapper objectMapper;

    public QuotationService(QuotationRepository quotations, ObjectMapper objectMapper,
                            QuotationReviewRepository reviews) {
        this.quotations = quotations;
        this.objectMapper = objectMapper;
        this.reviews = reviews;
    }

    public QuotationRepository.PageResult<QuotationRepository.Summary> search(
            QuotationRepository.Search search) {
        var actor = ActorContext.required();
        var page = search == null ? 1 : Math.max(1, search.page());
        var size = search == null ? 10 : Math.max(1, Math.min(200, search.size()));
        return quotations.search(actor.organizationId(), actor.userId(), actor.role(), new QuotationRepository.Search(
                search == null ? null : search.keyword(),
                search == null ? null : search.customerName(),
                search == null ? null : search.dateFrom(),
                search == null ? null : search.dateTo(), page, size));
    }

    public QuotationRepository.Detail detail(UUID id) {
        var actor = ActorContext.required();
        return quotations.detail(actor.organizationId(), actor.userId(), actor.role(), id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "报价记录不存在"));
    }

    @Transactional
    public QuotationRepository.Detail update(UUID id, QuotationRepository.Edit edit) {
        if (edit == null || edit.customerName() == null || edit.customerName().isBlank()
                || edit.customerName().length() > 300 || edit.contacts() == null || edit.items() == null
                || edit.items().isEmpty() || edit.items().size() > 1000) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "客户名称和至少一条报价明细不能为空");
        }
        for (var item : edit.items()) {
            if (item == null || item.productName() == null || item.productName().isBlank()
                    || item.productName().length() > 300 || item.priceOptions() == null) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "报价明细的产品名称不能为空");
            }
            for (var price : item.priceOptions()) {
                if (price == null || price.unitPrice() == null || price.unitPrice().signum() < 0) {
                    throw new ApiException(ApiErrorCode.BAD_REQUEST, "单价必须为非负数字");
                }
            }
        }
        var actor = ActorContext.required();
        var before = detail(id);
        var review = reviews.ensure(actor.organizationId(), id);
        if ("SUBMITTED".equals(review.status()) || "APPROVED".equals(review.status())) {
            throw new ApiException(ApiErrorCode.REVIEW_STATE_CONFLICT,
                    "待审核或已通过的报价记录不可修改");
        }
        if (edit.versionNo() != before.versionNo()) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "报价记录已被修改，请刷新后重试");
        }
        quotations.saveVersion(actor.organizationId(), id, actor.userId(), before.versionNo(),
                objectMapper.valueToTree(before));
        if (quotations.update(actor.organizationId(), actor.userId(), actor.role(), id, edit) == 0) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "报价记录已被修改，请刷新后重试");
        }
        var saved = detail(id);
        quotations.saveVersion(actor.organizationId(), id, actor.userId(), saved.versionNo(),
                objectMapper.valueToTree(saved));
        return saved;
    }

    public List<QuotationRepository.Version> versions(UUID id) {
        var actor = ActorContext.required();
        detail(id);
        return quotations.versions(actor.organizationId(), id);
    }

    @Transactional
    public QuotationRepository.Detail rollback(UUID id, int targetVersion, int expectedVersion) {
        var actor = ActorContext.required();
        var current = detail(id);
        if (current.versionNo() != expectedVersion) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "报价记录已被修改，请刷新后重试");
        }
        var historical = quotations.versions(actor.organizationId(), id).stream()
                .filter(version -> version.versionNo() == targetVersion && targetVersion < expectedVersion)
                .findFirst().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "历史版本不存在"));
        QuotationRepository.Detail snapshot;
        try {
            snapshot = objectMapper.treeToValue(historical.detail(), QuotationRepository.Detail.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "历史版本快照无效");
        }
        var review = reviews.ensure(actor.organizationId(), id);
        if (!"NOT_SUBMITTED".equals(review.status())) {
            reviews.transition(actor.organizationId(), id, review.lockVersion(), review.status(),
                    "NOT_SUBMITTED", expectedVersion, actor.userId(), "回退到 V" + targetVersion);
        }
        return update(id, new QuotationRepository.Edit(snapshot.summary().customerName(),
                snapshot.summary().relatedCompany(), snapshot.summary().contactName(),
                snapshot.contactTitle(), snapshot.summary().phone(), snapshot.fax(), snapshot.email(),
                snapshot.addressLine1(), snapshot.addressLine2(), snapshot.website(), snapshot.note(),
                snapshot.contacts().stream().map(contact -> new QuotationRepository.ContactDraft(
                        contact.position(), contact.sourceColumn(), contact.name(), contact.title(), contact.phone())).toList(),
                snapshot.items().stream().map(item -> new QuotationRepository.ItemDraft(
                        item.lineNo(), item.sourceRowNo(), item.productName(), item.unitPrice(),
                        item.specification(), item.priceOptions().stream().map(price ->
                                new QuotationRepository.PriceOptionDraft(price.position(), price.unitPrice(),
                                        price.specification())).toList(), item.quoteDate(), item.customerCode(),
                        item.note())).toList(), snapshot.workbookSnapshot(), expectedVersion));
    }

    @Transactional
    public void delete(UUID id) {
        var actor = ActorContext.required();
        if (quotations.delete(actor.organizationId(), actor.userId(), actor.role(), id) == 0) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "报价记录不存在或已删除");
        }
    }
}
