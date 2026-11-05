package com.jsd.aird.rnd.adapter.in.web;

import java.time.LocalDate;
import java.util.UUID;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.rnd.application.QuotationService;
import com.jsd.aird.rnd.application.QuotationReviewService;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.infrastructure.XlsxStructureParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.rnd.application.port.QuotationRepository;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 报价记录查看接口。 */
@RestController
@RequestMapping("/api/v1/quotations")
public class QuotationController {
    private final QuotationService service;
    private final QuotationReviewService reviewService;
    private final FileStorageFacade files;
    private final XlsxStructureParser xlsxParser;

    public QuotationController(QuotationService service, QuotationReviewService reviewService,
                               FileStorageFacade files, XlsxStructureParser xlsxParser) {
        this.service = service;
        this.reviewService = reviewService;
        this.files = files;
        this.xlsxParser = xlsxParser;
    }

    @GetMapping
    public ApiResponse<QuotationRepository.PageResult<QuotationRepository.Summary>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return success(service.search(new QuotationRepository.Search(
                keyword, customerName, dateFrom, dateTo, page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<QuotationRepository.Detail> detail(@PathVariable UUID id) {
        return success(service.detail(id));
    }

    @GetMapping("/{id}/source-workbook")
    public ApiResponse<JsonNode> sourceWorkbook(@PathVariable UUID id) throws Exception {
        var detail = service.detail(id);
        if (!"XLSX".equalsIgnoreCase(detail.sourceFormat()) || detail.summary().sourceFileId() == null) {
            throw new com.jsd.aird.shared.error.ApiException(
                    com.jsd.aird.shared.error.ApiErrorCode.BAD_REQUEST, "源文件不是 XLSX 工作簿");
        }
        try (var stored = files.open(ActorContext.required().organizationId(), detail.summary().sourceFileId())) {
            return success(xlsxParser.parseInstance(stored.stream()).snapshot());
        }
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<java.util.List<QuotationRepository.Version>> versions(@PathVariable UUID id) {
        return success(service.versions(id));
    }

    @PostMapping("/{id}/versions/{versionNo}/rollback")
    public ApiResponse<QuotationRepository.Detail> rollback(@PathVariable UUID id,
            @PathVariable int versionNo, @RequestBody RollbackCommand command) {
        return success(service.rollback(id, versionNo, command.expectedVersion()));
    }

    @GetMapping("/{id}/review")
    public ApiResponse<QuotationReviewService.View> review(@PathVariable UUID id) {
        return success(reviewService.get(id));
    }

    @PostMapping("/{id}/review/submit")
    public ApiResponse<QuotationReviewService.View> submitReview(@PathVariable UUID id,
            @RequestBody ReviewCommand command) {
        return success(reviewService.submit(id, command.quotationVersion(), command.reviewVersion()));
    }

    @PostMapping("/{id}/review/approve")
    public ApiResponse<QuotationReviewService.View> approveReview(@PathVariable UUID id,
            @RequestBody ReviewCommand command) {
        return success(reviewService.approve(id, command.quotationVersion(),
                command.reviewVersion(), command.comment()));
    }

    @PostMapping("/{id}/review/reject")
    public ApiResponse<QuotationReviewService.View> rejectReview(@PathVariable UUID id,
            @RequestBody ReviewCommand command) {
        return success(reviewService.reject(id, command.quotationVersion(),
                command.reviewVersion(), command.comment()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return success(null);
    }

    @PutMapping("/{id}")
    public ApiResponse<QuotationRepository.Detail> update(@PathVariable UUID id,
                                                            @RequestBody QuotationRepository.Edit edit) {
        return success(service.update(id, edit));
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record ReviewCommand(int quotationVersion, long reviewVersion, String comment) { }
    public record RollbackCommand(int expectedVersion) { }
}
