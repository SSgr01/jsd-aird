package com.jsd.aird.shared.office;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EmbeddedOfficeImageTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesPngAndJpegBytesAndIntrinsicDimensions() throws Exception {
        for (var type : new String[]{"png", "jpeg"}) {
            var output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB), type, output);
            var drawing = mapper.createObjectNode().put("drawingId", type)
                    .put("source", "data:image/" + type + ";base64," + Base64.getEncoder().encodeToString(output.toByteArray()));
            var image = EmbeddedOfficeImage.read(drawing, mapper.createArrayNode());
            assertThat(image.bytes()).isEqualTo(output.toByteArray());
            assertThat(image.width()).isEqualTo(16);
            assertThat(image.height()).isEqualTo(12);
            assertThat(image.extension()).isEqualTo(type.equals("jpeg") ? "jpg" : "png");
        }
    }

    @Test
    void rejectsTemporaryExternalAndOversizedSources() {
        for (var source : new String[]{"blob:expired", "https://example.com/image.png",
                "data:image/png;base64," + "A".repeat(7_000_001)}) {
            var drawing = mapper.createObjectNode().put("drawingId", "invalid").put("source", source);
            assertThatThrownBy(() -> EmbeddedOfficeImage.read(drawing, mapper.createArrayNode()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("图片");
        }
    }
}
