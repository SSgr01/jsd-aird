package com.jsd.aird.ai.formula.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.GenerateValidationFoldsRequest;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.GenerateValidationFoldsResponse;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.RecommendRequest;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.RecommendResponse;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.ScoreRequest;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.ScoreResponse;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.SnapshotValidationResponse;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.TrainRequest;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.TrainResponse;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.ValidateSnapshotRequest;

public interface FormulaModelComputeClient {
    SnapshotValidationResponse validate(ValidateSnapshotRequest request);

    GenerateValidationFoldsResponse generateValidationFolds(GenerateValidationFoldsRequest request);

    TrainResponse train(TrainRequest request);

    ScoreResponse score(ScoreRequest request);

    RecommendResponse recommend(RecommendRequest request);

    JsonNode health();
}
