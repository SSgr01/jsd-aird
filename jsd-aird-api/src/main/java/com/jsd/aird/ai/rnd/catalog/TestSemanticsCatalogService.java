package com.jsd.aird.ai.rnd.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.jsd.aird.ai.rnd.catalog.CatalogContracts.*;

@Service
public class TestSemanticsCatalogService {
    private static final String PARSER_VERSION = "customer-test-catalog-poi-v1";
    private static final Pattern UNIT = Pattern.compile("(mJ/cm[²2]|mW/cm[²2]|mN/m|μm|um|nm|MPa|N|Ω|V|%|℃|°|GU|cps|KU|s|g/ml)", Pattern.CASE_INSENSITIVE);
    private static final List<String> HEADER_NAMES = List.of("测试项目", "测试方法", "测试结果", "影响因素");

    private final CatalogRepository repository;
    private final FileStorageFacade files;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TestSemanticsCatalogService(CatalogRepository repository, FileStorageFacade files,
                                       JdbcTemplate jdbc, ObjectMapper mapper) {
        this.repository = repository;
        this.files = files;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public ImportSummary importFile(UUID fileId) {
        var actor = ActorContext.required();
        if (fileId == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "目录文件不能为空");
        try (var stored = files.open(actor.organizationId(), fileId)) {
            byte[] bytes = stored.stream().readAllBytes();
            String sha = sha256(bytes);
            var prior = repository.findImportByHash(sha);
            if (prior.isPresent()) return prior.get();
            var rows = parse(bytes, stored.originalName());
            int version = repository.nextVersion();
            UUID importId = UUID.randomUUID();
            ObjectNode summary = mapper.createObjectNode();
            summary.put("sourceScope", "GLOBAL");
            summary.put("exampleResultsAreHints", true);
            summary.put("atomicProposals", rows.size());
            var typeCounts = summary.putObject("suggestedValueTypes");
            rows.stream().collect(java.util.stream.Collectors.groupingBy(Row::valueType, LinkedHashMap::new,
                    java.util.stream.Collectors.counting())).forEach(typeCounts::put);
            repository.insertImport(importId, actor.organizationId(), fileId, sha, stored.originalName(), version,
                    PARSER_VERSION, rows.stream().mapToInt(Row::sourceRow).distinct().toArray().length, rows.size(), summary, actor.userId());
            for (Row row : rows) {
                UUID standardId = findStandardField(row.standardFieldCode());
                String standardCode = standardId == null ? null : row.standardFieldCode();
                var previous = repository.latestProposalForSemantic(row.semanticKey());
                String changeStatus = previous.isEmpty() ? "NEW" : proposalChanged(previous.get(), row) ? "CHANGED" : "UNCHANGED";
                UUID previousId = previous.map(ProposalView::id).orElse(null);
                ObjectNode diff = mapper.createObjectNode();
                if (previous.isPresent()) {
                    var old = previous.get();
                    if (!java.util.Objects.equals(old.rawTestMethod(), row.rawMethod())) diff.put("rawTestMethod", true);
                    if (!java.util.Objects.equals(old.rawExampleResult(), row.rawExample())) diff.put("rawExampleResult", true);
                    if (!java.util.Objects.equals(old.rawInfluenceFactors(), row.rawFactors())) diff.put("rawInfluenceFactors", true);
                    if (!java.util.Objects.equals(old.suggestedValueType(), row.valueType())) diff.put("suggestedValueType", true);
                    if (!java.util.Objects.equals(old.suggestedUnit(), row.unit())) diff.put("suggestedUnit", true);
                }
                repository.insertProposal(UUID.randomUUID(), importId, row.sourceRow(), row.ordinal(), row.semanticKey(),
                        row.rawItem(), row.rawMethod(), row.rawExample(), row.rawFactors(), row.atomicName(), row.suggestedCode(),
                        row.valueType(), row.unit(), standardId, standardCode, row.qualifiers(), array(row.aliases()),
                        array(row.factors()), row.evidence(), row.confidence(), changeStatus, previousId, diff);
            }
            files.activate(fileId);
            return repository.findImport(importId).orElseThrow();
        } catch (IOException e) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "测试项目清单无法读取", e);
        } catch (Exception e) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "测试项目清单无法读取", e);
        }
    }

    public List<ImportSummary> latestImports(int limit) {
        ActorContext.required();
        return repository.latestImports(limit);
    }

    public ProposalPage proposals(UUID importId, String keyword, String reviewStatus, int page, int size) {
        ActorContext.required();
        if (repository.findImport(importId).isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND, "目录导入批次不存在");
        return repository.proposals(importId, keyword, reviewStatus, Math.max(1, page), Math.max(1, Math.min(200, size)));
    }

    private List<Row> parse(byte[] bytes, String name) throws IOException {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0); var formatter = new DataFormatter();
            int header = findHeader(sheet, formatter);
            int itemCol = 1, methodCol = 2, resultCol = 3, factorCol = 4;
            if (header >= 0) {
                for (int c=0;c<sheet.getRow(header).getLastCellNum();c++) {
                    String value = normalize(cell(sheet, header, c, formatter));
                    if (value.contains("测试项目")) itemCol=c;
                    else if (value.contains("测试方法")) methodCol=c;
                    else if (value.contains("测试结果")) resultCol=c;
                    else if (value.contains("影响因素")) factorCol=c;
                }
            } else header=0;
            var rows = new ArrayList<Row>();
            for (int r=header+1;r<=sheet.getLastRowNum();r++) {
                String item=cell(sheet,r,itemCol,formatter), method=cell(sheet,r,methodCol,formatter), result=cell(sheet,r,resultCol,formatter), factors=cell(sheet,r,factorCol,formatter);
                if (item.isBlank() && method.isBlank() && result.isBlank() && factors.isBlank()) continue;
                rows.addAll(expand(r+1,item,method,result,factors));
            }
            return rows;
        } catch (RuntimeException e) {
            throw new IOException("Excel解析失败", e);
        }
    }

    private int findHeader(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter f) {
        for (int r=0;r<=Math.min(10,sheet.getLastRowNum());r++) {
            int found=0; var row=sheet.getRow(r); if(row==null)continue;
            for (var cell: row) { String value=normalize(f.formatCellValue(cell)); if(HEADER_NAMES.stream().anyMatch(value::contains))found++; }
            if(found>=2)return r;
        }
        return -1;
    }

    private List<Row> expand(int sourceRow, String item, String method, String example, String factors) {
        var names = new ArrayList<String>(); var qualifier = mapper.createObjectNode();
        String all = item + " " + method + " " + example;
        Matcher angle = Pattern.compile("(20|60|85)\\s*°").matcher(all);
        if (normalize(item).contains("光泽") && angle.find()) {
            angle.reset(); while(angle.find()) names.add(angle.group(1) + "°" + item.replace("光泽", "光泽"));
            qualifier.put("baseMetric", "光泽"); qualifier.put("qualifierType", "ANGLE");
        } else if (item.contains("粒径")) {
            names.addAll(List.of("D10粒径","D50粒径","D90粒径","平均粒径")); qualifier.put("baseMetric", "粒径");
        } else if (item.contains("色差")) {
            names.addAll(List.of("L值","b值","色差ΔE")); qualifier.put("baseMetric", "色差");
        } else if (item.contains("相对分子量")) {
            names.addAll(List.of("Mn分子量","Mw分子量","Mz分子量","Mz+1分子量","PDI分散系数")); qualifier.put("baseMetric", "相对分子量");
        } else if (item.equals("硬度") && method.contains("铅笔")) names.add("铅笔硬度");
        else if (item.equals("硬度") && method.contains("邵氏")) names.add("邵氏A硬度");
        else if (item.contains("高温高湿")) names.addAll(List.of("高温高湿颜色变化","高温高湿附着力变化"));
        else if (item.contains("芥末酱")) names.addAll(List.of("耐芥末酱发黄","耐芥末酱色差ΔE"));
        else if (item.contains("水煮") && (example.contains("水斑") || example.contains("发白"))) names.addAll(List.of("耐水煮水斑","耐水煮发白"));
        else if (item.contains("记号笔") || item.contains("双杰笔") || item.contains("荧光笔")) names.addAll(List.of(item + "收缩等级", item + "渗痕等级"));
        else names.add(item);
        if (item.contains("钢丝绒") || item.contains("耐磨")) {
            Matcher load=Pattern.compile("(500\\s*g|1000\\s*g|1\\s*kg|1kg)",Pattern.CASE_INSENSITIVE).matcher(all);
            if(load.find()) qualifier.put("load",load.group(1));
            Matcher contact=Pattern.compile("(1\\s*cm²|2\\s*cm²|1cm2|2cm2)",Pattern.CASE_INSENSITIVE).matcher(all);
            if(contact.find()) qualifier.put("contactArea",contact.group(1));
        }
        String unit=unit(item, method, example); List<String> aliases=List.of(item,method,names.get(0));
        List<String> factorList=splitFactors(factors);
        var result=new ArrayList<Row>(); int ordinal=0;
        for(String atomic:names){String type=suggestType(item,method,example,atomic);String standard=standardCode(atomic,item);int confidence="UNKNOWN".equals(type)?35:80;if(names.size()>1)confidence-=5;ObjectNode evidence=mapper.createObjectNode().put("sheetName","Sheet1").put("sourceRow",sourceRow).put("sourceFile", "customer-test-method-catalog").put("exampleResultsAreHints",true);
            ObjectNode q=qualifier.deepCopy(); if (atomic.contains("20°")||atomic.contains("60°")||atomic.contains("85°"))q.put("angle",atomic.substring(0,atomic.indexOf('°')+1));
            String semantic=normalize(standard==null?atomic:standard)+"|"+normalize(atomic)+"|"+normalize(method)+"|"+normalize(q.toString());
            result.add(new Row(sourceRow,ordinal++,semantic,item,method,example,factors,atomic,suggestCode(semantic),type,unit,standard,q,aliases,factorList,evidence,confidence));}
        return result;
    }

    private String standardCode(String atomic,String item){String value=atomic+item;if(value.contains("光泽"))return "FILM.PROPERTY.GLOSS";if(value.contains("硬度"))return "FILM.PROPERTY.HARDNESS";if(value.contains("附着"))return "FILM.PROPERTY.ADHESION";if(value.contains("表干"))return "FILM.PROPERTY.SURFACE_DRYNESS";if(value.contains("耐磨")||value.contains("钢丝绒")||value.contains("RCA"))return "FILM.PROPERTY.ABRASION_RESISTANCE";if(value.contains("膜厚"))return "FILM.PROPERTY.THICKNESS";if(value.contains("翘曲"))return "FILM.PROPERTY.WARPAGE";if(value.contains("拉伸"))return "FILM.PROPERTY.ELONGATION";if(value.contains("雾度"))return "FILM.PROPERTY.HAZE";if(value.contains("透过率"))return "FILM.PROPERTY.TRANSMITTANCE";if(value.contains("L值"))return "FILM.PROPERTY.COLOR_L";if(value.contains("色差")||value.contains("ΔE"))return "FILM.PROPERTY.COLOR_DIFFERENCE";return null;}
    private String suggestType(String item,String method,String example,String atomic){String v=normalize(item+method+example+atomic);if(item.contains("板面"))return "COMPOSITE";if(v.contains("不相溶")||v.contains("相溶")||v.contains("可溶")||v.contains("不溶")||v.contains("发白不发白")||v.contains("发黄不发黄"))return "BINARY";if(v.matches(".*(3b|2b|hb|[0-9]h|[0-9]b|5b|4b|无痕|微痕|浅痕|深痕|等级|裂纹|收缩|渗痕).*"))return "ORDINAL";if(v.contains("不结晶")||v.contains("不分层")||v.contains("<"))return "CENSORED";if(example.isBlank()&&method.isBlank())return "UNKNOWN";if(example.matches(".*[-+]?\\d+(?:\\.\\d+)?[^A-Za-z]*.*"))return "CONTINUOUS";return "UNKNOWN";}
    private String unit(String item, String method, String example){
        String value = item + " " + method + " " + example;
        // Gloss examples often contain an angle (20°/60°/85°) before the actual unit.
        // Preserve the semantic unit GU instead of accidentally treating the angle as a unit.
        if (value.contains("光泽") || value.toLowerCase(Locale.ROOT).contains("gloss")) return "GU";
        Matcher m=UNIT.matcher(value);
        return m.find()?m.group(1).replace("um","μm"):null;
    }
    private List<String> splitFactors(String value){if(value==null||value.isBlank())return List.of();return Arrays.stream(value.split("[、,，;；/]")).map(String::strip).filter(s->!s.isBlank()).distinct().toList();}
    private UUID findStandardField(String code){if(code==null||code.isBlank())return null;return jdbc.query("SELECT id FROM tpl.standard_field_dictionary WHERE dictionary_code=? AND status='ACTIVE' ORDER BY version_no DESC LIMIT 1",(rs,n)->rs.getObject(1,UUID.class),code).stream().findFirst().orElse(null);}
    private ArrayNode array(List<String> values){var a=mapper.createArrayNode();if(values!=null)values.forEach(a::add);return a;}
    private String cell(org.apache.poi.ss.usermodel.Sheet sheet,int row,int col,DataFormatter f){var r=sheet.getRow(row);if(r==null||r.getCell(col)==null)return "";return f.formatCellValue(r.getCell(col)).strip();}
    private String normalize(String value){return value==null?"":Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-/:：()（）]+","");}
    private String suggestCode(String name){return "CUSTOM.TEST."+sha256(normalize(name).getBytes(StandardCharsets.UTF_8)).substring(0,16).toUpperCase(Locale.ROOT);}
    private String sha256(byte[] bytes){try{var d=MessageDigest.getInstance("SHA-256");var out=d.digest(bytes);var s=new StringBuilder();for(byte b:out)s.append(String.format("%02x",b));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private boolean proposalChanged(ProposalView previous, Row row) {
        return !java.util.Objects.equals(previous.rawItemName(), row.rawItem())
                || !java.util.Objects.equals(previous.rawTestMethod(), row.rawMethod())
                || !java.util.Objects.equals(previous.rawExampleResult(), row.rawExample())
                || !java.util.Objects.equals(previous.rawInfluenceFactors(), row.rawFactors())
                || !java.util.Objects.equals(previous.atomicName(), row.atomicName())
                || !java.util.Objects.equals(previous.suggestedValueType(), row.valueType())
                || !java.util.Objects.equals(previous.suggestedUnit(), row.unit())
                || !java.util.Objects.equals(previous.standardFieldCode(), row.standardFieldCode());
    }

    private String json(JsonNode value){return value==null?"{}":value.toString();}

    private record Row(int sourceRow,int ordinal,String semanticKey,String rawItem,String rawMethod,String rawExample,String rawFactors,String atomicName,String suggestedCode,String valueType,String unit,String standardFieldCode,JsonNode qualifiers,List<String> aliases,List<String> factors,JsonNode evidence,int confidence) { }
}
