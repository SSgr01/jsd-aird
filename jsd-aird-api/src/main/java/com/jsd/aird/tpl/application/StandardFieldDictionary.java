package com.jsd.aird.tpl.application;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Versioned semantic vocabulary used by recognition compilation.  The database
 * dictionary is the source of truth for administration; this small immutable
 * v1 snapshot keeps imports deterministic when a dictionary lookup is not
 * available in the recognition worker.
 */
public final class StandardFieldDictionary {

    public static final int VERSION = 1;

    private static final Map<String, Entry> ALIASES = Map.ofEntries(
            entry("品名", "PRODUCTION.PRODUCT_NAME", "productName", "品名"),
            entry("产品名称", "PRODUCTION.PRODUCT_NAME", "productName", "品名"),
            entry("产品名", "PRODUCTION.PRODUCT_NAME", "productName", "品名"),
            entry("实际产量", "PRODUCTION.ACTUAL_OUTPUT", "actualOutput", "实际产量"),
            entry("实际生产量", "PRODUCTION.ACTUAL_OUTPUT", "actualOutput", "实际产量"),
            entry("类别", "PRODUCTION.CATEGORY", "category", "类别"),
            entry("订单号", "PRODUCTION.ORDER_NO", "orderNo", "订单号"),
            entry("表单编号", "DOCUMENT.FORM_NO", "formNo", "表单编号"),
            entry("反应釜", "PRODUCTION.REACTOR", "reactor", "反应釜"),
            entry("包装批号", "PRODUCTION.PACKAGE_BATCH_NO", "packageBatchNo", "包装批号"),
            entry("制造日期", "PRODUCTION.MANUFACTURE_DATE", "manufactureDate", "制造日期"),
            entry("包装物料", "PACKAGING.MATERIAL", "packageMaterial", "包装物料"),
            entry("包装规格", "PACKAGING.SPECIFICATION", "packageSpecification", "包装规格"),
            entry("包装数量", "PACKAGING.QUANTITY", "packageQuantity", "包装数量"),
            entry("涂料外观", "COATING.PROPERTY.APPEARANCE", "coatingAppearance", "涂料外观"),
            entry("漆膜外观", "FILM.PROPERTY.APPEARANCE", "filmAppearance", "漆膜外观"),
            entry("原料编号", "FORMULA.ITEM.MATERIAL_CODE", "materialCode", "原料编号"),
            entry("物料编号", "FORMULA.ITEM.MATERIAL_CODE", "materialCode", "原料编号"),
            entry("配方比例", "FORMULA.ITEM.RATIO", "ratio", "配方比例"),
            entry("比例", "FORMULA.ITEM.RATIO", "ratio", "配方比例"),
            entry("理论投料量", "FORMULA.ITEM.THEORETICAL_KG", "theoreticalKg", "理论投料量"),
            entry("理论用量", "FORMULA.ITEM.THEORETICAL_KG", "theoreticalKg", "理论投料量"),
            entry("实际投料量", "FORMULA.ITEM.ACTUAL_KG", "actualKg", "实际投料量"),
            entry("实际投料", "FORMULA.ITEM.ACTUAL_KG", "actualKg", "实际投料量"),
            entry("批号", "FORMULA.ITEM.BATCH_NO", "batchNo", "批号"),
            entry("原料批号", "FORMULA.ITEM.BATCH_NO", "batchNo", "批号"),
            entry("序号", "FORMULA.ITEM.SEQUENCE", "sequence", "序号"),
            entry("备注", "FORMULA.ITEM.REMARK", "remark", "备注")
    );

    private StandardFieldDictionary() {
    }

    public static Optional<Entry> match(String label) {
        var normalized = normalize(label);
        return Optional.ofNullable(ALIASES.get(normalized));
    }

    public record Entry(String fieldCode, String pathSegment, String displayName) {
    }

    private static Map.Entry<String, Entry> entry(
            String alias, String fieldCode, String pathSegment, String displayName
    ) {
        return Map.entry(normalize(alias), new Entry(fieldCode, pathSegment, displayName));
    }

    private static String normalize(String value) {
        return value == null ? "" : value
                .replace('：', ':')
                .replaceAll("[\\s:：]", "")
                .strip()
                .toLowerCase(Locale.ROOT);
    }
}
