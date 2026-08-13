package com.jsd.aird.tpl.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.StandardFieldService;
import com.jsd.aird.tpl.application.TemplateImportContractCompiler;
import com.jsd.aird.tpl.application.TemplateOfficeExportService;
import com.jsd.aird.tpl.application.TemplateRecognitionCompiler;
import com.jsd.aird.tpl.application.TemplateRecognitionReviewService;
import com.jsd.aird.tpl.application.TemplateWorkspaceService;
import com.jsd.aird.tpl.application.port.BlankWordDocumentFactory;
import com.jsd.aird.tpl.application.port.TemplateImportRepository;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.application.port.WordDocumentParser;
import com.jsd.aird.tpl.application.port.WordOoxmlPatcher;
import com.jsd.aird.tpl.domain.TemplateFormat;
import com.jsd.aird.tpl.domain.TemplateStatus;
import com.jsd.aird.tpl.infrastructure.Docx4jBlankWordDocumentFactory;
import com.jsd.aird.tpl.infrastructure.DocxStructureParser;
import com.jsd.aird.tpl.infrastructure.WordOoxmlPatchService;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class TemplateWorkspaceBlankWordTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TemplateRepository repository = mock(TemplateRepository.class);
    private final TemplateImportRepository importRepository = mock(TemplateImportRepository.class);
    private final TemplateRecognitionReviewService recognitionReviewService = mock(TemplateRecognitionReviewService.class);
    private final StandardFieldService standardFieldService = mock(StandardFieldService.class);
    private final FileObjectRepository fileRepository = mock(FileObjectRepository.class);
    private final ObjectStorage objectStorage = mock(ObjectStorage.class);
    private final WordOoxmlPatcher wordOoxmlPatcher = mock(WordOoxmlPatcher.class);
    private final TemplateImportContractCompiler importContractCompiler = mock(TemplateImportContractCompiler.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final AtomicReference<TemplateRepository.NewTemplate> insertedTemplate = new AtomicReference<>();
    private final AtomicReference<TemplateRepository.NewVersion> insertedVersion = new AtomicReference<>();
    private final AtomicReference<FileObjectRepository.NewFileObject> insertedFile = new AtomicReference<>();
    private final AtomicReference<TemplateRepository.TemplateWorkspace> currentWorkspace = new AtomicReference<>();
    private final Map<UUID, FileObjectRepository.FileObject> files = new HashMap<>();
    private final Map<String, byte[]> storedObjects = new HashMap<>();

    @AfterEach
    void cleanUp() {
        ActorContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createsBlankWordWithNativeArtifactSnapshotAndDocumentStructure() throws Exception {
        var service = service(new Docx4jBlankWordDocumentFactory(),
                new DocxStructureParser(objectMapper));
        prepareSuccessfulRepository();
        ActorContext.set(new Actor(ORGANIZATION_ID, USER_ID, "developer"));

        var workspace = service.createBlank(new TemplateWorkspaceService.CreateBlankCommand(
                "研发/空白:*?模板", null, TemplateFormat.DOCX, null));

        var file = insertedFile.get();
        assertThat(file).isNotNull();
        assertThat(file.originalName()).isEqualTo("研发_空白___模板.docx");
        assertThat(storedObjects.get(file.objectKey())).isNotEmpty();
        assertThat(workspace.inlineSnapshot().path("snapshotFormatVersion").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(workspace.inlineSnapshot().path("editorMode").asText()).isEqualTo("UNIVER_DOCS");
        assertThat(workspace.documentStructure().path("structureHash").asText()).hasSize(64);
        assertThat(workspace.wordDocument().path("sourceDocxFileId").asText()).isEqualTo(file.id().toString());
        assertThat(workspace.wordDocument().path("workingDocxFileId").asText()).isEqualTo(file.id().toString());
        assertThat(workspace.wordDocument().path("documentHash").asText()).isEqualTo(file.sha256());
        assertThat(workspace.wordDocument().path("state").asText()).isEqualTo("WORKING");
        verify(repository).appendOutbox(
                eq("FILE_OBJECT"), eq(file.id()), eq("FILE_ACTIVATION_REQUESTED"), any());
    }

    @Test
    void savesReloadsAndExportsHeadingsFromABlankWordTemplate() throws Exception {
        var parser = new DocxStructureParser(objectMapper);
        var patcher = new WordOoxmlPatchService();
        var service = service(new Docx4jBlankWordDocumentFactory(), parser, patcher);
        prepareSuccessfulRepository();
        ActorContext.set(new Actor(ORGANIZATION_ID, USER_ID, "developer"));
        var created = service.createBlank(new TemplateWorkspaceService.CreateBlankCommand(
                "章节回归模板", null, TemplateFormat.DOCX, null));
        var editedSnapshot = created.inlineSnapshot().deepCopy();
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) editedSnapshot.path("body");
        body.put("dataStream", "研究目标\r实验正文\r\n");
        body.set("textRuns", objectMapper.createArrayNode());
        body.set("customRanges", objectMapper.createArrayNode());
        var paragraphs = objectMapper.createArrayNode();
        paragraphs.addObject().put("startIndex", 0).putObject("paragraphStyle")
                .put("namedStyleType", 4).put("headingId", "heading-1");
        paragraphs.addObject().put("startIndex", 5).putObject("paragraphStyle");
        body.set("paragraphs", paragraphs);
        var stagedSnapshot = stageSnapshot(editedSnapshot);

        var saved = service.saveDraft(created.versionId(), new TemplateWorkspaceService.SaveDraftCommand(
                created.lockVersion(), created.workspaceHash(), created.schema(), objectMapper.createArrayNode(),
                objectMapper.createObjectNode(), stagedSnapshot.id(), stagedSnapshot.sha256(),
                "univer-docs-0.25.1", "word-document-v1", 5, "word-document-save",
                UUID.randomUUID().toString(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), null, objectMapper.createArrayNode()
        ));

        assertThat(saved.lockVersion()).isEqualTo(1);
        assertThat(saved.wordDocument().path("workingDocxFileId").asText())
                .isNotEqualTo(created.wordDocument().path("workingDocxFileId").asText());
        verify(repository).appendOutbox(
                eq("FILE_OBJECT"),
                eq(UUID.fromString(saved.wordDocument().path("workingDocxFileId").asText())),
                eq("FILE_ACTIVATION_REQUESTED"),
                any());
        assertThat(saved.documentStructure().path("nodes"))
                .anyMatch(node -> "HEADING".equals(node.path("type").asText())
                        && "研究目标".equals(node.path("text").asText()));
        var reloaded = service.get(created.versionId());
        assertThat(reloaded.lockVersion()).isEqualTo(1);
        assertThat(reloaded.snapshotFileId()).isEqualTo(stagedSnapshot.id());
        assertThat(reloaded.documentStructure().path("nodes"))
                .anyMatch(node -> "HEADING".equals(node.path("type").asText()));

        var exportService = new TemplateOfficeExportService(
                repository, fileRepository, objectStorage, objectMapper,
                new SnapshotWorkbookExporter(objectMapper), patcher);
        var exported = exportService.export(created.versionId(), "DOCX", "DRAFT");
        var exportedStructure = parser.parse(new ByteArrayInputStream(exported.content()))
                .structureSummary().path("documentIR");
        assertThat(exportedStructure.path("nodes"))
                .anyMatch(node -> "HEADING".equals(node.path("type").asText())
                        && "研究目标".equals(node.path("text").asText()));
        assertThat(exportedStructure.path("structureHash").asText()).hasSize(64);
    }

    @Test
    void leavesExcelBlankCreationOnTheExistingSnapshotOnlyPath() {
        var blankWordFactory = mock(BlankWordDocumentFactory.class);
        var parser = mock(WordDocumentParser.class);
        var service = service(blankWordFactory, parser);
        prepareSuccessfulRepository();
        ActorContext.set(new Actor(ORGANIZATION_ID, USER_ID, "developer"));

        var workspace = service.createBlank(new TemplateWorkspaceService.CreateBlankCommand(
                "空白 Excel", null, TemplateFormat.XLSX, null));

        assertThat(workspace.inlineSnapshot().path("sheets").path("sheet-1").isObject()).isTrue();
        assertThat(workspace.wordDocument().isMissingNode()).isTrue();
        verify(blankWordFactory, never()).create(anyString());
        verify(fileRepository, never()).insert(any());
    }

    @Test
    void doesNotCreateTemplateWhenBlankWordParsingFails() {
        var blankWordFactory = mock(BlankWordDocumentFactory.class);
        var parser = mock(WordDocumentParser.class);
        when(blankWordFactory.create(anyString())).thenReturn(new byte[]{1, 2, 3});
        when(parser.parse(any(InputStream.class)))
                .thenThrow(new ApiException(com.jsd.aird.shared.error.ApiErrorCode.BAD_REQUEST,
                        "invalid docx"));
        var service = service(blankWordFactory, parser);
        ActorContext.set(new Actor(ORGANIZATION_ID, USER_ID, "developer"));

        assertThatThrownBy(() -> service.createBlank(new TemplateWorkspaceService.CreateBlankCommand(
                "解析失败", null, TemplateFormat.DOCX, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("invalid docx");

        verify(objectStorage, never()).put(anyString(), any(InputStream.class), anyLong(), anyString());
        verify(repository, never()).insertTemplate(any());
        verify(repository, never()).insertVersion(any());
    }

    @Test
    void doesNotCreateTemplateWhenBlankWordStorageFails() {
        var service = service(new Docx4jBlankWordDocumentFactory(),
                new DocxStructureParser(objectMapper));
        doThrow(new IllegalStateException("storage unavailable"))
                .when(objectStorage).put(anyString(), any(InputStream.class), anyLong(), anyString());
        ActorContext.set(new Actor(ORGANIZATION_ID, USER_ID, "developer"));

        assertThatThrownBy(() -> service.createBlank(new TemplateWorkspaceService.CreateBlankCommand(
                "存储失败", null, TemplateFormat.DOCX, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Word 文件暂存失败");

        verify(fileRepository, never()).insert(any());
        verify(repository, never()).insertTemplate(any());
        verify(repository, never()).insertVersion(any());
    }

    @Test
    void deletesUploadedWordObjectWhenFileRegistrationFails() throws Exception {
        var service = service(new Docx4jBlankWordDocumentFactory(),
                new DocxStructureParser(objectMapper));
        prepareObjectStorage();
        doThrow(new IllegalStateException("database unavailable")).when(fileRepository).insert(any());
        ActorContext.set(new Actor(ORGANIZATION_ID, USER_ID, "developer"));

        assertThatThrownBy(() -> service.createBlank(new TemplateWorkspaceService.CreateBlankCommand(
                "失败模板", null, TemplateFormat.DOCX, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Word 文件暂存失败");

        var key = storedObjects.keySet().iterator().next();
        verify(objectStorage).delete(key);
        verify(repository, never()).insertTemplate(any());
    }

    @Test
    void deletesUploadedWordObjectAfterSurroundingTransactionRollsBack() throws Exception {
        var service = service(new Docx4jBlankWordDocumentFactory(),
                new DocxStructureParser(objectMapper));
        prepareObjectStorage();
        doAnswer(invocation -> {
            insertedFile.set(invocation.getArgument(0));
            return null;
        }).when(fileRepository).insert(any());
        doThrow(new IllegalStateException("template insert failed")).when(repository).insertTemplate(any());
        ActorContext.set(new Actor(ORGANIZATION_ID, USER_ID, "developer"));
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> service.createBlank(new TemplateWorkspaceService.CreateBlankCommand(
                "回滚模板", null, TemplateFormat.DOCX, null)))
                .isInstanceOf(IllegalStateException.class);

        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).isNotEmpty();
        TransactionSynchronizationUtils.invokeAfterCompletion(
                synchronizations, TransactionSynchronization.STATUS_ROLLED_BACK);
        var key = insertedFile.get().objectKey();
        verify(objectStorage).delete(key);
    }

    private TemplateWorkspaceService service(
            BlankWordDocumentFactory blankWordDocumentFactory,
            WordDocumentParser wordDocumentParser
    ) {
        return service(blankWordDocumentFactory, wordDocumentParser, wordOoxmlPatcher);
    }

    private TemplateWorkspaceService service(
            BlankWordDocumentFactory blankWordDocumentFactory,
            WordDocumentParser wordDocumentParser,
            WordOoxmlPatcher patcher
    ) {
        return new TemplateWorkspaceService(
                repository,
                importRepository,
                new TemplateRecognitionCompiler(objectMapper),
                recognitionReviewService,
                standardFieldService,
                new JsonCanonicalizer(objectMapper),
                objectMapper,
                fileRepository,
                objectStorage,
                blankWordDocumentFactory,
                patcher,
                wordDocumentParser,
                importContractCompiler,
                transactionManager,
                "test-bucket"
        );
    }

    private void prepareSuccessfulRepository() {
        prepareObjectStorage();
        doAnswer(invocation -> {
            var value = invocation.getArgument(0, FileObjectRepository.NewFileObject.class);
            insertedFile.set(value);
            files.put(value.id(), new FileObjectRepository.FileObject(
                    value.id(), value.organizationId(), value.objectKey(), value.originalName(),
                    value.contentType(), value.size(), value.sha256(), "STAGED"));
            return null;
        }).when(fileRepository).insert(any());
        doAnswer(invocation -> {
            insertedTemplate.set(invocation.getArgument(0));
            return null;
        }).when(repository).insertTemplate(any());
        doAnswer(invocation -> {
            insertedVersion.set(invocation.getArgument(0));
            return null;
        }).when(repository).insertVersion(any());
        when(fileRepository.find(any(), any())).thenAnswer(invocation ->
                Optional.ofNullable(files.get(invocation.getArgument(1, UUID.class))));
        when(repository.findFile(any(), any())).thenAnswer(invocation -> {
            var file = files.get(invocation.getArgument(1, UUID.class));
            return file == null ? Optional.empty()
                    : Optional.of(new TemplateRepository.FileReference(file.id(), file.status(), file.sha256()));
        });
        when(repository.findWorkspace(any(), any())).thenAnswer(invocation -> {
            var workspace = currentWorkspace.get();
            if (workspace == null) {
                workspace = workspace(insertedTemplate.get(), insertedVersion.get());
                currentWorkspace.set(workspace);
            }
            return Optional.of(workspace);
        });
        when(repository.updateDraft(any())).thenAnswer(invocation -> {
            var update = invocation.getArgument(0, TemplateRepository.DraftUpdate.class);
            var previous = currentWorkspace.get();
            var layout = update.layoutSummary();
            currentWorkspace.set(new TemplateRepository.TemplateWorkspace(
                    previous.templateId(), previous.versionId(), previous.recognitionRunId(), previous.templateCode(),
                    previous.name(), previous.format(), previous.status(), previous.versionNo(), update.schema(),
                    previous.mapping(), previous.data(), layout.path("documentStructure"), layout.path("wordDocument"),
                    layout.path("initialSnapshot"), update.snapshotFileId(), update.snapshotHash(), previous.snapshotKind(),
                    update.editorAppVersion(), update.pluginManifestHash(), update.snapshotFormatVersion(),
                    update.schemaHash(), update.mappingHash(), update.dataHash(), update.workspaceHash(),
                    previous.lockVersion() + 1, update.reconciliationRequired()
            ));
            return 1;
        });
    }

    private void prepareObjectStorage() {
        doAnswer(invocation -> {
            var key = invocation.getArgument(0, String.class);
            var source = invocation.getArgument(1, InputStream.class);
            storedObjects.put(key, source.readAllBytes());
            return null;
        }).when(objectStorage).put(anyString(), any(InputStream.class), anyLong(), anyString());
        when(objectStorage.get(anyString())).thenAnswer(invocation -> {
            var bytes = storedObjects.get(invocation.getArgument(0, String.class));
            return new ObjectStorage.StoredObject(
                    new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
        });
    }

    private FileObjectRepository.FileObject stageSnapshot(com.fasterxml.jackson.databind.JsonNode snapshot) throws Exception {
        var bytes = objectMapper.writeValueAsBytes(snapshot);
        var id = UUID.randomUUID();
        var key = ORGANIZATION_ID + "/test-snapshots/" + id + ".json";
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var file = new FileObjectRepository.FileObject(
                id, ORGANIZATION_ID, key, "docx-univer-snapshot.json", "application/json",
                bytes.length, sha, "STAGED");
        files.put(id, file);
        storedObjects.put(key, bytes);
        return file;
    }

    private TemplateRepository.TemplateWorkspace workspace(
            TemplateRepository.NewTemplate template,
            TemplateRepository.NewVersion version
    ) {
        var layout = version.layoutSummary();
        return new TemplateRepository.TemplateWorkspace(
                template.id(), version.id(), null, template.code(), template.name(), template.format(),
                TemplateStatus.DRAFT, 1, version.schema(), objectMapper.createArrayNode(),
                objectMapper.createObjectNode(), layout.path("documentStructure"), layout.path("wordDocument"),
                layout.path("initialSnapshot"), null, null, version.snapshotKind(), version.editorAppVersion(),
                version.pluginManifestHash(), layout.path("initialSnapshot").path("snapshotFormatVersion").asInt(),
                version.schemaHash(), version.mappingHash(), version.dataHash(), version.workspaceHash(), 0, false
        );
    }
}
