package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.SourceAnchorComparison;
import org.junit.jupiter.api.Test;

class SourceAnchorComparisonTest {
    @Test void browserNumericTokensPreserveCoordinatesWithoutAcceptingOtherChanges() throws Exception {
        var mapper = new ObjectMapper();
        var canonical = mapper.readTree("{\"page\":1,\"polygon\":[0.0,1.0],\"node\":\"fixed\"}");
        assertThat(SourceAnchorComparison.same(canonical, mapper.readTree("{\"node\":\"fixed\",\"polygon\":[0,1],\"page\":1.0}"))).isTrue();
        assertThat(SourceAnchorComparison.same(canonical, mapper.readTree("{\"page\":1,\"polygon\":[0,1.01],\"node\":\"fixed\"}"))).isFalse();
        assertThat(SourceAnchorComparison.same(canonical, mapper.readTree("{\"page\":\"1\",\"polygon\":[0,1],\"node\":\"fixed\"}"))).isFalse();
        assertThat(SourceAnchorComparison.same(canonical, mapper.readTree("{\"page\":1,\"polygon\":[0,1],\"node\":\"other\"}"))).isFalse();
    }
}
