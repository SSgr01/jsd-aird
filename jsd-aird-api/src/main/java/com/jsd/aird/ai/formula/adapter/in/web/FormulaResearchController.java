package com.jsd.aird.ai.formula.adapter.in.web;

import com.jsd.aird.ai.formula.api.FormulaResearchFacade;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.platform.web.RequestIdHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai")
public class FormulaResearchController {
    private final FormulaResearchFacade research;

    public FormulaResearchController(FormulaResearchFacade research) { this.research = research; }

    @GetMapping("/formulation-readiness")
    public ApiResponse<FormulaResearchFacade.ReadinessView> readiness(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID categoryId) {
        return success(research.readiness(new FormulaResearchFacade.ReadinessQuery(projectId, categoryId)));
    }

    @PostMapping("/research-requests/parse")
    public ApiResponse<FormulaResearchFacade.ParsedResearchRequest> parse(
            @RequestBody FormulaResearchFacade.ParseRequest request) {
        return success(research.parse(request));
    }

    @PostMapping("/formula-predictions")
    public ResponseEntity<ApiResponse<FormulaResearchFacade.SubmitView>> formulaPrediction(
            @RequestBody FormulaResearchFacade.ResearchRequest request) {
        return accepted(research.submitFormulaPrediction(request));
    }

    @PostMapping("/experiment-optimizations")
    public ResponseEntity<ApiResponse<FormulaResearchFacade.SubmitView>> experimentOptimization(
            @RequestBody FormulaResearchFacade.ResearchRequest request) {
        return accepted(research.submitExperimentOptimization(request));
    }

    @GetMapping("/research-runs/{runId}")
    public ApiResponse<FormulaResearchFacade.ResearchRunView> run(@PathVariable UUID runId) {
        return success(research.run(runId));
    }

    @PostMapping("/research-runs/{runId}/experiment-drafts")
    public ApiResponse<List<FormulaResearchFacade.ExperimentDraftView>> experimentDrafts(
            @PathVariable UUID runId, @RequestBody FormulaResearchFacade.DraftRequest request) {
        return success(research.createExperimentDrafts(runId, request));
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    private <T> ResponseEntity<ApiResponse<T>> accepted(T value) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(success(value));
    }
}
