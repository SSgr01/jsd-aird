package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.rnd.api.ExperimentDraftFacade;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.ExperimentModels.Summary;
import com.jsd.aird.shared.excel.WorkbookReviewContent;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportedDraftReviewTest {
    final ObjectMapper json=new ObjectMapper();
    final ExperimentRepository repository=mock(ExperimentRepository.class);
    final ExperimentDraftApplicationFacade service=new ExperimentDraftApplicationFacade(repository,
            new ExperimentEditModelNormalizer(json),json,null);

    ObjectNode model() {
        var model=json.createObjectNode().put("sourceImportJobId",UUID.randomUUID().toString());
        model.putObject("documentSnapshot").putObject("sheets").putObject("s")
                .putObject("cellData").putObject("0").putObject("0").put("v","已核对值");
        return model;
    }
    ObjectNode proof(ObjectNode model) {
        return json.createObjectNode().put("importJobId",model.path("sourceImportJobId").asText())
                .put("revision","review-3").put("confirmedBy",UUID.randomUUID().toString()).put("confirmedAt","2026-11-25T08:00:00Z")
                .put("dataHash",new JsonCanonicalizer(json).hash(WorkbookReviewContent.workbook(json,model.path("documentSnapshot"))));
    }
    ObjectNode create(ObjectNode model,ObjectNode proof) {
        when(repository.create(any())).thenReturn(mock(Summary.class));
        service.createImportedDraft(new ExperimentDraftFacade.ImportedDraftCommand(UUID.randomUUID(),UUID.randomUUID(),"tester",
                "实验",null,"EXCEL_IMPORT",null,null,null,"tester",null,UUID.randomUUID(),null,json.createObjectNode(),model,proof));
        var capture=ArgumentCaptor.forClass(ExperimentRepository.Create.class);verify(repository).create(capture.capture());
        return (ObjectNode)capture.getValue().editModel();
    }
    @Test void carriesConfirmedSourceIntoTheFinalDraftFingerprint() {
        var model=model();var result=create(model,proof(model));
        assertThat(result.at("/importDataReview/confirmed").asBoolean()).isTrue();
        assertThat(result.at("/importDataReview/confirmedRevision").asInt()).isEqualTo(1);
        assertThat(result.at("/importDataReview/dataHash").asText()).isEqualTo(new JsonCanonicalizer(json).hash(
                WorkbookReviewContent.workbook(json,result.path("documentSnapshot"))));
    }
    @Test void changedSourceDoesNotInheritConfirmation() {
        var model=model();var proof=proof(model);((ObjectNode)model.at("/documentSnapshot/sheets/s/cellData/0/0")).put("v","已修改");
        assertThat(create(model,proof).at("/importDataReview/confirmed").asBoolean()).isFalse();
    }
    @Test void unreviewedImportAndDifferentJobDoNotInheritConfirmation() {
        var model=model();var proof=proof(model);proof.put("importJobId",UUID.randomUUID().toString());
        assertThat(create(model,proof).at("/importDataReview/confirmed").asBoolean()).isFalse();
    }
}
