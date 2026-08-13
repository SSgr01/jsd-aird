package com.jsd.aird.tpl.infrastructure;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;

import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.tpl.application.port.BlankWordDocumentFactory;
import org.docx4j.model.structure.PageSizePaper;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.PPr;
import org.docx4j.wml.RPr;
import org.docx4j.wml.Style;
import org.docx4j.wml.Styles;
import org.springframework.stereotype.Component;

@Component
public class Docx4jBlankWordDocumentFactory implements BlankWordDocumentFactory {

    private static final String NORMAL_STYLE_ID = "Normal";
    private static final String BODY_FONT = "宋体";

    private final ObjectFactory factory = new ObjectFactory();

    @Override
    public byte[] create(String documentTitle) {
        try (var output = new ByteArrayOutputStream()) {
            var word = WordprocessingMLPackage.createPackage(PageSizePaper.A4, false);
            var main = word.getMainDocumentPart();
            ensureAuthoringStyles(main.getStyleDefinitionsPart().getJaxbElement());
            if (main.getContent().isEmpty()) main.getContent().add(factory.createP());
            word.save(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED,
                    "空白 Word 原生工件生成失败");
        }
    }

    private void ensureAuthoringStyles(Styles styles) {
        if (styles == null) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED,
                    "空白 Word 缺少样式定义");
        }
        if (!hasStyle(styles, NORMAL_STYLE_ID)) {
            styles.getStyle().add(normalStyle());
        }
        var sizes = new int[]{44, 36, 32, 28, 24};
        for (var level = 1; level <= sizes.length; level++) {
            var styleId = "Heading" + level;
            if (!hasStyle(styles, styleId)) {
                styles.getStyle().add(headingStyle(level, sizes[level - 1]));
            }
        }
    }

    private boolean hasStyle(Styles styles, String styleId) {
        return styles.getStyle().stream().anyMatch(style -> styleId.equals(style.getStyleId()));
    }

    private Style normalStyle() {
        var style = paragraphStyle(NORMAL_STYLE_ID, "Normal", null, 28, false);
        style.setDefault(true);
        return style;
    }

    private Style headingStyle(int level, int halfPointSize) {
        var style = paragraphStyle("Heading" + level, "heading " + level,
                NORMAL_STYLE_ID, halfPointSize, true);
        var next = factory.createStyleNext();
        next.setVal(NORMAL_STYLE_ID);
        style.setNext(next);
        var priority = factory.createStyleUiPriority();
        priority.setVal(BigInteger.valueOf(9L + level));
        style.setUiPriority(priority);
        var quickFormat = factory.createBooleanDefaultTrue();
        quickFormat.setVal(true);
        style.setQFormat(quickFormat);
        var paragraph = new PPr();
        var outline = factory.createPPrBaseOutlineLvl();
        outline.setVal(BigInteger.valueOf(level - 1L));
        paragraph.setOutlineLvl(outline);
        style.setPPr(paragraph);
        return style;
    }

    private Style paragraphStyle(
            String styleId,
            String displayName,
            String basedOnId,
            int halfPointSize,
            boolean bold
    ) {
        var style = factory.createStyle();
        style.setType("paragraph");
        style.setStyleId(styleId);
        var name = factory.createStyleName();
        name.setVal(displayName);
        style.setName(name);
        if (basedOnId != null) {
            var basedOn = factory.createStyleBasedOn();
            basedOn.setVal(basedOnId);
            style.setBasedOn(basedOn);
        }
        var run = new RPr();
        var fonts = factory.createRFonts();
        fonts.setAscii(BODY_FONT);
        fonts.setHAnsi(BODY_FONT);
        fonts.setEastAsia(BODY_FONT);
        run.setRFonts(fonts);
        var size = factory.createHpsMeasure();
        size.setVal(BigInteger.valueOf(halfPointSize));
        run.setSz(size);
        run.setSzCs(size);
        if (bold) {
            var enabled = factory.createBooleanDefaultTrue();
            enabled.setVal(true);
            run.setB(enabled);
            run.setBCs(enabled);
        }
        style.setRPr(run);
        return style;
    }
}
