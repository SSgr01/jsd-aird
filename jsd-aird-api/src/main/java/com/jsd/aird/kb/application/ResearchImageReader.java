package com.jsd.aird.kb.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade.SourceRef;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository.PublishedContentView;
import com.jsd.aird.ops.api.FileStorageFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Called only after ResearchKnowledgeService validates the canonical evidence source. */
@Service
@EnableConfigurationProperties(ResearchImageProperties.class)
public class ResearchImageReader {
    private final FileStorageFacade storage;
    private final KnowledgeResultAssetService assets;
    private final ObjectMapper json;
    private final ResearchImageProperties limits;

    public ResearchImageReader(FileStorageFacade storage, KnowledgeResultAssetService assets, ObjectMapper json,
            ResearchImageProperties limits) {
        this.storage = storage; this.assets = assets; this.json = json; this.limits = limits;
    }

    public JsonNode read(SourceRef source, PublishedContentView publication, String mode) {
        if (mode == null || !Set.of("auto", "page", "detail").contains(mode)) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var org = ActorContext.required().organizationId();
        var anchor = source.anchor();
        var original = anchor.path("original").isObject() ? anchor.path("original") : anchor;
        int page = page(original);
        if (page < 1) page = page(anchor);
        String asset = original.path("assetFileId").asText("");
        var keys = new LinkedHashSet<String>();
        anchor.path("sourceNodeKeys").forEach(key -> keys.add(key.asText()));
        // Resolve only source nodes of this canonical evidence, never arbitrary asset IDs or URLs.
        for (var node : publication.sourceNodes()) if (keys.contains(node.sourceNodeKey().toString())) {
            if (page < 1) page = page(node.sourceAnchor());
            if (asset.isBlank()) asset = node.sourceAnchor().path("assetFileId").asText("");
        }
        try {
            if ("auto".equals(mode) && !asset.isBlank()) {
                try (var file = assets.openForVersion(org, UUID.fromString(source.document_id()),
                        UUID.fromString(source.version_id()), UUID.fromString(asset))) {
                    if (!Set.of("image/png", "image/jpeg", "image/webp", "image/gif").contains(file.contentType()))
                        return page(source, publication, page, mode);
                    var path = spool(file.stream(), file.size(), limits.maxImageBytes(), "原图");
                    try { return figure(source, publication, page, path, file.contentType()); }
                    finally { Files.deleteIfExists(path); }
                }
            }
            return page(source, publication, page, mode);
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw new ApiException(ApiErrorCode.BAD_REQUEST, "原图读取失败，请回读文献或使用原文页面核对"); }
    }

