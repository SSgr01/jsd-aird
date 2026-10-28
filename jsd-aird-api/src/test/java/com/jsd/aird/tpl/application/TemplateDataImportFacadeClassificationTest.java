package com.jsd.aird.tpl.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TemplateDataImportFacadeClassificationTest {
    @Test void onlyUnifiedExperimentTemplatesAreOfferedWithoutHidingOrdinaryDataTemplates() throws Exception {
        var json = new ObjectMapper();
        var repository = mock(TemplateRepository.class);
        var org = UUID.randomUUID();
        var old = template("OLD_EXPERIMENT");
        var general = template("GENERAL_DATA");
        var test = template("EXP_TEST_UNIFIED");
        var synthesis = template("EXP_SYNTHESIS_UNIFIED");
        var oldId = old.versionId(); var testId = test.versionId(); var synthesisId = synthesis.versionId();
        var experimentContract = new TemplateRepository.ImportContract(9, 1, "hash",
                json.readTree("{\"templateUsage\":\"EXPERIMENT_DATA\",\"experimentImport\":{\"recordMode\":\"SINGLE_FILE\"}}"));
        when(repository.findPublishedDataTemplates(org)).thenReturn(List.of(old, general));
        when(repository.findImportContracts(eq(org), anyList())).thenReturn(java.util.Map.of(oldId, experimentContract));
        var service = new TemplateDataImportFacadeImpl(repository, null, null, null, null, null, json);

        assertThat(service.listPublished(org)).extracting(item -> item.templateCode()).containsExactly("GENERAL_DATA");
        assertThatThrownBy(() -> service.getPublishedExperimentTemplate(org, "OLD_EXPERIMENT"))
                .hasMessageContaining("统一");

        when(repository.findPublishedDataTemplates(org)).thenReturn(List.of(old, general, test, synthesis));
        when(repository.findImportContracts(eq(org), anyList())).thenReturn(java.util.Map.of(
                oldId, experimentContract, testId, experimentContract, synthesisId, experimentContract));
        assertThat(service.listPublished(org)).extracting(item -> item.templateCode())
                .containsExactly("GENERAL_DATA", "EXP_TEST_UNIFIED", "EXP_SYNTHESIS_UNIFIED");
    }

    private TemplateRepository.TemplateListItem template(String code) {
        var item = mock(TemplateRepository.TemplateListItem.class);
        when(item.versionId()).thenReturn(UUID.randomUUID());
        when(item.templateCode()).thenReturn(code);
        when(item.format()).thenReturn(TemplateFormat.XLSX);
        return item;
    }

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
