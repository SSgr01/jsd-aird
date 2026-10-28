package com.jsd.aird.tpl.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TemplateDataImportFacadeClassificationTest {
    @Test void batchOnlyLegacyContractCanBeImportedAsOrdinaryData() throws Exception {
        var json = new ObjectMapper();
        var repository = mock(TemplateRepository.class);
        var workspace = mock(TemplateRepository.TemplateWorkspace.class);
        var org = UUID.randomUUID(); var version = UUID.randomUUID();
        when(repository.findPublishedDataTemplate(org, version)).thenReturn(Optional.of(workspace));
        when(workspace.templateCode()).thenReturn("GENERAL_BATCH");
        when(workspace.format()).thenReturn(TemplateFormat.XLSX);
        when(workspace.schema()).thenReturn(json.createObjectNode());
        when(workspace.mapping()).thenReturn(json.createArrayNode());
        var contract = json.readTree("""
                {"templateUsage":"EXPERIMENT_DATA","experimentImport":{"identities":[{"identityType":"BATCH_NO"}]}}
                """);
        when(repository.findImportContract(org, workspace.versionId())).thenReturn(Optional.of(
                new TemplateRepository.ImportContract(9, 1, "hash", contract)));
        var service = new TemplateDataImportFacadeImpl(repository, null, null, null, null, null, json);

        var result = service.getPublished(org, version);

        assertThat(result.importContractVersion()).isLessThan(9);
        verify(repository, never()).findPublishedDataTemplates(org);
    }
}
