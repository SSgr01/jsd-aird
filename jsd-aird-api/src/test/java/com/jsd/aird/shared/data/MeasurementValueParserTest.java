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
    @Test void keepsExplicitMassAndPartUnitsWithoutConvertingThemToPercentages() {
        assertThat(MeasurementValueParser.number("70份","份")).isEqualByComparingTo("70");
        assertThat(MeasurementValueParser.number("30g","g")).isEqualByComparingTo("30");
        assertThat(MeasurementValueParser.number("5mL","mL")).isEqualByComparingTo("5");
        assertThat(MeasurementValueParser.inferredUnit("70份")).isEqualTo("份");
        assertThat(MeasurementValueParser.number("70份","%")).isNull();
        assertThat(MeasurementValueParser.number("30g","%")).isNull();
        assertThat(MeasurementValueParser.number("5mL","%")).isNull();
    }
}
