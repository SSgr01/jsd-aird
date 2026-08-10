package com.jsd.aird.data.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DataAssetExportServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataRepository repository = mock(DataRepository.class);
    private final TemplateDataImportFacade templates = mock(TemplateDataImportFacade.class);
    private final DataAssetExportService service = new DataAssetExportService(repository, templates, objectMapper);

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    @Test
    void createsOneWorkbookPerAssetAndManifestWithSafeNames() throws Exception {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID, USER_ID, "developer"));
        var templateId = UUID.randomUUID();
        var first = asset("../酸碱/原料", 1);
        var second = asset("原料", 2);
        when(repository.findAsset(ORGANIZATION_ID, first.id())).thenReturn(java.util.Optional.of(first));
        when(repository.findAsset(ORGANIZATION_ID, second.id())).thenReturn(java.util.Optional.of(second));
        when(templates.getPublished(ORGANIZATION_ID, templateId)).thenReturn(template());
        when(templates.exportPublishedWorkbook(eq(ORGANIZATION_ID), eq(templateId), any(), any()))
                .thenReturn(new TemplateDataImportFacade.WorkbookExport(
                        "xlsx-content".getBytes(java.nio.charset.StandardCharsets.UTF_8), List.of()));

        var result = service.export(new DataAssetExportService.ExportCommand(
                "MATERIAL", templateId, List.of(first.id(), second.id())));

        try (var zip = new ZipInputStream(new ByteArrayInputStream(result.content()))) {
            var entries = new java.util.ArrayList<String>();
            String manifest = null;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.add(entry.getName());
                var content = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if ("manifest.csv".equals(entry.getName())) manifest = content;
            }
            assertThat(entries).hasSize(3).contains("manifest.csv");
            assertThat(entries).noneMatch(item -> item.contains("/") || item.contains("\\") || item.contains(".."));
            assertThat(manifest).contains("资产ID,资产编码,资产名称,数据类型,修订号,修订ID,模板版本ID,导入批次ID,原文件SHA-256");
            assertThat(manifest).contains(first.id().toString()).contains(second.id().toString());
        }
    }

    @Test
    void rejectsMixedTargetDataTypesBeforeRendering() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID, USER_ID, "developer"));
        var templateId = UUID.randomUUID();
        var material = asset("M-1", 1);
        var formula = new DataRepository.AssetDetail(UUID.randomUUID(), "FORMULA", material.assetKey(), material.displayName(),
                material.currentRevisionId(), material.status(), material.rawData(), material.normalizedData(),
                material.correctedData(), material.importJobId(), material.templateVersionId(), material.currentRevisionNo(),
                material.sourceSha256(), material.updatedAt());
        when(repository.findAsset(ORGANIZATION_ID, material.id())).thenReturn(java.util.Optional.of(material));
        when(repository.findAsset(ORGANIZATION_ID, formula.id())).thenReturn(java.util.Optional.of(formula));
        when(templates.getPublished(ORGANIZATION_ID, templateId)).thenReturn(template());

        var thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.export(
                new DataAssetExportService.ExportCommand("MATERIAL", templateId, List.of(material.id(), formula.id()))));

        assertThat(thrown).hasMessageContaining("同一数据类型");
    }

    private TemplateDataImportFacade.DataTemplateDefinition template() {
        return new TemplateDataImportFacade.DataTemplateDefinition(
                UUID.randomUUID(), UUID.randomUUID(), "material-template", "物料模板", "原料", "MATERIAL", 1,
                "XLSX", objectMapper.createObjectNode(), objectMapper.createArrayNode(), List.of());
    }

    private DataRepository.AssetDetail asset(String key, int revisionNo) {
        var revisionId = UUID.randomUUID();
        var fields = objectMapper.createObjectNode();
        fields.set("name", objectMapper.createObjectNode().put("normalizedValue", "标准值"));
        return new DataRepository.AssetDetail(UUID.randomUUID(), "MATERIAL", key, "测试资产", revisionId, "ACTIVE",
                objectMapper.createObjectNode(), fields, fields.deepCopy(), UUID.randomUUID(), UUID.randomUUID(), revisionNo,
                "sha-" + revisionNo, Instant.now());
    }
}
