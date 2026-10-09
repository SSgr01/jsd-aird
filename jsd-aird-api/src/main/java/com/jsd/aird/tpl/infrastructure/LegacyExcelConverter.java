package com.jsd.aird.tpl.infrastructure;

import java.util.LinkedHashMap;
import java.util.Map;

import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFPalette;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.PaneInformation;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDataValidationConstraint;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Converts legacy HSSF (BIFF8 .xls) workbooks into XSSF (OOXML) workbooks so the
 * shared XLSX physical-facts parser can process files saved by Excel 97-2003.
 * Business fidelity mirrors the XLSX path: values, formulas, merged regions,
 * row/column geometry, core cell styles (font, fill, borders, alignment,
 * number format), freeze panes, data validations and named ranges.
 */
final class LegacyExcelConverter {

    private LegacyExcelConverter() {
    }

    static XSSFWorkbook toXssf(HSSFWorkbook source) {
        // Only the source is owned here: the caller keeps the converted
        // workbook open for the remaining read-only parsing pass.
        try (source) {
            var target = new XSSFWorkbook();
            var palette = source.getCustomPalette();
            var fonts = new LinkedHashMap<Short, XSSFFont>();
            var styles = new LinkedHashMap<Short, CellStyle>();
            var evaluator = target.getCreationHelper().createFormulaEvaluator();

            for (int sheetIndex = 0; sheetIndex < source.getNumberOfSheets(); sheetIndex++) {
                var targetSheet = target.createSheet(source.getSheetName(sheetIndex));
                if (source.isSheetVeryHidden(sheetIndex)) target.setSheetVisibility(sheetIndex, SheetVisibility.VERY_HIDDEN);
                else if (source.isSheetHidden(sheetIndex)) target.setSheetVisibility(sheetIndex, SheetVisibility.HIDDEN);
                copySheet(source.getSheetAt(sheetIndex), targetSheet,
                        target, palette, fonts, styles, evaluator);
            }
            copyNames(source, target);
            return target;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "旧版 .xls 工作簿转换失败：" + exception.getMessage());
        }
    }

    private static void copySheet(HSSFSheet sourceSheet, XSSFSheet targetSheet, XSSFWorkbook target,
                                  HSSFPalette palette, Map<Short, XSSFFont> fonts,
                                  Map<Short, CellStyle> styles, FormulaEvaluator evaluator) {
        targetSheet.setDisplayGridlines(sourceSheet.isDisplayGridlines());
        targetSheet.setRightToLeft(sourceSheet.isRightToLeft());
        targetSheet.setDefaultColumnWidth(sourceSheet.getDefaultColumnWidth());
        targetSheet.setDefaultRowHeightInPoints(sourceSheet.getDefaultRowHeightInPoints());

        var lastColumn = copyRows(sourceSheet, targetSheet, target, palette, fonts, styles, evaluator);
        copyColumnGeometry(sourceSheet, targetSheet, lastColumn);
        for (int i = 0; i < sourceSheet.getNumMergedRegions(); i++) {
            targetSheet.addMergedRegion(sourceSheet.getMergedRegion(i).copy());
        }
        copyFreezePane(sourceSheet, targetSheet);
        copyValidations(sourceSheet, targetSheet);
    }

    private static int copyRows(HSSFSheet sourceSheet, XSSFSheet targetSheet, XSSFWorkbook target,
                                HSSFPalette palette, Map<Short, XSSFFont> fonts,
                                Map<Short, CellStyle> styles, FormulaEvaluator evaluator) {
        var lastColumn = 0;
        for (Row sourceRow : sourceSheet) {
            var targetRow = targetSheet.createRow(sourceRow.getRowNum());
            targetRow.setHeightInPoints(sourceRow.getHeightInPoints());
            targetRow.setZeroHeight(sourceRow.getZeroHeight());
            for (Cell sourceCell : sourceRow) {
                var targetCell = targetRow.createCell(sourceCell.getColumnIndex());
                copyCell(sourceCell, targetCell, target, palette, fonts, styles, evaluator);
                if (sourceCell.getColumnIndex() > lastColumn) lastColumn = sourceCell.getColumnIndex();
            }
        }
        return lastColumn;
    }

    private static void copyCell(Cell sourceCell, XSSFCell targetCell, XSSFWorkbook target,
                                 HSSFPalette palette, Map<Short, XSSFFont> fonts,
                                 Map<Short, CellStyle> styles, FormulaEvaluator evaluator) {
        targetCell.setCellStyle(resolveStyle(target, sourceCell.getCellStyle().getIndex(), sourceCell,
                palette, fonts, styles));
        switch (sourceCell.getCellType()) {
            case STRING -> targetCell.setCellValue(sourceCell.getStringCellValue());
            case NUMERIC -> targetCell.setCellValue(sourceCell.getNumericCellValue());
            case BOOLEAN -> targetCell.setCellValue(sourceCell.getBooleanCellValue());
            case ERROR -> targetCell.setCellErrorValue(sourceCell.getErrorCellValue());
            case FORMULA -> {
                targetCell.setCellFormula(sourceCell.getCellFormula());
                // XSSF keeps the cached result inside the file; without it the
                // parser would read a missing value for every formula cell.
                try {
                    evaluator.evaluateFormulaCell(targetCell);
                } catch (Exception ignored) {
                    // Unresolvable formulas (external links etc.) keep no cache.
                }
            }
            case BLANK, _NONE -> { }
        }
    }

    private static CellStyle resolveStyle(XSSFWorkbook target, short styleIndex, Cell sourceCell,
                                          HSSFPalette palette, Map<Short, XSSFFont> fonts,
                                          Map<Short, CellStyle> styles) {
        return styles.computeIfAbsent(styleIndex, key -> {
            var source = (HSSFCellStyle) sourceCell.getSheet().getWorkbook().getCellStyleAt(key);
            var converted = target.createCellStyle();
            converted.setFont(resolveFont(target, source.getFontIndexAsInt(), sourceCell, palette, fonts));
            converted.setAlignment(source.getAlignment());
            converted.setVerticalAlignment(source.getVerticalAlignment());
            converted.setWrapText(source.getWrapText());
            converted.setRotation(source.getRotation());
            converted.setHidden(source.getHidden());
            converted.setLocked(source.getLocked());
            var formatString = source.getDataFormatString();
            if (formatString != null) {
                converted.setDataFormat(target.createDataFormat().getFormat(formatString));
            }
            copyFill(source, (XSSFCellStyle) converted, palette);
            copyBorders(source, (XSSFCellStyle) converted, palette);
            return converted;
        });
    }

    private static XSSFFont resolveFont(XSSFWorkbook target, int fontIndex, Cell sourceCell,
                                        HSSFPalette palette, Map<Short, XSSFFont> fonts) {
        return fonts.computeIfAbsent((short) fontIndex, key -> {
            var source = (HSSFFont) sourceCell.getSheet().getWorkbook().getFontAt(key);
            var converted = target.createFont();
            if (source.getFontName() != null) converted.setFontName(source.getFontName());
            converted.setFontHeightInPoints(source.getFontHeightInPoints());
            converted.setBold(source.getBold());
            converted.setItalic(source.getItalic());
            converted.setUnderline(source.getUnderline());
            converted.setStrikeout(source.getStrikeout());
            converted.setCharSet(source.getCharSet());
            var color = paletteColor(palette, source.getColor());
            if (color != null) converted.setColor(color);
            return converted;
        });
    }

    private static void copyFill(HSSFCellStyle source, XSSFCellStyle target, HSSFPalette palette) {
        if (source.getFillPattern() == FillPatternType.NO_FILL) return;
        target.setFillPattern(source.getFillPattern());
        var foreground = paletteColor(palette, source.getFillForegroundColor());
        if (foreground != null) target.setFillForegroundColor(foreground);
        var background = paletteColor(palette, source.getFillBackgroundColor());
        if (background != null) target.setFillBackgroundColor(background);
    }

    private static void copyBorders(HSSFCellStyle source, XSSFCellStyle target, HSSFPalette palette) {
        target.setBorderTop(source.getBorderTop());
        target.setBorderRight(source.getBorderRight());
        target.setBorderBottom(source.getBorderBottom());
        target.setBorderLeft(source.getBorderLeft());
        setBorder(target::setTopBorderColor, palette, source.getTopBorderColor());
        setBorder(target::setRightBorderColor, palette, source.getRightBorderColor());
        setBorder(target::setBottomBorderColor, palette, source.getBottomBorderColor());
        setBorder(target::setLeftBorderColor, palette, source.getLeftBorderColor());
    }

    private static void setBorder(java.util.function.Consumer<XSSFColor> setter,
                                  HSSFPalette palette, short index) {
        var color = paletteColor(palette, index);
        if (color != null) setter.accept(color);
    }

    private static void copyColumnGeometry(HSSFSheet sourceSheet, XSSFSheet targetSheet, int lastColumn) {
        var upper = Math.min(Math.max(lastColumn, sourceSheet.getDefaultColumnWidth()), 256);
        for (int column = 0; column <= upper; column++) {
            if (sourceSheet.isColumnHidden(column)) targetSheet.setColumnHidden(column, true);
            var width = sourceSheet.getColumnWidth(column);
            if (width > 0) targetSheet.setColumnWidth(column, width);
        }
    }

    private static void copyFreezePane(HSSFSheet sourceSheet, XSSFSheet targetSheet) {
        PaneInformation pane = sourceSheet.getPaneInformation();
        if (pane == null || !pane.isFreezePane()) return;
        targetSheet.createFreezePane(pane.getVerticalSplitPosition(), pane.getHorizontalSplitPosition());
    }

    private static void copyValidations(HSSFSheet sourceSheet, XSSFSheet targetSheet) {
        DataValidationHelper helper = targetSheet.getDataValidationHelper();
        for (DataValidation validation : sourceSheet.getDataValidations()) {
            var constraint = validation.getValidationConstraint();
            var regions = validation.getRegions();
            if (constraint == null || regions == null) continue;
            var converted = new XSSFDataValidationConstraint(
                    constraint.getValidationType(), constraint.getOperator(),
                    constraint.getFormula1(), constraint.getFormula2());
            var target = helper.createValidation(converted, regions);
            if (target == null) continue;
            target.setEmptyCellAllowed(validation.getEmptyCellAllowed());
            target.setSuppressDropDownArrow(validation.getSuppressDropDownArrow());
            targetSheet.addValidationData(target);
        }
    }

    private static void copyNames(HSSFWorkbook source, XSSFWorkbook target) {
        for (var name : source.getAllNames()) {
            try {
                var converted = target.createName();
                converted.setNameName(name.getNameName());
                converted.setRefersToFormula(name.getRefersToFormula());
            } catch (Exception ignored) {
                // Complex or broken name formulas are not worth failing the upload.
            }
        }
    }

    private static XSSFColor paletteColor(HSSFPalette palette, short index) {
        if (index <= 0) return null;
        var color = palette.getColor(index);
        return color == null ? null : toXssfColor(color);
    }

    private static XSSFColor toXssfColor(HSSFColor color) {
        var triplet = color.getTriplet();
        if (triplet == null || triplet.length < 3) return null;
        return new XSSFColor(new byte[]{(byte) triplet[0], (byte) triplet[1], (byte) triplet[2]}, null);
    }
}
