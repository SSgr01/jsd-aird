package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.infrastructure.OfficeDocumentParser;
import com.jsd.aird.shared.business.BusinessReadPort.FileContent;
import com.jsd.aird.shared.resource.IsolatedDocumentTasks;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static com.jsd.aird.shared.business.BusinessViews.hash;

class BusinessFileProjectionTest {
    private final ObjectMapper json=new ObjectMapper();
    private final BusinessFileProjection projection=new BusinessFileProjection(List.of(new OfficeDocumentParser()),mock(IsolatedDocumentTasks.class),json);
    @Test void originalDocxHasAHashBoundReadableViewWithoutChangingTheBytes() throws Exception {
        byte[] bytes;
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new ByteArrayOutputStream()){
            doc.createParagraph().createRun().setText("澄禾电子 技术要求：编号000123，待确认项为空，当前数量0。");
            doc.write(out);bytes=out.toByteArray();
        }
        var transfer=json.createObjectNode();var file=new FileContent("澄禾 技术要求.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes,hash(bytes));
        projection.attach(transfer,file);
        var view=transfer.path("text_projection");byte[] read=Base64.getDecoder().decode(view.path("content_base64").asText());
        assertThat(view.path("source_sha256").asText()).isEqualTo(hash(bytes));
        assertThat(view.path("sha256").asText()).isEqualTo(hash(read));
        assertThat(new String(read,StandardCharsets.UTF_8)).contains("000123","当前数量0","原上传文件");
        assertThat(hash(file.bytes())).isEqualTo(file.sha256());
    }
    @Test void unsupportedProjectionPreservesOriginalAndExplainsTheReason(){
        var bytes="{\\rtf1 content}".getBytes(StandardCharsets.UTF_8);var transfer=json.createObjectNode();
        projection.attach(transfer,new FileContent("说明.rtf","application/rtf",bytes,hash(bytes)));
        assertThat(transfer.has("text_projection")).isFalse();assertThat(transfer.path("reading_notice").asText()).contains("没有可用的文本投影");
    }
}
