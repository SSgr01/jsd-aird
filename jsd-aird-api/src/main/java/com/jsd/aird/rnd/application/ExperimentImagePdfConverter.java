package com.jsd.aird.rnd.application;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

/** Converts an experiment image to a one-page PDF accepted by the shared MinerU parser. */
public final class ExperimentImagePdfConverter {
    private ExperimentImagePdfConverter() { }

    public static byte[] convert(byte[] source) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) throw new IOException("无法读取实验图片");
        image = orient(image, exifOrientation(source));
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var scale = Math.min(72f / 150f,
                    14400f / Math.max(image.getWidth(), image.getHeight()));
            var width = image.getWidth() * scale;
            var height = image.getHeight() * scale;
            var page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);
            var pdfImage = LosslessFactory.createFromImage(document, image);
            try (var content = new PDPageContentStream(document, page)) {
                content.drawImage(pdfImage, 0, 0, width, height);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation < 2 || orientation > 8) return source;
        var width = source.getWidth();
        var height = source.getHeight();
        var swap = orientation >= 5;
        var target = new BufferedImage(swap ? height : width, swap ? width : height,
                BufferedImage.TYPE_INT_RGB);
        var transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            default -> new AffineTransform(0, -1, 1, 0, 0, width);
        };
        var graphics = target.createGraphics();
        try {
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** Reads the orientation in JPEG APP1/Exif without changing other image formats. */
    static int exifOrientation(byte[] bytes) {
        if (bytes.length < 4 || unsigned(bytes, 0) != 0xff || unsigned(bytes, 1) != 0xd8) return 1;
        for (var marker = 2; marker + 4 <= bytes.length;) {
            if (unsigned(bytes, marker) != 0xff) return 1;
            var type = unsigned(bytes, marker + 1);
            if (type == 0xda || type == 0xd9) return 1;
            var length = bigShort(bytes, marker + 2);
            if (length < 2 || marker + 2 + length > bytes.length) return 1;
            var start = marker + 4;
            if (type == 0xe1 && length >= 16
                    && bytes[start] == 'E' && bytes[start + 1] == 'x'
                    && bytes[start + 2] == 'i' && bytes[start + 3] == 'f'
                    && bytes[start + 4] == 0 && bytes[start + 5] == 0) {
                return tiffOrientation(bytes, start + 6, marker + 2 + length);
            }
            marker += 2 + length;
        }
        return 1;
    }

    private static int tiffOrientation(byte[] bytes, int start, int end) {
        if (start + 8 > end) return 1;
        var little = bytes[start] == 'I' && bytes[start + 1] == 'I';
        if (!little && !(bytes[start] == 'M' && bytes[start + 1] == 'M')) return 1;
        if (shortAt(bytes, start + 2, little) != 42) return 1;
        var offset = intAt(bytes, start + 4, little);
        if (offset < 8 || offset > end - start - 2) return 1;
        var ifd = start + offset;
        var count = shortAt(bytes, ifd, little);
        if (count > (end - ifd - 2) / 12) return 1;
        for (var index = 0; index < count; index++) {
            var entry = ifd + 2 + index * 12;
            if (shortAt(bytes, entry, little) == 0x0112
                    && shortAt(bytes, entry + 2, little) == 3
                    && intAt(bytes, entry + 4, little) == 1) {
                var value = shortAt(bytes, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int unsigned(byte[] bytes, int at) { return bytes[at] & 0xff; }
    private static int bigShort(byte[] bytes, int at) {
        return (unsigned(bytes, at) << 8) | unsigned(bytes, at + 1);
    }
    private static int shortAt(byte[] bytes, int at, boolean little) {
        return little ? unsigned(bytes, at) | (unsigned(bytes, at + 1) << 8) : bigShort(bytes, at);
    }
    private static int intAt(byte[] bytes, int at, boolean little) {
        return little ? shortAt(bytes, at, true) | (shortAt(bytes, at + 2, true) << 16)
                : (shortAt(bytes, at, false) << 16) | shortAt(bytes, at + 2, false);
    }
}
