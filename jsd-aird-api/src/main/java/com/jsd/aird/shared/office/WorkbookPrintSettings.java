package com.jsd.aird.shared.office;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;

/** Native print settings live in worksheet custom metadata, outside the editable cell grid. */
public final class WorkbookPrintSettings {
    private WorkbookPrintSettings(){}
    public static ObjectNode read(ObjectMapper json,XSSFSheet sheet,int lastRow,int lastColumn){
        var setup=sheet.getPrintSetup();var out=json.createObjectNode().put("paperSize",setup.getPaperSize()).put("landscape",setup.getLandscape()).put("fitToWidth",setup.getFitWidth()).put("fitToHeight",setup.getFitHeight()).put("fitToPage",sheet.getFitToPage()).put("lastRow",lastRow).put("lastColumn",lastColumn);
        for(var side:new int[]{Sheet.LeftMargin,Sheet.RightMargin,Sheet.TopMargin,Sheet.BottomMargin,Sheet.HeaderMargin,Sheet.FooterMargin})out.put("margin"+side,sheet.getMargin((short)side));
        var repeat=sheet.getRepeatingRows();if(repeat!=null)out.put("repeatFirstRow",repeat.getFirstRow()).put("repeatLastRow",repeat.getLastRow());
        repeat=sheet.getRepeatingColumns();if(repeat!=null)out.put("repeatFirstColumn",repeat.getFirstColumn()).put("repeatLastColumn",repeat.getLastColumn());
        out.put("headerLeft",sheet.getHeader().getLeft()).put("headerCenter",sheet.getHeader().getCenter()).put("headerRight",sheet.getHeader().getRight());
        out.put("footerLeft",sheet.getFooter().getLeft()).put("footerCenter",sheet.getFooter().getCenter()).put("footerRight",sheet.getFooter().getRight());
        return out;
    }
    public static void apply(JsonNode source,XSSFSheet sheet){
        var p=source.path("custom").path("print");if(!p.isObject())return;
        var setup=sheet.getPrintSetup();setup.setPaperSize((short)p.path("paperSize").asInt(9));setup.setLandscape(p.path("landscape").asBoolean(true));
        setup.setFitWidth((short)p.path("fitToWidth").asInt(1));setup.setFitHeight((short)p.path("fitToHeight").asInt(0));sheet.setFitToPage(p.path("fitToPage").asBoolean(true));
        for(var side:new int[]{Sheet.LeftMargin,Sheet.RightMargin,Sheet.TopMargin,Sheet.BottomMargin,Sheet.HeaderMargin,Sheet.FooterMargin})if(p.has("margin"+side))sheet.setMargin((short)side,p.path("margin"+side).asDouble());
        if(p.has("repeatFirstRow"))sheet.setRepeatingRows(new CellRangeAddress(p.path("repeatFirstRow").asInt(),p.path("repeatLastRow").asInt(),-1,-1));
        if(p.has("repeatFirstColumn"))sheet.setRepeatingColumns(new CellRangeAddress(-1,-1,p.path("repeatFirstColumn").asInt(),p.path("repeatLastColumn").asInt()));
        sheet.getHeader().setLeft(p.path("headerLeft").asText(""));sheet.getHeader().setCenter(p.path("headerCenter").asText(""));sheet.getHeader().setRight(p.path("headerRight").asText(""));
        sheet.getFooter().setLeft(p.path("footerLeft").asText(""));sheet.getFooter().setCenter(p.path("footerCenter").asText(""));sheet.getFooter().setRight(p.path("footerRight").asText(""));
        sheet.getWorkbook().setPrintArea(sheet.getWorkbook().getSheetIndex(sheet),0,p.path("lastColumn").asInt(7),0,p.path("lastRow").asInt());
        if(source.path("custom").path("labTemplate").path("version").asInt()==1) {
            sheet.setRepeatingColumns(new CellRangeAddress(-1,-1,0,1));
            if(p.path("lastColumn").asInt(7)>7) {
                sheet.setFitToPage(false);setup.setFitWidth((short)0);setup.setFitHeight((short)0);setup.setScale((short)100);
                for(int column=7;column<p.path("lastColumn").asInt();column+=6)sheet.setColumnBreak(column);
            }
        }
    }
}
