package com.jsd.aird.ops.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;

import org.junit.jupiter.api.Test;

class FileContentTypeResolverTest {

    @Test
    void resolvesPdfWhenBrowserReportsOctetStream() throws Exception {
        var file = Files.createTempFile("content-type-", ".bin");
        try {
            Files.write(file, "%PDF-1.7\n".getBytes());
            assertThat(FileContentTypeResolver.resolve(file, "report.pdf", "application/octet-stream"))
                    .isEqualTo("application/pdf");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void fallsBackToExtensionForOfficeFiles() throws Exception {
        var file = Files.createTempFile("content-type-", ".xlsx");
        try {
            Files.write(file, new byte[]{'P', 'K', 3, 4});
            assertThat(FileContentTypeResolver.resolve(file, "data.xlsx", "application/octet-stream"))
                    .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
