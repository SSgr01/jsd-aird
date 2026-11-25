package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExperimentConversionServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void importEndpointsAcceptEmptyCategoryAndStillRequireSourceAndTemplate() {
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            var id=java.util.UUID.randomUUID();
            assertTrue(validator.validate(new com.jsd.aird.data.adapter.in.web.ExperimentConversionController.Create(id,id,id,null)).isEmpty());
            assertTrue(validator.validate(new com.jsd.aird.data.adapter.in.web.DataController.ExperimentSyncRequest(null,java.util.List.of())).isEmpty());
            var invalid=validator.validate(new com.jsd.aird.data.adapter.in.web.ExperimentConversionController.Create(id,null,null,null));
            assertEquals(java.util.Set.of("sourceFileId","templateVersionId"),invalid.stream().map(v->v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet()));
        }
    }
    @Test void validMachineResultAndConflictDraftBothRemainEligibleOnlyForHumanReview() {
        var task=json.createObjectNode().put("sourceSha256","source").put("kind","test");
        var plan=json.createObjectNode().put("source_sha256","source").put("kind","test");
        var report=json.createObjectNode().put("source_sha256","source").put("output_sha256","result").put("status","validated");
        assertDoesNotThrow(()->ExperimentConversionService.validateResult(task,plan,report,"result"));
        report.put("status","review_required");
        assertDoesNotThrow(()->ExperimentConversionService.validateResult(task,plan,report,"result"));
        for(var status:new String[]{"pending","failed","confirmed","published",""}){
            report.put("status",status);
            assertThrows(ApiException.class,()->ExperimentConversionService.validateResult(task,plan,report,"result"));
        }
    }
    @Test void refuseCrossFileCrossKindAndChangedOutput() {
        var task=json.createObjectNode().put("sourceSha256","source").put("kind","test");
        var plan=json.createObjectNode().put("source_sha256","source").put("kind","test");
        var report=json.createObjectNode().put("source_sha256","source").put("output_sha256","result").put("status","validated");
        assertThrows(ApiException.class,()->ExperimentConversionService.validateResult(task,plan,report,"tampered"));
        plan.put("source_sha256","another");
        assertThrows(ApiException.class,()->ExperimentConversionService.validateResult(task,plan,report,"result"));
        plan.put("source_sha256","source").put("kind","synthesis");
        assertThrows(ApiException.class,()->ExperimentConversionService.validateResult(task,plan,report,"result"));
        plan.put("kind","test");report.put("source_sha256","another");
        assertThrows(ApiException.class,()->ExperimentConversionService.validateResult(task,plan,report,"result"));
    }
}
