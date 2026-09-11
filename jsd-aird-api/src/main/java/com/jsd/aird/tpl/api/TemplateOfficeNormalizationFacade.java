package com.jsd.aird.tpl.api;

/** Public template-center boundary for normalizing legacy Office binaries. */
public interface TemplateOfficeNormalizationFacade {

    NormalizedOfficeFile normalizeOfficeFile(String originalName, String contentType, byte[] source);

    record NormalizedOfficeFile(
            String originalName,
            String normalizedName,
            String normalizedContentType,
            String originalFormat,
            String normalizedFormat,
            byte[] content,
            String status,
            String message
    ) { }
}
