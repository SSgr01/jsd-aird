package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jsd.aird.ops.api.FileStorageFacade;
import com.jsd.aird.shared.business.BusinessViews;
import com.jsd.aird.shared.security.*;
import com.jsd.aird.shared.error.ApiException;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessOriginalFilesTest {
    @Test void missingAndOversizedOriginalsAreReportedWithoutReadingUnboundedContent() {
        UUID org=UUID.randomUUID(),id=UUID.randomUUID();ActorContext.set(new Actor(org,UUID.randomUUID(),"reader"));
        try {
            var storage=mock(FileStorageFacade.class);var entries=new ArrayList<BusinessOriginalFiles.Entry>();
            var service=new BusinessOriginalFiles(mock(JdbcTemplate.class),storage){@Override public List<Entry> list(com.fasterxml.jackson.databind.node.ObjectNode ref){return List.copyOf(entries);}};
            var ref=JsonNodeFactory.instance.objectNode();
            assertThatThrownBy(()->service.open(ref,null)).isInstanceOf(ApiException.class).hasMessageContaining("没有原文件");
            verifyNoInteractions(storage);
            entries.add(new BusinessOriginalFiles.Entry(id,"固化说明.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",8*1024*1024+1L,"x"));
            var unread=mock(java.io.InputStream.class);
            when(storage.open(org,id)).thenReturn(new FileStorageFacade.StoredFile(id,"固化说明.docx",entries.getFirst().content_type(),entries.getFirst().bytes(),"x",unread));
            assertThatThrownBy(()->service.open(ref,null)).isInstanceOf(ApiException.class).hasMessageContaining("超过8MB");
            verify(unread,never()).readNBytes(anyInt());
            when(storage.open(org,id)).thenThrow(new IllegalStateException("Object missing"));
            assertThatThrownBy(()->service.open(ref,id)).isInstanceOf(ApiException.class).hasMessageContaining("无法读取");
        } catch (java.io.IOException error) {throw new AssertionError(error);}
        finally {ActorContext.clear();}
    }

    @Test void fileSelectionRejectsUnrelatedFilesAndChecksExactBytes() {
        UUID org=UUID.randomUUID(),id=UUID.randomUUID();ActorContext.set(new Actor(org,UUID.randomUUID(),"reader"));
        try {
            byte[] bytes="保存的原件".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash=BusinessViews.hash(bytes);
            var storage=mock(FileStorageFacade.class);var entries=new ArrayList<BusinessOriginalFiles.Entry>();
            entries.add(new BusinessOriginalFiles.Entry(id,"交付说明.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes.length,hash));
            var service=new BusinessOriginalFiles(mock(JdbcTemplate.class),storage){@Override public List<Entry> list(com.fasterxml.jackson.databind.node.ObjectNode ref){return List.copyOf(entries);}};
            when(storage.open(org,id)).thenAnswer(call->new FileStorageFacade.StoredFile(id,"交付说明.docx",entries.getFirst().content_type(),bytes.length,hash,new ByteArrayInputStream(bytes)));
            var ref=JsonNodeFactory.instance.objectNode();
            assertThat(service.open(ref,null).bytes()).isEqualTo(bytes);
            assertThatThrownBy(()->service.open(ref,UUID.randomUUID())).isInstanceOf(ApiException.class);
            entries.add(new BusinessOriginalFiles.Entry(UUID.randomUUID(),"第二附件.pdf","application/pdf",1,"x"));
            assertThatThrownBy(()->service.open(ref,null)).isInstanceOf(ApiException.class).hasMessageContaining("多个");
            assertThat(service.open(ref,id).sha256()).isEqualTo(hash);
            entries.set(0,new BusinessOriginalFiles.Entry(id,"交付说明.docx",entries.getFirst().content_type(),bytes.length,"0".repeat(64)));
            assertThatThrownBy(()->service.open(ref,id)).isInstanceOf(ApiException.class).hasMessageContaining("哈希");
        } finally {ActorContext.clear();}
    }
}
