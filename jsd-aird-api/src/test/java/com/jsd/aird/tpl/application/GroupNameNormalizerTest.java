package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GroupNameNormalizerTest {

    @Test
    void rejectsTechnicalGroupKeysAndUsesBusinessFallback() {
        assertThat(GroupNameNormalizer.normalizeModelSuggestion("formulaDetailTable")).isEmpty();
        assertThat(GroupNameNormalizer.normalizeModelSuggestion("category")).isEmpty();
        assertThat(GroupNameNormalizer.normalizeModelSuggestion("产品基础信息")).contains("基础信息");
        assertThat(GroupNameNormalizer.normalizeCustomerDefined("my-custom-group")).isEqualTo("my-custom-group");
        assertThat(GroupNameNormalizer.normalizeCustomerGroup("category", "基础信息"))
                .isEqualTo("基础信息");
    }

    @Test
    void infersGroupsFromBusinessBlocks() {
        assertThat(GroupNameNormalizer.infer("ROW_TABLE", "配方明细表")).isEqualTo("配方明细");
        assertThat(GroupNameNormalizer.infer("FORM_FIELDS", "包装与产量信息")).isEqualTo("包装信息");
        assertThat(GroupNameNormalizer.infer("SIGNATURE_BLOCK", "人员签字")).isEqualTo("审核信息");
    }
}
