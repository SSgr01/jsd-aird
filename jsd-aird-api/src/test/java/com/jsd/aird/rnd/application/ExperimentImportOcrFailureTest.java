package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.DocumentParser;
import com.jsd.aird.kb.api.MediaExtractionProvider;
import com.jsd.aird.ops.application.port.*;
import com.jsd.aird.rnd.application.port.ExperimentImportRepository;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.tpl.api.TemplateFormat;
import com.jsd.aird.tpl.api.TemplateOfficeFacade;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExperimentImportOcrFailureTest {
    private final DocumentParser mineru = mock(DocumentParser.class);
    private final Actor actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "ocr-test");
    private final ExperimentImportService.Command command = new ExperimentImportService.Command(
            UUID.randomUUID(), "image.png", "hash", TemplateFormat.IMAGE,
            null, null, null, null, null, LocalDate.now(), "ALL", false,
            "CONTENT_ONLY", "图片实验", "测试人");

    private ExperimentImportService service(MediaExtractionProvider... providers) {
        return new ExperimentImportService(mock(ExperimentImportRepository.class), mock(ExperimentService.class),
                mock(FileStorageFacade.class), List.of(), List.of(providers), mineru, new ObjectMapper(),
                mock(OpsAsyncFacade.class), mock(TemplateOfficeNormalizationFacade.class), mock(TemplateOfficeFacade.class));
    }

    private byte[] image() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    @Test void configuredMineruFailureIsNotReportedAsMissingConfiguration() throws Exception {
        when(mineru.isConfigured()).thenReturn(true);
        var failure = new IllegalStateException("MinerU 精准接口提交失败：HTTP 401");
        when(mineru.parse(any(), anyString(), any(DocumentParser.ParseContext.class))).thenThrow(failure);
        var bytes = image();
        assertThatThrownBy(() -> service().parseSource(command, bytes, actor))
                .isInstanceOf(ApiException.class).hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("服务未配置").hasCause(failure);
    }

    @Test void unreadableImageIsReportedAsAnImageFailure() {
        when(mineru.isConfigured()).thenReturn(true);
        assertThatThrownBy(() -> service().parseSource(command, new byte[]{1, 2, 3}, actor))
                .isInstanceOf(ApiException.class).hasMessageContaining("无法读取实验图片")
                .hasMessageNotContaining("服务未配置");
    }

    @Test void absentProvidersStillReportMissingConfiguration() throws Exception {
        var bytes = image();
        assertThatThrownBy(() -> service().parseSource(command, bytes, actor))
                .isInstanceOf(ApiException.class).hasMessageContaining("服务未配置");
    }

    @Test void configuredFallbackCanStillReadTheImageAfterMineruFails() throws Exception {
        when(mineru.isConfigured()).thenReturn(true);
        when(mineru.parse(any(), anyString(), any(DocumentParser.ParseContext.class)))
                .thenThrow(new IllegalStateException("MinerU 精准接口提交失败：HTTP 503"));
        var fallback = mock(MediaExtractionProvider.class);
        when(fallback.isConfigured()).thenReturn(true);
        when(fallback.supports(anyString(), anyString())).thenReturn(true);
        when(fallback.extract(any(), anyString(), any()))
                .thenReturn(new DocumentParser.ParsedDocument(
                        List.of(new DocumentParser.TextBlock(1, "paragraph", "实验内容")), "fallback"));
        assertThat(service(fallback).parseSource(command, image(), actor).initialEditorSnapshot()).isNotNull();
        verify(fallback).extract(any(), anyString(), any());
    }
}
