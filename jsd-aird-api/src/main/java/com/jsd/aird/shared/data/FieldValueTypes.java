package com.jsd.aird.shared.data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.DateUtil;

/** Scalar types shared by workbook import and online experiment extraction. */
public final class FieldValueTypes {
    private FieldValueTypes() {}
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendPattern("H:m").optionalStart().appendPattern(":s").appendFraction(ChronoField.NANO_OF_SECOND,0,9,true).optionalEnd().toFormatter().withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern QUANTITY = Pattern.compile("^([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*(s|sec|秒|min|分钟|h|小时)?$",Pattern.CASE_INSENSITIVE);
    public static String tokens(String format) {
        return (format==null?"":format).replaceAll("\"[^\"]*\"|\\\\.|_.|\\*.","").replaceAll("(?i)\\[(?!(?:h+|m+|s+)\\])[^\\]]*\\]","");
    }
    public static boolean percentage(String format) { return tokens(format).contains("%"); }
    public static boolean clockFormat(String format) { return tokens(format).toLowerCase(Locale.ROOT).matches(".*[hs].*")&&!tokens(format).matches(".*[0#].*"); }
    public static boolean extended(String type) { return Set.of("integer","date","datetime","time","duration","boolean").contains(type.toLowerCase(Locale.ROOT)); }
    public static BigDecimal durationFactor(String unit) {
        return switch(unit==null?"":unit.strip().toLowerCase(Locale.ROOT)) {
            case "", "s","sec","秒" -> BigDecimal.ONE;
            case "min","分钟" -> BigDecimal.valueOf(60);
            case "h","小时" -> BigDecimal.valueOf(3600);
            default -> throw new IllegalArgumentException("时长单位应为 s、min 或 h");
        };
    }
    public static BigDecimal durationSeconds(String raw, boolean numeric, String format, String defaultUnit) {
        if(numeric&&clockFormat(format)) return new BigDecimal(raw).multiply(BigDecimal.valueOf(86400)).setScale(6,RoundingMode.HALF_UP).stripTrailingZeros();
        if(raw.matches("[+-]?\\d+:\\d{1,2}(?::\\d{1,2}(?:\\.\\d+)?)?")) {
            boolean negative=raw.startsWith("-");var parts=raw.replaceFirst("^[+-]","").split(":");
            var minutes=new BigDecimal(parts[1]);var seconds=parts.length==3?new BigDecimal(parts[2]):BigDecimal.ZERO;
            if(minutes.compareTo(BigDecimal.valueOf(60))>=0||seconds.compareTo(BigDecimal.valueOf(60))>=0)throw new IllegalArgumentException("时长分秒超出范围");
            var total=new BigDecimal(parts[0]).multiply(BigDecimal.valueOf(3600)).add(minutes.multiply(BigDecimal.valueOf(60))).add(seconds);
            return negative?total.negate():total;
        }
        var match=QUANTITY.matcher(raw.strip());if(!match.matches())throw new IllegalArgumentException("时长无法解析");
        return new BigDecimal(match.group(1)).multiply(durationFactor(match.group(2)==null?defaultUnit:match.group(2)));
    }
    public static String normalize(String type,String raw,boolean numeric,String format,String unit) {
        var kind=type.toLowerCase(Locale.ROOT);var text=raw.strip();
        return switch(kind) {
            case "integer" -> {
                var number=new BigDecimal(text);if(number.stripTrailingZeros().scale()>0)throw new IllegalArgumentException("整数不能包含小数");
                yield number.toBigIntegerExact().toString();
            }
            case "boolean" -> {
                if(numeric) {
                    var number=new BigDecimal(text);
                    if(number.compareTo(BigDecimal.ZERO)==0)yield "false";
                    if(number.compareTo(BigDecimal.ONE)==0)yield "true";
                    throw new IllegalArgumentException("是／否字段数值只能为 0 或 1");
                }
                yield switch(text.toLowerCase(Locale.ROOT)) {
                    case "true","是","1","y","yes" -> "true";
                    case "false","否","0","n","no" -> "false";
                    default -> throw new IllegalArgumentException("是／否字段无法解析");
                };
            }
            case "duration" -> durationSeconds(text,numeric,format,unit).stripTrailingZeros().toPlainString();
            case "date","datetime","time" -> {
                if(numeric) {
                    var serial=Double.parseDouble(text);
                    if(!Double.isFinite(serial)||serial<0||serial>=2958466||(!kind.equals("time")&&Math.floor(serial)==60))throw new IllegalArgumentException("Excel 日期数值无效");
                    var date=DateUtil.getLocalDateTime(serial,false,true);
                    yield temporal(kind,date);
                }
                if(kind.equals("date"))yield LocalDate.parse(text.replace('/','-').replace('.','-'),DATE).toString();
                if(kind.equals("time"))yield LocalTime.parse(text,TIME).format(DateTimeFormatter.ISO_LOCAL_TIME);
                var parts=text.replace('T',' ').split("\\s+",2);
                if(parts.length!=2)throw new IllegalArgumentException("日期时间缺少日期或时间");
                yield temporal(kind,LocalDate.parse(parts[0].replace('/','-').replace('.','-'),DATE).atTime(LocalTime.parse(parts[1],TIME)));
            }
            default -> text;
        };
    }
    private static String temporal(String kind,LocalDateTime date) {
        return switch(kind) {
            case "date" -> date.toLocalDate().toString();
            case "time" -> date.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME);
            default -> date.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace('T',' ');
        };
    }
}
