package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.data.application.ExperimentImportAssembler;
import com.jsd.aird.data.application.port.ExperimentAssemblyRepository.*;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Optional local acceptance against the unmodified customer attachment; no records are written. */
class CustomerWorkbookProjectionTest {
    private final ObjectMapper json=new ObjectMapper();
    private final DataFormatter format=new DataFormatter(Locale.ROOT);
    @Test void theCustomerWorkbookProducesTwelveSamplesWithCompleteZeroContainingCompositions() throws Exception {
        var path=Path.of(System.getProperty("customer.workbook","C:/Users/Ssgr/Desktop/UV_PU应用配方性能模拟验证-V1.xlsx"));
        Assumptions.assumeTrue(Files.isRegularFile(path),"Customer attachment is only present on the local acceptance machine");
        var sourceRecords=new ArrayList<SourceRecord>();
        try(var workbook=WorkbookFactory.create(path.toFile())) {
            for(var sheet:workbook) {
                if(sheet.getSheetName().startsWith("_"))continue;
                int header=-1;
                for(var row:sheet)if(value(row,2).equals("实验编号")){header=row.getRowNum();break;}
                assertThat(header).isGreaterThanOrEqualTo(0);
                for(int col=3;col<sheet.getRow(header).getLastCellNum();col++) {
                    var sample=value(sheet.getRow(header),col);if(sample.isBlank())continue;
                    var data=json.createObjectNode();var anchors=new ArrayList<SourceAnchor>();
                    data.set("identity",field("BASIC","SOURCE_IDENTITY","实验编号",sample,"identity",""));
                    boolean formula=false;
                    for(var row:sheet) {
                        var section=value(row,0);if(section.equals("实验配方"))formula=true;
                        else if(!section.isBlank()&&row.getRowNum()>header)formula=false;
                        var label=value(row,2);if(label.isBlank()||label.equals("实验编号")||label.equals("合计"))continue;
                        var raw=value(row,col);if(raw.isBlank())continue;
                        var key="field-"+row.getRowNum();
                        String unit=formula?"%":label.contains("含水率")||label.contains("固含")?"%":label.contains("μm")?"μm":"";
                        var wrapper=field(formula?"FORMULA":"TEST",formula?"RATIO":"VALUE",label,raw,key,unit);
                        wrapper.put("numberFormat",row.getCell(col).getCellStyle().getDataFormatString());
                        wrapper.put("displayValue",raw);data.set(key,wrapper);
                        if(row.getCell(col).getCellType()==CellType.NUMERIC)wrapper.put("rawNumericValue",row.getCell(col).getNumericCellValue());
                        anchors.add(new SourceAnchor(key,key,"/"+key,label,sheet.getSheetName(),sheet.getSheetName(),row.getRowNum()+1,col+1,"",row.getCell(col).getAddress().formatAsString(),json.getNodeFactory().textNode(raw)));
                    }
                    sourceRecords.add(new SourceRecord(UUID.randomUUID(),sheet.getSheetName()+":COLUMN:"+col,sheet.getSheetName(),sheet.getSheetName(),header+1,data,anchors));
                }
            }
        }
        assertThat(sourceRecords).hasSize(12);
        var datasetId=UUID.randomUUID();
        var source=new SourceImport(UUID.randomUUID(),UUID.randomUUID(),path.getFileName().toString(),"file-hash",datasetId,9,"contract-hash","COMPLETED","EXPERIMENT",null,UUID.randomUUID(),Instant.EPOCH,sourceRecords);
        var assembled=new ExperimentImportAssembler(json).assemble(source,json.createObjectNode());
        var model=assembled.candidates().getFirst().editModel();
        var result=new HistorySampleProjection(json).dataset(datasetId,"应用配方",List.of(new OptimizationRepository.HistoryRecordRow(UUID.randomUUID(),UUID.randomUUID(),"REPORT","应用配方",model,Instant.EPOCH)),Map.of(),MaterialCatalogSnapshot.empty());
        assertThat(result.path("rowCount").asInt()).isEqualTo(12);
        assertThat(result.path("rows").findValuesAsText("sampleNo")).doesNotHaveDuplicates();
        var materials=new ArrayList<String>();
        for(var column:result.path("columns"))if("MATERIAL_RATIO".equals(column.path("semanticType").asText())) {
            assertThat(column.path("mappingStatus").asText()).isEqualTo("UNMAPPED");
            assertThat(column.has("materialId")).isFalse();
            materials.add(column.path("code").asText());
        }
        assertThat(materials).hasSize(9);
        for(var row:result.path("rows")) {
            double total=materials.stream().mapToDouble(code->row.path("values").path(code).asDouble()).sum();
            assertThat(total).isCloseTo(100,org.assertj.core.data.Offset.offset(0.001));
            assertThat(materials.stream().allMatch(code->row.path("values").path(code).isNumber())).isTrue();
        }
        assertThat(result.path("columns").findValuesAsText("name")).noneMatch(name->name.contains("Sheet")||name.contains("SYN-")||name.equals("合计"));
    }
    private String value(Row row,int column){return row==null?"":format.formatCellValue(row.getCell(column)).strip();}
    private ObjectNode field(String domain,String type,String name,String value,String key,String unit) {
        var field=json.createObjectNode().put("normalizedValue",value).put("rawValue",value).put("normalizedUnit",unit)
                .put("labelPath",name).put("itemLabel",name).put("itemSourceKey",key).put("bindingId",key).put("valuePath","/"+key);
        field.putObject("experimentField").put("domain",domain).put("field",type);return field;
    }
}
