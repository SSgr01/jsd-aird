package com.jsd.aird.kb.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Input, raster and transport budgets are independent; a large PDF need not become a large image. */
@ConfigurationProperties("app.research-images")
public record ResearchImageProperties(
        @DefaultValue("536870912") long maxPdfBytes,
        @DefaultValue("134217728") long maxImageBytes,
        @DefaultValue("8388608") int maxOutputBytes,
        @DefaultValue("100000000") long maxImagePixels,
        @DefaultValue("24000000") long maxRenderPixels,
        @DefaultValue("200") int previewDpi,
        @DefaultValue("2600") int previewLongEdge,
        @DefaultValue("400") int detailDpi,
        @DefaultValue("4800") int detailLongEdge) {
    public ResearchImageProperties {
        if (maxPdfBytes < 1 || maxImageBytes < 1 || maxOutputBytes < 1024
                || maxImagePixels < 1 || maxRenderPixels < 1 || maxRenderPixels > maxImagePixels
                || previewDpi < 1 || previewLongEdge < 1 || detailDpi < previewDpi || detailLongEdge < previewLongEdge)
            throw new IllegalArgumentException("Invalid research image budgets");
    }
    public static ResearchImageProperties defaults() {
        return new ResearchImageProperties(536870912L, 134217728L, 8388608, 100000000L, 24000000L, 200, 2600, 400, 4800);
    }
}
