package com.jsd.aird.shared.business;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** Domain-owned reads. Paging is internal; authorization precedes model-visible paging. */
public interface BusinessReadPort {
    Set<String> resourceTypes();
    Set<String> filters(String type);
    default Set<String> requiredFilters(String type) { return Set.of(); }
    Page references(String type, ObjectNode filters, int page, int size);
    BusinessRecordView read(ObjectNode reference);
    default FileContent file(ObjectNode reference) {
        throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.BAD_REQUEST, "该业务对象没有可读取的原文件");
    }

    record FileContent(String name, String contentType, byte[] bytes, String sha256) { }

    record Page(List<ObjectNode> references, boolean more) {
        public Page { references = List.copyOf(references); }
    }
}
