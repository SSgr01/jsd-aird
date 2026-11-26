package com.jsd.aird.data.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.DataImportService;
import com.jsd.aird.data.application.DataWorkbookService;
import com.jsd.aird.data.application.ExperimentAssemblyService;
import com.jsd.aird.data.application.port.DataRepository;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;

class DataControllerWriteTest {
    final DataImportService imports=mock(DataImportService.class);
    final DataWorkbookService workbooks=mock(DataWorkbookService.class);
    final ExperimentAssemblyService assembly=mock(ExperimentAssemblyService.class);
    final DataController controller=new DataController(imports,null,workbooks,assembly,null,null,null);
    final UUID id=UUID.randomUUID();

    @Test void saveAcknowledgementDoesNotReadOrRebuildTheSavedWorkbook() {
        var json=new ObjectMapper();
        var request=new DataController.WorkbookDraftRequest("revision",json.createObjectNode(),json.createObjectNode(),json.createArrayNode());
        controller.saveWorkbookDraft(id,request);
        controller.saveExperimentWorkbookDraft(id,request);
        verify(imports).saveWorkbookDraft(id,request.revision(),request.snapshot(),request.templateInstance(),request.structureOperations());
        verify(imports).saveExperimentWorkbookDraft(id,request.revision(),request.snapshot(),request.templateInstance(),request.structureOperations());
        verifyNoInteractions(workbooks,assembly);
    }

    @Test void confirmationReturnsItsTransactionResultWithoutRunningAssemblyAfterCommit() {
        controller.commit(id,new com.jsd.aird.data.adapter.in.web.DataPublicationController.VersionRequest("revision"));
        verify(imports).commitAtRevision(id,"revision");
        verify(imports,never()).get(id);
        verifyNoInteractions(workbooks,assembly);
    }
}
