package com.jsd.aird.kb.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.OcrMode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Routes standalone raster images through MinerU by first wrapping them as a one-page PDF. */
@Component
public final class MineruImageDocumentParser implements DocumentParser {
    private final DocumentParser pdfParser;

    public MineruImageDocumentParser(@Qualifier("mineruDocumentParser") DocumentParser pdfParser) {
        this.pdfParser = pdfParser;
    }

    @Override
    public boolean supports(String fileName, String contentType) {
        return isImageSource(fileName, contentType);
    }

    public static boolean isImageSource(String fileName, String contentType) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        var type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("image/") || name.matches(".*\\.(png|jpe?g|gif|webp|bmp|tif|tiff)$");
    }

    @Override
    public boolean isConfigured() {
        return pdfParser instanceof MineruDocumentParser mineru
                ? mineru.isPreciselyConfigured() : pdfParser.isConfigured();
    }

    @Override
    public String unavailableReason() {
        return pdfParser instanceof MineruDocumentParser mineru
                ? mineru.preciseUnavailableReason() : pdfParser.unavailableReason();
    }

    @Override
    public ParsedDocument parse(InputStream source, String fileName) {
        return parse(source, fileName, null);
    }

    @Override
    public ParsedDocument parse(InputStream source, String fileName, ParseContext context) {
        try {
            var imageBytes = source.readAllBytes();
            var pdfBytes = MineruImagePdfConverter.convert(imageBytes);
            var pdfContext = context == null
                    ? new ParseContext(null, null, null, "application/pdf", pdfBytes.length, OcrMode.ON, false)
                    : new ParseContext(context.organizationId(), context.actorId(), context.sourceFileId(),
                            "application/pdf", pdfBytes.length, OcrMode.ON, false,
                            context.documentId(), context.documentVersionId());
            var parsed = pdfParser.parse(new ByteArrayInputStream(pdfBytes), pdfName(fileName), pdfContext);
            var metadata = new LinkedHashMap<>(parsed.metadata());
            metadata.put("sourceFormat", "IMAGE");
            metadata.put("imagePdfConversion", "MINERU_SINGLE_PAGE_PDF");
            metadata.put("sourceFileName", fileName == null ? "" : fileName);
            return new ParsedDocument(parsed.blocks(), parsed.parserVersion(), parsed.providerTaskId(),
                    metadata, parsed.sourceTables());
        } catch (IOException exception) {
            throw new IllegalArgumentException("图片无法转换为 MinerU 可识别的 PDF：" + exception.getMessage(), exception);
        }
    }

    private String pdfName(String fileName) {
        var name = fileName == null || fileName.isBlank() ? "image" : fileName;
        return name.replaceFirst("(?i)\\.[^.]+$", "") + ".pdf";
    }
}
