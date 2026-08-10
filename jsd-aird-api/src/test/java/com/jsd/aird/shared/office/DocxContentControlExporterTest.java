package com.jsd.aird.shared.office;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DocxContentControlExporterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DocxContentControlExporter exporter = new DocxContentControlExporter(objectMapper);

    @Test
    void replacesContentControlByStableMarker() throws Exception {
        var mapping = objectMapper.readTree("[{\"bindingId\":\"order\",\"markerId\":\"marker-1\",\"dataPath\":\"/orderNo\"}]");
        var result = exporter.export(docx("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                  <w:sdt><w:sdtPr><w:dataBinding w:storeItemID="marker-1"/></w:sdtPr><w:sdtContent><w:r><w:t>旧值</w:t></w:r></w:sdtContent></w:sdt>
                </w:body></w:document>
                """), mapping, objectMapper.readTree("{\"orderNo\":\"PO-1\"}"));
        assertThat(documentXml(result.content())).contains("PO-1").doesNotContain("旧值");
        assertThat(result.warnings()).isEmpty();
    }

    private byte[] docx(String xml) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) { zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write(xml.getBytes()); zip.closeEntry(); }
        return output.toByteArray();
    }

    private String documentXml(byte[] content) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) if ("word/document.xml".equals(entry.getName())) return new String(zip.readAllBytes());
        }
        return "";
    }
}
