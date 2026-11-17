package com.jsd.aird.spc.application;

import com.jsd.aird.shared.business.BusinessReadPort.FileContent;
import com.jsd.aird.shared.business.BusinessViews;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.springframework.stereotype.Component;

/** Render only an authorized, hash-checked original page for native vision attachments. */
@Component
public class SpectrumPageRenderer {
    private final SpectrumService spectra;
    public SpectrumPageRenderer(SpectrumService spectra) { this.spectra = spectra; }

    public FileContent render(UUID id, String expectedSha256, int page) {
        var stored = spectra.openChart(id);
        var chart = stored.chart();
        try (var input = stored.file().stream()) {
            if (!"READY".equals(chart.status()) || page < 1 || page > chart.pageCount())
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "图谱页面不可用或页码超出范围");
            if (!chart.sha256().equals(expectedSha256)) throw changed();
            if (stored.file().size() > 100L * 1024 * 1024)
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "图谱文件过大，请拆分后选择页面");
            byte[] original = input.readNBytes(100 * 1024 * 1024 + 1);
            if (original.length != stored.file().size() || !BusinessViews.hash(original).equals(expectedSha256)) throw changed();
            BufferedImage image;
            if ("application/pdf".equalsIgnoreCase(chart.contentType())) {
                try (var document = Loader.loadPDF(original)) {
                    if (page > document.getNumberOfPages()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "图谱页码超出原文件范围");
                    var box = document.getPage(page - 1).getCropBox();
                    if (box.getWidth() <= 0 || box.getHeight() <= 0) throw new ApiException(ApiErrorCode.BAD_REQUEST, "无法读取图谱页面尺寸");
                    float dpi = Math.min(160f, 2400f * 72f / Math.max(box.getWidth(), box.getHeight()));
                    image = new PDFRenderer(document).renderImageWithDPI(page - 1, dpi, ImageType.RGB);
                }
            } else {
                try (var source = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
                    var readers = ImageIO.getImageReaders(source);
                    if (!readers.hasNext()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "无法读取这张图谱图片");
                    var reader = readers.next();
                    try {
                        reader.setInput(source);
                        if (page != 1) throw new ApiException(ApiErrorCode.BAD_REQUEST, "图谱图片仅支持第 1 页");
                        int width = reader.getWidth(0), height = reader.getHeight(0);
                        int sampling = Math.max(1, (int)Math.ceil(Math.max(width, height) / 2400d));
                        var params = reader.getDefaultReadParam(); params.setSourceSubsampling(sampling, sampling, 0, 0);
                        image = reader.read(0, params);
                    } finally { reader.dispose(); }
                }
            }
            if (image == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "无法读取这张图谱图片");
            try (var output = new ByteArrayOutputStream()) {
                ImageIO.write(image, "png", output);
                byte[] bytes = output.toByteArray();
                if (bytes.length > 8 * 1024 * 1024) throw new ApiException(ApiErrorCode.BAD_REQUEST, "图谱页面过大，请缩小原图后重试");
                return new FileContent("spectrum-" + id + "-page-" + page + ".png", "image/png", bytes, BusinessViews.hash(bytes));
            } finally { image.flush(); }
        } catch (ApiException error) { throw error; }
        catch (Exception error) { throw new ApiException(ApiErrorCode.FILE_NOT_READY, "图谱页面暂不可读取，请检查原文件"); }
    }
    private ApiException changed() { return new ApiException(ApiErrorCode.VERSION_CONFLICT, "图谱原文件已变化，请重新选择"); }
}
