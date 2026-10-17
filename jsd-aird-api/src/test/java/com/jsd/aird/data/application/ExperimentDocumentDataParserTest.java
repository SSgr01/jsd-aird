package com.jsd.aird.data.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.rnd.application.ExperimentImportService;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class ExperimentDocumentDataParserTest {
    private final FileStorageFacade files = mock(FileStorageFacade.class);
    private final ExperimentDocumentDataParser parser =
            new ExperimentDocumentDataParser(files, mock(ExperimentImportService.class), new ObjectMapper());
    private final UUID organizationId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();
    private final List<TemplateDataImportFacade.FieldDefinition> fields = List.of(
            new TemplateDataImportFacade.FieldDefinition("VISCOSITY", "粘度", "TEXT", null,
                    true, false, List.of("黏度"), "/viscosity"),
            new TemplateDataImportFacade.FieldDefinition("HARDNESS", "硬度", "TEXT", null,
                    false, false, List.of(), "/hardness"));

    @Test
    void locatesWordFieldsByLabelRegardlessOfTheirPosition() throws Exception {
        var bytes = word("硬度：62", "黏度：1350");
        when(files.open(organizationId, fileId)).thenReturn(stored(bytes));

        var result = parser.parse(organizationId, fileId, "report.docx", "sha", "DOCX", fields);
        var sheet = result.sheets().getFirst();
        assertThat(sheet.rows().get(1)).containsExactly("1350", "62");
        assertThat(sheet.layoutIr().path("candidates").path("A").path("status").asText()).isEqualTo("MATCHED");
        assertThat(sheet.layoutIr().path("candidates").path("A").path("sourceLocation").asText()).isEqualTo("段落 2");
    }

    @Test
    void marksMissingAndAmbiguousLabelsWithoutTakingAnotherFieldsValue() throws Exception {
        var bytes = word("硬度：62", "硬度：65");
        when(files.open(organizationId, fileId)).thenReturn(stored(bytes));

        var sheet = parser.parse(organizationId, fileId, "report.docx", "sha", "DOCX", fields).sheets().getFirst();
        assertThat(sheet.layoutIr().path("candidates").path("A").path("status").asText()).isEqualTo("MISSING");
        assertThat(sheet.layoutIr().path("candidates").path("B").path("status").asText()).isEqualTo("AMBIGUOUS");
        assertThat(sheet.rows().get(1)).containsExactly("", "");
    }

    private byte[] word(String... paragraphs) throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            for (var text : paragraphs) document.createParagraph().createRun().setText(text);
            document.write(output);
            return output.toByteArray();
        }
    }

    private FileStorageFacade.StoredFile stored(byte[] content) {
        return new FileStorageFacade.StoredFile(fileId, "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                content.length, "sha", new ByteArrayInputStream(content));
    }
}
