package com.jsd.aird.shared.office;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Workbook;

/** Decodes self-contained snapshot images, shared by XLSX and DOCX export. */
public record EmbeddedOfficeImage(byte[] bytes, String extension, String contentType, int pictureType,
                                  int width, int height) {
    public static EmbeddedOfficeImage read(JsonNode drawing, JsonNode resources) {
        var source = drawing.path("source").asText("");
        if (!source.startsWith("data:")) {
            for (var resource : resources) {
                if (resource.path("id").asText().equals(source)
                        || resource.path("id").asText().equals(drawing.path("drawingId").asText())) {
                    source = resource.path("data").asText("");
                    break;
                }
            }
        }
        var comma = source.indexOf(',');
        if (comma < 0 || !source.substring(0, comma).matches("data:image/(png|jpe?g|gif|bmp);base64")) {
            throw new IllegalArgumentException("图片无法导出：缺少可用的嵌入图片数据（" + drawing.path("drawingId").asText() + "）");
        }
        try {
            if (source.length() - comma - 1 > 7_000_000) throw new IllegalArgumentException("图片超过 5 MB");
            var bytes = Base64.getDecoder().decode(source.substring(comma + 1));
            if (bytes.length > 5 * 1024 * 1024) throw new IllegalArgumentException("图片超过 5 MB");
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IllegalArgumentException("图片内容损坏");
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    var width = reader.getWidth(0);
                    var height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || (long) width * height > 40_000_000) {
                        throw new IllegalArgumentException("图片尺寸无效或过大");
                    }
                    var format = reader.getFormatName();
                    if (format.equalsIgnoreCase("JPEG")) {
                        return new EmbeddedOfficeImage(bytes, "jpg", "image/jpeg", Workbook.PICTURE_TYPE_JPEG, width, height);
                    }
                    if (!format.equalsIgnoreCase("PNG")) {
                        var output = new ByteArrayOutputStream();
                        ImageIO.write(reader.read(0), "png", output);
                        bytes = output.toByteArray();
                    }
                    return new EmbeddedOfficeImage(bytes, "png", "image/png", Workbook.PICTURE_TYPE_PNG, width, height);
                } finally { reader.dispose(); }
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("图片无法导出：" + exception.getMessage(), exception);
        }
    }

    public static double dimension(JsonNode value, double fallback) {
        var result = value.asDouble(fallback);
        if (!Double.isFinite(result) || result <= 0 || result > 100_000) {
            throw new IllegalArgumentException("图片布局尺寸无效");
        }
        return result;
    }
}
