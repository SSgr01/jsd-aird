package com.jsd.aird.ops.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.springframework.util.StringUtils;

/** Resolves an upload MIME type from content before trusting a browser header. */
public final class FileContentTypeResolver {

    private FileContentTypeResolver() { }

    public static String resolve(Path file, String originalName, String requestedType) throws IOException {
        var name = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        var requested = normalize(requestedType);
        var header = Files.readAllBytes(file);
        if (startsWith(header, "%PDF-".getBytes())) return "application/pdf";
        if (startsWith(header, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})) return "image/png";
        if (startsWith(header, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) return "image/jpeg";
        if (startsWith(header, "GIF8".getBytes())) return "image/gif";
        if (startsWith(header, "BM".getBytes())) return "image/bmp";
        if (startsWith(header, new byte[]{'I', 'I', '*', 0}) || startsWith(header, new byte[]{'M', 'M', 0, '*'})) {
            return name.endsWith(".tif") || name.endsWith(".tiff") ? "image/tiff" : requested;
        }
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (name.endsWith(".xls")) return "application/vnd.ms-excel";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".csv")) return "text/csv";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".tif") || name.endsWith(".tiff")) return "image/tiff";
        return StringUtils.hasText(requested) ? requested : "application/octet-stream";
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) return "";
        var type = value.strip().toLowerCase(Locale.ROOT);
        var separator = type.indexOf(';');
        return separator < 0 ? type : type.substring(0, separator).strip();
    }
}
