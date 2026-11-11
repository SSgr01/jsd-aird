package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.lab.LabWorkbook;
import java.util.*;
import java.util.regex.Pattern;

/** Maps frozen candidate inputs into the existing customer form. It never copies results. */
public final class ResearchDraftWorkbook {
    private ResearchDraftWorkbook() { }

    public static ObjectNode build(ObjectMapper json, JsonNode baseline, JsonNode request, List<JsonNode> candidates) {
        var original=baseline.path("sourceWorkbookSnapshot").isObject()?baseline.path("sourceWorkbookSnapshot"):baseline.path("templateSnapshot");
        if(!LabWorkbook.supported(original))throw invalid("请使用已发布的标准实验模板");
        var workbook=(ObjectNode)LabWorkbook.blank(original);
        var sheet=LabWorkbook.sheet(workbook);var originalSheet=LabWorkbook.sheet(original);
        // Material identities are part of the proposed recipe, unlike measured quantities.
        for(var rows=sheet.path("cellData").fields();rows.hasNext();) {
            var row=rows.next();
            for(var cells=row.getValue().fields();cells.hasNext();) {
                var cell=cells.next();var object=(ObjectNode)cell.getValue();
                var role=object.path("custom").path("lab").path("role").asText();
                if(Set.of("VALUE","MATERIAL_QTY").contains(role))object.remove(List.of("v","p","f"));
                if(object.path("custom") instanceof ObjectNode meta)meta.remove("measurementProtocol");
                if("MATERIAL_CODE".equals(role)) {
                    var value=originalSheet.path("cellData").path(row.getKey()).path(cell.getKey()).path("v");
                    if(!value.isMissingNode())object.set("v",value.deepCopy());
                }
            }
        }
        var dataset=request.path("datasetSnapshot");var selected=new HashSet<String>();
        request.path("selectedRowIdsByDataset").forEach(ids->ids.forEach(id->selected.add(id.asText())));
        JsonNode anchor=null;
        for(var row:dataset.path("rows")) {
            if(!selected.isEmpty()&&!selected.contains(row.path("id").asText()))continue;
            boolean complete=row.path("values").path(request.path("target").asText()).isNumber();
            for(var x:request.path("features"))complete&=row.path("values").hasNonNull(x.path("code").asText());
            if(complete){anchor=row;break;}
        }
        if(anchor==null)throw invalid("本轮缺少可定位的完整样品");
        var first=LabWorkbook.definition(workbook).path("firstSampleColumn").asInt(2);
        var last=LabWorkbook.definition(workbook).path("lastSampleColumn").asInt(7);
        if(candidates.isEmpty()||first+candidates.size()-1>last)throw invalid("所选方案超过模板的样品列数，请分批生成");
        var features=new HashSet<String>();request.path("features").forEach(f->features.add(f.path("code").asText()));
        for(int index=0;index<candidates.size();index++) {
            var candidate=candidates.get(index);int column=first+index;
            // Carry the intended method and conditions without any previous measured value.
            for(var refs:anchor.path("sourceRefs"))for(var ref:refs) {
                var at=position(ref);if(at==null)continue;
                var protocol=originalSheet.path("cellData").path(""+at[0]).path(""+at[1]).path("custom").path("measurementProtocol");
                if(protocol.isObject())LabWorkbook.cell(sheet,at[0],column).withObject("/custom").set("measurementProtocol",protocol.deepCopy());
            }
            var values=new LinkedHashMap<String,JsonNode>();
            for(var it=anchor.path("values").fields();it.hasNext();) {
                var entry=it.next();if(entry.getKey().startsWith("process:")&&!features.contains(entry.getKey()))values.put(entry.getKey(),entry.getValue());
            }
            for(var code:features) {
                var value=candidate.path("inputs").path(code);
                if(value.isMissingNode()||value.isNull())throw invalid("推荐方案缺少所选输入");
                values.put(code,value);
            }
            var occupied=new HashMap<String,JsonNode>();
            for(var entry:values.entrySet()) {
                var refs=anchor.path("sourceRefs").path(entry.getKey());
                if(refs.isEmpty()&&features.contains(entry.getKey()))throw invalid("该输入没有原表位置，请补齐字段对应关系");
                for(var ref:refs) {
                    var point=position(ref);if(point==null)throw invalid("原表位置不明确，请重新选择样品");
                    if(ref.hasNonNull("sheetId")&&!ref.path("sheetId").asText().equals(sheet.path("id").asText()))throw invalid("输入位于不同工作表，请分别生成实验");
                    var cell=LabWorkbook.cell(sheet,point[0],column);
                    var prior=occupied.putIfAbsent(point[0]+":"+column,entry.getValue());
                    if(prior!=null&&!prior.equals(entry.getValue()))throw invalid("多个输入对应同一位置，请先确认字段含义");
                    write(workbook,cell,entry.getValue());
                }
            }
            for(var name:List.of("sample.number","sample.experimentNo")) {
                var at=LabWorkbook.findField(sheet,name);
                if(at!=null)write(workbook,LabWorkbook.cell(sheet,at[0],column),json.getNodeFactory().textNode(candidate.path("title").asText("方案 "+(index+1))));
            }
        }
        return workbook;
    }

    public static int[] position(JsonNode ref) {
        if(ref.path("row").asInt()>0&&ref.path("column").asInt()>0)return new int[]{ref.path("row").asInt()-1,ref.path("column").asInt()-1};
        var match=Pattern.compile("^\\$?([A-Za-z]+)\\$?(\\d+)$").matcher(ref.path("cellRange").asText(ref.path("cell").asText()));
        if(!match.matches())return null;
        int column=0;for(char c:match.group(1).toUpperCase(Locale.ROOT).toCharArray())column=column*26+c-'A'+1;
        return new int[]{Integer.parseInt(match.group(2))-1,column-1};
    }

    private static void write(ObjectNode workbook,ObjectNode cell,JsonNode value) {
        cell.remove(List.of("p","f"));
        var style=cell.path("s").isTextual()?workbook.path("styles").path(cell.path("s").asText()):cell.path("s");
        if(value.isNumber()&&style.path("n").path("pattern").asText().contains("%"))cell.put("v",value.decimalValue().movePointLeft(2));
        else cell.set("v",value.deepCopy());
        cell.put("t",value.isNumber()?2:1);
    }
    private static ApiException invalid(String message){return new ApiException(ApiErrorCode.VALIDATION_ERROR,message);}
}
