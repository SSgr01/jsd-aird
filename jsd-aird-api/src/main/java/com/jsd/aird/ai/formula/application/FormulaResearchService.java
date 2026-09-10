package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.api.FormulaResearchFacade;
import com.jsd.aird.ai.formula.application.port.ResearchRepository;
import com.jsd.aird.ai.formula.application.port.ResearchRepository.NewRun;
import com.jsd.aird.ai.formula.application.port.ResearchRequestInterpreter;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.rnd.api.ExperimentDraftFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class FormulaResearchService implements FormulaResearchFacade {
    private static final Logger log = LoggerFactory.getLogger(FormulaResearchService.class);
    private final ResearchRepository repository;
    private final ResearchCaseLoader cases;
    private final ResearchComputationService computation;
    private final UvpuResearchProfile profile;
    private final UvpuAnalysisProfile analysisProfile;
    private final UvpuExperimentDraftProfile experimentDraftProfile;
    private final UvpuExperimentDraftTemplateFiller experimentTemplateFiller;
    private final TemplateDataImportFacade templates;
    private final ExperimentDraftFacade experimentDrafts;
    private final OpsAsyncFacade jobs;
    private final AuthorizationService authorization;
    private final JsonCanonicalizer canonicalizer;
    private final ObjectMapper json;
    private final ResearchRequestInterpreter interpreter;

    public FormulaResearchService(ResearchRepository repository, ResearchCaseLoader cases,
                                  ResearchComputationService computation, UvpuResearchProfile profile,
                                  UvpuAnalysisProfile analysisProfile,
                                  UvpuExperimentDraftProfile experimentDraftProfile,
                                  UvpuExperimentDraftTemplateFiller experimentTemplateFiller,
                                  TemplateDataImportFacade templates,
                                  ExperimentDraftFacade experimentDrafts, OpsAsyncFacade jobs,
                                  AuthorizationService authorization, JsonCanonicalizer canonicalizer,
                                  ObjectMapper json, ResearchRequestInterpreter interpreter) {
        this.repository = repository;
        this.cases = cases;
        this.computation = computation;
        this.profile = profile;
        this.analysisProfile = analysisProfile;
        this.experimentDraftProfile = experimentDraftProfile;
        this.experimentTemplateFiller = experimentTemplateFiller;
        this.templates = templates;
        this.experimentDrafts = experimentDrafts;
        this.jobs = jobs;
        this.authorization = authorization;
        this.canonicalizer = canonicalizer;
        this.json = json;
        this.interpreter = interpreter;
    }

    @Override
    public ReadinessView readiness(ReadinessQuery query) {
        requireAiUse();
        var request = query == null ? new ReadinessQuery(null, null) : query;
        var collection = cases.load(profile.definition().taskProfileCode(), request.projectId(), request.categoryId());
        var targets = profile.definition().targets().stream().map(target -> {
            var count = collection.cases().stream().filter(item -> {
                var eligible = item.analysisRow().caseEligibilityByTarget().get(target.targetKey());
                return eligible != null && eligible.eligible();
            }).count();
            var registeredByT05 = collection.cases().stream()
                    .anyMatch(item -> item.analysisRow().caseEligibilityByTarget().containsKey(target.targetKey()));
            var status = !registeredByT05 ? "REGISTERED_NOT_READY" : count == 0 ? "NO_ELIGIBLE_CASES"
                    : count < 5 ? "LIMITED_CASES" : "READY_FOR_FULL_STATISTICS";
            var message = !registeredByT05 ? "已登记，正式分析定义或真实数据尚未就绪"
                    : count == 0 ? "暂无可比较的正式实验"
                    : count < 5 ? "已有少量案例，可展示证据但不提供完整统计" : "可提供完整案例统计";
            var ordinalLabels = "ORDINAL".equals(target.valueType())
                    ? (target.name().contains("附着力")
                        ? List.of("0B", "1B", "2B", "3B", "4B", "5B")
                        : List.of("H", "2H", "3H", "4H"))
                    : List.<String>of();
            var positiveClass = "BINARY".equals(target.valueType()) ? "OK" : null;
            return new TargetReadiness(target.targetKey(), target.name(), target.valueType(), target.unit(),
                    target.direction(), count, status, message, ordinalLabels, positiveClass, 0.50d);
        }).toList();
        var baselines = collection.cases().stream().sorted(Comparator
                        .comparing(ResearchCaseLoader.ResearchCaseView::experimentDate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(item -> item.analysisRow().analysisRowId()))
                .limit(50).map(item -> new BaselineOption(item.analysisRow().analysisRowId(),
                        item.analysisRow().experimentId(), item.analysisRow().experimentVersionId(),
                        item.analysisRow().experimentNo(), item.analysisRow().sourceIdentity(), item.experimentDate(),
                        item.title(), formulaSummary(item.analysisRow().formula()))).toList();
        var materials = analysisProfile.definition().materials().stream()
                .map(item -> new MaterialOption(item.code(), item.role(), item.modelAllowed())).toList();
        return new ReadinessView(profile.definition().taskProfileCode(), collection.analysisProfileVersion(),
                "CASE_STAT_RULE", targets, baselines, materials);
    }

    @Override
    public ParsedResearchRequest parse(ParseRequest request) {
        requireAiUse();
        var text = request == null || request.text() == null ? "" : request.text().strip();
        var runType = request == null ? "FORMULA_PREDICTION" : request.runType();
        var current = request == null || request.currentDraft() == null
                ? ResearchDraft.empty() : request.currentDraft();
        var goalsByTarget = new LinkedHashMap<String, Goal>();
        current.goals().forEach(goal -> goalsByTarget.put(goal.targetKey(), goal));
        for (var target : profile.definition().targets()) {
            var normalized = text.toUpperCase(Locale.ROOT);
            var alias = target.aliases().stream()
                    .filter(value -> normalized.contains(value.toUpperCase(Locale.ROOT))).findFirst();
            if (alias.isEmpty()) continue;
            var clause = targetClause(text, alias.get());
            var mode = inferredMode(clause, target.direction());
            var value = targetValue(clause, alias.get(), target.valueType(), target.unit());
            var mandatory = isMandatory(clause, mode);
            var weight = importance(clause);
            goalsByTarget.put(target.targetKey(), new Goal(target.targetKey(), mode, mandatory, weight,
                    value, null, null, null));
        }

        var context = new LinkedHashMap<String, Object>(current.context());
        recognizeSubstrate(text).ifPresent(value -> context.put("substrate", value));
        var constraints = mergeConstraints(current.constraints(), text);
        var candidateCount = candidateCount(text, current.candidateCount());
        var modelResult = interpreter.interpret(interpreterRequest(text, runType, current));
        var merged = applyModelSuggestion(text, goalsByTarget, context, constraints, modelResult);
        constraints = merged.constraints();
        var draft = new ResearchDraft(List.copyOf(goalsByTarget.values()), context, constraints, candidateCount,
                current.baselineAnalysisRowId());

        var unresolved = new ArrayList<UnresolvedField>();
        var warnings = new ArrayList<String>();
        if (draft.goals().isEmpty()) {
            unresolved.add(new UnresolvedField("goals", "TARGET_REQUIRED", "请至少选择一个已登记的性能目标"));
            warnings.add("未从描述中识别出已登记目标，请使用结构化目标选择");
        }
        for (var goal : draft.goals()) {
            if (List.of("AT_LEAST", "AT_MOST", "MATCH").contains(goal.mode()) && goal.value() == null) {
                unresolved.add(new UnresolvedField("goals." + goal.targetKey(), "TARGET_VALUE_REQUIRED",
                        "该目标需要补充明确的目标值"));
            }
        }
        if ("EXPERIMENT_OPTIMIZATION".equals(runType)
                && (draft.baselineAnalysisRowId() == null || draft.baselineAnalysisRowId().isBlank())) {
            unresolved.add(new UnresolvedField("baselineAnalysisRowId", "BASELINE_REQUIRED",
                    "AI实验优化必须选择唯一的基线实验"));
        }
        var overlap = new LinkedHashSet<>(constraints.requiredMaterials());
        overlap.retainAll(constraints.forbiddenMaterials());
        if (!overlap.isEmpty()) {
            unresolved.add(new UnresolvedField("constraints.materials", "MATERIAL_CONSTRAINT_CONFLICT",
                    "同一材料不能同时设为必选和禁用：" + String.join("、", overlap)));
        }
        if (containsUnsupportedTarget(text)) {
            unresolved.add(new UnresolvedField("goals", "TARGET_UNSUPPORTED",
                    "描述中包含尚未登记或暂不可预测的性能，请在目标列表中确认"));
            warnings.add("未登记目标不会被自动映射，也不会生成性能数值");
        }
        if (text.contains("附着力") && draft.goals().stream().noneMatch(goal ->
                profile.target(goal.targetKey()).map(target -> target.targetKey().contains("ADHESION")).orElse(false))) {
            unresolved.add(new UnresolvedField("goals", "TARGET_AMBIGUOUS",
                    "附着力需要确认测试条件和基材，请从已登记目标中选择"));
        }
        var confirmationStatus = unresolved.stream().anyMatch(item -> item.code().contains("CONFLICT"))
                ? "CONFLICT" : unresolved.isEmpty() ? "READY" : "NEEDS_INPUT";
        var modelUsed = "MODEL".equals(modelResult.status()) && merged.acceptedSuggestions() > 0;
        log.debug("Formula research interpretation status={}, accepted={}, rejected={}, rejectionCodes={}",
                modelResult.status(), merged.acceptedSuggestions(), merged.rejectedSuggestions(),
                merged.rejectionCodes());
        var fallbackReason = modelUsed ? null : "MODEL".equals(modelResult.status())
                ? "NO_VALID_MODEL_SUGGESTION:" + String.join(",", merged.rejectionCodes())
                : modelResult.failureReason();
        return new ParsedResearchRequest(text, draft.goals(), warnings, draft, unresolved, confirmationStatus,
                modelUsed ? "LLM_ASSISTED" : "DETERMINISTIC_FALLBACK",
                modelUsed ? modelResult.model() : null, modelResult.promptVersion(),
                fallbackReason);
    }

    @Override
    public SubmitView submitFormulaPrediction(ResearchRequest request) {
        return submit("FORMULA_PREDICTION", request);
    }

    @Override
    public SubmitView submitExperimentOptimization(ResearchRequest request) {
        return submit("EXPERIMENT_OPTIMIZATION", request);
    }

    private SubmitView submit(String runType, ResearchRequest request) {
        var actor = requireAiUse();
        if (request == null) throw validation("研究请求不能为空");
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw validation("必须提供幂等键");
        }
        if ("EXPERIMENT_OPTIMIZATION".equals(runType)
                && (request.baselineAnalysisRowId() == null || request.baselineAnalysisRowId().isBlank())) {
            throw validation("AI实验优化必须选择基线实验");
        }
        validateTargets(request.goals());
        var requestNode = json.valueToTree(request);
        var hashInput = json.createObjectNode().put("runType", runType).set("request", requestNode);
        var requestHash = canonicalizer.hash(hashInput);
        var persistedKey = "T06:" + actor.userId() + ":" + runType + ":" + request.idempotencyKey().strip();
        var runId = UUID.randomUUID();
        var result = repository.createOrFind(new NewRun(runId, actor.organizationId(), runType, "CASE_STAT_RULE",
                "QUEUED", request.taskProfileCode(), persistedKey, requestHash, requestNode,
                actor.userId(), actor.username()));
        if (!result.run().requestHash().equals(requestHash)) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "相同幂等键对应的请求内容不同");
        }
        if (result.created()) {
            var payload = json.createObjectNode().put("organizationId", actor.organizationId().toString())
                    .put("actorId", actor.userId().toString()).put("actorName", actor.username())
                    .put("actorRole", actor.role()).put("runId", runId.toString());
            jobs.enqueue(actor.organizationId(), "RESEARCH_RUN", payload, "research-run:" + runId, 45);
        }
        return new SubmitView(result.run().id(), result.run().status());
    }

    @Override
    public ResearchRunView run(UUID runId) {
        var actor = requireAiUse();
        var run = repository.findRun(actor.organizationId(), runId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "研究运行不存在"));
        requireRunOwner(actor.userId(), run);
        return view(run);
    }

    @Override
    @Transactional
    public List<ExperimentDraftView> createExperimentDrafts(UUID runId, DraftRequest request) {
        var actor = requireAiUse();
        if (request == null || request.candidateIds().isEmpty()) throw validation("至少选择一个候选方案");
        if (request.plannedExperimentDate() == null) throw validation("必须填写计划实验日期");
        if (request.categoryId() == null) throw validation("必须选择实验分类");
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) throw validation("必须提供幂等键");
        var run = repository.findRun(actor.organizationId(), runId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "研究运行不存在"));
        requireRunOwner(actor.userId(), run);
        if (!List.of("SUCCEEDED", "PARTIAL").contains(run.status())) throw validation("研究运行尚未产生可用候选");
        if (!experimentDraftProfile.definition().taskProfileCode().equals(run.taskProfileCode())) {
            throw new ApiException(ApiErrorCode.EXPERIMENT_TEMPLATE_REQUIRED,
                    "当前AI任务没有配置实验草稿模板：" + run.taskProfileCode());
        }
        var template = templates.getPublishedExperimentTemplate(actor.organizationId(),
                experimentDraftProfile.definition().templateCode());
        var result = new ArrayList<ExperimentDraftView>();
        for (var candidateId : request.candidateIds()) {
            repository.candidate(actor.organizationId(), runId, candidateId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "候选方案不存在"));
            repository.lockCandidate(actor.organizationId(), runId, candidateId);
            var existing = repository.experimentLink(actor.organizationId(), candidateId);
            if (existing.isPresent()) {
                var link = existing.get();
                result.add(new ExperimentDraftView(candidateId, link.experimentId(), link.experimentVersionId(),
                        link.experimentNo(), "已创建的实验草稿"));
                continue;
            }
            var candidate = repository.candidate(actor.organizationId(), runId, candidateId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "候选方案不存在"));
            var title = experimentTitle(candidate, run);
            var ownerName = request.ownerName() == null || request.ownerName().isBlank()
                    ? actor.username() : request.ownerName().strip();
            var editModel = editModel(candidate, run, request.plannedExperimentDate(), title);
            var documentSnapshot = experimentTemplateFiller.fill(template, editModel, candidate.process(),
                    candidate.modelContext(), ownerName, request.plannedExperimentDate(), candidate.candidateNo(),
                    candidate.id().toString());
            editModel.put("documentFormat", "excel");
            editModel.set("documentSnapshot", documentSnapshot);
            var projectId = request.projectId() == null ? uuid(run.result(), "referenceProjectId") : request.projectId();
            var stageId = request.stageId() == null ? uuid(run.result(), "referenceStageId") : request.stageId();
            var taskId = request.taskId() == null ? uuid(run.result(), "referenceTaskId") : request.taskId();
            var created = experimentDrafts.createResearchDraft(new ExperimentDraftFacade.ResearchDraftCommand(
                    title, request.categoryId(), projectId, stageId, taskId,
                    ownerName, request.plannedExperimentDate(), template.versionId(), template.snapshotHash(),
                    template.snapshot(), editModel));
            var key = "T06:DRAFT:" + request.idempotencyKey().strip() + ":" + candidateId;
            repository.saveExperimentLink(new ResearchRepository.NewExperimentLink(UUID.randomUUID(),
                    actor.organizationId(), runId, candidateId, created.experimentId(), created.experimentVersionId(),
                    created.experimentNo(), key, actor.userId()));
            result.add(new ExperimentDraftView(candidateId, created.experimentId(), created.experimentVersionId(),
                    created.experimentNo(), created.title()));
        }
        return List.copyOf(result);
    }

    public JsonNode execute(UUID organizationId, UUID runId) {
        var run = repository.findRun(organizationId, runId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "研究运行不存在"));
        if (!repository.markRunning(organizationId, runId)) return run.result();
        var request = json.convertValue(run.request(), ResearchRequest.class);
        var computed = computation.compute(run.runType(), request);
        repository.complete(organizationId, runId, computed.mode(), computed.status(), computed.analysisProfileVersion(),
                computed.result(), computed.candidates());
        return computed.result();
    }

    public void fail(UUID organizationId, UUID runId, Exception exception) {
        var code = exception instanceof ApiException api ? api.errorCode().code() : ApiErrorCode.INTERNAL_ERROR.code();
        repository.fail(organizationId, runId, code, exception.getMessage());
    }

    private ResearchRunView view(ResearchRepository.RunRow run) {
        var candidates = repository.candidates(run.organizationId(), run.id()).stream().map(item ->
                new CandidateView(item.id(), item.candidateNo(), item.strategy(), item.title(), item.formula(),
                        item.process(), item.modelContext(), item.estimates(), item.ruleCheck(), item.evidence(), item.confidence(),
                        item.score(), item.contentHash())).toList();
        return new ResearchRunView(run.id(), run.runType(), run.mode(), run.status(), run.taskProfileCode(),
                run.analysisProfileVersion(), run.request(), run.result(), run.errorCode(), run.errorMessage(),
                candidates, run.createdAt(), run.startedAt(), run.finishedAt());
    }

    private ObjectNode editModel(ResearchRepository.CandidateRow candidate, ResearchRepository.RunRow run,
                                 LocalDate plannedDate, String title) {
        var request = json.convertValue(run.request(), ResearchRequest.class);
        var targetNames = request.goals().stream().map(goal -> profile.target(goal.targetKey())
                .map(UvpuResearchProfile.Target::name).orElse(goal.targetKey())).toList();
        var substrate = substrateName(candidate, request);
        var goalDescription = request.goals().stream().map(this::goalDescription)
                .reduce((left, right) -> left + "、" + right).orElse("关键应用性能");
        var purpose = "验证候选配方在" + substrate + "上的应用性能，重点验证" + goalDescription + "。";
        var plan = "按候选配方制样，在既定施工和UV固化条件下进行测试，记录"
                + String.join("、", targetNames) + "及相关验证结果。";
        var model = json.createObjectNode().put("schemaVersion", 2).put("title", title)
                .put("purpose", purpose)
                .put("plan", plan)
                .put("plannedExperimentDate", plannedDate.toString());
        var formulas = model.putArray("formulaItems");
        for (var item : businessFormula(candidate.formula())) {
            var code = item.path("materialCode").asText();
            var ratio = item.path("ratio");
            var target = formulas.addObject().put("itemId", "T06:" + candidate.id() + ":" + code)
                    .put("materialCode", code).put("materialName", item.path("materialName").asText(code))
                    .put("rawUnit", "%").put("unit", "%")
                    .put("ratioDerivation", "AI_CANDIDATE_STEP_ROUNDED")
                    .put("ratioRuleVersion", experimentDraftProfile.definition().draftProfileVersion());
            if (ratio.isNumber()) {
                target.set("ratio", ratio.deepCopy());
                target.set("rawValue", ratio.deepCopy());
            }
            target.set("sourceRefs", json.createArrayNode());
        }
        var process = model.putArray("processSteps");
        var stepNo = 1;
        for (var definition : experimentDraftProfile.definition().processFacts()) {
            var fact = candidate.process().path(definition.code()).isObject()
                    ? candidate.process().path(definition.code()) : candidate.modelContext().path(definition.code());
            if (!fact.isObject() || !"PARSED".equals(fact.path("status").asText("PARSED"))) continue;
            var businessValue = businessProcessValue(definition.code(), fact);
            if (businessValue == null || businessValue.toString().isBlank()) continue;
            var itemId = UUID.nameUUIDFromBytes((candidate.id() + ":PROCESS:" + definition.code())
                    .getBytes(StandardCharsets.UTF_8)).toString();
            var displayValue = businessValue instanceof BigDecimal decimal
                    ? decimal.toPlainString() : businessValue.toString();
            var target = process.addObject().put("itemId", itemId)
                    .put("stepNo", stepNo++).put("operation", definition.displayName())
                    .put("rawValue", displayValue).put("unit", definition.unit());
            if (businessValue instanceof BigDecimal decimal) target.put("value", decimal);
            else target.put("value", businessValue.toString());
            target.set("sourceRefs", json.createArrayNode());
        }
        var tests = model.putArray("testResults");
        for (var goal : request.goals()) {
            var targetDefinition = profile.target(goal.targetKey()).orElse(null);
            if (targetDefinition == null) continue;
            var analysisTarget = analysisProfile.definition().targets().stream()
                    .filter(item -> item.targetKey().equals(goal.targetKey())).findFirst().orElse(null);
            var test = tests.addObject()
                    .put("itemId", UUID.nameUUIDFromBytes((candidate.id() + ":TEST:" + goal.targetKey())
                            .getBytes(StandardCharsets.UTF_8)).toString())
                    .put("testItem", targetDefinition.name()).putNull("value")
                    .put("rawValue", "").put("unit", targetDefinition.unit())
                    .put("judgement", "").put("plannedCriterion", goalDescription(goal))
                    .put("targetKey", goal.targetKey()).put("status", "PLANNED")
                    .put("testMethod", analysisTarget == null ? "" : analysisTarget.testMethod())
                    .put("testCondition", analysisTarget == null ? "" : analysisTarget.condition())
                    .put("substrate", analysisTarget == null ? "" : analysisTarget.substrate());
            test.set("sourceRefs", json.createArrayNode());
        }
        model.set("dynamicValues", json.createObjectNode());
        model.set("sourceGroups", json.createArrayNode());
        model.set("sourceContexts", json.createArrayNode());
        model.set("conclusion", json.createObjectNode().put("mainConclusion", ""));
        return model;
    }

    private List<ObjectNode> businessFormula(JsonNode formula) {
        var result = new ArrayList<ObjectNode>();
        ObjectNode balance = null;
        var nonBalanceTotal = BigDecimal.ZERO;
        if (formula == null || !formula.isArray()) return result;
        for (var item : formula) {
            if (!item.path("ratioPercent").isNumber()) continue;
            var code = item.path("materialCode").asText();
            var copy = json.createObjectNode().put("materialCode", code)
                    .put("materialName", item.path("materialName").asText(code));
            var material = analysisProfile.material(code, item.path("materialName").asText()).orElse(null);
            if (material != null && "BALANCE".equals(material.role())) {
                balance = copy;
            } else {
                var rounded = item.path("ratioPercent").decimalValue().setScale(1, RoundingMode.HALF_UP);
                copy.put("ratio", rounded);
                nonBalanceTotal = nonBalanceTotal.add(rounded);
            }
            result.add(copy);
        }
        if (balance != null) {
            balance.put("ratio", new BigDecimal("100.0").subtract(nonBalanceTotal)
                    .setScale(1, RoundingMode.HALF_UP));
        }
        return List.copyOf(result);
    }

    private Object businessProcessValue(String code, JsonNode fact) {
        if (fact.path("numericValue").isNumber()) {
            return fact.path("numericValue").decimalValue().stripTrailingZeros();
        }
        var value = fact.path("textValue").asText("");
        return switch (code) {
            case "substrate" -> switch (value) {
                case "PET_100UM_OPTICAL" -> "100μm光学级PET膜";
                case "PMMA_PC" -> "PMMA/PC复合板";
                case "PC" -> "PC膜";
                case "PET" -> "PET膜";
                default -> cleanSourceText(fact.path("rawValue").asText(value));
            };
            case "applicationMethod" -> "WIRE_BAR_ROLL_COATING".equals(value)
                    ? "绕丝棒辊涂" : cleanSourceText(fact.path("rawValue").asText(value));
            case "curingSource" -> "MERCURY_UV".equals(value)
                    ? "汞灯UV固化" : cleanSourceText(fact.path("rawValue").asText(value));
            default -> cleanSourceText(fact.path("rawValue").asText(value));
        };
    }

    private String cleanSourceText(String value) {
        return value == null ? "" : value.replaceAll("[（(]本页.*?[）)]", "").strip();
    }

    private String experimentTitle(ResearchRepository.CandidateRow candidate, ResearchRepository.RunRow run) {
        var request = json.convertValue(run.request(), ResearchRequest.class);
        var names = request.goals().stream().map(goal -> profile.target(goal.targetKey())
                        .map(UvpuResearchProfile.Target::name).orElse("应用性能"))
                .map(name -> name.replaceAll("（.*?）", "")).distinct().limit(3).toList();
        return substrateName(candidate, request) + "UV配方—" + String.join("/", names) + "优化实验";
    }

    private String substrateName(ResearchRepository.CandidateRow candidate, ResearchRequest request) {
        var requested = request.context().get("substrate");
        var code = requested == null ? candidate.modelContext().path("substrate").path("textValue").asText("")
                : requested.toString();
        return switch (code) {
            case "PET_100UM_OPTICAL" -> "PET光学膜";
            case "PMMA_PC" -> "PMMA/PC复合板";
            case "PC" -> "PC膜";
            case "PET" -> "PET膜";
            default -> "UV/PU应用";
        };
    }

    private String goalDescription(Goal goal) {
        var target = profile.target(goal.targetKey()).orElse(null);
        var name = target == null ? goal.targetKey() : target.name();
        var unit = target == null || target.unit() == null ? "" : target.unit();
        var value = goal.value() == null ? "" : goal.value().stripTrailingZeros().toPlainString() + unit;
        return switch (goal.mode()) {
            case "AT_LEAST" -> value.isBlank() ? "提高" + name : name + "≥" + value;
            case "AT_MOST" -> value.isBlank() ? "降低" + name : name + "≤" + value;
            case "MATCH" -> value.isBlank() ? "验证" + name : name + "目标" + value;
            case "MAXIMIZE" -> "提高" + name;
            case "MINIMIZE" -> "降低" + name;
            default -> "验证" + name;
        };
    }

    private ResearchRequestInterpreter.Request interpreterRequest(String text, String runType,
                                                                  ResearchDraft current) {
        var targets = new ArrayList<ResearchRequestInterpreter.TargetOption>();
        for (var index = 0; index < profile.definition().targets().size(); index++) {
            var target = profile.definition().targets().get(index);
            targets.add(new ResearchRequestInterpreter.TargetOption("TARGET_" + (index + 1), target.targetKey(),
                    target.name(), target.valueType(), target.unit(), target.direction(), target.aliases()));
        }
        var materials = analysisProfile.definition().materials().stream().map(material ->
                new ResearchRequestInterpreter.MaterialOption(material.code(), material.role(),
                        material.aliases())).toList();
        var goals = current.goals().stream().map(goal -> new ResearchRequestInterpreter.GoalState(
                goal.targetKey(), goal.mode(), goal.mandatory(), goal.weight(), goal.value())).toList();
        var substrate = current.context().get("substrate") instanceof String value ? value : null;
        var draft = new ResearchRequestInterpreter.DraftContext(goals, substrate,
                current.constraints().requiredMaterials(), current.constraints().forbiddenMaterials(),
                current.candidateCount(), current.baselineAnalysisRowId() != null
                && !current.baselineAnalysisRowId().isBlank());
        return new ResearchRequestInterpreter.Request(text, runType, targets, materials, draft);
    }

    private ModelMergeResult applyModelSuggestion(String text, Map<String, Goal> goalsByTarget,
                                                  Map<String, Object> context, Constraints constraints,
                                                  ResearchRequestInterpreter.Result result) {
        if (result == null || !"MODEL".equals(result.status()) || result.suggestion() == null) {
            return new ModelMergeResult(constraints, 0, 0, List.of());
        }
        var accepted = 0;
        var rejected = 0;
        var rejectionCodes = new ArrayList<String>();
        var suggestion = result.suggestion();
        for (var change : suggestion.goalChanges().stream().limit(8).toList()) {
            if (change == null || !validEvidence(text, change.evidence())) {
                rejected++;
                rejectionCodes.add("GOAL_EVIDENCE_NOT_GROUNDED");
                continue;
            }
            var target = targetByReference(change.targetRef()).orElse(null);
            if (target == null) {
                rejected++;
                rejectionCodes.add("TARGET_NOT_REGISTERED");
                continue;
            }
            var operation = normalized(change.operation());
            if ("REMOVE".equals(operation)) {
                if (!containsAny(change.evidence(), "取消", "删除", "去掉", "不要再", "不再要求")) {
                    rejected++;
                    rejectionCodes.add("REMOVE_NOT_EXPLICIT");
                    continue;
                }
                goalsByTarget.remove(target.targetKey());
                accepted++;
                continue;
            }
            if (!"UPSERT".equals(operation)) {
                rejected++;
                rejectionCodes.add("GOAL_OPERATION_NOT_ALLOWED");
                continue;
            }
            var suggestedValue = change.value();
            if (suggestedValue != null && !numberAppears(suggestedValue, change.evidence())) {
                rejected++;
                rejectionCodes.add("GOAL_VALUE_NOT_IN_SOURCE");
                suggestedValue = null;
            }
            var existing = goalsByTarget.get(target.targetKey());
            var requestedMode = normalized(change.mode());
            var mode = List.of("MINIMIZE", "MAXIMIZE", "AT_LEAST", "AT_MOST", "MATCH")
                    .contains(requestedMode) ? requestedMode
                    : existing == null ? inferredMode(change.evidence(), target.direction()) : existing.mode();
            var value = suggestedValue;
            if (value == null && existing != null && List.of("AT_LEAST", "AT_MOST", "MATCH").contains(mode)) {
                value = existing.value();
            }
            if (value == null && existing == null && List.of("AT_LEAST", "AT_MOST", "MATCH").contains(mode)) {
                mode = inferredMode(change.evidence(), target.direction());
                if (List.of("AT_LEAST", "AT_MOST", "MATCH").contains(mode)) mode = target.direction();
            }
            var mandatory = existing != null && existing.mandatory();
            if (Boolean.TRUE.equals(change.mandatory()) && isMandatory(change.evidence(), mode)) mandatory = true;
            if (Boolean.FALSE.equals(change.mandatory())
                    && containsAny(change.evidence(), "非必达", "不强制", "一般", "次要")) mandatory = false;
            var weight = existing == null ? BigDecimal.ONE : existing.weight();
            if (change.weight() != null) {
                if (change.weight().compareTo(new BigDecimal("3")) == 0
                        && importance(change.evidence()).compareTo(new BigDecimal("3")) == 0) {
                    weight = new BigDecimal("3");
                } else if (change.weight().compareTo(BigDecimal.ONE) == 0
                        && containsAny(change.evidence(), "一般", "普通", "次要")) {
                    weight = BigDecimal.ONE;
                }
            }
            goalsByTarget.put(target.targetKey(), new Goal(target.targetKey(), mode, mandatory, weight, value,
                    existing == null ? null : existing.minimum(), existing == null ? null : existing.maximum(),
                    existing == null ? null : existing.tolerance()));
            accepted++;
        }

        var required = new LinkedHashSet<>(constraints.requiredMaterials());
        var forbidden = new LinkedHashSet<>(constraints.forbiddenMaterials());
        for (var change : suggestion.materialChanges().stream().limit(8).toList()) {
            if (change == null || !validEvidence(text, change.evidence())) {
                rejected++;
                rejectionCodes.add("MATERIAL_EVIDENCE_NOT_GROUNDED");
                continue;
            }
            var material = analysisProfile.material(change.materialCode(), null).orElse(null);
            if (material == null || !mentionsMaterial(change.evidence(), material)) {
                rejected++;
                rejectionCodes.add("MATERIAL_NOT_REGISTERED_OR_MENTIONED");
                continue;
            }
            switch (normalized(change.operation())) {
                case "REQUIRE" -> required.add(material.code());
                case "FORBID" -> forbidden.add(material.code());
                case "ALLOW" -> {
                    required.remove(material.code());
                    forbidden.remove(material.code());
                }
                default -> {
                    rejected++;
                    rejectionCodes.add("MATERIAL_OPERATION_NOT_ALLOWED");
                    continue;
                }
            }
            accepted++;
        }
        constraints = new Constraints(List.copyOf(required), List.copyOf(forbidden), constraints.fixedMaterials(),
                constraints.materialRanges(), constraints.forbiddenMaterialCombinations(), constraints.processRanges(),
                constraints.maxMaterialCount(), constraints.requireCostCheck(), constraints.requireInventoryCheck());

        var substrate = suggestion.substrateChange();
        if (substrate != null && !"KEEP".equals(normalized(substrate.operation()))) {
            if (!validEvidence(text, substrate.evidence())) {
                rejected++;
                rejectionCodes.add("SUBSTRATE_EVIDENCE_NOT_GROUNDED");
            } else if ("CLEAR".equals(normalized(substrate.operation()))
                    && containsAny(substrate.evidence(), "不限基材", "清除基材", "不限制基材")) {
                context.remove("substrate");
                accepted++;
            } else if ("SET".equals(normalized(substrate.operation()))
                    && recognizeSubstrate(substrate.evidence()).filter(substrate.value()::equals).isPresent()) {
                context.put("substrate", substrate.value());
                accepted++;
            } else {
                rejected++;
                rejectionCodes.add("SUBSTRATE_CHANGE_NOT_ALLOWED");
            }
        }
        return new ModelMergeResult(constraints, accepted, rejected, List.copyOf(rejectionCodes));
    }

    private boolean validEvidence(String text, String evidence) {
        return evidence != null && evidence.strip().length() >= 2 && containsIgnoreCase(text, evidence.strip());
    }

    private Optional<UvpuResearchProfile.Target> targetByReference(String reference) {
        if (reference == null) return Optional.empty();
        var normalized = normalized(reference);
        var matcher = Pattern.compile("TARGET_([1-9][0-9]*)").matcher(normalized);
        if (matcher.matches()) {
            var index = Integer.parseInt(matcher.group(1)) - 1;
            if (index >= 0 && index < profile.definition().targets().size()) {
                return Optional.of(profile.definition().targets().get(index));
            }
        }
        return profile.target(reference);
    }

    private boolean mentionsMaterial(String evidence, UvpuAnalysisProfile.MaterialDefinition material) {
        if (containsIgnoreCase(evidence, material.code())) return true;
        return material.aliases().stream().anyMatch(alias -> containsIgnoreCase(evidence, alias));
    }

    private boolean numberAppears(BigDecimal value, String evidence) {
        var matcher = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?").matcher(evidence);
        while (matcher.find()) {
            try {
                if (new BigDecimal(matcher.group()).compareTo(value) == 0) return true;
            } catch (NumberFormatException ignored) {
                // Continue checking the remaining explicit numbers.
            }
        }
        return false;
    }

    private boolean containsIgnoreCase(String text, String value) {
        return text != null && value != null
                && text.toUpperCase(Locale.ROOT).contains(value.toUpperCase(Locale.ROOT));
    }

    private String normalized(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private record ModelMergeResult(Constraints constraints, int acceptedSuggestions, int rejectedSuggestions,
                                    List<String> rejectionCodes) {}

    private void validateTargets(List<Goal> goals) {
        if (goals == null || goals.isEmpty()) throw validation("至少选择一个性能目标");
        for (var goal : goals) if (profile.target(goal.targetKey()).isEmpty()) {
            throw validation("未登记的性能目标：" + goal.targetKey());
        }
    }

    private String formulaSummary(List<com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade.FormulaComponent> items) {
        return items.stream().filter(item -> item.ratioPercent() != null && item.ratioPercent().signum() > 0)
                .map(item -> item.materialCode() + " " + item.ratioPercent().stripTrailingZeros().toPlainString() + "%")
                .limit(4).reduce((left, right) -> left + "、" + right).orElse("未形成规范化配方");
    }

    private String targetClause(String text, String alias) {
        for (var clause : text.split("[，。；;、\\n]")) {
            if (clause.toUpperCase(Locale.ROOT).contains(alias.toUpperCase(Locale.ROOT))) return clause.strip();
        }
        return text;
    }

    private BigDecimal targetValue(String clause, String alias, String valueType, String unit) {
        var withoutAlias = Pattern.compile(Pattern.quote(alias), Pattern.CASE_INSENSITIVE)
                .matcher(clause).replaceFirst(" ");
        var suffix = "B".equalsIgnoreCase(unit) ? "B" : "H";
        var pattern = "ORDINAL".equals(valueType)
                ? Pattern.compile("(?<![A-Za-z0-9])([0-5](?:\\.[0-9]+)?)\\s*" + suffix,
                        Pattern.CASE_INSENSITIVE)
                : Pattern.compile("(?<![A-Za-z0-9])(-?[0-9]+(?:\\.[0-9]+)?)");
        var matcher = pattern.matcher(withoutAlias);
        var found = matcher.find();
        if (!found && "ORDINAL".equals(valueType)) {
            matcher = pattern.matcher(clause);
            found = matcher.find();
        }
        if (!found) return null;
        try { return new BigDecimal(matcher.group(1)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private boolean isMandatory(String clause, String mode) {
        return clause.contains("必须") || clause.contains("必达") || clause.contains("至少")
                || clause.contains("不低于") || clause.contains("不超过") || clause.contains("至多")
                || List.of("AT_LEAST", "AT_MOST").contains(mode);
    }

    private BigDecimal importance(String clause) {
        return clause.contains("最重要") || clause.contains("优先") || clause.contains("重点")
                ? new BigDecimal("3") : BigDecimal.ONE;
    }

    private Optional<String> recognizeSubstrate(String text) {
        var normalized = text.toUpperCase(Locale.ROOT).replace(" ", "");
        if (normalized.contains("100ΜMPET") || normalized.contains("100μMPET")
                || normalized.contains("100UMPET") || normalized.contains("PET光学膜")) {
            return Optional.of("PET_100UM_OPTICAL");
        }
        if (normalized.contains("PMMA/PC") || normalized.contains("PMMA-PC")) return Optional.of("PMMA_PC");
        if (normalized.contains("PC膜") || normalized.contains("PC基材")) return Optional.of("PC");
        if (normalized.contains("PET膜") || normalized.contains("PET基材")) return Optional.of("PET");
        return Optional.empty();
    }

    private Constraints mergeConstraints(Constraints current, String text) {
        var base = current == null ? Constraints.empty() : current;
        var required = new LinkedHashSet<>(base.requiredMaterials());
        var forbidden = new LinkedHashSet<>(base.forbiddenMaterials());
        for (var material : analysisProfile.definition().materials()) {
            var aliases = new ArrayList<>(material.aliases());
            aliases.add(material.code());
            for (var alias : aliases) {
                if (!text.toUpperCase(Locale.ROOT).contains(alias.toUpperCase(Locale.ROOT))) continue;
                var context = UvpuAnalysisProfile.normalize(targetClause(text, alias));
                if (containsAny(context, "不要", "禁用", "不使用", "排除")) forbidden.add(material.code());
                if (containsAny(context, "必须使用", "必选", "保留", "保持")) required.add(material.code());
                break;
            }
        }
        return new Constraints(List.copyOf(required), List.copyOf(forbidden), base.fixedMaterials(),
                base.materialRanges(), base.forbiddenMaterialCombinations(), base.processRanges(),
                base.maxMaterialCount(), base.requireCostCheck(), base.requireInventoryCheck());
    }

    private boolean containsAny(String text, String... values) {
        for (var value : values) if (text.contains(value)) return true;
        return false;
    }

    private int candidateCount(String text, Integer current) {
        var matcher = Pattern.compile("([1-4一二三四])\\s*(?:组|个|条)(?:候选|方案|实验|配方)?").matcher(text);
        if (!matcher.find()) return current == null ? 4 : current;
        return switch (matcher.group(1)) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            default -> Integer.parseInt(matcher.group(1));
        };
    }

    private boolean containsUnsupportedTarget(String text) {
        return text.contains("漆膜外观") || text.contains("涂料外观");
    }

    private String inferredMode(String text, String direction) {
        if (text.contains("至少") || text.contains("不低于")) return "AT_LEAST";
        if (text.contains("不超过") || text.contains("至多")) return "AT_MOST";
        if (text.contains("必达")) return "MINIMIZE".equals(direction) ? "AT_MOST" : "AT_LEAST";
        if (text.contains("接近")) return "MATCH";
        if ((text.contains("降低到") || text.contains("降到")) && targetValuePresent(text)) return "AT_MOST";
        if ((text.contains("提高到") || text.contains("升到")) && targetValuePresent(text)) return "AT_LEAST";
        if (text.contains("降低") || text.contains("减少")) return "MINIMIZE";
        if (text.contains("提高") || text.contains("增加")) return "MAXIMIZE";
        if (text.contains("达到")) return "MATCH";
        return direction;
    }

    private boolean targetValuePresent(String text) {
        return Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?").matcher(text).find();
    }

    private UUID uuid(JsonNode value, String field) {
        var text = value == null ? null : value.path(field).asText(null);
        if (text == null || text.isBlank()) return null;
        try { return UUID.fromString(text); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private void requireRunOwner(UUID actorId, ResearchRepository.RunRow run) {
        if (!actorId.equals(run.createdBy())) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "研究运行不存在");
        }
    }

    private com.jsd.aird.shared.security.Actor requireAiUse() {
        var actor = ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), "ai.use", "AI", null, "USE"));
        return actor;
    }

    private static ApiException validation(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_ERROR, message);
    }
}
