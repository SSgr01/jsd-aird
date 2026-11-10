package com.jsd.aird.kb.api;
import java.util.Locale;
public interface ImageDocumentParser extends com.jsd.aird.kb.api.DocumentParser {
    static boolean isImageSource(String fileName, String contentType) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        var type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("image/") || name.matches(".*\\.(png|jpe?g|gif|webp|bmp|tif|tiff)$");
    }
}
