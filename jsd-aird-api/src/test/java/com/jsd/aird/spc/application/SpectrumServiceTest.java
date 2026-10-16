package com.jsd.aird.spc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.spc.application.port.SpectrumRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SpectrumServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    @Test
    void repeatedCreateRequestReturnsExistingChartWithoutInsertingAgain() {
        ActorContext.set(new Actor(ORGANIZATION_ID, USER_ID, "tester"));
        var repository = mock(SpectrumRepository.class);
        var storage = mock(FileStorageFacade.class);
        var category = category();
        var chart = chart(category.id(), UUID.randomUUID());
        var fileId = UUID.randomUUID();
        var requestId = UUID.randomUUID();
        when(repository.findCategory(ORGANIZATION_ID, category.id())).thenReturn(Optional.of(category));
        when(repository.findChartByRequestId(ORGANIZATION_ID, requestId))
                .thenReturn(Optional.empty(), Optional.of(chart));
        when(repository.findChartByHash(ORGANIZATION_ID, chart.sha256())).thenReturn(Optional.empty());
        when(repository.insertChart(any())).thenReturn(chart);
        when(storage.open(ORGANIZATION_ID, fileId)).thenAnswer(ignored -> storedFile(fileId, chart.sha256()));

        var service = new SpectrumService(repository, storage, new ObjectMapper());
        var command = new SpectrumService.CreateChartCommand(fileId.toString(), chart.title(), category.id(),
                null, null, null, null, requestId);

        assertThat(service.createChart(command).id()).isEqualTo(chart.id());
        assertThat(service.createChart(command).id()).isEqualTo(chart.id());

        verify(repository, times(2)).lockChartRequest(ORGANIZATION_ID, requestId);
        verify(repository).lockChartHash(ORGANIZATION_ID, chart.sha256());
        verify(repository, times(1)).insertChart(any());
        verify(storage, times(1)).activate(fileId);
    }

    @Test
    void rejectsSameFileHashWhenItUsesAnotherRequestId() {
        ActorContext.set(new Actor(ORGANIZATION_ID, USER_ID, "tester"));
        var repository = mock(SpectrumRepository.class);
        var storage = mock(FileStorageFacade.class);
        var category = category();
        var existing = chart(category.id(), UUID.randomUUID());
        var fileId = UUID.randomUUID();
        var requestId = UUID.randomUUID();
        when(repository.findCategory(ORGANIZATION_ID, category.id())).thenReturn(Optional.of(category));
        when(repository.findChartByRequestId(ORGANIZATION_ID, requestId)).thenReturn(Optional.empty());
        when(repository.findChartByHash(ORGANIZATION_ID, existing.sha256())).thenReturn(Optional.of(existing));
        when(storage.open(ORGANIZATION_ID, fileId)).thenAnswer(ignored -> storedFile(fileId, existing.sha256()));

        var service = new SpectrumService(repository, storage, new ObjectMapper());

        assertThatThrownBy(() -> service.createChart(new SpectrumService.CreateChartCommand(fileId.toString(),
                "重复图谱", category.id(), null, null, null, null, requestId)))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).errorCode())
                        .isEqualTo(ApiErrorCode.RESOURCE_CONFLICT));
        verify(repository).lockChartHash(ORGANIZATION_ID, existing.sha256());
        verify(repository, never()).insertChart(any());
    }

    @Test
    void preventsDeletingChartUsedByProjectOrActiveReference() {
        ActorContext.set(new Actor(ORGANIZATION_ID, USER_ID, "tester"));
        var repository = mock(SpectrumRepository.class);
        var chart = chart(UUID.randomUUID(), UUID.randomUUID());
        when(repository.findChart(ORGANIZATION_ID, chart.id())).thenReturn(Optional.of(chart));
        when(repository.hasProjectUsage(ORGANIZATION_ID, chart.id())).thenReturn(true);

        assertThatThrownBy(() -> new SpectrumService(repository, mock(FileStorageFacade.class), new ObjectMapper())
                .deleteChart(chart.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).errorCode())
                        .isEqualTo(ApiErrorCode.REFERENCE_IN_USE));
        verify(repository, never()).deleteChart(ORGANIZATION_ID, chart.id());
    }

    @Test
    void exportContainsChartManifestAndOriginalFile() throws Exception {
        ActorContext.set(new Actor(ORGANIZATION_ID, USER_ID, "tester"));
        var repository = mock(SpectrumRepository.class);
        var storage = mock(FileStorageFacade.class);
        var chart = chart(UUID.randomUUID(), UUID.randomUUID());
        when(repository.findChart(ORGANIZATION_ID, chart.id())).thenReturn(Optional.of(chart));
        when(repository.listChartAnalysisExports(ORGANIZATION_ID, chart.id(), 20)).thenReturn(List.of(
                new SpectrumRepository.ChartAnalysisExportRow(UUID.randomUUID(), "AI_CHAT", "列出图谱主峰",
                        "[\"" + chart.id() + "\"]", "SUCCEEDED",
                        "{\"observations\":[{\"label\":\"主峰\",\"value\":\"1730 cm-1\"}]}",
                        Instant.now(), Instant.now())));
        when(storage.open(ORGANIZATION_ID, chart.fileObjectId())).thenAnswer(ignored ->
                new FileStorageFacade.StoredFile(chart.fileObjectId(), chart.originalName(), chart.contentType(),
                        chart.size(), chart.sha256(), new ByteArrayInputStream("graph-image".getBytes(StandardCharsets.UTF_8))));

        var output = new ByteArrayOutputStream();
        new SpectrumService(repository, storage, new ObjectMapper()).writeExport(List.of(chart.id()), output);

        var entries = new java.util.LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(entries).containsKey("图谱清单.csv");
        assertThat(entries.get("图谱清单.csv")).contains(chart.title()).contains(chart.sha256());
        assertThat(entries.get("AI分析结果.json")).contains("主峰").contains("1730 cm-1");
        assertThat(entries.get("原始文件/" + chart.id() + "_" + chart.originalName())).isEqualTo("graph-image");
    }

    private SpectrumRepository.CategoryRow category() {
        return new SpectrumRepository.CategoryRow(UUID.randomUUID(), ORGANIZATION_ID, "IR", "红外 IR",
                null, null, "[]", 10, true, 0);
    }

    private SpectrumRepository.ChartRow chart(UUID categoryId, UUID fileId) {
        return new SpectrumRepository.ChartRow(UUID.randomUUID(), ORGANIZATION_ID, categoryId, "IR", "红外 IR",
                fileId, "谱图一", "谱图一.png", "image/png", 11, "sha-256-value", null, null, null, "{}",
                1, "READY", Instant.now(), Instant.now());
    }

    private FileStorageFacade.StoredFile storedFile(UUID fileId, String sha256) {
        return new FileStorageFacade.StoredFile(fileId, "谱图一.png", "image/png", 11, sha256,
                new ByteArrayInputStream("graph-image".getBytes(StandardCharsets.UTF_8)));
    }
}
