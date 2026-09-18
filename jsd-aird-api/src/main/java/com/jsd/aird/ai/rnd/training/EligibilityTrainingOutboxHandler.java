package com.jsd.aird.ai.rnd.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.OutboxEventHandler;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class EligibilityTrainingOutboxHandler implements OutboxEventHandler {
    private final TrainingService training;
    public EligibilityTrainingOutboxHandler(TrainingService training){this.training=training;}
    @Override public boolean supports(String eventType){return "AI_ELIGIBILITY_COMPLETED".equals(eventType);}
    @Override public void handle(UUID aggregateId, JsonNode payload){training.scheduleFromEligibility(
            UUID.fromString(payload.path("organizationId").asText()),
            UUID.fromString(payload.path("targetId").asText()),true);}
}
