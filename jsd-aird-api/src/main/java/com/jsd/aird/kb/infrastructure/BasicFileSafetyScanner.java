package com.jsd.aird.kb.infrastructure;

import java.io.InputStream;
import java.io.BufferedInputStream;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipInputStream;

import com.jsd.aird.kb.domain.FileSafetyScanner;
import org.springframework.stereotype.Component;

@Component
public class BasicFileSafetyScanner implements FileSafetyScanner {

    private static final Set<String> SUPPORTED = Set.of(
            "pdf", "docx", "xlsx", "xls", "pptx", "ppt", "csv", "txt", "md",
            "png", "jpg", "jpeg", "gif", "wav", "mp3", "m4a"
    );
    private static final int MAX_ZIP_ENTRIES = 20_000;
    private static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024;

    @Override
    public ScanResult scan(InputStream source, String fileName, String contentType, long size) {
        var extension = extension(fileName);
        if (!SUPPORTED.contains(extension)) {
            return new ScanResult(ScanResult.Status.REJECTED, "文件类型暂不支持: " + extension);
        }
        if (size < 0 || size > 512L * 1024 * 1024) {
            return new ScanResult(ScanResult.Status.REJECTED, "文件超过 512MB 限制");
        }
        if (!mimeMatches(extension, contentType)) {
            return new ScanResult(ScanResult.Status.REJECTED, "文件 MIME 类型与扩展名不匹配");
        }
        var input = source.markSupported() ? source : new BufferedInputStream(source);
        try {
            input.mark(32);
            var magic = input.readNBytes(16);
            input.reset();
            if (!magicMatches(extension, magic)) {
                return new ScanResult(ScanResult.Status.REJECTED, "文件内容与扩展名不匹配");
            }
        } catch (Exception exception) {
            return new ScanResult(ScanResult.Status.REJECTED, "文件头校验失败");
        }
        if (Set.of("docx", "xlsx", "pptx", "xlsm", "pptm").contains(extension)) {
            return scanZip(input);
        }
        return new ScanResult(ScanResult.Status.SAFE, "local-basic-scanner");
    }

    private boolean mimeMatches(String extension, String contentType) {
        if (contentType == null || contentType.isBlank() || "application/octet-stream".equalsIgnoreCase(contentType)) {
            return true;
        }
        var type = contentType.toLowerCase(Locale.ROOT);
        if (Set.of("txt", "csv", "md").contains(extension)) {
            return type.startsWith("text/") || type.contains("csv") || ("csv".equals(extension) && type.contains("ms-excel"));
        }
        if ("pdf".equals(extension)) return "application/pdf".equals(type);
        if (Set.of("docx", "xlsx", "pptx").contains(extension)) return type.contains("openxmlformats") || type.contains("zip");
        if ("xls".equals(extension)) return type.contains("ms-excel") || type.contains("ole");
        if ("ppt".equals(extension)) return type.contains("ms-powerpoint") || type.contains("ole");
        if (Set.of("png", "jpg", "jpeg", "gif").contains(extension)) return type.startsWith("image/");
        return type.startsWith("audio/") || type.startsWith("application/");
    }

    private boolean magicMatches(String extension, byte[] bytes) {
        if (Set.of("txt", "csv", "md").contains(extension)) {
            for (byte value : bytes) if (value == 0) return false;
            return true;
        }
        if ("pdf".equals(extension)) return startsWith(bytes, new byte[]{'%', 'P', 'D', 'F', '-'});
        if (Set.of("docx", "xlsx", "pptx").contains(extension)) return zipMagic(bytes);
        if (Set.of("xls", "ppt").contains(extension)) {
            return startsWith(bytes, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
        }
        if ("png".equals(extension)) return startsWith(bytes, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        if (Set.of("jpg", "jpeg").contains(extension)) return startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        if ("gif".equals(extension)) return startsWith(bytes, new byte[]{'G', 'I', 'F', '8'});
        if ("wav".equals(extension)) return startsWith(bytes, new byte[]{'R', 'I', 'F', 'F'})
                && bytes.length >= 12 && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
        if ("mp3".equals(extension)) return startsWith(bytes, new byte[]{'I', 'D', '3'})
                || (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0);
        return bytes.length >= 8 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p';
    }

    private boolean zipMagic(byte[] bytes) {
        return startsWith(bytes, new byte[]{'P', 'K', 3, 4}) || startsWith(bytes, new byte[]{'P', 'K', 5, 6})
                || startsWith(bytes, new byte[]{'P', 'K', 7, 8});
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) if (value[index] != prefix[index]) return false;
        return true;
    }

    private ScanResult scanZip(InputStream source) {
        long expanded = 0;
        var entries = 0;
        try (var zip = new ZipInputStream(source)) {
            var buffer = new byte[8192];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries++;
                if (entries > MAX_ZIP_ENTRIES) return new ScanResult(ScanResult.Status.REJECTED, "压缩包条目过多");
                if (entry.getName().toLowerCase(Locale.ROOT).contains("vbaProject".toLowerCase(Locale.ROOT))) {
                    return new ScanResult(ScanResult.Status.REJECTED, "包含宏代码，禁止进入知识库");
                }
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    expanded += read;
                    if (expanded > MAX_UNCOMPRESSED_BYTES) {
                        return new ScanResult(ScanResult.Status.REJECTED, "压缩包展开后超过安全限制");
                    }
                }
            }
            return new ScanResult(ScanResult.Status.SAFE, "local-basic-scanner");
        } catch (Exception exception) {
            return new ScanResult(ScanResult.Status.REJECTED, "压缩文件结构校验失败");
        }
    }

    private String extension(String fileName) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        var dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
}
