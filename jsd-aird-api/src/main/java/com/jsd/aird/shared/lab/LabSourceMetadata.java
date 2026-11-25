package com.jsd.aird.shared.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jsd.aird.shared.excel.WorkbookStructure;
import java.util.Set;

/** Source omissions are immutable provenance, never invented experiment metadata. */
public final class LabSourceMetadata {
    private LabSourceMetadata() {}

    public static ArrayNode gaps(ObjectMapper json,JsonNode snapshot) {
        var gaps=json.createArrayNode();
        for(var sheet:LabWorkbookSheets.businessSheets(snapshot)) {
            var fields=sheet.path("custom").path(LabWorkbook.DEFINITION).path("fields");
            fields.fields().forEachRemaining(entry->{
                var field=entry.getValue();var semantic=field.path("semantic").asText();
                if(!Set.of("TITLE","OWNER","EXPERIMENT_DATE").contains(semantic)||!field.path("scope").asText().equals("EXPERIMENT"))return;
                var at=LabWorkbook.findField(sheet,entry.getKey());
                if(at==null||!LabWorkbook.value(sheet,at[0],at[1]).isBlank())return;
                gaps.addObject().put("fieldCode",entry.getKey()).put("semantic",semantic).put("label",field.path("label").asText())
                        .put("sheetId",sheet.path("id").asText()).put("sheetName",sheet.path("name").asText())
                        .put("address",LabWorkbook.address(at[0],at[1])).put("originalValue","").put("status","NOT_RECORDED")
                        .put("reason","原始实验资料未记录；保留空白，不以整理日期、目录人员或推测内容代填");
            });
        }
        return gaps;
    }

    public static boolean sourceOmitted(JsonNode gaps,JsonNode locator) {
        var range=WorkbookStructure.range(WorkbookStructure.first(locator,"valueRange","address"));
        if(range==null||range[0]!=range[2]||range[1]!=range[3])return false;
        for(var gap:gaps)if(gap.path("status").asText().equals("NOT_RECORDED")&&gap.path("originalValue").asText().isBlank()
                &&Set.of("TITLE","OWNER","EXPERIMENT_DATE").contains(gap.path("semantic").asText())
                &&gap.path("sheetId").equals(locator.path("sheetId"))) {
            var at=WorkbookStructure.range(gap.path("address").asText());
            if(at!=null&&at[0]==range[0]&&at[1]==range[1])return true;
        }
        return false;
    }
}
