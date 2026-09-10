package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade.TargetObservation;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.TargetSpec;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.ValueType;
import com.jsd.aird.ai.formula.application.port.FormulaModelRepository;
import com.jsd.aird.ai.formula.application.port.FormulaModelRepository.ActivationRow;
import com.jsd.aird.ai.formula.application.port.FormulaModelRepository.FeedbackRow;
import com.jsd.aird.ai.formula.application.port.FormulaModelRepository.ModelTargetRow;
import com.jsd.aird.ai.formula.application.port.FormulaModelRepository.MonitoringScope;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Production safety monitor. It never trains, activates or automatically rolls
 * back a model: daily it pauses a drifting target for human review, and weekly
 * it only emits a rebuild recommendation when enough eligible rows accumulate.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class FormulaModelMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(FormulaModelMaintenanceService.class);
    static final int FEEDBACK_WINDOW = 10;
    static final BigDecimal DRIFT_MULTIPLIER = new BigDecimal("1.5");

    private final FormulaModelRepository repository;
    private final FormulaModelTaskProfileRegistry profiles;
    private final ResearchCaseLoader cases;

    public FormulaModelMaintenanceService(FormulaModelRepository repository,
                                          FormulaModelTaskProfileRegistry profiles,
                                          ResearchCaseLoader cases) {
        this.repository = repository;
        this.profiles = profiles;
        this.cases = cases;
    }

    @Scheduled(cron = "${app.ai.formula-model.feedback-cron:0 30 2 * * *}")
    public void monitorFeedback() {
        for (var scope : repository.monitoringScopes()) {
            withActor(scope, () -> {
                inspectFeedback(scope);
                return null;
            });
        }
    }

    @Scheduled(cron = "${app.ai.formula-model.rebuild-check-cron:0 0 2 * * SUN}")
    public void checkRebuildReadiness() {
        for (var scope : repository.monitoringScopes()) {
            withActor(scope, () -> {
                var snapshot = repository.latestReadySnapshot(scope.organizationId(), scope.taskProfileId());
                if (snapshot.isEmpty()) return null;
                var collection = cases.load(profiles.production().code(), null, null);
                var currentRows = collection.cases().stream().map(ResearchCaseLoader.ResearchCaseView::analysisRow)
                        .filter(row -> profiles.production().targets().stream().anyMatch(target -> {
                            var decision = row.modelEligibilityByTarget().get(target.targetKey());
                            return decision != null && decision.eligible();
                        })).map(row -> row.analysisRowId()).distinct().count();
                var previousRows = snapshot.get().rowCount();
                var added = currentRows - previousRows;
                var recommended = added >= 10 || (previousRows > 0 && currentRows >= Math.ceil(previousRows * 1.20d));
                log.info("formula_model_rebuild_check organizationId={} taskProfileId={} snapshotId={} "
                                + "snapshotRows={} currentEligibleRows={} addedRows={} recommended={}",
                        scope.organizationId(), scope.taskProfileId(), snapshot.get().id(), previousRows,
                        currentRows, added, recommended);
                return null;
            });
        }
    }

    void inspectFeedback(MonitoringScope scope) {
        var collection = cases.load(profiles.production().code(), null, null);
        var byExperiment = collection.cases().stream().collect(Collectors.groupingBy(
                item -> item.analysisRow().experimentId()));
        for (var activation : repository.activeTargets(scope.organizationId(), scope.taskProfileId())) {
            try {
                var target = profileTarget(activation.targetKey());
                var modelTarget = repository.modelTargets(scope.organizationId(), activation.modelVersionId()).stream()
                        .filter(item -> item.targetKey().equals(activation.targetKey())).findFirst().orElse(null);
                if (modelTarget == null || modelTarget.primaryMetricValue() == null) continue;
                var feedback = repository.recentModelFeedback(scope.organizationId(), activation.modelVersionId(),
                        activation.targetKey(), 50);
                var errors = feedbackErrors(target, modelTarget, feedback, byExperiment);
                if (errors.size() < FEEDBACK_WINDOW) continue;
                var live = errors.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(errors.size()), MathContext.DECIMAL64);
                var limit = modelTarget.primaryMetricValue().multiply(DRIFT_MULTIPLIER);
                if (live.compareTo(limit) > 0) {
                    repository.pauseModelForQualityReview(scope.organizationId(), activation.id(),
                            "MODEL_QUALITY_DRIFT: last10=" + live + ", validation="
                                    + modelTarget.primaryMetricValue());
                    log.warn("formula_model_feedback_drift organizationId={} targetKey={} modelVersionId={} "
                                    + "feedbackMetric={} validationMetric={} action=PAUSE_PENDING_REVIEW",
                            scope.organizationId(), activation.targetKey(), activation.modelVersionId(), live,
                            modelTarget.primaryMetricValue());
                }
            } catch (RuntimeException exception) {
                log.warn("formula_model_feedback_check_failed organizationId={} targetKey={} modelVersionId={}",
                        scope.organizationId(), activation.targetKey(), activation.modelVersionId(), exception);
            }
        }
    }

    private List<BigDecimal> feedbackErrors(TargetSpec target, ModelTargetRow modelTarget,
                                            List<FeedbackRow> feedback,
                                            Map<UUID, List<ResearchCaseLoader.ResearchCaseView>> byExperiment) {
        var errors = new ArrayList<BigDecimal>();
        for (var item : feedback.stream().sorted(Comparator.comparing(FeedbackRow::linkedAt).reversed()).toList()) {
            var matching = byExperiment.getOrDefault(item.experimentId(), List.of()).stream()
                    .map(view -> view.analysisRow().targets().get(target.targetKey()))
                    .filter(value -> value != null && "PARSED".equals(value.status()))
                    .findFirst().orElse(null);
            var error = error(target.valueType(), modelTarget, item.estimate(), matching);
            if (error != null) errors.add(error);
            if (errors.size() == FEEDBACK_WINDOW) break;
        }
        return List.copyOf(errors);
    }

    private BigDecimal error(ValueType type, ModelTargetRow modelTarget, JsonNode estimate,
                             TargetObservation actual) {
        if (estimate == null || actual == null || !"EXACT".equals(actual.observationType())) return null;
        return switch (type) {
            case CONTINUOUS -> continuousError(modelTarget, estimate, actual);
            case ORDINAL -> ordinalError(estimate, actual);
            case BINARY, CATEGORICAL -> classificationError(estimate, actual);
            case CENSORED_COUNT -> null;
        };
    }

    private BigDecimal continuousError(ModelTargetRow modelTarget, JsonNode estimate, TargetObservation actual) {
        if (!estimate.path("pointEstimate").isNumber() || actual.numericValue() == null) return null;
        var mae = decimal(modelTarget.result().path("metrics").path("mae"));
        var nmae = decimal(modelTarget.result().path("metrics").path("nmae"));
        if (mae == null || nmae == null || nmae.signum() <= 0) return null;
        var scale = mae.divide(nmae, MathContext.DECIMAL64);
        if (scale.signum() <= 0) return null;
        return estimate.path("pointEstimate").decimalValue().subtract(actual.numericValue()).abs()
                .divide(scale, MathContext.DECIMAL64);
    }

    private BigDecimal ordinalError(JsonNode estimate, TargetObservation actual) {
        var predicted = grade(estimate.path("predictedClass").asText(null));
        var observed = grade(actual.ordinalValue());
        return predicted == null || observed == null ? null : predicted.subtract(observed).abs();
    }

    private BigDecimal classificationError(JsonNode estimate, TargetObservation actual) {
        if (actual.ordinalValue() == null) return null;
        var probability = estimate.path("classProbabilities").path(actual.ordinalValue());
        if (!probability.isNumber()) return null;
        var bounded = Math.max(1e-12d, Math.min(1d, probability.asDouble()));
        return BigDecimal.valueOf(-Math.log(bounded));
    }

    private TargetSpec profileTarget(String targetKey) {
        return profiles.production().targets().stream().filter(item -> item.targetKey().equals(targetKey))
                .findFirst().orElseThrow();
    }

    private BigDecimal decimal(JsonNode node) {
        return node != null && node.isNumber() ? node.decimalValue() : null;
    }

    private BigDecimal grade(String value) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.strip().toUpperCase(Locale.ROOT);
        if ("H".equals(normalized)) return BigDecimal.ONE;
        if (!normalized.matches("\\d+(?:\\.\\d+)?H")) return null;
        return new BigDecimal(normalized.substring(0, normalized.length() - 1));
    }

    private <T> T withActor(MonitoringScope scope, Supplier<T> action) {
        var previous = ActorContext.current();
        ActorContext.set(new Actor(scope.organizationId(), scope.actorId(), "formula-model-maintenance", "ADMIN"));
        try {
            return action.get();
        } catch (RuntimeException exception) {
            log.warn("formula_model_maintenance_scope_failed organizationId={} taskProfileId={}",
                    scope.organizationId(), scope.taskProfileId(), exception);
            return null;
        } finally {
            if (previous == null) ActorContext.clear(); else ActorContext.set(previous);
        }
    }
}
