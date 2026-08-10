package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.tpl.application.port.StandardFieldRepository;
import org.junit.jupiter.api.Test;

class StandardFieldServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StandardFieldService service = new StandardFieldService(mock(StandardFieldRepository.class));

    @Test
    void unmatchedRecognitionFieldBecomesTemplateLocalAndCanBeSaved() {
        var schema = objectMapper.createObjectNode().put("type", "object");
        var model = objectMapper.createObjectNode();
        var fields = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("name", "客户批号")
                .put("requiresStandardConfirmation", true)
                .put("standardMatchStatus", "UNMATCHED"));
        model.set("fields", fields);
        schema.set("x-jsd-field-model", model);

        var normalized = service.normalizeDraftFields(schema);

        var field = normalized.path("x-jsd-field-model").path("fields").get(0);
        assertThat(field.path("fieldOrigin").asText()).isEqualTo("TEMPLATE_LOCAL");
        assertThat(field.path("standardSelectionStatus").asText()).isEqualTo("CUSTOM");
        assertThat(field.path("requiresStandardConfirmation").asBoolean()).isFalse();
        assertDoesNotThrow(() -> service.validateFormalFields(normalized));
    }

    @Test
    void explicitlyRequiredStandardFieldStillBlocksUntilConfirmed() {
        var schema = objectMapper.createObjectNode().put("type", "object");
        var model = objectMapper.createObjectNode();
        model.set("fields", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("name", "统一订单号")
                .put("standardRequired", true)
                .put("requiresStandardConfirmation", true)
                .put("fieldOrigin", "PENDING_STANDARD")));
        schema.set("x-jsd-field-model", model);

        assertThrows(ApiException.class, () -> service.validateFormalFields(schema));
    }
}
