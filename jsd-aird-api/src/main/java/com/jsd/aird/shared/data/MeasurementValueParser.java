package com.jsd.aird.shared.data;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** Strict scalar parsing. Ranges, limits and observations are not exact measurements. */
public final class MeasurementValueParser {
    private MeasurementValueParser() {}
    private static final String NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?";
    private static final Pattern SCALAR = Pattern.compile("^(" + NUMBER + ")\\s*(%|℃|°C|°|cm|mm|[µμu]m|nm|mJ/cm[²2]|mW/cm[²2]|J/cm[²2]|W/cm[²2]|mN/m|MPa|mPa[·.]s|cps|KU|g/m[lL]|g|kg|mg|m[lL]|L|phr|份|质量份|重量份|体积份|%RH|次|cycles|s|秒|N|V|Ω|B)?$", Pattern.CASE_INSENSITIVE);
    public static String unit(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).strip()
                .replace("℃", "°C").replace("µ", "μ").replace("um", "μm")
                .replace("cm2", "cm²").replace("mJ/cm²", "mJ/cm²");
    }
    public static boolean missing(String value) {
        var text = value == null ? "" : value.strip();
        return text.isEmpty() || text.matches("(?i)(?:—|-|/|N/?A|NULL|未测(?:试)?|/?[（(]?未测试[）)]?)");
    }
    public static BigDecimal number(String text, String expectedUnit) {
        if (text == null || missing(text)) return null;
        var matcher = SCALAR.matcher(Normalizer.normalize(text.strip(), Normalizer.Form.NFKC));
        if (!matcher.matches()) return null;
        var suffix = matcher.group(2);
        var factor=suffix==null||expectedUnit.isBlank()?BigDecimal.ONE:factor(suffix,expectedUnit);
        if(factor==null)return null;
        try {
            var value = new BigDecimal(matcher.group(1)).multiply(factor);
            return Double.isFinite(value.doubleValue()) ? value : null;
        } catch (NumberFormatException error) { return null; }
    }
    public static String inferredUnit(String text) {
        var matcher = SCALAR.matcher(Normalizer.normalize(text == null ? "" : text.strip(), Normalizer.Form.NFKC));
        return matcher.matches() && matcher.group(2) != null ? unit(matcher.group(2)) : "";
    }
    private static BigDecimal factor(String left, String right) {
        var a = unit(left).replace(" ", "").toLowerCase();
        var b = unit(right).replace(" ", "").toLowerCase();
        if(a.equals(b)||(a.equals("次")&&b.equals("cycles"))||(a.equals("秒")&&b.equals("s")))return BigDecimal.ONE;
        var viscosity=java.util.Set.of("cps","cp","mpa.s","mpa·s");
        if(viscosity.contains(a)&&viscosity.contains(b))return BigDecimal.ONE;
        return switch(a+"->"+b) {
            case "cm->mm", "mm->cm" -> new BigDecimal(a.equals("cm")?"10":"0.1");
            case "mm->μm" -> new BigDecimal("1000");
            case "μm->mm" -> new BigDecimal("0.001");
            default -> null;
        };
    }
}
