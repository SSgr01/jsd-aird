package com.jsd.aird.ops.application.port;

/** Shared boundary for normalizing legacy Office files for imports and previews. */
public interface TemplateOfficeNormalizationFacade {

    NormalizedOfficeFile normalizeOfficeFile(String originalName, String contentType, byte[] source);

    /**
     * Lenient variant for browser preview: converted files are returned even
     * when high-fidelity validation reports layout differences.
     */
    NormalizedOfficeFile normalizeOfficeFileForPreview(String originalName, String contentType, byte[] source);

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
