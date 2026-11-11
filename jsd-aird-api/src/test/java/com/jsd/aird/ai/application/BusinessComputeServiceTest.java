package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.rnd.api.AiRndContracts.PredictionRequest;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ai.rnd.modeling.ModelingWorkbenchService;
import com.jsd.aird.ai.rnd.modeling.WorkbenchModelRegistrationService;
import com.jsd.aird.ai.rnd.prediction.*;
import com.jsd.aird.ai.rnd.research.*;
import com.jsd.aird.ai.rnd.optimization.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessComputeServiceTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final BusinessDataService data=mock(BusinessDataService.class);
    private final BusinessExistingData existing=mock(BusinessExistingData.class);
    private final PredictionService predictions=mock(PredictionService.class);
    private final FormulaDesignService formulas=mock(FormulaDesignService.class);
    private final OptimizationService optimizations=mock(OptimizationService.class);
    private final PredictionRepository predictionRows=mock(PredictionRepository.class);
    private final FormulaDesignRepository formulaRows=mock(FormulaDesignRepository.class);
    private final OptimizationRepository optimizationRows=mock(OptimizationRepository.class);
    private final Actor actor=new Actor(id(100),id(101),"isolated-compute-acceptance");
    private final String key="a".repeat(64);
    private BusinessComputeService service;
    private static UUID id(long n){return new UUID(0,n);}
    private ObjectNode object(String text)throws Exception{return (ObjectNode)json.readTree(text);}
    @BeforeEach void setup(){
        ActorContext.set(actor);service=new BusinessComputeService(data,existing,predictions,formulas,optimizations,predictionRows,formulaRows,optimizationRows,json,null,null,null,null,null,null,null);
        when(data.candidate(any())).thenAnswer(call->{com.jsd.aird.shared.business.BusinessRecordView row=call.getArgument(0);return new BusinessDataService(List.of(),List.of(),existing,json).candidate(row);});
    }
    @AfterEach void clear(){ActorContext.clear();}
    @Test void sourceAuthorizationChecksEachVersionOnceWithoutLoadingWorkbooks()throws Exception {
        var source=mock(com.jsd.aird.rnd.api.ResearchExperimentFacade.class);service.experimentAccess(source);
        var snapshot=dataset();((com.fasterxml.jackson.databind.node.ArrayNode)snapshot.path("rows")).add(snapshot.path("rows").path(0).deepCopy());
        when(optimizations.historyDataset(id(1))).thenReturn(snapshot);
        service.invoke("get_compute_context",json.createObjectNode().put("kind","EXPERIMENT_OPTIMIZATION").put("dataset_id",id(1).toString()));
        verify(source).requireReadableVersion(actor.organizationId(),id(2),id(3));
        verify(source).requireReadableVersion(actor.organizationId(),id(4),id(5));
        verifyNoInteractions(existing);
    }
    @Test void failedSubmissionCanBeClosedAfterTakingItsLockAndCannotBeReplayed()throws Exception {
        var jdbc=mock(JdbcTemplate.class);var audit=mock(com.jsd.aird.ops.application.port.AuditLogFacade.class);
        service=new BusinessComputeService(data,existing,predictions,formulas,optimizations,predictionRows,formulaRows,optimizationRows,json,null,null,null,null,jdbc,null,audit);
        var request=json.createObjectNode().put("tool","run_experiment_optimization").put("operation_key",key).put("close_uncommitted",true);
        var receipt=service.receipt(request);
        assertThat(receipt.path("status").asText()).isEqualTo("FAILED");
        assertThat(receipt.path("result").path("error").asText()).isEqualTo("COMPUTE_NOT_COMMITTED");
        verify(jdbc).queryForList(contains("pg_advisory_xact_lock"),any(Object[].class));
        verify(audit).append(eq(actor.organizationId()),eq(actor.userId()),eq("AI_ASSISTANT_COMPUTE_CLOSED"),anyString(),any(),eq(receipt));
        doReturn(List.of(receipt)).when(jdbc).query(anyString(),any(RowMapper.class),eq(actor.organizationId()),eq(actor.userId()),eq(key),eq("run_experiment_optimization"));
        assertThatThrownBy(()->service.invoke("run_experiment_optimization",args("{\"mode\":\"HISTORICAL_GP_BO\"}")))
            .isInstanceOf(ApiException.class).hasMessageContaining("已关闭");
        verifyNoInteractions(optimizations);
    }
    private ObjectNode args(String request)throws Exception{var out=json.createObjectNode().put("operation_key",key);out.set("request",object(request));return out;}

    @Test void nativePredictionContractPreservesModelVersionNumbersAndUnits()throws Exception{
        var context=object("{\"requiredInputs\":[{\"code\":\"temperature\",\"unit\":\"°C\",\"valueType\":\"NUMBER\"}],\"selected\":[{\"targetId\":\""+id(1)+"\",\"modelVersionId\":\""+id(2)+"\"}]}");
        when(predictions.context(List.of(id(1)))).thenReturn(context);
        var query=json.createObjectNode().put("kind","PREDICTION");query.putArray("target_ids").add(id(1).toString());
        var result=service.invoke("get_compute_context",query);
        assertThat(result.path("requiredInputs").path(0).path("unit").asText()).isEqualTo("°C");
        assertThat(result.path("request_contract").path("fields").path("targets").asText()).contains("expectedModelVersionId");
        when(predictions.predict(any(),eq(key))).thenReturn(new PredictionContracts.PredictionHttpResult(202,object("{\"predictionId\":\""+id(3)+"\",\"executionStatus\":\"RUNNING\"}")));
        var request=args("{\"inputs\":{\"temperature\":25,\"mass\":0},\"targets\":[{\"targetId\":\""+id(1)+"\",\"expectedModelVersionId\":\""+id(2)+"\"}]}");
        var accepted=service.invoke("run_prediction",request);var captured=ArgumentCaptor.forClass(PredictionRequest.class);
        verify(predictions).predict(captured.capture(),eq(key));
        assertThat(captured.getValue().inputs().get("mass").isNumber()).isTrue();
        assertThat(captured.getValue().targets().getFirst().expectedModelVersionId()).isEqualTo(id(2));
        assertThat(accepted.path("run_id").asText()).isEqualTo(id(3).toString());
        assertThat(accepted.path("result_semantics").asText()).contains("不是实测");
    }
    @Test void formulaSubmissionUsesExistingServiceAndReturnsDurablePollIdentity()throws Exception{
        when(formulas.submit(any(),eq(key))).thenReturn(new FormulaDesignContracts.RunAccepted(id(1),id(2),FormulaDesignContracts.ExecutionStatus.QUEUED,Map.of(id(3),id(4)),1000));
        var args=args("{\"typedGoals\":[{\"targetId\":\""+id(3)+"\",\"expectedModelVersionId\":\""+id(4)+"\"}],\"candidateCount\":2}");
        var accepted=service.invoke("run_formula_design",args);
        verify(formulas).submit(args.path("request"),key);
        assertThat(accepted.path("modelBindings").path(id(3).toString()).asText()).isEqualTo(id(4).toString());
        assertThat(accepted.path("executionStatus").asText()).isEqualTo("QUEUED");
        assertThat(accepted.path("poll_tool").asText()).isEqualTo("get_compute_result");
    }
    private ObjectNode dataset()throws Exception{
        return object("{\"id\":\""+id(1)+"\",\"name\":\"隔离样品集\",\"sourceDocumentCount\":2,\"columns\":[{\"code\":\"mass\",\"filledCount\":2,\"unit\":\"g\"},{\"code\":\"secret\",\"filledCount\":1}],\"rows\":[{\"id\":\"row-a\",\"sourceExperimentId\":\""+id(2)+"\",\"versionId\":\""+id(3)+"\",\"values\":{\"mass\":0}},{\"id\":\"row-b\",\"sourceExperimentId\":\""+id(4)+"\",\"versionId\":\""+id(5)+"\",\"values\":{\"secret\":999}}],\"issues\":[{\"rowId\":\"row-b\",\"rawValue\":\"未授权秘密\"}]}" );
    }
    @Test void datasetPresentationFiltersRowsColumnsIssuesAndCountsAfterAuthorization()throws Exception{
        when(optimizations.historyDataset(id(1))).thenReturn(dataset());
        when(existing.invoke(eq("business_read"),any())).thenAnswer(call->{ObjectNode args=call.getArgument(1);if(args.path("ref").path("id").asText().equals(id(4).toString()))throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED);return json.createObjectNode();});
        var request=json.createObjectNode().put("kind","EXPERIMENT_OPTIMIZATION").put("dataset_id",id(1).toString());
        var result=service.invoke("get_compute_context",request);
        assertThat(result.path("rowCount").asInt()).isEqualTo(1);
        assertThat(result.path("columns").path(0).path("filledCount").asInt()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("999","secret","未授权秘密","sourceDocumentCount");
        assertThat(result.path("request_contract").path("fields").toString()).contains("xColumns","yColumn");
        var submission=args("{\"mode\":\"HISTORICAL_GP_BO\",\"datasetIds\":[\""+id(1)+"\"],\"selectedRowIdsByDataset\":{\""+id(1)+"\":[\"row-a\"]}}");
        assertThatThrownBy(()->service.invoke("run_experiment_optimization",submission)).hasMessageContaining("整个数据集");
        verify(optimizations,never()).submit(any(),any());
    }
    @Test void optimizationFreezesTheExactAuthorizedSnapshotInsteadOfRereadingUnpinnedData()throws Exception{
        var raw=dataset();when(optimizations.historyDataset(id(1))).thenReturn(raw);
        when(optimizations.submit(any(),eq(key))).thenReturn(new OptimizationContracts.OptimizationAccepted(id(8),id(9),"QUEUED",Map.of(),1000));
        var request=args("{\"mode\":\"HISTORICAL_GP_BO\",\"datasetIds\":[\""+id(1)+"\"],\"selectedRowIdsByDataset\":{\""+id(1)+"\":[\"row-a\"]},\"xColumns\":[\"mass\"],\"yColumn\":\"result\"}");
        service.invoke("run_experiment_optimization",request);
        verify(optimizations).submit(argThat(body->body.path("expectedDatasetHashes").path(id(1).toString()).asText().equals(new ConfigurationHashing(json).hash(raw))),eq(key));
        assertThat(request.path("request").has("expectedDatasetHashes")).isFalse();
    }
    @Test void computeContextPagesAuthorizedRowsWithoutEditorOrUnselectedValuePayloads()throws Exception{
        var raw=dataset();when(optimizations.historyDataset(id(1))).thenReturn(raw);
        var request=json.createObjectNode().put("kind","EXPERIMENT_OPTIMIZATION").put("dataset_id",id(1).toString()).put("limit",1);
        request.putArray("column_codes").add("mass");
        var first=service.invoke("get_compute_context",request);
        assertThat(first.path("rowCount").asInt()).isEqualTo(2);
        assertThat(first.path("rows").size()).isEqualTo(1);
        assertThat(first.path("next_offset").asInt()).isEqualTo(1);
        assertThat(first.path("rows").path(0).at("/values/mass").asInt()).isZero();
        var second=service.invoke("get_compute_context",request.put("offset",1));
        assertThat(second.path("rows").path(0).path("id").asText()).isEqualTo("row-b");
        assertThat(second.has("next_offset")).isFalse();
        assertThat(second.path("rows").path(0).path("values").isEmpty()).isTrue();
    }
    @Test void unknownReceiptsNeverResubmitAndAcceptanceDoesNotClaimComputeSuccess()throws Exception{
        when(predictionRows.byKey(actor.organizationId(),actor.userId(),key)).thenReturn(Optional.empty());
        var request=json.createObjectNode().put("tool","run_prediction").put("operation_key",key);
        assertThat(service.receipt(request).path("status").asText()).isEqualTo("UNKNOWN");
        verifyNoInteractions(predictions,formulas,optimizations);
        var row=mock(PredictionRepository.PredictionRow.class);when(row.id()).thenReturn(id(1));
        when(predictionRows.byKey(actor.organizationId(),actor.userId(),key)).thenReturn(Optional.of(row));
        var receipt=service.receipt(request);
        assertThat(receipt.path("result").path("executionStatus").asText()).isEqualTo("ACCEPTED");
        assertThat(receipt.path("result").path("id").asText()).isEqualTo(id(1).toString());
        assertThatThrownBy(()->service.invoke("run_prediction",args("{\"inputs\":{}}" ).put("operation_key","model-chosen-key"))).hasMessageContaining("操作身份");
        verifyNoInteractions(predictions);
    }
    @Test void resultReadsReauthorizeAndSeparatePendingFromStableEvidence()throws Exception{
        when(predictions.record(id(1))).thenReturn(new PredictionContracts.PredictionHttpResult(202,object("{\"executionStatus\":\"RUNNING\"}")));
        assertThat(service.result("PREDICTION",id(1)).path("candidates").size()).isZero();
        when(predictions.record(id(1))).thenReturn(new PredictionContracts.PredictionHttpResult(200,object("{\"executionStatus\":\"SUCCEEDED\",\"value\":0}")));
        var result=service.result("PREDICTION",id(1));var source=result.path("candidates").path(0).path("source");
        service.validateSource(source,null);
        when(predictions.record(id(1))).thenReturn(new PredictionContracts.PredictionHttpResult(200,object("{\"executionStatus\":\"SUCCEEDED\",\"value\":1}")));
        assertThatThrownBy(()->service.validateSource(source,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.errorCode()).isEqualTo(ApiErrorCode.VERSION_CONFLICT));
        doThrow(new ApiException(ApiErrorCode.DATA_SCOPE_DENIED)).when(data).require(eq("AI_PREDICTION"),eq(id(1)),eq("READ"),any());
        clearInvocations(predictions);
        assertThatThrownBy(()->service.result("PREDICTION",id(1))).isInstanceOf(ApiException.class);
        verifyNoInteractions(predictions);
    }
    @Test void activationDoesNotInvalidateAlreadyRetrievedTrainingEvidence()throws Exception{
        var modeling=mock(ModelingWorkbenchService.class);var registration=mock(WorkbenchModelRegistrationService.class);
        service=new BusinessComputeService(data,existing,predictions,formulas,optimizations,predictionRows,formulaRows,optimizationRows,json,modeling,registration,null,null,null,null,null);
        when(modeling.run(id(1))).thenReturn(object("{\"status\":\"SUCCEEDED\",\"datasetSnapshot\":{\"rows\":[],\"columns\":[]},\"result\":{\"rowCount\":69,\"metrics\":{\"mae\":0.1}}}"));
        var before=service.result("MODEL_TRAINING",id(1)).path("candidates").path(0).path("source");
        when(registration.registration(actor.organizationId(),id(1))).thenReturn(object("{\"status\":\"ACTIVE\",\"version\":1}"));
        var after=service.result("MODEL_TRAINING",id(1));
        assertThat(after.at("/result/registration/status").asText()).isEqualTo("ACTIVE");
        assertThat(after.at("/candidates/0/source")).isEqualTo(before);
        service.validateSource(before,null);
    }
    @Test void optimizationCanReuseAnExplicitTrainingSelectionAndRechecksItsSourceAccess()throws Exception{
        var modeling=mock(ModelingWorkbenchService.class);
        service=new BusinessComputeService(data,existing,predictions,formulas,optimizations,predictionRows,formulaRows,optimizationRows,json,modeling,null,null,null,null,null,null);
        var run=object("{\"status\":\"SUCCEEDED\",\"rowCount\":69,\"selectedRowCount\":69,\"datasetSnapshot\":{\"rows\":[],\"columns\":[]},\"xColumns\":[{\"code\":\"resin\"}],\"yColumn\":{\"code\":\"gloss\"},\"selectedRowIdsByDataset\":{\""+id(2)+"\":[\"row-a\",\"row-b\"]}}");
        when(modeling.run(id(1))).thenReturn(run);
        var args=json.createObjectNode().put("kind","EXPERIMENT_OPTIMIZATION").put("training_run_id",id(1).toString());
        var context=service.invoke("get_compute_context",args);
        assertThat(context.at("/request_draft/trainingRunId").asText()).isEqualTo(id(1).toString());
        assertThat(context.path("request_draft").has("selectedRowIdsByDataset")).isFalse();
        when(optimizations.historyDataset(id(2))).thenReturn(dataset());
        when(optimizations.submit(any(),eq(key))).thenReturn(new OptimizationContracts.OptimizationAccepted(id(3),id(4),"QUEUED",Map.of(),1000));
        var submission=args("{\"trainingRunId\":\""+id(1)+"\",\"formulaTotal\":100,\"direction\":\"MAXIMIZE\"}");
        service.invoke("run_experiment_optimization",submission);
        verify(optimizations).submit(argThat(body->body.path("selectedRowIdsByDataset").equals(run.path("selectedRowIdsByDataset"))
            &&body.path("xColumns").path(0).asText().equals("resin")&&body.path("yColumn").asText().equals("gloss")&&body.path("formulaTotal").asInt()==100),eq(key));
        verify(data).require("AI_MODEL_TRAINING",id(1),"READ",args);
        run.put("status","RUNNING");
        assertThatThrownBy(()->service.invoke("get_compute_context",args)).hasMessageContaining("等待");
    }
}