    private JsonNode page(SourceRef source, PublishedContentView publication, int page, String mode) throws Exception {
        if (page < 1 || !"application/pdf".equals(publication.contentType()))
            throw new ApiException(ApiErrorCode.NOT_FOUND, "该证据没有可读取的原图或PDF页码，请定位图注或相邻原文后重试");
        var org = ActorContext.required().organizationId();
        try (var file = storage.open(org, publication.fileObjectId())) {
            var path = spool(file.stream(), file.size(), limits.maxPdfBytes(), "PDF");
            try (var pdf = Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
                if (page > pdf.getNumberOfPages()) throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "证据页码超出原文范围");
                var box = pdf.getPage(page - 1).getCropBox();
                double width = box.getWidth(), height = box.getHeight(), longest = Math.max(width, height);
                if (!Double.isFinite(width * height) || width <= 0 || height <= 0) throw new ApiException(ApiErrorCode.BAD_REQUEST);
                boolean detail = "detail".equals(mode);
                double scale = Math.min((detail ? limits.detailDpi() : limits.previewDpi()) / 72d,
                    Math.min((detail ? limits.detailLongEdge() : limits.previewLongEdge()) / longest,
                        Math.sqrt(limits.maxRenderPixels() / (width * height))));
                var renderer = new PDFRenderer(pdf); renderer.setSubsamplingAllowed(true);
                var image = renderer.renderImage(page - 1, (float) scale, ImageType.RGB);
                try { return encoded(source, image, "page", page, mode, (float) (scale * 72), image.getWidth(), image.getHeight()); }
                finally { image.flush(); }
            } finally { Files.deleteIfExists(path); }
        }
    }

    private JsonNode figure(SourceRef source, PublishedContentView publication, int page, Path path, String type) throws Exception {
        try (var input = ImageIO.createImageInputStream(path.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            // Some deployments lack a WebP ImageIO provider. Use the authorized PDF instead of returning unreadable bytes.
            if (!readers.hasNext()) return page(source, publication, page, "detail");
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > limits.maxImagePixels())
                    throw new ApiException(ApiErrorCode.BAD_REQUEST, "原图像素超过配置预算，请使用view=detail读取PDF高清页");
                double scale = Math.min(1d, Math.min((double) limits.detailLongEdge() / Math.max(width, height),
                    Math.sqrt((double) limits.maxRenderPixels() / ((long) width * height))));
                if (Files.size(path) <= limits.maxOutputBytes() && scale == 1d) {
                    var suffix = switch (type) { case "image/jpeg" -> ".jpg"; case "image/webp" -> ".webp"; case "image/gif" -> ".gif"; default -> ".png"; };
                    return result(source, Files.readAllBytes(path), type, suffix, "figure", page, "auto", width, height, width, height, null);
                }
                var param = reader.getDefaultReadParam();
                int sample = Math.max(1, (int) Math.floor(1 / scale));
                sample = Math.max(sample, (int) Math.ceil(Math.sqrt((double) width * height / limits.maxRenderPixels())));
                param.setSourceSubsampling(sample, sample, 0, 0);
                var image = reader.read(0, param);
                double remainingScale = Math.min(1d, Math.min(width * scale / image.getWidth(), height * scale / image.getHeight()));
                if (remainingScale < 1d) {
                    var fitted = resize(image, Math.max(1, (int) (image.getWidth() * remainingScale)), Math.max(1, (int) (image.getHeight() * remainingScale)));
                    image.flush(); image = fitted;
                }
                try { return encoded(source, image, "figure", page, "auto", null, width, height); }
                finally { image.flush(); }
            } finally { reader.dispose(); }
        }
    }

    /** Spill inputs and PDFBox scratch to disk; declared and actual lengths are both checked. */
    private Path spool(InputStream input, long declaredSize, long maximum, String description) throws Exception {
        if (declaredSize < 1 || declaredSize > maximum)
            throw new ApiException(ApiErrorCode.BAD_REQUEST, description + "超过配置的读取预算（" + maximum / 1024 / 1024 + "MB）");
        var path = Files.createTempFile("research-image-input-", ".bin");
        try {
            try (var output = Files.newOutputStream(path)) {
                var buffer = new byte[65536]; long count = 0; int size;
                while ((size = input.read(buffer)) != -1) {
                    count += size;
                    if (count > maximum) throw new ApiException(ApiErrorCode.BAD_REQUEST, description + "实际大小超过配置预算");
                    if (count > declaredSize) throw new ApiException(ApiErrorCode.VERSION_CONFLICT, description + "文件长度已变化");
                    output.write(buffer, 0, size);
                }
                if (count != declaredSize) throw new ApiException(ApiErrorCode.VERSION_CONFLICT, description + "文件长度已变化");
            }
            return path;
        } catch (Exception exception) { Files.deleteIfExists(path); throw exception; }
    }

    private JsonNode encoded(SourceRef source, BufferedImage original, String view, int page, String mode,
            Float dpi, int originalWidth, int originalHeight) throws Exception {
        var path = Files.createTempFile("research-image-output-", ".bin");
        BufferedImage image = original;
        try {
            for (int attempt = 0; attempt < 8; attempt++) {
                try (var output = Files.newOutputStream(path)) { ImageIO.write(image, "png", output); }
                if (Files.size(path) <= limits.maxOutputBytes())
                    return result(source, Files.readAllBytes(path), "image/png", ".png", view, page, mode,
                        image.getWidth(), image.getHeight(), originalWidth, originalHeight, dpi);
                // Try high-quality JPEG at the same resolution before reducing fine structure detail.
                var rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = rgb.createGraphics();
                try { graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight()); graphics.drawImage(image, 0, 0, null); }
                finally { graphics.dispose(); }
                var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                try (var fileOutput = Files.newOutputStream(path); var output = ImageIO.createImageOutputStream(fileOutput)) {
                    writer.setOutput(output); var parameters = writer.getDefaultWriteParam();
                    parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); parameters.setCompressionQuality(0.94f);
                    writer.write(null, new javax.imageio.IIOImage(rgb, null, null), parameters);
                } finally { writer.dispose(); rgb.flush(); }
                if (Files.size(path) <= limits.maxOutputBytes())
                    return result(source, Files.readAllBytes(path), "image/jpeg", ".jpg", view, page, mode,
                        image.getWidth(), image.getHeight(), originalWidth, originalHeight, dpi);
                int width = Math.max(1, (int) (image.getWidth() * 0.8)), height = Math.max(1, (int) (image.getHeight() * 0.8));
                var smaller = resize(image, width, height);
                if (image != original) image.flush(); image = smaller;
                if (dpi != null) dpi *= 0.8f;
            }
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "图片无法满足输出预算，请调整app.research-images.max-output-bytes");
        } finally { if (image != original) image.flush(); Files.deleteIfExists(path); }
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        var resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB); var graphics = resized.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        return resized;
    }

    private JsonNode result(SourceRef source, byte[] bytes, String type, String suffix, String view, int page,
            String mode, int width, int height, int originalWidth, int originalHeight, Float dpi) throws Exception {
        if (bytes.length == 0 || bytes.length > limits.maxOutputBytes()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "图片超过配置的输出预算");
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var result = json.createObjectNode(); result.set("source", json.valueToTree(source));
        result.putObject("image").put("view", view).put("page", page > 0 ? page : null).put("detail", mode)
            .put("width", width).put("height", height).put("original_width", originalWidth).put("original_height", originalHeight)
            .put("resized", width != originalWidth || height != originalHeight).put("lossy", "image/jpeg".equals(type))
            .put("dpi", dpi).put("output_byte_budget", limits.maxOutputBytes());
        result.putObject("business_file").put("name", "knowledge-" + sha.substring(0, 20) + suffix)
            .put("content_type", type).put("bytes", bytes.length).put("sha256", sha)
            .put("content_base64", java.util.Base64.getEncoder().encodeToString(bytes));
        return result;
    }

    static int page(JsonNode anchor) {
        for (var key : new String[]{"page", "page_no", "pageNo"}) if (anchor.path(key).canConvertToInt()) return anchor.path(key).asInt();
        return -1;
    }
}
