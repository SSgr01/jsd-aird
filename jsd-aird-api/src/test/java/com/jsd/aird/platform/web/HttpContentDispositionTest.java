package com.jsd.aird.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class HttpContentDispositionTest {

    @Test
    void encodesUnicodeFileNameWithoutPuttingUnicodeInTheHeader() {
        var header = HttpContentDisposition.inline("利用超疏水-亲水微图案.pdf.jpg");

        assertThat(StandardCharsets.ISO_8859_1.newEncoder().canEncode(header)).isTrue();
        assertThat(header).startsWith("inline; filename=\"download.jpg\"; filename*=UTF-8''");
        assertThat(header).contains("%E5%88%A9%E7%94%A8");
    }

    @Test
    void stripsLineBreaksBeforeBuildingTheHeader() {
        assertThat(HttpContentDisposition.inline("report\r\nInjected.pdf"))
                .doesNotContain("\r", "\n")
                .contains("filename=\"download.pdf\"");
    }
}
