package com.jsd.aird.shared.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import com.jsd.aird.shared.data.FieldValueTypes;

/** Field-specific parsing never replaces the original observation. */
public final class LabValues {
    private LabValues() {}
    public static final List<String> ADHESION = List.of("5B","4B","3B","2B","B","0");
    public static final List<String> HARDNESS = List.of("9H","8H","7H","6H","5H","4H","3H","2H","H","F","HB","B","2B","3B","4B","5B","6B","7B","8B","9B");
    private static final Pattern SCALAR = Pattern.compile("^([<>≤≥]?)[ ]*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)[ ]*(.*?)$");
    public static String text(JsonNode raw) {
        return raw == null || raw.isNull() || raw.isMissingNode() ? "" : raw.asText("").strip();
    }
    public static String state(String raw, boolean material) {
        var s=Normalizer.normalize(raw == null ? "" : raw.strip(),Normalizer.Form.NFKC);
        if(s.isBlank())return material?"UNFILLED":"NOT_MEASURED";
        if(s.matches("(?i)未测(?:试)?|未检测|NOT[_ ]?MEASURED"))return "NOT_MEASURED";
        if(s.matches("(?i)/|不适用|N/?A|NOT[_ ]?APPLICABLE"))return material?"ABSENT":"NOT_APPLICABLE";
        return "RECORDED";
    }
    public static boolean recorded(String value) { return state(value,false).equals("RECORDED"); }
    public static ObjectNode parse(ObjectMapper json,JsonNode raw,JsonNode field,String pattern) {
        var out=json.createObjectNode();
        out.set("rawValue",raw==null?json.nullNode():raw.deepCopy());
        var text=text(raw); var material="ACTUAL_QTY".equals(field.path("semantic").asText());
        var status=state(text,material);
        out.put("valueStatus",status).put("unit",field.path("unit").asText(""));
        if(!status.equals("RECORDED")) {
            out.put("parseStatus","EMPTY"); out.putNull("value");
            if(status.equals("ABSENT")) {out.put("value",0);out.put("usageStatus","ABSENT");}
            return out;
        }
        var kind=field.path("resultKind").asText("TEXT");
        var type=field.path("valueType").asText("").toLowerCase(Locale.ROOT);
        if(FieldValueTypes.extended(type)&&!type.equals("integer")) {
            try {
                var value=FieldValueTypes.normalize(type,text,raw!=null&&raw.isNumber(),pattern,field.path("unit").asText(""));
                if(type.equals("boolean"))out.put("value",Boolean.parseBoolean(value));
                else if(type.equals("duration"))out.put("value",new BigDecimal(value)).put("rawUnit",field.path("unit").asText("s")).put("unit","s");
                else out.put("value",value);
                return out.put("parseStatus","EXACT");
            }catch(IllegalArgumentException|java.time.DateTimeException invalid){return out.put("value",text).put("parseStatus","CONFLICT");}
        }
        if(kind.equals("ADHESION")||kind.equals("HARDNESS")) {
            var grade=text.toUpperCase(Locale.ROOT);
            var grades=kind.equals("ADHESION")?ADHESION:HARDNESS;
            out.put("value",grade).put("parseStatus",grades.contains(grade)?"GRADE":"TEXT_ONLY");
            if(grades.contains(grade))out.put("gradeOrder",grades.indexOf(grade));
            return out;
        }
        var normalized=Normalizer.normalize(text,Normalizer.Form.NFKC).replace('µ','μ').strip();
        if(field.path("parser").asText().equals("CURVATURE")) {
            var direction=normalized.startsWith("反翘")?"REVERSE":normalized.startsWith("正翘")?"FORWARD":"";
            if(!direction.isBlank()) {out.put("direction",direction);normalized=normalized.substring(2).strip();}
        }
        var match=SCALAR.matcher(normalized);
        if(!kind.equals("TEXT")&&match.matches()) {
            var unit=match.group(3).strip();var expected=field.path("unit").asText("");
            var canonical=unit.isEmpty()?expected:canonicalUnit(unit);
            var allowed=unit.isEmpty()||canonicalUnit(expected).equals(canonical);
            for(var candidate:field.path("allowedUnits"))allowed|=canonicalUnit(candidate.asText()).equals(canonical);
            if(allowed) {
                var number=new BigDecimal(match.group(2));
                if(raw!=null&&raw.isNumber()&&FieldValueTypes.percentage(pattern)&&expected.equals("%"))number=number.movePointRight(2);
                if(canonical.equals("min")){number=number.multiply(BigDecimal.valueOf(60));canonical="s";}
                if(canonical.equals("h")){number=number.multiply(BigDecimal.valueOf(3600));canonical="s";}
                out.put("rawUnit",unit.isEmpty()?expected:unit).put("unit",canonical);
                out.put("value",number).put("parseStatus",match.group(1).isEmpty()?"EXACT":"LIMIT");
                if(!match.group(1).isEmpty())out.put("operator",match.group(1));
                if(material&&(!canonical.equals("g")||number.signum()<0||!match.group(1).isEmpty()))out.put("parseStatus","CONFLICT");
                if(type.equals("integer")&&number.stripTrailingZeros().scale()>0)out.put("parseStatus","CONFLICT");
                return out;
            }
            // A number followed by an observation/method remains a mixed text result.
            // Only an unambiguous unit token is a unit conflict.
            out.put("parseStatus",material||unit.matches("[A-Za-zμµ℃°%²³Ω·./]+")?"CONFLICT":normalized.matches(".*[~～—].*")?"RANGE":"TEXT_ONLY");
        } else out.put("parseStatus",kind.equals("TEXT")?"TEXT":normalized.matches(".*[~～—].*")?"RANGE":"TEXT_ONLY");
        out.put("value",text);
        if(material)out.put("parseStatus","CONFLICT");
        return out;
    }
    public static String canonicalUnit(String unit) {
        var u=Normalizer.normalize(unit,Normalizer.Form.NFKC).replace('µ','μ').replace(" ","").toLowerCase(Locale.ROOT);
        return switch(u) {
            case "cps","cp","mpa·s","mpa.s"->"cP";
            case "秒","s"->"s";case "分钟","min"->"min";case "小时","h"->"h";
            case "°c","℃"->"℃";case "ku"->"KU";case "kev"->"keV";case "mev"->"MeV";
            case "um","μm"->"μm";case "g"->"g";default->u;
        };
    }
}
