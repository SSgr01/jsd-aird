package com.jsd.aird.ai.rnd.prediction;

import com.fasterxml.jackson.databind.JsonNode;


public final class PredictionContracts {
    private PredictionContracts() { }

    public record PredictionHttpResult(int status, JsonNode body) { }
}
