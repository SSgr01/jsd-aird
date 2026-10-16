package com.jsd.aird.shared.data;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class MeasurementValueParserTest {
    @Test void parsesOnlyExactScalarsAndExplicitCompatibleUnits() {
        assertThat(MeasurementValueParser.number("200cps","mPa.s")).isEqualByComparingTo("200");
        assertThat(MeasurementValueParser.number("0.02mm","μm")).isEqualByComparingTo("20");
        assertThat(MeasurementValueParser.number("8.8-9.8","μm")).isNull();
        assertThat(MeasurementValueParser.number("≥1000次","次")).isNull();
        assertThat(MeasurementValueParser.number("228次\n少量浅丝痕","次")).isNull();
        assertThat(MeasurementValueParser.number("3H，OK；4H，轻微痕","")).isNull();
        assertThat(MeasurementValueParser.number("1e309","")).isNull();
        assertThat(MeasurementValueParser.missing("/（未测试）")).isTrue();
    }
}
