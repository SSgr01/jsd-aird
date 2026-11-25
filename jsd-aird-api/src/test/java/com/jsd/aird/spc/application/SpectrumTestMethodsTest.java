package com.jsd.aird.spc.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SpectrumTestMethodsTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void preservesIndependentAtrAndTransmittanceAndHistoricalSnapshot() throws Exception {
        var snap = json.readTree("""
          {"schemaVersion":1,"methodType":"IR","presetName":"ATR","presetRevision":"1",
           "instrument":{"model":"IRXross","crystal":"金刚石"},"conditions":{"samplingMode":"ATR","signalType":"透过率（%T）","scans":20}}
          """);
        var metadata = json.createObjectNode().set("testMethodSnapshot", snap);
        var normalized = SpectrumTestMethods.normalizeMetadata(metadata);
        ((com.fasterxml.jackson.databind.node.ObjectNode)snap.path("conditions")).put("scans", 40);
        assertThat(normalized.path("testMethodSnapshot").path("conditions").path("scans").asInt()).isEqualTo(20);
        assertThat(SpectrumTestMethods.summary(normalized, null)).contains("采样方式：ATR", "纵轴类型：透过率（%T）", "扫描次数：20");
    }
    @Test void permitsMissingConditionsForAllSixMethodsAndDoesNotGuess() {
        assertThat(SpectrumTestMethods.schema()).hasSize(6);
        for (var method : SpectrumTestMethods.schema()) {
            var snap = json.createObjectNode().put("schemaVersion", 1).put("methodType", method.path("code").asText());
            var normalized = SpectrumTestMethods.snapshot(snap);
            assertThat(normalized.path("conditions").isEmpty()).isTrue();
            assertThat(SpectrumTestMethods.warnings(normalized).isEmpty()).isFalse();
        }
    }
    @Test void retainsLegacyTextAndSuspiciousUvWithoutCorrectingIt() throws Exception {
        assertThat(SpectrumTestMethods.summary(json.createObjectNode(), "历史原文")).isEqualTo("历史原文");
        var snap = SpectrumTestMethods.snapshot(json.readTree("""
          {"schemaVersion":1,"methodType":"UV","conditions":{"rangeMin":110,"rangeMax":500,"solvent":"PM"},"legacyText":"旧条件"}
          """));
        assertThat(snap.path("conditions").path("rangeMin").asInt()).isEqualTo(110);
        assertThat(SpectrumTestMethods.warnings(snap).toString()).contains("起始波长", "PM");
        assertThat(SpectrumTestMethods.summary(json.createObjectNode().set("testMethodSnapshot", snap), null)).contains("旧条件");
    }
    @Test void rejectsInvertedRangesNegativeThicknessAndMixedMethodFields() throws Exception {
        for (var body : new String[] {
            "{\"schemaVersion\":1,\"methodType\":\"UV\",\"conditions\":{\"rangeMin\":500,\"rangeMax\":110}}",
            "{\"schemaVersion\":1,\"methodType\":\"MECHANICAL\",\"conditions\":{\"thickness\":-1}}",
            "{\"schemaVersion\":1,\"methodType\":\"IR\",\"conditions\":{\"medium\":\"水\"}}",
            "{\"schemaVersion\":1,\"methodType\":\"IR\",\"conditions\":{\"scans\":1.5}}"
        }) {
            var node = json.readTree(body);
            assertThatThrownBy(() -> SpectrumTestMethods.snapshot(node)).isInstanceOf(ApiException.class);
        }
    }
    @Test void gcProgramRetainsZeroHoldAndPartialStagesWithoutInferringRate() throws Exception {
        var snap = SpectrumTestMethods.snapshot(json.readTree("""
          {"schemaVersion":1,"methodType":"GC","conditions":{"ovenProgram":[{"temperature":40,"hold":0},{"temperature":180}]}}
          """));
        assertThat(snap.path("conditions").path("ovenProgram").get(0).path("hold").asInt()).isZero();
        assertThat(snap.path("conditions").path("ovenProgram").get(1).has("rate")).isFalse();
        assertThat(SpectrumTestMethods.warnings(snap).toString()).contains("程序不完整");
    }
    @Test void specimenDimensionsSatisfyCrossSectionRequirementsAndConcentrationNeedsUnits() throws Exception {
        var snap = SpectrumTestMethods.snapshot(json.readTree("""
          {"schemaVersion":1,"methodType":"MECHANICAL","conditions":{"specimens":[{"name":"A","width":4,"thickness":2},{"name":"B","width":4.1,"thickness":2.1}]}}
          """));
        assertThat(SpectrumTestMethods.warnings(snap).toString()).doesNotContain("实测宽度", "实测厚度");
        var uv = SpectrumTestMethods.snapshot(json.readTree("""
          {"schemaVersion":1,"methodType":"UV","conditions":{"concentration":"2"}}
          """));
        assertThat(SpectrumTestMethods.warnings(uv).toString()).contains("单位");
    }
}
