package com.jsd.aird.ai.application;

import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;

/** Business copy only. Raw evidence, identifiers and standard form names remain intact. */
public final class ResearchWording {
    private ResearchWording() { }

    public static String business(String value) {
        if (value == null) return null;
        return value.replace("测试实验", "\u0001FORM\u0001")
            .replace("验收", "完成检查").replace("测试", "测定")
            .replace("模拟", "研究用").replace("验证", "评估")
            .replace("\u0001FORM\u0001", "测试实验");
    }

    public static void requireBusinessCopy(String value) {
        if (value == null || !value.equals(business(value)))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "请使用业务表述，保留来源事实及预测与实测的区别");
    }
}
