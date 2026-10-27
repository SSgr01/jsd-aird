package com.jsd.aird.shared.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkbookStructureTest {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode snapshot(){var out=json.createObjectNode();out.withObject("/sheets/s").put("id","s").put("name","明细");return out;}
    ObjectNode instance(){
        var out=json.createObjectNode().put("version",2);out.withArray("/model/fields").addObject().put("id","f").put("bindingId","f").put("fieldCode","f").put("name","浓度").put("reviewStatus","CONFIRMED");
        out.putArray("records").addObject().put("id","r").put("regionId","table").put("sheetId","s").put("axis","ROW").put("index",3).put("size",1);out.putArray("items");out.putArray("issues");
        var binding=out.putArray("mapping").addObject().put("bindingId","f").put("fieldCode","f").put("mappingKind","SCALAR").put("dataPath","/measurements/concentration").put("bindingStatus","VALID");binding.withObject("/locator").put("sheetId","s").put("address","D3");return out;
    }
    @Test void projectsOrdinaryFieldsIncludingZeroWithoutAnExperimentTemplate(){
        var snapshot=snapshot();snapshot.withObject("/sheets/s/cellData/2/3").put("v",0);WorkbookStructure.attach(snapshot,instance());
        var model=WorkbookStructure.project(json,snapshot,json.createObjectNode());
        assertThat(model.at("/dynamicValues/measurements/concentration").intValue()).isZero();
        assertThat(model.at("/templateInstance/records/0/id").asText()).isEqualTo("r");
    }
    @Test void retainsPendingFieldsAsDraftIssues(){
        var source=instance();source.withArray("/model/fields").get(0).withObject("").put("reviewStatus","NEEDS_CONFIRMATION");
        assertThat(WorkbookStructure.validate(json,snapshot(),source).path("issues").get(0).path("code").asText()).isEqualTo("FIELD_UNCONFIRMED");
    }
    @Test void ordinaryFieldsDoNotRequireExperimentSemantics(){
        var source=instance();((ObjectNode)source.path("model").path("fields").get(0)).put("experimentSemanticStatus","NEEDS_REVIEW");
        source.withObject("/execution").put("templateUsage","GENERAL_DATA");
        assertThat(WorkbookStructure.validate(json,snapshot(),source).path("issues")).isEmpty();
        source.withObject("/execution").put("templateUsage","EXPERIMENT_DATA");
        assertThat(WorkbookStructure.validate(json,snapshot(),source).path("issues")).hasSize(1);
    }
    @Test void rejectsDuplicateRecordIdentitiesAndInvalidCoordinates(){
        var source=instance();source.withArray("/records").add(source.path("records").get(0).deepCopy());
        assertThatThrownBy(()->WorkbookStructure.validate(json,snapshot(),source)).isInstanceOf(ApiException.class);
        var bad=instance();bad.withArray("/mapping").get(0).withObject("/locator").put("address","XFE1");
        assertThatThrownBy(()->WorkbookStructure.validate(json,snapshot(),bad)).isInstanceOf(ApiException.class);
    }
    @Test void blocksBindingsToAnotherSheet(){
        var source=instance();source.withArray("/mapping").get(0).withObject("/locator").put("sheetId","another");
        assertThatThrownBy(()->WorkbookStructure.validate(json,snapshot(),source)).isInstanceOf(ApiException.class);
    }
}
