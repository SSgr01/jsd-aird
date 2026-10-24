package com.jsd.aird.shared.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;

/** Opaque revision for existing aggregates without a numeric version column.
 * Validate it only after locking the aggregate row in the write transaction. */
public final class RevisionToken {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final JsonCanonicalizer CANONICAL = new JsonCanonicalizer(MAPPER);
    private RevisionToken() {}

    public static String of(Object... values) {
        return CANONICAL.hash(MAPPER.valueToTree(values));
    }

    public static String ofRecord(Record record) {
        try {
            var components=record.getClass().getRecordComponents();
            var values=new Object[components.length];
            for(int index=0;index<components.length;index++)values[index]=components[index].getAccessor().invoke(record);
            return of(values);
        } catch(ReflectiveOperationException error) { throw new IllegalStateException("Cannot read revision fields",error); }
    }

    public static void require(String expected, String actual) {
        if (expected == null || !expected.matches("[a-f0-9]{64}"))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "必须提交读取时的数据版本");
        if (!expected.equals(actual))
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "数据已被其他用户修改，请核对最新版本后操作");
    }
}
