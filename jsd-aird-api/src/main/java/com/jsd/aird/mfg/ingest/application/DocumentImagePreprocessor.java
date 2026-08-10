package com.jsd.aird.mfg.ingest.application;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

import org.springframework.stereotype.Component;

/** Conservative page cleanup: trims camera background, normalizes contrast and limits resolution. */
@Component
public class DocumentImagePreprocessor {

    private static final int MAX_SIDE = 2400;

    public ProcessedImage process(byte[] source) {
        return process(source, "application/octet-stream");
    }

    public ProcessedImage process(byte[] source, String originalContentType) {
        var fallbackContentType = originalContentType == null || originalContentType.isBlank()
                ? "application/octet-stream"
                : originalContentType;
        try {
            var decoded = ImageIO.read(new ByteArrayInputStream(source));
            if (decoded == null) return new ProcessedImage(fallbackContentType, source);
            var page = cropPage(decoded);
            var scale = Math.min(1d, (double) MAX_SIDE / Math.max(page.getWidth(), page.getHeight()));
            var width = Math.max(1, (int) Math.round(page.getWidth() * scale));
            var height = Math.max(1, (int) Math.round(page.getHeight() * scale));
            var normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var graphics = normalized.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(page, 0, 0, width, height, null);
            graphics.dispose();
            normalizeContrast(normalized);
            return new ProcessedImage("image/jpeg", jpeg(normalized));
        } catch (Exception ignored) {
            return new ProcessedImage(fallbackContentType, source);
        }
    }

    private BufferedImage cropPage(BufferedImage source) {
        var minX = source.getWidth();
        var minY = source.getHeight();
        var maxX = -1;
        var maxY = -1;
        var step = Math.max(1, Math.min(source.getWidth(), source.getHeight()) / 1000);
        for (var y = 0; y < source.getHeight(); y += step) {
            for (var x = 0; x < source.getWidth(); x += step) {
                var color = new Color(source.getRGB(x, y), true);
                var luminance = .2126d * color.getRed() + .7152d * color.getGreen() + .0722d * color.getBlue();
                if (luminance < 246d || color.getAlpha() < 245) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < minX || maxY < minY) return source;
        var margin = Math.max(8, Math.min(source.getWidth(), source.getHeight()) / 100);
        minX = Math.max(0, minX - margin);
        minY = Math.max(0, minY - margin);
        maxX = Math.min(source.getWidth() - 1, maxX + margin);
        maxY = Math.min(source.getHeight() - 1, maxY + margin);
        if ((maxX - minX) < source.getWidth() / 3 || (maxY - minY) < source.getHeight() / 3) return source;
        return source.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private void normalizeContrast(BufferedImage image) {
        for (var y = 0; y < image.getHeight(); y++) {
            for (var x = 0; x < image.getWidth(); x++) {
                var source = new Color(image.getRGB(x, y));
                image.setRGB(x, y, new Color(channel(source.getRed()), channel(source.getGreen()),
                        channel(source.getBlue())).getRGB());
            }
        }
    }

    private int channel(int value) {
        return Math.max(0, Math.min(255, (int) Math.round((value - 128) * 1.08d + 132d)));
    }

    private byte[] jpeg(BufferedImage image) throws Exception {
        var output = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            var parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(.94f);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    public record ProcessedImage(String contentType, byte[] content) {
    }
}
