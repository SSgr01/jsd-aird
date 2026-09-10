package com.jsd.aird.ai.formula.adapter.in.web;

import com.jsd.aird.ai.formula.api.FormulaModelManagementFacade;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.platform.web.RequestIdHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai/formula-models")
public class FormulaModelManagementController {
    private final FormulaModelManagementFacade models;

    public FormulaModelManagementController(FormulaModelManagementFacade models) {
        this.models = models;
    }

    @PostMapping("/builds")
    public ResponseEntity<ApiResponse<FormulaModelManagementFacade.BuildView>> build(
            @RequestBody(required = false) FormulaModelManagementFacade.BuildRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(success(models.startBuild(
                request == null ? new FormulaModelManagementFacade.BuildRequest(null, null, null) : request)));
    }

    @GetMapping("/builds/{buildId}")
    public ApiResponse<FormulaModelManagementFacade.BuildView> build(@PathVariable UUID buildId) {
        return success(models.build(buildId));
    }

    @PostMapping("/{versionId}/targets/{targetKey}/activate")
    public ApiResponse<FormulaModelManagementFacade.ActivationView> activate(
            @PathVariable UUID versionId, @PathVariable String targetKey,
            @RequestBody(required = false) FormulaModelManagementFacade.ActivationRequest request) {
        return success(models.activate(versionId, targetKey,
                request == null ? new FormulaModelManagementFacade.ActivationRequest("") : request));
    }

    @PostMapping("/targets/{targetKey}/rollback")
    public ApiResponse<FormulaModelManagementFacade.ActivationView> rollback(
            @PathVariable String targetKey,
            @RequestBody(required = false) FormulaModelManagementFacade.ActivationRequest request) {
        return success(models.rollback(targetKey,
                request == null ? new FormulaModelManagementFacade.ActivationRequest("") : request));
    }

    @GetMapping("/status")
    public ApiResponse<FormulaModelManagementFacade.StatusView> status() {
        return success(models.status());
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }
}
