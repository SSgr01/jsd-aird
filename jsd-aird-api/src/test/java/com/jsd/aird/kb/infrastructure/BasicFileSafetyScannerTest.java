package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class BasicFileSafetyScannerTest {

    private final BasicFileSafetyScanner scanner = new BasicFileSafetyScanner();

    @Test
    void acceptsSupportedImagesForProviderProcessing() {
        var result = scanner.scan(new ByteArrayInputStream(new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3}),
                "sample.png", "image/png", 3);

        assertThat(result.status()).isEqualTo(BasicFileSafetyScanner.ScanResult.Status.SAFE);
    }

    @Test
    void rejectsUnsupportedExtension() {
        var result = scanner.scan(new ByteArrayInputStream(new byte[] {1}),
                "payload.exe", "application/octet-stream", 1);

        assertThat(result.status()).isEqualTo(BasicFileSafetyScanner.ScanResult.Status.REJECTED);
    }

    @Test
    void rejectsOfficeArchiveContainingMacroProject() {
        var result = scanner.scan(new ByteArrayInputStream(zipBytes("word/vbaProject.bin")),
                "payload.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 128);

        assertThat(result.status()).isEqualTo(BasicFileSafetyScanner.ScanResult.Status.REJECTED);
        assertThat(result.reason()).contains("宏");
    }

    private byte[] zipBytes(String entryName) {
        try (var output = new java.io.ByteArrayOutputStream();
             var zip = new java.util.zip.ZipOutputStream(output)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
            zip.write("macro".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
