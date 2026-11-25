package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.tpl.api.TemplateDataImportFacade.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class RepeatedSheetTemplateTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void checksEverySheetAndDoesNotUseAnotherSheetsLabelsToAcceptAMismatch() throws Exception {
        var contract = json.readTree("""
            {"templateUsage":"EXPERIMENT_DATA","components":[{"componentId":"basic","sheetId":"sheet-1",
             "range":"A1:B2","requiredComponent":true,"bindings":[{"bindingId":"title","fieldCode":"title",
             "labelPath":"实验题目","mappingKind":"SCALAR","locator":{"sheetId":"sheet-1","labelRange":"A1","valueRange":"B1"}}]}]}
            """);
        var locator = json.readTree("""
            {"componentId":"basic","sheetId":"sheet-1","labelRange":"A1","valueRange":"B1"}
            """);
        var binding = new ImportBinding("title","title","/title","SCALAR",null,null,1,1,1,
                json.createObjectNode(),locator,true,false,false,"INPUT","STRING",null,"实验题目",null,true);
        var field = new FieldDefinition("title","实验题目","STRING",null,true,false,List.of(),"/title");
        var definition = new DataTemplateDefinition(UUID.randomUUID(),UUID.randomUUID(),"EXP","实验",null,1,"XLSX",
                json.createObjectNode(),json.createArrayNode(),List.of(field),9,7,"hash",contract);
        var sheets = List.of(sheet("sheet-1","实验题目"),sheet("sheet-2","实验题目"),sheet("sheet-3","无关报表"));
        var execution = RepeatedSheetTemplate.expand(json,definition,List.of(binding),new ParsedTabularFile("XLSX","test",sheets));
        var resolved = new BindingLabelResolver(json).resolve(execution.bindings(),List.of(field),sheets);
        var result = new ImportCompatibilityEvaluator(json).evaluate(execution.definition().importContract(),sheets,resolved.fieldResolutions());
        assertThat(execution.bindings()).hasSize(3);
        assertThat(result.componentMatches()).hasSize(3);
        assertThat(result.componentMatches().get(0).path("sheetId").asText()).isEqualTo("sheet-1");
        assertThat(result.componentMatches().get(1).path("status").asText()).isEqualTo("COMPATIBLE");
        assertThat(result.componentMatches().get(2).path("status").asText()).isEqualTo("INCOMPATIBLE");
        assertThat(result.status()).isEqualTo("INCOMPATIBLE");
        assertThat(contract.path("components")).hasSize(1);
        assertThat(locator.path("sheetId").asText()).isEqualTo("sheet-1");
    }

    private ParsedSheet sheet(String id,String label) {
        return new ParsedSheet(id,id,0,1,2,1,2,List.of(1),1,2,List.of(List.of(label,"值"),List.of("","")));
    }
}
