package com.jsd.aird.kb.infrastructure;

import java.io.InputStream;
import java.io.BufferedInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipInputStream;

import com.jsd.aird.kb.domain.FileSafetyScanner;
import org.springframework.stereotype.Component;

@Component
public class BasicFileSafetyScanner implements FileSafetyScanner {

    private static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final Set<String> SUPPORTED = Set.of(
            "pdf", "doc", "docx", "xlsx", "xls", "pptx", "ppt", "csv", "txt", "md", "markdown",
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff",
            "wav", "mp3", "m4a", "aac", "flac", "ogg", "opus",
            "mp4", "m4v", "mov", "webm"
    );
    private static final int MAX_ZIP_ENTRIES = 20_000;
    private static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024;
    private static final byte[] EICAR_SIGNATURE =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final int[] EICAR_PREFIX = prefixTable(EICAR_SIGNATURE);

    @Override
    public Set<String> supportedExtensions() {
        return SUPPORTED;
    }

    @Override
    public long maxFileBytes() {
        return MAX_FILE_BYTES;
    }

    @Override
    public ScanResult scan(InputStream source, String fileName, String contentType, long size) {
        var extension = extension(fileName);
        if (!SUPPORTED.contains(extension)) {
            return new ScanResult(ScanResult.Status.REJECTED, "文件类型暂不支持: " + extension);
        }
        if (size < 0 || size > MAX_FILE_BYTES) {
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
        try {
            if (containsEicar(input)) {
                return new ScanResult(ScanResult.Status.REJECTED, "检测到 EICAR 安全测试签名");
            }
        } catch (Exception exception) {
            return new ScanResult(ScanResult.Status.UNAVAILABLE, "文件内容安全检查未完成");
        }
        return new ScanResult(ScanResult.Status.SAFE, "local-basic-scanner");
    }

    private boolean mimeMatches(String extension, String contentType) {
        if (contentType == null || contentType.isBlank() || "application/octet-stream".equalsIgnoreCase(contentType)) {
            return true;
        }
        var type = contentType.toLowerCase(Locale.ROOT);
        if (Set.of("txt", "csv", "md", "markdown").contains(extension)) {
            return type.startsWith("text/") || type.contains("csv") || ("csv".equals(extension) && type.contains("ms-excel"));
        }
        if ("pdf".equals(extension)) return "application/pdf".equals(type);
        if (Set.of("docx", "xlsx", "pptx").contains(extension)) return type.contains("openxmlformats") || type.contains("zip");
        if ("doc".equals(extension)) return type.contains("msword") || type.contains("ole");
        if ("xls".equals(extension)) return type.contains("ms-excel") || type.contains("ole");
        if ("ppt".equals(extension)) return type.contains("ms-powerpoint") || type.contains("ole");
        if (Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff").contains(extension)) return type.startsWith("image/");
        if (Set.of("mp4", "m4v", "mov", "webm").contains(extension)) return type.startsWith("video/");
        return type.startsWith("audio/") || ("m4a".equals(extension) && type.startsWith("application/"));
    }

    private boolean magicMatches(String extension, byte[] bytes) {
        if (Set.of("txt", "csv", "md", "markdown").contains(extension)) {
            for (byte value : bytes) if (value == 0) return false;
            return true;
        }
        if ("pdf".equals(extension)) return startsWith(bytes, new byte[]{'%', 'P', 'D', 'F', '-'});
        if (Set.of("docx", "xlsx", "pptx").contains(extension)) return zipMagic(bytes);
        if (Set.of("doc", "xls", "ppt").contains(extension)) {
            return startsWith(bytes, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
        }
        if ("png".equals(extension)) return startsWith(bytes, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        if (Set.of("jpg", "jpeg").contains(extension)) return startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        if ("gif".equals(extension)) return startsWith(bytes, new byte[]{'G', 'I', 'F', '8'});
        if ("webp".equals(extension)) return startsWith(bytes, new byte[]{'R', 'I', 'F', 'F'})
                && bytes.length >= 12 && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
        if ("bmp".equals(extension)) return startsWith(bytes, new byte[]{'B', 'M'});
        if (Set.of("tif", "tiff").contains(extension)) return startsWith(bytes, new byte[]{'I', 'I', 0x2A, 0})
                || startsWith(bytes, new byte[]{'M', 'M', 0, 0x2A});
        if ("wav".equals(extension)) return startsWith(bytes, new byte[]{'R', 'I', 'F', 'F'})
                && bytes.length >= 12 && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
        if ("mp3".equals(extension)) return startsWith(bytes, new byte[]{'I', 'D', '3'})
                || (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0);
        if ("aac".equals(extension)) return startsWith(bytes, new byte[]{'A', 'D', 'I', 'F'})
                || (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xF6) == 0xF0);
        if ("flac".equals(extension)) return startsWith(bytes, new byte[]{'f', 'L', 'a', 'C'});
        if (Set.of("ogg", "opus").contains(extension)) return startsWith(bytes, new byte[]{'O', 'g', 'g', 'S'});
        if (Set.of("m4a", "mp4", "m4v", "mov").contains(extension)) {
            return bytes.length >= 8 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p';
        }
        if ("webm".equals(extension)) return startsWith(bytes, new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});
        return false;
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
                var signatureMatcher = new SignatureMatcher();
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    expanded += read;
                    if (expanded > MAX_UNCOMPRESSED_BYTES) {
                        return new ScanResult(ScanResult.Status.REJECTED, "压缩包展开后超过安全限制");
                    }
                    if (signatureMatcher.matches(buffer, read)) {
                        return new ScanResult(ScanResult.Status.REJECTED, "检测到 EICAR 安全测试签名");
                    }
                }
            }
            return new ScanResult(ScanResult.Status.SAFE, "local-basic-scanner");
        } catch (Exception exception) {
            return new ScanResult(ScanResult.Status.REJECTED, "压缩文件结构校验失败");
        }
    }

    private boolean containsEicar(InputStream source) throws java.io.IOException {
        var matcher = new SignatureMatcher();
        var buffer = new byte[8192];
        int read;
        while ((read = source.read(buffer)) >= 0) {
            if (read > 0 && matcher.matches(buffer, read)) return true;
        }
        return false;
    }

    private static int[] prefixTable(byte[] pattern) {
        var prefix = new int[pattern.length];
        for (var index = 1; index < pattern.length; index++) {
            var matched = prefix[index - 1];
            while (matched > 0 && pattern[index] != pattern[matched]) matched = prefix[matched - 1];
            if (pattern[index] == pattern[matched]) matched++;
            prefix[index] = matched;
        }
        return prefix;
    }

    private static final class SignatureMatcher {
        private int matched;

        private boolean matches(byte[] value, int length) {
            for (var index = 0; index < length; index++) {
                while (matched > 0 && value[index] != EICAR_SIGNATURE[matched]) {
                    matched = EICAR_PREFIX[matched - 1];
                }
                if (value[index] == EICAR_SIGNATURE[matched]) matched++;
                if (matched == EICAR_SIGNATURE.length) return true;
            }
            return false;
        }
    }

    private String extension(String fileName) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        var dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
}
