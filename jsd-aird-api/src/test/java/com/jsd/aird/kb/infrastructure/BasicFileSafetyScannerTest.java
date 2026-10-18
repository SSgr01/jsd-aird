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
    void rejectsCanonicalEicarSafetyTestFile() {
        var sample = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                .getBytes(StandardCharsets.US_ASCII);

        var result = scanner.scan(new ByteArrayInputStream(sample), "eicar-test.txt", "text/plain", sample.length);

        assertThat(result.status()).isEqualTo(BasicFileSafetyScanner.ScanResult.Status.REJECTED);
        assertThat(result.reason()).contains("EICAR");
    }

    @Test
    void rejectsEicarSignatureInsideOfficeArchiveEntry() {
        var sample = "prefix X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H* suffix";
        var archive = zipBytes("word/document.xml", sample);

        var result = scanner.scan(new ByteArrayInputStream(archive), "eicar-test.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", archive.length);

        assertThat(result.status()).isEqualTo(BasicFileSafetyScanner.ScanResult.Status.REJECTED);
        assertThat(result.reason()).contains("EICAR");
    }

    @Test
    void acceptsOle2ContainerHeaderForDownstreamDocumentValidation() {
        var result = scanner.scan(new ByteArrayInputStream(oleHeader()),
                "project-brief.doc", "application/msword", 512);

        assertThat(result.status()).isEqualTo(BasicFileSafetyScanner.ScanResult.Status.SAFE);
    }

    @Test
    void acceptsMp4VideoOriginal() {
        var result = scanner.scan(new ByteArrayInputStream(new byte[] {0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}),
                "inspection.mp4", "video/mp4", 16);

        assertThat(result.status()).isEqualTo(BasicFileSafetyScanner.ScanResult.Status.SAFE);
    }

    @Test
    void acceptsWebmVideoOriginal() {
        var result = scanner.scan(new ByteArrayInputStream(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2}),
                "inspection.webm", "video/webm", 6);

        assertThat(result.status()).isEqualTo(BasicFileSafetyScanner.ScanResult.Status.SAFE);
    }

    @Test
    void rejectsOfficeArchiveContainingMacroProject() {
        var result = scanner.scan(new ByteArrayInputStream(zipBytes("word/vbaProject.bin")),
                "payload.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 128);

        assertThat(result.status()).isEqualTo(BasicFileSafetyScanner.ScanResult.Status.REJECTED);
        assertThat(result.reason()).contains("宏");
    }

    private byte[] zipBytes(String entryName) {
        return zipBytes(entryName, "macro");
    }

    private byte[] zipBytes(String entryName, String content) {
        try (var output = new java.io.ByteArrayOutputStream();
             var zip = new java.util.zip.ZipOutputStream(output)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private byte[] oleHeader() {
        return new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    }
}
