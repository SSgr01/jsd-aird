package com.jsd.aird.bootstrap.document;

import com.jsd.aird.tpl.infrastructure.DocxStructureParser;
import com.jsd.aird.tpl.infrastructure.XlsxStructureParser;
import com.jsd.aird.tpl.infrastructure.GenericTabularStructureParser;
import com.jsd.aird.tpl.infrastructure.WordOoxmlPatchService;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.file.*;

/** Composition-root CLI for pure document computation, with no application context or credentials. */
public final class OfficeProcessMain {
    private OfficeProcessMain(){ }
    public static void main(String[] args)throws Exception{
        try{execute(args);}catch(OutOfMemoryError exhausted){System.exit(75);}
    }
    private static void execute(String[] args)throws Exception{
        if(args.length!=4)throw new IllegalArgumentException("Invalid office invocation");
        var json=new ObjectMapper().findAndRegisterModules();var source=Path.of(args[1]);var argument=json.readTree(Path.of(args[2]).toFile());var output=Path.of(args[3]);
        switch(args[0]){
            case "word-observations"->{
                var result=json.createArrayNode();
                try(var input=Files.newInputStream(source);var document=new org.apache.poi.xwpf.usermodel.XWPFDocument(input)){
                    for(int i=0;i<document.getParagraphs().size();i++)observations(result,document.getParagraphs().get(i).getText(),"段落 "+(i+1));
                    for(int t=0;t<document.getTables().size();t++){var table=document.getTables().get(t);for(int r=0;r<table.getRows().size();r++){
                        var row=table.getRow(r);String location="表格 "+(t+1)+" 行 "+(r+1);
                        if(row.getTableCells().size()>=2){var label=row.getCell(0).getText().trim();var value=row.getCell(1).getText().trim();if(!label.isBlank()&&!value.isBlank())result.addObject().put("label",label).put("value",value).put("location",location);}
                        for(var cell:row.getTableCells())observations(result,cell.getText(),location);
                    }}
                }json.writeValue(output.toFile(),result);
            }
            case "inventory-cells"->{
                try(var input=Files.newInputStream(source);var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(input);var generator=json.getFactory().createGenerator(output.toFile(),com.fasterxml.jackson.core.JsonEncoding.UTF8)){
                    var sheet=workbook.getSheetAt(0);var formatter=new org.apache.poi.ss.usermodel.DataFormatter();
                    generator.writeStartArray();generator.writeStartArray();generator.writeEndArray();
                    for(int i=1;i<=sheet.getLastRowNum();i++){var row=sheet.getRow(i);generator.writeStartArray();for(int column=0;column<4;column++)generator.writeString(row==null?"":formatter.formatCellValue(row.getCell(column)));generator.writeEndArray();}
                    generator.writeEndArray();
                }
            }
            case "normalize"->json.writeValue(output.toFile(),new com.jsd.aird.tpl.application.TemplateFileNormalizationService(null,argument.path("executable").asText("soffice"),java.time.Duration.ofMillis(argument.path("timeout").asLong(90000))).normalize(argument.path("name").asText(),argument.path("contentType").asText(),Files.readAllBytes(source),argument.path("lenient").asBoolean()));
            case "xlsx"->{try(var input=Files.newInputStream(source)){json.writeValue(output.toFile(),new XlsxStructureParser(json).parse(input));}}
            case "tabular"->{try(var input=Files.newInputStream(source)){json.writeValue(output.toFile(),new GenericTabularStructureParser().parse(input,argument.asText()));}}
            case "knowledge-office"->{try(var input=Files.newInputStream(source)){json.writeValue(output.toFile(),new com.jsd.aird.kb.infrastructure.OfficeDocumentParser().parse(input,argument.asText()));}}
            case "docx-workbook"->json.writeValue(output.toFile(),new com.jsd.aird.mfg.upload.infrastructure.DocxTableWorkbookConverter(json).convert(Files.readAllBytes(source),argument.asText()));
            case "parse"->{try(var input=Files.newInputStream(source)){json.writeValue(output.toFile(),new DocxStructureParser(json).parse(input));}}
            case "patch"->Files.write(output,new WordOoxmlPatchService().apply(Files.readAllBytes(source),(ArrayNode)argument));
            case "snapshot"->Files.write(output,new WordOoxmlPatchService().applySnapshot(Files.readAllBytes(source),argument));
            default->throw new IllegalArgumentException("Unknown office computation");
        }
    }
    private static void observations(ArrayNode result,String text,String location){
        if(text==null)return;
        for(String line:text.split("\\R")){int colon=line.indexOf('：');if(colon<0)colon=line.indexOf(':');if(colon<=0 || colon>=line.length()-1)continue;
            String label=line.substring(0,colon).trim(),value=line.substring(colon+1).trim();if(!label.isBlank()&&!value.isBlank())result.addObject().put("label",label).put("value",value).put("location",location);}
    }
}
