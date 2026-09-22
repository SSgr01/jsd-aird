package com.jsd.aird.ai.rnd.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Customer-facing catalog entries that are formally registered but do not
 * yet have enough approved data for a model. They are intentionally disabled
 * in the client and never accepted as prediction requests. */
public final class RegisteredUvpuTargetCatalog {
    private RegisteredUvpuTargetCatalog() { }

    private record Entry(String code, String name, String category, String valueType, String unit) { }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("UVPU.PC.WARP.ANGLE", "PC膜卷曲角度（UV即时）", "膜性能", "CONTINUOUS", "°"),
            new Entry("UVPU.PET.WARP.ANGLE", "PET膜卷曲角度（UV即时）", "膜性能", "CONTINUOUS", "°"),
            new Entry("UVPU.PC.WARP.HEIGHT", "PC膜翘曲高度（UV即时）", "膜性能", "CONTINUOUS", "cm"),
            new Entry("UVPU.PET.ADHESION.INITIAL", "PET膜初始附着力", "膜性能", "ORDINAL", "B"),
            new Entry("UVPU.PC.ADHESION.INITIAL", "PC膜初始附着力", "膜性能", "ORDINAL", "B"),
            new Entry("UVPU.PMMA_PC.ADHESION.INITIAL", "PMMA/PC复合板初始附着力", "膜性能", "ORDINAL", "B"),
            new Entry("UVPU.PET.ADHESION.BOIL_85C_1H", "PET膜85℃水煮1小时附着力", "膜性能", "ORDINAL", "B"),
            new Entry("UVPU.PC.ADHESION.BOIL_85C_1H", "PC膜85℃水煮1小时附着力", "膜性能", "ORDINAL", "B"),
            new Entry("UVPU.PMMA_PC.ADHESION.BOIL_85C_1H", "PMMA/PC复合板85℃水煮1小时附着力", "膜性能", "ORDINAL", "B"),
            new Entry("UVPU.PET.ADHESION.REBOIL_1H", "PET膜再沸水煮1小时附着力", "膜性能", "ORDINAL", "B"),
            new Entry("UVPU.PC.ADHESION.REBOIL_1H", "PC膜再沸水煮1小时附着力", "膜性能", "ORDINAL", "B"),
            new Entry("UVPU.PMMA_PC.ADHESION.REBOIL_1H", "PMMA/PC复合板再沸水煮1小时附着力", "膜性能", "ORDINAL", "B"),
            new Entry("UVPU.PC.ELONGATION.THERMAL_PULL", "PC膜热拉伸率", "力学性能", "CONTINUOUS", "%")
    );

    public static void append(ObjectMapper json, ArrayNode catalog) {
        var existing = new java.util.HashSet<String>();
        catalog.forEach(item -> existing.add(item.path("code").asText()));
        ENTRIES.stream().filter(entry -> !existing.contains(entry.code())).forEach(entry -> {
            var node = json.createObjectNode()
                    .put("targetId", UUID.nameUUIDFromBytes(("uvpu-registered-target:" + entry.code()).getBytes(StandardCharsets.UTF_8)).toString())
                    .put("code", entry.code()).put("name", entry.name()).put("category", entry.category())
                    .put("valueType", entry.valueType()).put("targetStatus", "REGISTERED_DATA_INSUFFICIENT")
                    .put("registered", true).put("formalPredictionAvailable", false).put("available", false)
                    .put("statusLabel", "已登记，数据不足").put("unit", entry.unit());
            var reasons = node.putArray("unavailableReasons"); reasons.add("REGISTERED_DATA_INSUFFICIENT");
            catalog.add(node);
        });
    }
}
