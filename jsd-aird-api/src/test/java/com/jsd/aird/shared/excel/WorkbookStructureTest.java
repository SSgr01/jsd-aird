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
        var snapshot=snapshot();snapshot.withObject("/sheets/s").putObject("cellData").putObject("2").putObject("3").put("v",0);WorkbookStructure.attach(snapshot,instance());
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
    @Test void removesOnlyPreviouslyProjectedPathsWhenAFieldIsDeleted(){
        var old=instance();var snapshot=snapshot();snapshot.withObject("/sheets/s").putObject("cellData").putObject("2").putObject("3").put("v",12);WorkbookStructure.attach(snapshot,old);
        var model=WorkbookStructure.project(json,snapshot,json.createObjectNode());
        model.withObject("/dynamicValues/unrelated").put("keep",true);
        var next=old.deepCopy();next.putArray("mapping");next.withObject("/model").putArray("fields");WorkbookStructure.attach(snapshot,next);
        var saved=WorkbookStructure.project(json,snapshot,model);
        assertThat(saved.at("/dynamicValues/measurements/concentration").isMissingNode()).isTrue();
        assertThat(saved.at("/dynamicValues/instanceFields")).isEmpty();
        assertThat(saved.at("/dynamicValues/unrelated/keep").booleanValue()).isTrue();
    }
    @Test void detectsPartialRecordGeometryWithoutDiscardingItsIdentity(){
        var source=instance();source.withArray("/records").get(0).withObject("").put("regionId","table").put("size",1);
        source.withArray("/mapping").addObject().put("bindingId","table").put("mappingKind","REPEAT_REGION").put("repeatAxis","ROW").put("recordHeight",2).put("recordStride",2)
            .withObject("/locator").put("sheetId","s").put("dataRange","C3:D3");
        var checked=WorkbookStructure.validate(json,snapshot(),source);
        assertThat(checked.path("records").get(0).path("id").asText()).isEqualTo("r");
        assertThat(checked.path("issues").findValuesAsText("code")).contains("PARTIAL_RECORD");
        checked.withArray("/records").get(0).withObject("").put("size",2);
        checked.withArray("/mapping").get(1).withObject("/locator").put("dataRange","C3:D4");
        assertThat(WorkbookStructure.validate(json,snapshot(),checked).path("issues").findValuesAsText("code")).doesNotContain("PARTIAL_RECORD");
    }
    @Test void excludesAnExistingMergedBusinessHeadingFromUnboundIssues(){
        var source=instance();source.withArray("/model/groups").addObject().put("id","g").put("name","涂层性能");
        source.withArray("/issues").addObject().put("code","UNBOUND_VALUE").put("sheetId","s").put("address","A3");
        var snapshot=snapshot();snapshot.withObject("/sheets/s").putObject("cellData").putObject("2").putObject("0").put("v","涂层性能");
        snapshot.withArray("/sheets/s/mergeData").addObject().put("startRow",2).put("endRow",5).put("startColumn",0).put("endColumn",0);
        assertThat(WorkbookStructure.validate(json,snapshot,source).path("issues")).isEmpty();
    }
    @Test void doesNotLetAnotherSheetsProjectionCoverAnUnboundValue(){
        var source=instance();source.withArray("/issues").addObject().put("code","UNBOUND_VALUE").put("sheetId","other").put("address","B5");
        source.withArray("/execution/listProjections").addObject().put("parentBindingId","f").put("labelRange","B4:B6").put("valueRange","C4:H6");
        var snapshot=snapshot();snapshot.withObject("/sheets/other").put("id","other").putObject("cellData").putObject("4").putObject("1").put("v","正常");
        var checked=WorkbookStructure.validate(json,snapshot,source);
        assertThat(checked.path("issues").findValuesAsText("code")).contains("UNBOUND_VALUE");
    }
}
