package com.jsd.aird.platform.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import javax.imageio.ImageIO;

/** Utilities for serving TIFF uploads through browser-friendly preview responses. */
public final class TiffPreview {

    private TiffPreview() {
    }

    public static boolean isTiff(String fileName, String contentType) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        var type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return type.equals("image/tiff") || name.endsWith(".tif") || name.endsWith(".tiff");
    }

    public static byte[] toPng(InputStream source) throws IOException {
        BufferedImage image = ImageIO.read(source);
        if (image == null) throw new IOException("TIFF 文件无法解码");
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG 预览生成失败");
            return output.toByteArray();
        }
    }

    public static String previewName(String fileName) {
        var value = fileName == null || fileName.isBlank() ? "file-preview.png" : fileName;
        return value.replaceFirst("(?i)\\.(tiff?)$", ".png");
    }
}
