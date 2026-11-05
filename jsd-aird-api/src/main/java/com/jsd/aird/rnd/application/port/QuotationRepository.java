package com.jsd.aird.rnd.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;

/** 报价记录的读写端口。 */
public interface QuotationRepository {

    UUID create(UUID organizationId, UUID actorId, Draft draft);

    PageResult<Summary> search(UUID organizationId, UUID userId, String role, Search search);

    Optional<Detail> detail(UUID organizationId, UUID userId, String role, UUID quotationId);

    int delete(UUID organizationId, UUID userId, String role, UUID quotationId);

    void retireReplaced(UUID organizationId, UUID quotationId);

    int update(UUID organizationId, UUID userId, String role, UUID quotationId, Edit edit);

    List<Version> versions(UUID organizationId, UUID quotationId);

    void saveVersion(UUID organizationId, UUID quotationId, UUID actorId, int versionNo, JsonNode detail);

    record Version(int versionNo, Instant createdAt, UUID createdBy, String createdByName,
                   JsonNode detail) { }

    record Edit(String customerName, String relatedCompany, String contactName, String contactTitle,
                String phone, String fax, String email, String addressLine1, String addressLine2,
                String website, String note, List<ContactDraft> contacts, List<ItemDraft> items,
                JsonNode workbookSnapshot, int versionNo) { }

    record Draft(String quotationNo, String customerName, String relatedCompany, String contactName,
                 String contactTitle, String phone, String fax, String email, String addressLine1,
                 String addressLine2, String website, String note, UUID sourceFileId,
                 String sourceFileName, String sourceFormat, List<ContactDraft> contacts,
                 List<ItemDraft> items,
                 UUID templateVersionId, UUID projectId, String projectName,
                 UUID stageId, String stageName, UUID taskId, String taskName,
                 String visibility) { }

    record ContactDraft(int position, int sourceColumn, String name, String title, String phone) { }

    record PriceOptionDraft(int position, java.math.BigDecimal unitPrice, String specification) { }

    record ItemDraft(int lineNo, int sourceRowNo, String productName, String unitPrice,
                     String specification, List<PriceOptionDraft> priceOptions,
                     LocalDate quoteDate, String customerCode, String note) { }

    record Summary(UUID id, String quotationNo, String customerName, String relatedCompany,
                   String contactName, String phone, UUID sourceFileId, String sourceFileName,
                   int itemCount, LocalDate firstQuoteDate, LocalDate lastQuoteDate, Instant createdAt,
                   String reviewStatus) { }

    record Item(UUID id, int lineNo, int sourceRowNo, String productName, String unitPrice,
                String specification, LocalDate quoteDate, String customerCode, String note,
                List<PriceOption> priceOptions) { }

    record Contact(UUID id, int position, int sourceColumn, String name, String title, String phone) { }

    record PriceOption(UUID id, int position, java.math.BigDecimal unitPrice, String specification) { }

    record Detail(Summary summary, String contactTitle, String fax, String email, String addressLine1,
                  String addressLine2, String website, String note, String sourceFormat,
                  List<Contact> contacts, List<Item> items, JsonNode workbookSnapshot, int versionNo) { }

    record Search(String keyword, String customerName, LocalDate dateFrom, LocalDate dateTo,
                  int page, int size) { }

    record PageResult<T>(List<T> items, long page, long size, long total, long totalPages) { }
}
