package com.jsd.aird.ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FileObjectServiceDataSourceTest {

    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final FileObjectRepository repository = mock(FileObjectRepository.class);
    private final FileObjectService service = new FileObjectService(storage, repository, "test-bucket");

    @BeforeEach
    void setUp() {
        ActorContext.set(new Actor(UUID.randomUUID(), UUID.randomUUID(), "uploader"));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    @Test
    void privateFilePolicyGuardsDownloadPreviewOpenAndPresignedUrlsBeforeStorage() {
        var policy = mock(com.jsd.aird.ops.api.FileReadPolicy.class);
        service.configureReadPolicies(java.util.List.of(policy));
        var actor = ActorContext.required();
        var fileId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.NOT_FOUND))
            .when(policy).requireRead(actor.organizationId(), actor.userId(), fileId);
        assertThatThrownBy(() -> service.download(fileId)).isInstanceOf(com.jsd.aird.shared.error.ApiException.class);
        assertThatThrownBy(() -> service.open(actor.organizationId(), fileId)).isInstanceOf(com.jsd.aird.shared.error.ApiException.class);
        assertThatThrownBy(() -> service.presignedUrl(actor.organizationId(), fileId, java.time.Duration.ofMinutes(1)))
            .isInstanceOf(com.jsd.aird.shared.error.ApiException.class);
        verifyNoInteractions(storage, repository);
    }

    @Test
    void stagesExcelCsvWordAndPdfSources() {
        var xls = oleBytes();
        var pdf = "%PDF-1.7\nvalid test source".getBytes(StandardCharsets.US_ASCII);

        service.stage("legacy.xls", "application/vnd.ms-excel", "DATA_SOURCE", stream(xls));
        service.stage("sheet.xlsx", "application/octet-stream", "DATA_SOURCE", stream(ooxml(true)));
        service.stage("table.csv", "text/csv", "DATA_SOURCE", stream("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8)));
        service.stage("legacy.doc", "application/msword", "DATA_SOURCE", stream(oleBytes()));
        service.stage("report.docx", "application/octet-stream", "DATA_SOURCE", stream(ooxml(false)));
        var stagedPdf = service.stage("report.pdf", "application/pdf", "DATA_SOURCE", stream(pdf));

        assertThat(stagedPdf.contentType()).isEqualTo("application/pdf");
        verify(storage, org.mockito.Mockito.times(6)).put(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(repository, org.mockito.Mockito.times(6)).insert(any());
    }

    @Test
    void rejectsUnsupportedOrDamagedDataSourcesBeforeStorage() {
        assertThatThrownBy(() -> service.stage("payload.txt", "text/plain", "DATA_SOURCE",
                stream("not supported".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("仅接受");
        assertThatThrownBy(() -> service.stage("damaged.xlsx", "application/octet-stream", "DATA_SOURCE",
                stream("not a workbook".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("OOXML");
        assertThatThrownBy(() -> service.stage("damaged.docx", "application/octet-stream", "DATA_SOURCE",
                stream(zip("[Content_Types].xml", "empty document"))))
                .hasMessageContaining("正文内容");
        assertThatThrownBy(() -> service.stage("damaged.pdf", "application/pdf", "DATA_SOURCE",
                stream("not a PDF".getBytes(StandardCharsets.US_ASCII))))
                .hasMessageContaining("PDF 文件头无效");
        assertThatThrownBy(() -> service.stage("damaged.doc", "application/msword", "DATA_SOURCE",
                stream("{\\rtf1 invalid doc".getBytes(StandardCharsets.US_ASCII))))
                .hasMessageContaining("DOC 文件内容无效");

        verifyNoInteractions(storage, repository);
    }

    @Test
    void shortensLongUtf8DerivedImageKeysWhilePreservingTheOriginalName() {
        var name = "动力学分析和光固化的硅酸盐基硫醇 - 烯的纳米复合材料的物理性能：乙烯基POSS烯的对聚合反应动力学和硫醇 - 三烯丙基醚网络的物理性质的影响.pdf.mineru."
                + "a".repeat(64) + ".jpg";
        assertThat(name.length()).isLessThan(255);
        assertThat(name.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(255);
        var actor = ActorContext.required();
        var staged = service.stageDerived(actor.organizationId(), actor.userId(), name,
                "image/jpeg", "KB_MINERU_RESULT_ASSET", stream(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}));
        var insert = ArgumentCaptor.forClass(FileObjectRepository.NewFileObject.class);
        verify(repository).insert(insert.capture());
        var file = insert.getValue();
        assertThat(file.objectKey()).endsWith("/" + staged.fileId() + "/" + staged.fileId() + ".jpg");
        assertThat(file.originalName()).isEqualTo(name);
        assertThat(staged.originalName()).isEqualTo(name);
        assertThat(staged.contentType()).isEqualTo("image/jpeg");
        verify(storage).put(org.mockito.ArgumentMatchers.eq(file.objectKey()), any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq("image/jpeg"));
    }

    @Test
    void acceptsLongOriginalNamesWithoutChangingTheirDisplayOrDownloadNames() {
        var name = "测试".repeat(50) + ".pdf";
        var staged = service.stage(name, "application/pdf", "DATA_SOURCE", stream("%PDF-1.7\nsource".getBytes(StandardCharsets.US_ASCII)));
        var insert = ArgumentCaptor.forClass(FileObjectRepository.NewFileObject.class);
        verify(repository).insert(insert.capture());
        assertThat(insert.getValue().objectKey()).endsWith("/" + staged.fileId() + ".pdf");
        assertThat(insert.getValue().originalName()).isEqualTo(name);
        assertThat(staged.originalName()).isEqualTo(name);
    }

    @Test
    void preservesTheExact255ByteBoundaryForExistingFilenameBehavior() {
        var name = "中".repeat(83) + "aa.pdf";
        assertThat(name.getBytes(StandardCharsets.UTF_8)).hasSize(255);
        service.stage(name, "application/pdf", "DATA_SOURCE", stream("%PDF-1.7\nsource".getBytes(StandardCharsets.US_ASCII)));
        var insert = ArgumentCaptor.forClass(FileObjectRepository.NewFileObject.class);
        verify(repository).insert(insert.capture());
        assertThat(insert.getValue().objectKey()).endsWith("/" + name);
    }

    private ByteArrayInputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private byte[] oleBytes() {
        return new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    }

    private byte[] ooxml(boolean workbook) {
        var contentType = "[Content_Types].xml";
        var document = workbook ? "xl/workbook.xml" : "word/document.xml";
        return zip(contentType, "<Types/>", document, "<document/>");
    }

    private byte[] zip(String firstEntry, String firstValue) {
        return zip(firstEntry, firstValue, null, null);
    }

    private byte[] zip(String firstEntry, String firstValue, String secondEntry, String secondValue) {
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes)) {
            put(zip, firstEntry, firstValue);
            if (secondEntry != null) put(zip, secondEntry, secondValue);
            zip.finish();
            return bytes.toByteArray();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private void put(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
