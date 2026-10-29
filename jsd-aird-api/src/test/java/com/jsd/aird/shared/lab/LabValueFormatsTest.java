package com.jsd.aird.shared.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class LabValueFormatsTest {
    private final ObjectMapper json=new ObjectMapper();
    static Stream<Arguments> formats() {
        double day=org.apache.poi.ss.usermodel.DateUtil.getExcelDate(java.time.LocalDate.of(2026,10,26));
        return Stream.of(
            Arguments.of("string","00123","","General","00123",""),
            Arguments.of("string","8 s / 2#杯","","General","8 s / 2#杯",""),
            Arguments.of("string","轻微发白","","General","轻微发白",""),
            Arguments.of("number",0,"g","General",0,"g"),
            Arguments.of("integer",0,"次","General",0,"次"),
            Arguments.of("integer","80次","次","General",80,"次"),
            Arguments.of("boolean",false,"","General",false,""),
            Arguments.of("boolean",0.0,"","General",false,""),
            Arguments.of("boolean",1.0,"","General",true,""),
            Arguments.of("boolean","否","","General",false,""),
            Arguments.of("boolean","YES","","General",true,""),
            Arguments.of("date",day,"","yyyy-mm-dd","2026-10-26",""),
            Arguments.of("date","2026/10/26","","General","2026-10-26",""),
            Arguments.of("datetime",day+0.5,"","yyyy-mm-dd hh:mm:ss","2026-10-26 12:00:00",""),
            Arguments.of("datetime","2026/10/26 12:00:00","","General","2026-10-26 12:00:00",""),
            Arguments.of("time",0.5,"","hh:mm:ss","12:00:00",""),
            Arguments.of("time","12:34:56","","General","12:34:56",""),
            Arguments.of("duration",1.5,"","[h]:mm:ss",129600,"s"),
            Arguments.of("duration","01:30:00","s","General",5400,"s"),
            Arguments.of("duration",30,"min","General",1800,"s"),
            Arguments.of("number",0.09,"%","0.00%",9,"%"),
            Arguments.of("number",9,"%","0.00\"%\"",9,"%"),
            Arguments.of("number",9,"%","0.00\\%",9,"%"));
    }
    @ParameterizedTest @MethodSource("formats")
    void parsesAllFieldTypesWithoutLosingMeaning(String type,Object raw,String unit,String format,Object expected,String normalizedUnit) {
        var field=json.createObjectNode().put("valueType",type).put("resultKind",java.util.Set.of("number","integer","duration").contains(type)?"NUMBER":"TEXT").put("unit",unit);
        var result=LabValues.parse(json,json.valueToTree(raw),field,format);
        assertThat(result.path("parseStatus").asText()).isNotEqualTo("CONFLICT");
        if(expected instanceof Number number)assertThat(result.path("value").decimalValue()).isEqualByComparingTo(number.toString());
        else assertThat(result.path("value")).isEqualTo(json.valueToTree(expected));
        assertThat(result.path("unit").asText()).isEqualTo(normalizedUnit);
        assertThat(result.path("rawValue")).isEqualTo(json.valueToTree(raw));
    }
    @Test void invalidTypedValuesRemainVisibleAndCannotBecomeExactMeasurements() {
        for(var input:java.util.List.of(new String[]{"integer","2.5"},new String[]{"date","2026-02-30"},new String[]{"datetime","2026-10-26 25:00:00"},new String[]{"time","25:61:00"},new String[]{"boolean","不确定"},new String[]{"duration","1:70:00"})) {
            var field=json.createObjectNode().put("valueType",input[0]).put("resultKind",input[0].equals("integer")?"NUMBER":"TEXT").put("unit","");
            var result=LabValues.parse(json,json.getNodeFactory().textNode(input[1]),field,"General");
            assertThat(result.path("parseStatus").asText()).as(input[0]).isEqualTo("CONFLICT");
            assertThat(result.path("rawValue").asText()).isEqualTo(input[1]);
        }
    }
}
