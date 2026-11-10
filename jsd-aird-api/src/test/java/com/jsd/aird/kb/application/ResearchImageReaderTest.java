package com.jsd.aird.kb.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade.SourceRef;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository.PublishedContentView;
import com.jsd.aird.ops.api.FileStorageFacade;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.util.Random;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchImageReaderTest {
    final ObjectMapper json = new ObjectMapper();
    final FileStorageFacade storage = mock(FileStorageFacade.class);
    final KnowledgeResultAssetService assets = mock(KnowledgeResultAssetService.class);
    final PublishedContentView publication = mock(PublishedContentView.class);
    final UUID org = UUID.randomUUID(), document = UUID.randomUUID(), version = UUID.randomUUID(), asset = UUID.randomUUID();
    final ResearchImageReader reader = new ResearchImageReader(storage, assets, json, ResearchImageProperties.defaults());
    @TempDir Path directory;

    @BeforeEach void before() {
        ActorContext.set(new Actor(org, UUID.randomUUID(), "test"));
        when(publication.sourceNodes()).thenReturn(List.of());
    }
    @AfterEach void after() { ActorContext.clear(); }
    SourceRef source(int page, boolean hasAsset) {
        var anchor = json.createObjectNode(); var original = anchor.putObject("original").put("page", page);
        if (hasAsset) original.put("assetFileId", asset.toString());
        return new SourceRef("KB_PUBLICATION", document.toString(), version.toString(), UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), "a".repeat(64), anchor, "study.pdf", null);
    }

    @Test void figureBytesAreBoundToTheExactDocumentVersion() throws Exception {
        var bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==");
        when(assets.openForVersion(org,document,version,asset)).thenReturn(new FileStorageFacade.StoredFile(asset,
            "original.png", "image/png", bytes.length, "unused", new ByteArrayInputStream(bytes)));
        var result = reader.read(source(3,true),publication,"auto");
        assertThat(Base64.getDecoder().decode(result.path("business_file").path("content_base64").asText())).isEqualTo(bytes);
        assertThat(result.path("image").path("view").asText()).isEqualTo("figure");
        assertThat(result.path("image").path("page").asInt()).isEqualTo(3);
        assertThat(result.path("business_file").path("sha256").asText()).hasSize(64);
        verify(assets).openForVersion(org,document,version,asset); verifyNoInteractions(storage);
    }

    @Test void absentFigureFallsBackToABoundedRealPdfPage() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) { pdf.addPage(new PDPage()); pdf.save(output); }
        var bytes = output.toByteArray(); var id = UUID.randomUUID();
        when(publication.fileObjectId()).thenReturn(id); when(publication.contentType()).thenReturn("application/pdf");
        when(storage.open(org,id)).thenAnswer(ignored -> new FileStorageFacade.StoredFile(id,"study.pdf","application/pdf",
            bytes.length,"unused",new ByteArrayInputStream(bytes)));
        var result = reader.read(source(1,false),publication,"auto");
        var png = Base64.getDecoder().decode(result.path("business_file").path("content_base64").asText());
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).isNotNull(); assertThat(Math.max(image.getWidth(),image.getHeight())).isLessThanOrEqualTo(2600);
        assertThat(result.path("image").path("view").asText()).isEqualTo("page");
        var detail = reader.read(source(1,false),publication,"detail");
        assertThat(detail.path("image").path("detail").asText()).isEqualTo("detail");
        assertThat(detail.path("image").path("height").asInt()).isGreaterThan(image.getHeight()).isLessThanOrEqualTo(4800);
        assertThat(detail.path("image").path("dpi").asDouble()).isGreaterThan(200);
        assertThatThrownBy(() -> reader.read(source(2,false),publication,"page")).isInstanceOf(ApiException.class);
        verifyNoInteractions(assets);
    }

    @Test void realPdfLargerThan64MbIsReadFromDisk() throws Exception {
        var path = directory.resolve("large.pdf");
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            var padding = pdf.getDocument().createCOSStream();
            try (var stream = padding.createRawOutputStream()) {
                var block = new byte[1024 * 1024];
                for (int i = 0; i < 65; i++) stream.write(block);
            }
            pdf.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("ReaderTestPadding"), padding);
            pdf.save(path.toFile());
        }
        assertThat(Files.size(path)).isGreaterThan(64L * 1024 * 1024);
        var id = UUID.randomUUID();
        when(publication.fileObjectId()).thenReturn(id); when(publication.contentType()).thenReturn("application/pdf");
        when(storage.open(org,id)).thenAnswer(ignored -> new FileStorageFacade.StoredFile(id,"large.pdf","application/pdf",
            Files.size(path),"unused",Files.newInputStream(path)));
        var result = reader.read(source(1,false), publication, "detail");
        assertThat(result.path("image").path("height").asInt()).isGreaterThan(2200);
        assertThat(result.path("business_file").path("bytes").asInt()).isBetween(1, 8 * 1024 * 1024);
    }

    @Test void realImageLargerThan8MbBecomesADecodablePreview() throws Exception {
        var image = new BufferedImage(2400, 1600, BufferedImage.TYPE_INT_RGB); var random = new Random(17);
        for (int y=0; y<image.getHeight(); y++) for (int x=0; x<image.getWidth(); x++) image.setRGB(x,y,random.nextInt());
        var path = directory.resolve("large.png"); ImageIO.write(image,"png",path.toFile()); image.flush();
        assertThat(Files.size(path)).isGreaterThan(8 * 1024 * 1024);
        when(assets.openForVersion(org,document,version,asset)).thenAnswer(ignored -> new FileStorageFacade.StoredFile(asset,
            "large.png","image/png",Files.size(path),"unused",Files.newInputStream(path)));
        var result = reader.read(source(3,true),publication,"auto");
        var bytes = Base64.getDecoder().decode(result.path("business_file").path("content_base64").asText());
        assertThat(bytes.length).isBetween(1,8 * 1024 * 1024);
        var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(decoded.getWidth()).isEqualTo(2400); assertThat(decoded.getHeight()).isEqualTo(1600);
        assertThat(result.path("image").path("lossy").asBoolean()).isTrue();
        assertThat(result.path("image").path("resized").asBoolean()).isFalse();
        verifyNoInteractions(storage);
    }

    @Test void configuredBudgetAndActualFileLengthAreEnforced() throws Exception {
        var small = new ResearchImageReader(storage,assets,json,
            new ResearchImageProperties(1024,1024,1024,10000,10000,72,100,144,200));
        when(assets.openForVersion(org,document,version,asset)).thenAnswer(ignored -> new FileStorageFacade.StoredFile(asset,
            "oversize.png","image/png",1025,"unused",new ByteArrayInputStream(new byte[1025])));
        assertThatThrownBy(() -> small.read(source(1,true),publication,"auto")).isInstanceOf(ApiException.class).hasMessageContaining("预算");
        when(assets.openForVersion(org,document,version,asset)).thenAnswer(ignored -> new FileStorageFacade.StoredFile(asset,
            "changed.png","image/png",10,"unused",new ByteArrayInputStream(new byte[11])));
        assertThatThrownBy(() -> small.read(source(1,true),publication,"auto")).isInstanceOf(ApiException.class).hasMessageContaining("长度");
        verifyNoInteractions(storage);
    }

    @Test void inaccessibleAssetAndOversizeContentNeverFallBackSilently() throws Exception {
        when(assets.openForVersion(org,document,version,asset)).thenThrow(new ApiException(com.jsd.aird.shared.error.ApiErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> reader.read(source(1,true),publication,"auto")).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
        reset(assets);
        when(assets.openForVersion(org,document,version,asset)).thenReturn(new FileStorageFacade.StoredFile(asset,"large.png",
            "image/png",9*1024*1024,"unused",new ByteArrayInputStream(new byte[0])));
        assertThatThrownBy(() -> reader.read(source(1,true),publication,"auto")).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }
}
