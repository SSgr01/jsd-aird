package com.jsd.aird.recognition.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ai.rnd.facts.UnifiedFactService;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.recognition.application.port.SourceRecognitionRepository;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.rnd.api.ExperimentDraftFacade;
import com.jsd.aird.rnd.application.ExperimentImportService;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shared source-recognition application service. Data and RND expose their own
 * routes and permissions, while every read and mutation is checked against the
 * immutable sourceOwner recorded on the job.
 */
@Service
public class SourceRecognitionService {
    public static final String DATA_CENTER = "DATA_CENTER";
    public static final String EXPERIMENT = "EXPERIMENT";

    private final SourceRecognitionRepository repository;
    private final FileObjectRepository files;
    private final ExperimentImportService sourceParser;
    private final ExperimentDraftFacade experiments;
    private final ConfigurationHashing hashing;
    private final AuditLogFacade audit;
    private final ObjectMapper json;
    private final UnifiedFactService facts;
    private final AuthorizationService authorization;

    public SourceRecognitionService(SourceRecognitionRepository repository, FileObjectRepository files,
                                    ExperimentImportService sourceParser, ExperimentDraftFacade experiments,
                                    ConfigurationHashing hashing, AuditLogFacade audit, ObjectMapper json,
                                    UnifiedFactService facts, AuthorizationService authorization) {
        this.repository = repository;
        this.files = files;
        this.sourceParser = sourceParser;
        this.experiments = experiments;
        this.hashing = hashing;
        this.audit = audit;
        this.json = json;
        this.facts = facts;
        this.authorization = authorization;
    }

    @Transactional
    public SourceRecognitionRepository.Job create(String owner, CreateCommand command) {
        var actor = ActorContext.required();
        requireOwner(owner);
        if (command == null || command.sourceFileId() == null) throw validation("来源文件不能为空");
        var file = files.find(actor.organizationId(), command.sourceFileId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY, "来源文件不存在或尚未就绪"));
        var format = command.sourceFormat() == null || command.sourceFormat().isBlank()
                ? sourceFormat(file.originalName()) : normalizeFormat(command.sourceFormat());
        var mode = normalizeMode(command.recognitionMode(), command.templateVersionId());
        if ("TEMPLATE_GUIDED".equals(mode) && command.templateVersionId() == null)
            throw validation("模板辅助识别必须选择模板版本");
        var existing = repository.findBySourceHash(actor.organizationId(), owner, file.sha256());
        if (existing.isPresent() && !command.duplicateOverride()) return existing.get();
        files.activate(file.id());
        var id = UUID.randomUUID();
        var metadata = json.createObjectNode()
                .put("schemaVersion", 1).put("sourceOwner", owner).put("recognitionMode", mode)
                .put("sourceFileId", file.id().toString()).put("sourceFileName", file.originalName())
                .put("sourceFileSha256", file.sha256()).put("sourceFormat", format);
        put(metadata, "projectId", command.projectId());
        put(metadata, "stageId", command.stageId());
        put(metadata, "taskId", command.taskId());
        if (command.experimentDate() != null) metadata.put("experimentDate", command.experimentDate().toString());
        metadata.put("ownerName", actor.username());
        var purpose = owner.equals(EXPERIMENT) ? "EXPERIMENT_DRAFT" : normalizePurpose(command.importPurpose());
        if ("EXPERIMENT_DRAFT".equals(purpose)) requireExperimentCreate(actor.organizationId(), actor.userId());
        repository.create(new SourceRecognitionRepository.NewJob(id, actor.organizationId(), file.id(),
                file.originalName(), file.sha256(), format, owner, mode, command.templateVersionId(),
                command.categoryId(), purpose, command.targetExperimentCategoryId(),
                normalizeVisibility(command.visibility()), metadata, json.createArrayNode(), actor.userId()));
        audit.append(actor.organizationId(), actor.userId(), "SOURCE_RECOGNITION_CREATED",
                "SOURCE_RECOGNITION_JOB", id, metadata);
        return required(actor.organizationId(), id, owner);
    }

    public List<SourceRecognitionRepository.Job> list(String owner) {
        return repository.list(ActorContext.required().organizationId(), checkedOwner(owner));
    }

    public SourceRecognitionRepository.Job get(String owner, UUID id) {
        return required(ActorContext.required().organizationId(), id, checkedOwner(owner));
    }

    /** Worker entry point. */
    public SourceRecognitionRepository.Job process(UUID organizationId, UUID id, String owner) {
        var job = required(organizationId, id, checkedOwner(owner));
        try {
            var parsed = sourceParser.parseSourceFile(organizationId, job.sourceFileId(), job.sourceFileName(),
                    job.sourceSha256(), TemplateFormat.valueOf(job.sourceFormat()));
            var parserVersion = parsed.structureSummary() == null ? "source-recognition-v1"
                    : parsed.structureSummary().path("parserVersion").asText("source-recognition-v1");
            var workspace = initialWorkspace(job, parsed.initialEditorSnapshot(), parsed.structureSummary(), parserVersion);
            repository.saveParsed(organizationId, id, parserVersion, workspace, workspace.path("experiments"));
            return required(organizationId, id, owner);
        } catch (RuntimeException exception) {
            repository.fail(organizationId, id, customerMessage(exception));
            throw exception;
        }
    }

    @Transactional
    public SourceRecognitionRepository.Job updateBoundaries(String owner, UUID id, long expectedRevision,
                                                             JsonNode boundaries) {
        var actor = ActorContext.required();
        var current = required(actor.organizationId(), id, checkedOwner(owner));
        validateBoundaries(boundaries);
        var workspace = ((ObjectNode) current.workspace()).deepCopy();
        workspace.set("experiments", boundaries.deepCopy());
        var updated = repository.updateWorkspace(actor.organizationId(), id, owner, expectedRevision,
                workspace, boundaries, current.recognitionProfileId());
        auditChange(actor.organizationId(), actor.userId(), id, "SOURCE_BOUNDARIES_UPDATED", expectedRevision);
        return updated;
    }

    @Transactional
    public SourceRecognitionRepository.Job updateMappings(String owner, UUID id, long expectedRevision,
                                                           JsonNode candidates, JsonNode unrecognized,
                                                           JsonNode issues) {
        var actor = ActorContext.required();
        var current = required(actor.organizationId(), id, checkedOwner(owner));
        requireArray(candidates, "字段候选");
        requireArray(unrecognized, "未识别内容");
        requireArray(issues, "识别问题");
        validateCandidateDecisions(candidates);
        validateCandidateAssignments(current.boundaries(), candidates);
        var workspace = ((ObjectNode) current.workspace()).deepCopy();
        workspace.set("candidates", candidates.deepCopy());
        workspace.set("unrecognizedFragments", unrecognized.deepCopy());
        workspace.set("issues", issues.deepCopy());
        var updated = repository.updateWorkspace(actor.organizationId(), id, owner, expectedRevision,
                workspace, current.boundaries(), current.recognitionProfileId());
        auditChange(actor.organizationId(), actor.userId(), id, "SOURCE_MAPPING_UPDATED", expectedRevision);
        return updated;
    }

    @Transactional
    public SourceRecognitionRepository.RecognitionProfile saveProfile(String owner, UUID id,
                                                                       String name, long expectedRevision) {
        var actor = ActorContext.required();
        var current = required(actor.organizationId(), id, checkedOwner(owner));
        if (current.recognitionRevision() != expectedRevision)
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "识别工作区版本已变化");
        if (name == null || name.isBlank()) throw validation("识别配置名称不能为空");
        var rules = json.createObjectNode().set("candidates", current.workspace().path("candidates").deepCopy());
        ((ObjectNode) rules).set("experiments", current.boundaries().deepCopy());
        var result = repository.saveProfile(actor.organizationId(), name.strip(), current.sourceFormat(), rules,
                hashing.hash(rules), actor.userId());
        repository.updateWorkspace(actor.organizationId(), id, owner, expectedRevision,
                current.workspace(), current.boundaries(), result.id());
        return result;
    }

    @Transactional
    public SourceRecognitionRepository.Job retry(String owner, UUID id) {
        var actor = ActorContext.required();
        if (repository.markParsing(actor.organizationId(), id, checkedOwner(owner)) != 1)
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "当前识别任务不可重试");
        return required(actor.organizationId(), id, owner);
    }

    @Transactional
    public FinalizeResult finalizeWorkspace(String owner, UUID id, long expectedRevision) {
        var actor = ActorContext.required();
        var job = required(actor.organizationId(), id, checkedOwner(owner));
        if (job.recognitionRevision() != expectedRevision)
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "识别工作区版本已变化，请刷新后提交");
        if (!"WAITING_MAPPING".equals(job.status()))
            throw validation("识别任务尚未进入人工确认阶段");
        validateBoundaries(job.boundaries());
        requireConfirmedBoundaries(job.boundaries());
        validateCandidateDecisions(job.workspace().path("candidates"));
        validateCandidateAssignments(job.boundaries(), job.workspace().path("candidates"));
        if (DATA_CENTER.equals(owner) && hasUnconfirmedLowConfidence(job.workspace()))
            throw validation("仍有低置信度字段未确认；正式数据提交前必须确认、忽略或修正");
        var recognizedExperiments = recognizedExperiments(job);
        var samples = recognizedExperiments.stream().flatMap(item -> item.samples().stream()).toList();
        if (samples.isEmpty()) throw validation("至少需要确认一个实验样本边界");
        if ((EXPERIMENT.equals(owner) || "EXPERIMENT_DRAFT".equals(job.importPurpose())))
            requireExperimentCreate(actor.organizationId(), actor.userId());
        if (repository.claimFinalization(actor.organizationId(), id, owner, expectedRevision) != 1)
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "识别任务已被提交或版本已变化，请刷新后重试");
        SourceRecognitionRepository.FinalizedData data = null;
        if (DATA_CENTER.equals(owner)) {
            var contract = json.createObjectNode().put("recognitionMode", job.recognitionMode())
                    .put("recognitionRevision", job.recognitionRevision())
                    .put("parserVersion", job.parserVersion());
            data = repository.finalizeData(actor.organizationId(), id, actor.userId(), samples,
                    contract, hashing.hash(json.valueToTree(samples)));
            facts.projectSubmission(actor.organizationId(), data.submissionId());
        }
        var drafts = new ArrayList<DraftResult>();
        if (EXPERIMENT.equals(owner) || "EXPERIMENT_DRAFT".equals(job.importPurpose())) {
            if (job.targetExperimentCategoryId() == null) throw validation("创建实验草稿必须选择实验分类");
            var submissionId = data == null ? null : data.submissionId();
            for (var recognizedExperiment : recognizedExperiments) {
                var model = editModel(job, recognizedExperiment);
                var created = experiments.createImportedDraft(new ExperimentDraftFacade.ImportedDraftCommand(
                        actor.organizationId(), actor.userId(), actor.username(), recognizedExperiment.title(),
                        job.targetExperimentCategoryId(), isWorkbook(job.sourceFormat()) ? "EXCEL_IMPORT" : "OCR_IMPORT",
                        uuid(job.workspace(), "projectId"), uuid(job.workspace(), "stageId"),
                        uuid(job.workspace(), "taskId"), job.workspace().path("ownerName").asText(actor.username()),
                        date(job.workspace().path("experimentDate").asText(null)), null, null,
                        json.createObjectNode(), model));
                repository.linkExperiment(actor.organizationId(), id, submissionId,
                        recognizedExperiment.experimentBoundaryId(),
                        created.experimentId(), created.experimentVersionId(), created.experimentNo(),
                        recognizedExperiment.sourceCoordinates(), model.path("recognitionSnapshot"),
                        recognizedExperiment.samples(), recognizedExperiment.contentHash(), actor.userId());
                drafts.add(new DraftResult(created.experimentId(), created.experimentVersionId(),
                        created.experimentNo(), created.title(), recognizedExperiment.experimentBoundaryId(),
                        recognizedExperiment.samples().stream().map(SourceRecognitionRepository.RecognizedSample::logicalSampleKey).toList()));
            }
        }
        repository.markFinalized(actor.organizationId(), id);
        var detail = json.createObjectNode().put("sourceOwner", owner).put("draftCount", drafts.size())
                .put("sampleCount", samples.size());
        if (data != null) detail.put("confirmedSubmissionId", data.submissionId().toString())
                .put("submissionRevision", data.revisionNo());
        audit.append(actor.organizationId(), actor.userId(), "SOURCE_RECOGNITION_FINALIZED",
                "SOURCE_RECOGNITION_JOB", id, detail);
        return new FinalizeResult(id, data == null ? null : data.submissionId(),
                data == null ? null : data.revisionNo(), List.copyOf(drafts));
    }

    @Transactional
    public void cancel(String owner, UUID id) {
        var actor = ActorContext.required();
        if (repository.cancel(actor.organizationId(), id, checkedOwner(owner)) != 1)
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "已完成任务不能取消");
    }

    ObjectNode initialWorkspace(SourceRecognitionRepository.Job job, JsonNode documentSnapshot,
                                JsonNode structureSummary, String parserVersion) {
        var workspace = job.workspace() != null && job.workspace().isObject()
                ? ((ObjectNode) job.workspace()).deepCopy() : json.createObjectNode();
        workspace.put("schemaVersion", 1).put("parserVersion", parserVersion);
        workspace.set("documentSnapshot", documentSnapshot == null ? json.createObjectNode() : documentSnapshot.deepCopy());
        workspace.set("structureSummary", structureSummary == null ? json.createObjectNode() : structureSummary.deepCopy());
        var fragments = sourceFragments(documentSnapshot, structureSummary, job.sourceFormat());
        var candidates = json.createArrayNode();
        var unrecognized = json.createArrayNode();
        var issues = json.createArrayNode();
        var index = 0;
        var measurementIndexes = new HashMap<String, Integer>();
        for (var fragment : fragments) {
            var candidate = classify(fragment, parserVersion, index++);
            if (candidate == null) unrecognized.add(fragment.deepCopy());
            else {
                if ("TEST".equals(candidate.path("category").asText())) {
                    var replicateGroupKey = "test:" + candidate.path("fieldCode").asText();
                    candidate.put("observationId", candidate.path("candidateId").asText())
                            .put("replicateGroupKey", replicateGroupKey)
                            .put("measurementIndex", measurementIndexes.merge(replicateGroupKey, 1, Integer::sum));
                }
                candidates.add(candidate);
                if ("REVIEW_REQUIRED".equals(candidate.path("status").asText())) {
                    issues.addObject().put("issueId", candidate.path("candidateId").asText())
                            .put("severity", "WARNING").put("type", "LOW_CONFIDENCE")
                            .put("message", "该字段需要人工确认，不会自动进入正式数据");
                }
            }
        }
        workspace.set("candidates", candidates);
        workspace.set("unrecognizedFragments", unrecognized);
        workspace.set("issues", issues);
        var experiments = defaultBoundaries(job, candidates);
        workspace.set("experiments", experiments);
        return workspace;
    }

    private void requireExperimentCreate(UUID organizationId, UUID actorId) {
        var decision = authorization.check(new PermissionCheck(organizationId, actorId,
                "experiment.create", "EXPERIMENT", null, "WRITE"));
        if (!decision.allowed()) throw new ApiException(ApiErrorCode.PERMISSION_DENIED,
                "创建实验草稿需要实验新建权限");
    }

    private ArrayNode sourceFragments(JsonNode snapshot, JsonNode summary, String format) {
        var result = json.createArrayNode();
        var seen = new HashSet<String>();
        if (summary != null) for (var block : summary.path("textBlocks")) {
            addFragment(result, seen, block.path("content").asText(""),
                    coordinate("PAGE", block.path("pageNo").asInt(0), null, null),
                    block.path("confidence").asDouble(.65));
        }
        if (snapshot != null) {
            for (var sheetName : iterable(snapshot.path("sheetOrder"))) {
                var sheetId = sheetName.asText();
                var sheet = snapshot.path("sheets").path(sheetId);
                var rows = sheet.path("cellData").fields();
                while (rows.hasNext()) {
                    var row = rows.next();
                    var cells = row.getValue().fields();
                    var orderedCells = new ArrayList<Map.Entry<String, JsonNode>>();
                    cells.forEachRemaining(orderedCells::add);
                    orderedCells.sort(java.util.Comparator.comparingInt(cell -> integer(cell.getKey())));
                    for (var cellIndex = 0; cellIndex < orderedCells.size(); cellIndex++) {
                        var cell = orderedCells.get(cellIndex);
                        var label = cell.getValue().path("v").asText("").strip();
                        var rowNumber = integer(row.getKey()) + 1;
                        var labelAddress = columnName(integer(cell.getKey()) + 1) + rowNumber;
                        var labelRule = fieldRule(label);
                        if (labelRule != null && cellIndex + 1 < orderedCells.size()) {
                            var valueCell = orderedCells.get(cellIndex + 1);
                            var value = valueCell.getValue().path("v").asText("").strip();
                            var adjacentLooksLikeValue = !"TEST".equals(labelRule.category())
                                    || fieldRule(value) == null || value.matches(".*\\d.*");
                            if (!value.isBlank() && adjacentLooksLikeValue) {
                                var valueAddress = columnName(integer(valueCell.getKey()) + 1) + rowNumber;
                                var source = coordinate("CELL", 0, sheetId, valueAddress);
                                source.put("labelAddress", labelAddress).put("valueAddress", valueAddress);
                                addFragment(result, seen, label + ": " + value, source, .9);
                                cellIndex++;
                                continue;
                            }
                        }
                        addFragment(result, seen, label,
                                coordinate("CELL", 0, sheetId, labelAddress), .9);
                    }
                }
            }
            var text = snapshot.path("body").path("dataStream").asText("");
            var line = 0;
            for (var value : text.split("\\R")) {
                line++;
                addFragment(result, seen, value, coordinate("DOCUMENT_BLOCK", line, null, null), .82);
            }
        }
        return result;
    }

    private void addFragment(ArrayNode target, Set<String> seen, String text, ObjectNode coordinate,
                             double confidence) {
        if (text == null || text.isBlank()) return;
        var normalized = text.strip();
        var key = normalized + "|" + coordinate;
        if (!seen.add(key)) return;
        target.addObject().put("fragmentId", UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString())
                .put("rawText", normalized).put("confidence", confidence).set("sourceCoordinate", coordinate);
    }

    private ObjectNode classify(JsonNode fragment, String parserVersion, int index) {
        var text = fragment.path("rawText").asText("").strip();
        if (text.isBlank()) return null;
        var match = LABEL_VALUE.matcher(text);
        var label = match.matches() ? match.group(1).strip() : text;
        var value = match.matches() ? match.group(2).strip() : text;
        var definition = fieldRule(label);
        if (definition == null) return null;
        var confidence = Math.min(fragment.path("confidence").asDouble(.6), match.matches() ? .92 : .68);
        var candidate = json.createObjectNode()
                .put("candidateId", UUID.nameUUIDFromBytes((index + "|" + text).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString())
                .put("category", definition.category()).put("fieldCode", definition.fieldCode())
                .put("labelPath", label).put("rawText", value).put("rawUnit", unit(value))
                .put("parsedValue", stripUnit(value)).put("standardUnit", unit(value))
                .put("confidence", confidence).put("parserVersion", parserVersion)
                .put("status", confidence >= .85 ? "SUGGESTED" : "REVIEW_REQUIRED")
                .put("experimentBoundaryId", "experiment-1").put("sampleBoundaryId", "sample-1")
                .put("sourceGroupKey", physicalGroupKey(fragment));
        candidate.set("sourceCoordinate", fragment.path("sourceCoordinate").deepCopy());
        return candidate;
    }

    private ArrayNode defaultBoundaries(SourceRecognitionRepository.Job job, ArrayNode candidates) {
        var experiments = json.createArrayNode();
        var experiment = experiments.addObject().put("experimentBoundaryId", "experiment-1")
                .put("title", baseName(job.sourceFileName())).put("confirmed", false);
        experiment.set("sourceCoordinates", json.createObjectNode().put("kind", "WHOLE_FILE"));
        var sample = experiment.putArray("samples").addObject().put("sampleBoundaryId", "sample-1")
                .put("logicalSampleKey", "SOURCE:" + job.id() + ":sample-1")
                .put("title", baseName(job.sourceFileName()));
        var sourceGroups = json.createArrayNode();
        var seenGroups = new HashSet<String>();
        for (var candidate : candidates) {
            var group = candidate.path("sourceGroupKey").asText("");
            if (!group.isBlank() && seenGroups.add(group)) sourceGroups.add(group);
        }
        if (sourceGroups.isEmpty()) sourceGroups.add("file:" + job.sourceSha256());
        sample.set("sourceGroupKeys", sourceGroups);
        experiment.set("sharedConditions", json.createArrayNode());
        return experiments;
    }

    List<SourceRecognitionRepository.RecognizedExperiment> recognizedExperiments(
            SourceRecognitionRepository.Job job) {
        var result = new ArrayList<SourceRecognitionRepository.RecognizedExperiment>();
        for (var boundary : job.boundaries()) {
            var experimentBoundaryId = boundary.path("experimentBoundaryId").asText();
            var samples = new ArrayList<SourceRecognitionRepository.RecognizedSample>();
            for (var sample : boundary.path("samples")) {
                var sampleBoundaryId = sample.path("sampleBoundaryId").asText();
                var logicalSampleKey = sample.path("logicalSampleKey").asText();
                var sourceGroupKeys = strings(sample.path("sourceGroupKeys"));
                var rawItems = json.createArrayNode();
                var correctedItems = json.createArrayNode();
                var effectiveItems = json.createArrayNode();
                var observations = json.createArrayNode();
                var formulaItems = json.createArrayNode();
                var processSteps = json.createArrayNode();
                for (var candidate : job.workspace().path("candidates")) {
                    if (!experimentBoundaryId.equals(candidate.path("experimentBoundaryId").asText())) continue;
                    if (!sampleBoundaryId.equals(candidate.path("sampleBoundaryId").asText())) continue;
                    var rawItem = ((ObjectNode) candidate.deepCopy());
                    rawItems.add(rawItem);
                    if (candidate.has("correctedValue")) correctedItems.add(candidate.deepCopy());
                    if ("IGNORED".equals(candidate.path("status").asText())) continue;
                    var effectiveItem = ((ObjectNode) candidate.deepCopy());
                    effectiveItem.set("effectiveValue", candidate.has("correctedValue")
                            ? candidate.path("correctedValue").deepCopy()
                            : candidate.path("parsedValue").deepCopy());
                    effectiveItems.add(effectiveItem);
                    switch (candidate.path("category").asText()) {
                        case "FORMULA" -> formulaItems.add(effectiveItem.deepCopy());
                        case "PROCESS" -> processSteps.add(effectiveItem.deepCopy());
                        case "TEST" -> observations.add(effectiveItem.deepCopy());
                        default -> { }
                    }
                }
                var raw = json.createObjectNode().set("recognizedItems", rawItems);
                var corrected = json.createObjectNode().set("recognizedItems", correctedItems);
                var effective = json.createObjectNode();
                effective.set("recognizedItems", effectiveItems);
                effective.set("formulaItems", formulaItems);
                effective.set("processSteps", processSteps);
                effective.set("observations", observations);
                effective.set("unrecognizedContent", job.workspace().path("unrecognizedFragments").deepCopy());
                var coordinates = json.createObjectNode()
                        .put("experimentBoundaryId", experimentBoundaryId)
                        .put("sampleBoundaryId", sampleBoundaryId)
                        .put("logicalSampleKey", logicalSampleKey);
                coordinates.set("sourceGroupKeys", json.valueToTree(sourceGroupKeys));
                coordinates.set("boundary", boundary.path("sourceCoordinates").deepCopy());
                var contentHash = hashing.hash(effective);
                samples.add(new SourceRecognitionRepository.RecognizedSample(experimentBoundaryId,
                        sampleBoundaryId, logicalSampleKey, sourceGroupKeys,
                        sample.path("title").asText(boundary.path("title").asText(baseName(job.sourceFileName()))),
                        raw, corrected, effective, coordinates, contentHash));
            }
            var coordinates = json.createObjectNode().put("experimentBoundaryId", experimentBoundaryId);
            coordinates.set("boundary", boundary.path("sourceCoordinates").deepCopy());
            var hashSource = json.createObjectNode();
            hashSource.set("boundary", boundary.deepCopy());
            hashSource.set("samples", json.valueToTree(samples));
            result.add(new SourceRecognitionRepository.RecognizedExperiment(experimentBoundaryId,
                    boundary.path("title").asText(baseName(job.sourceFileName())), samples,
                    coordinates, hashing.hash(hashSource)));
        }
        return List.copyOf(result);
    }

    private ObjectNode editModel(SourceRecognitionRepository.Job job,
                                 SourceRecognitionRepository.RecognizedExperiment recognizedExperiment) {
        var model = json.createObjectNode().put("title", recognizedExperiment.title()).put("purpose", "").put("plan", "")
                .put("documentFormat", isWorkbook(job.sourceFormat()) ? "excel" : "word")
                .put("sourceFileId", job.sourceFileId().toString()).put("sourceFileName", job.sourceFileName())
                .put("sourceFileSha256", job.sourceSha256())
                .put("experimentBoundaryId", recognizedExperiment.experimentBoundaryId())
                .put("visibility", job.visibility());
        model.set("documentSnapshot", job.workspace().path("documentSnapshot").deepCopy());
        var formulas = model.putArray("formulaItems");
        var processes = model.putArray("processSteps");
        var tests = model.putArray("testResults");
        var dynamic = model.putObject("dynamicValues");
        var logicalSamples = model.putArray("logicalSamples");
        for (var sample : recognizedExperiment.samples()) {
            var sampleNode = logicalSamples.addObject()
                    .put("sampleBoundaryId", sample.sampleBoundaryId())
                    .put("logicalSampleKey", sample.logicalSampleKey())
                    .put("title", sample.title());
            sampleNode.set("sourceGroupKeys", json.valueToTree(sample.sourceGroupKeys()));
            sampleNode.set("sourceCoordinates", sample.sourceCoordinates().deepCopy());
        }
        for (var candidate : job.workspace().path("candidates")) {
            if ("IGNORED".equals(candidate.path("status").asText())) continue;
            if (!recognizedExperiment.experimentBoundaryId().equals(
                    candidate.path("experimentBoundaryId").asText())) continue;
            var group = candidate.path("category").asText();
            var item = ((ObjectNode) candidate.deepCopy());
            item.set("effectiveValue", candidate.has("correctedValue")
                    ? candidate.path("correctedValue").deepCopy()
                    : candidate.path("parsedValue").deepCopy());
            if ("FORMULA".equals(group)) formulas.add(item);
            else if ("PROCESS".equals(group)) processes.add(item);
            else if ("TEST".equals(group)) tests.add(item);
            else dynamic.set(candidate.path("candidateId").asText(), item);
        }
        model.set("tables", json.createArrayNode());
        model.set("events", json.createArrayNode());
        model.set("conclusion", json.createObjectNode().put("resultStatus", "")
                .put("mainConclusion", "").put("failureCategory", ""));
        var recognition = json.createObjectNode().put("schemaVersion", 1)
                .put("recognitionJobId", job.id().toString()).put("recognitionRevision", job.recognitionRevision())
                .put("parserVersion", job.parserVersion())
                .put("experimentBoundaryId", recognizedExperiment.experimentBoundaryId())
                .put("contentHash", recognizedExperiment.contentHash());
        recognition.set("sourceCoordinates", recognizedExperiment.sourceCoordinates().deepCopy());
        recognition.set("logicalSamples", json.valueToTree(recognizedExperiment.samples()));
        recognition.set("unrecognizedFragments", job.workspace().path("unrecognizedFragments").deepCopy());
        recognition.set("issues", job.workspace().path("issues").deepCopy());
        model.set("recognitionSnapshot", recognition);
        return model;
    }

    private boolean hasUnconfirmedLowConfidence(JsonNode workspace) {
        for (var candidate : workspace.path("candidates"))
            if ("REVIEW_REQUIRED".equals(candidate.path("status").asText())) return true;
        return false;
    }

    void validateBoundaries(JsonNode boundaries) {
        requireArray(boundaries, "实验边界");
        if (boundaries.isEmpty()) throw validation("至少需要一个实验边界");
        var experimentIds = new HashSet<String>();
        var sampleIds = new HashSet<String>();
        var logicalKeys = new HashSet<String>();
        var groups = new HashSet<String>();
        for (var boundary : boundaries) {
            var id = boundary.path("experimentBoundaryId").asText("").strip();
            if (id.isBlank() || !experimentIds.add(id)) throw validation("实验边界ID不能为空且不能重复");
            if (!boundary.path("samples").isArray() || boundary.path("samples").isEmpty())
                throw validation("每个实验至少需要一个样本边界");
            for (var sample : boundary.path("samples")) {
                var sampleId = sample.path("sampleBoundaryId").asText("").strip();
                var logicalKey = sample.path("logicalSampleKey").asText("").strip();
                if (sampleId.isBlank() || !sampleIds.add(sampleId))
                    throw validation("样本边界ID不能为空且在识别任务内不能重复");
                if (logicalKey.isBlank() || logicalKey.length() > 320 || !logicalKeys.add(logicalKey))
                    throw validation("逻辑样本Key不能为空、不能重复且最长320字符");
                if (!sample.path("sourceGroupKeys").isArray() || sample.path("sourceGroupKeys").isEmpty())
                    throw validation("每个逻辑样本至少需要一个物理来源组");
                for (var sourceGroup : sample.path("sourceGroupKeys")) {
                    var group = sourceGroup.asText("").strip();
                    if (group.isBlank() || !groups.add(group))
                        throw validation("物理来源组不能为空且不能同时归属于多个逻辑样本");
                }
            }
        }
    }

    private void requireConfirmedBoundaries(JsonNode boundaries) {
        for (var boundary : boundaries)
            if (!boundary.path("confirmed").asBoolean(false))
                throw validation("实验和样本边界必须由用户逐项确认");
    }

    private void validateCandidateDecisions(JsonNode candidates) {
        var ids = new HashSet<String>();
        for (var candidate : candidates) {
            var id = candidate.path("candidateId").asText("");
            if (id.isBlank() || !ids.add(id)) throw validation("字段候选ID不能为空且不能重复");
            if (!Set.of("SUGGESTED", "REVIEW_REQUIRED", "CONFIRMED", "CORRECTED", "IGNORED")
                    .contains(candidate.path("status").asText())) throw validation("字段候选状态不合法");
            if (!candidate.path("sourceCoordinate").isObject()) throw validation("字段候选必须保留来源坐标");
            if (candidate.path("experimentBoundaryId").asText("").isBlank()
                    || candidate.path("sampleBoundaryId").asText("").isBlank())
                throw validation("字段候选必须归属明确的实验和逻辑样本边界");
            if (candidate.path("sourceGroupKey").asText("").isBlank())
                throw validation("字段候选必须保留物理来源组");
            if ("TEST".equals(candidate.path("category").asText())) {
                if (candidate.path("observationId").asText("").isBlank()
                        || candidate.path("replicateGroupKey").asText("").isBlank()
                        || candidate.path("measurementIndex").asInt(0) < 1)
                    throw validation("测试结果必须保留观测ID、重复测量组和测量序号");
            }
        }
    }

    void validateCandidateAssignments(JsonNode boundaries, JsonNode candidates) {
        var assignments = new HashMap<String, Set<String>>();
        for (var boundary : boundaries) {
            var experimentBoundaryId = boundary.path("experimentBoundaryId").asText();
            for (var sample : boundary.path("samples")) {
                assignments.put(experimentBoundaryId + "\u0000" + sample.path("sampleBoundaryId").asText(),
                        new HashSet<>(strings(sample.path("sourceGroupKeys"))));
            }
        }
        var observationIds = new HashSet<String>();
        var measurementKeys = new HashSet<String>();
        for (var candidate : candidates) {
            var assignmentKey = candidate.path("experimentBoundaryId").asText() + "\u0000"
                    + candidate.path("sampleBoundaryId").asText();
            var sourceGroups = assignments.get(assignmentKey);
            if (sourceGroups == null) throw validation("字段候选引用了不存在的实验或样本边界");
            if (!sourceGroups.contains(candidate.path("sourceGroupKey").asText()))
                throw validation("字段候选的物理来源组与逻辑样本边界不一致");
            if ("TEST".equals(candidate.path("category").asText())) {
                var observationId = candidate.path("observationId").asText();
                if (!observationIds.add(observationId)) throw validation("观测ID不能重复");
                var measurementKey = assignmentKey + "\u0000" + candidate.path("replicateGroupKey").asText()
                        + "\u0000" + candidate.path("measurementIndex").asInt();
                if (!measurementKeys.add(measurementKey)) throw validation("同一重复测量组内的测量序号不能重复");
            }
        }
    }

    private void auditChange(UUID organizationId, UUID actorId, UUID id, String action, long revision) {
        audit.append(organizationId, actorId, action, "SOURCE_RECOGNITION_JOB", id,
                json.createObjectNode().put("expectedRevision", revision));
    }

    private SourceRecognitionRepository.Job required(UUID organizationId, UUID id, String owner) {
        return repository.find(organizationId, id, owner)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "识别任务不存在"));
    }

    private String checkedOwner(String value) { requireOwner(value); return value; }
    private void requireOwner(String value) {
        if (!Set.of(DATA_CENTER, EXPERIMENT).contains(value)) throw validation("来源所有者不合法");
    }
    private void requireArray(JsonNode value, String name) {
        if (value == null || !value.isArray()) throw validation(name + "必须是数组");
    }
    private String normalizeMode(String value, UUID templateVersionId) {
        if (value == null || value.isBlank()) return templateVersionId == null ? "FREEFORM" : "TEMPLATE_GUIDED";
        var result = value.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("FREEFORM", "TEMPLATE_GUIDED").contains(result)) throw validation("识别模式不合法");
        return result;
    }
    private String normalizePurpose(String value) {
        var result = value == null || value.isBlank() ? "DATA_ONLY" : value.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("DATA_ONLY", "EXPERIMENT_DRAFT").contains(result)) throw validation("导入用途不合法");
        return result;
    }
    private String normalizeVisibility(String value) {
        var result = value == null || value.isBlank() ? "ALL" : value.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "QUALITY", "PROJECT").contains(result)) throw validation("可见范围不合法");
        return result;
    }
    private String normalizeFormat(String value) {
        try { return TemplateFormat.valueOf(value.strip().toUpperCase(Locale.ROOT)).name(); }
        catch (RuntimeException ignored) { throw validation("不支持的来源文件格式"); }
    }
    private String sourceFormat(String fileName) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx")) return "XLSX";
        if (name.endsWith(".xls")) return "XLS";
        if (name.endsWith(".csv")) return "CSV";
        if (name.endsWith(".doc") || name.endsWith(".docx")) return "DOCX";
        if (name.endsWith(".pdf")) return "PDF";
        if (name.matches(".*\\.(png|jpg|jpeg|tif|tiff|bmp|webp)$")) return "IMAGE";
        throw validation("仅支持 Excel、CSV、Word、PDF 和图片来源");
    }
    private boolean isWorkbook(String format) { return Set.of("XLS", "XLSX", "CSV", "IMAGE").contains(format); }
    private String baseName(String name) { return name == null ? "未命名实验" : name.replaceFirst("(?i)\\.[^.]+$", ""); }
    private String customerMessage(RuntimeException exception) {
        return exception instanceof ApiException ? exception.getMessage() : "来源文件识别失败，请检查文件后重试";
    }
    private void put(ObjectNode node, String name, UUID value) { if (value != null) node.put(name, value.toString()); }
    private UUID uuid(JsonNode node, String name) {
        try { var value = node.path(name).asText(""); return value.isBlank() ? null : UUID.fromString(value); }
        catch (RuntimeException ignored) { return null; }
    }
    private LocalDate date(String value) { try { return value == null ? null : LocalDate.parse(value); } catch (RuntimeException ignored) { return null; } }
    private int integer(String value) { try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return 0; } }
    private Iterable<JsonNode> iterable(JsonNode value) { return value != null && value.isArray() ? value : List.of(); }
    private List<String> strings(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        var result = new ArrayList<String>();
        for (var item : value) {
            var text = item.asText("").strip();
            if (!text.isBlank() && !result.contains(text)) result.add(text);
        }
        return List.copyOf(result);
    }
    private ObjectNode coordinate(String kind, int order, String sheetId, String address) {
        var node = json.createObjectNode().put("kind", kind).put("order", order);
        if (sheetId != null) node.put("sheetId", sheetId);
        if (address != null) node.put("address", address);
        return node;
    }
    private String columnName(int value) {
        var n = Math.max(1, value); var result = new StringBuilder();
        while (n > 0) { n--; result.insert(0, (char) ('A' + n % 26)); n /= 26; }
        return result.toString();
    }
    private String unit(String value) {
        var match = UNIT.matcher(value == null ? "" : value.strip()); return match.matches() ? match.group(2) : "";
    }
    private String stripUnit(String value) {
        var match = UNIT.matcher(value == null ? "" : value.strip()); return match.matches() ? match.group(1) : value;
    }
    private String physicalGroupKey(JsonNode fragment) {
        var coordinate = fragment.path("sourceCoordinate");
        return switch (coordinate.path("kind").asText()) {
            case "CELL" -> {
                var address = coordinate.path("address").asText("");
                var row = address.replaceFirst("^[A-Za-z]+", "");
                yield "sheet:" + coordinate.path("sheetId").asText("unknown") + ":row:"
                        + (row.isBlank() ? coordinate.path("order").asText("unknown") : row);
            }
            case "PAGE" -> "page:" + coordinate.path("pageNo").asText(coordinate.path("order").asText("unknown"));
            case "DOCUMENT_BLOCK" -> "block:" + coordinate.path("order").asText(fragment.path("fragmentId").asText());
            default -> "fragment:" + fragment.path("fragmentId").asText();
        };
    }
    private static ApiException validation(String message) { return new ApiException(ApiErrorCode.VALIDATION_ERROR, message); }

    public record CreateCommand(UUID sourceFileId, String sourceFormat, String recognitionMode,
                                UUID templateVersionId, UUID categoryId, String importPurpose,
                                UUID targetExperimentCategoryId, UUID projectId, UUID stageId,
                                UUID taskId, LocalDate experimentDate, String visibility,
                                boolean duplicateOverride) {}
    public record DraftResult(UUID experimentId, UUID experimentVersionId, String experimentNo,
                              String title, String experimentBoundaryId, List<String> logicalSampleKeys) {}
    public record FinalizeResult(UUID recognitionJobId, UUID confirmedSubmissionId,
                                 Integer submissionRevision, List<DraftResult> drafts) {}

    private record FieldRule(Pattern pattern, String category, String fieldCode) {}
    private static final Pattern LABEL_VALUE = Pattern.compile("^(.{1,40}?)[：:=]\\s*(.+)$");
    private static final Pattern UNIT = Pattern.compile("^\\s*(.*?)\\s*(%|份|g|kg|mg|GU|mJ/cm(?:2|²)|mW/cm(?:2|²)|℃|°C|min|s|rpm)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final List<FieldRule> FIELD_RULES = List.of(
            rule("实验名称|标题|名称|title", "BASIC", "title"),
            rule("样本编号|样品编号|试样编号|sample\\s*(id|no|number)", "BASIC", "sampleIdentity"),
            rule("日期|date", "BASIC", "experimentDate"),
            rule("人员|实验员|操作员|operator|owner", "BASIC", "ownerName"),
            rule("目的|purpose", "BASIC", "purpose"), rule("计划|plan", "BASIC", "plan"),
            rule("结论|conclusion", "BASIC", "conclusion"),
            rule("材料|物料|树脂|单体|光引发剂|助剂|配方|比例|用量|份数", "FORMULA", "formulaComponent"),
            rule("温度|时间|转速|能量|光强|涂布器|湿度|工艺|设备|步骤", "PROCESS", "processCondition"),
            rule("60\\s*[°度]?\\s*光泽|光泽", "TEST", "gloss60"),
            rule("铅笔硬度|邵氏硬度|硬度", "TEST", "hardness"),
            rule("UV\\s*表干|表干", "TEST", "uvSurfaceDry"),
            rule("附着力", "TEST", "adhesion"), rule("粘度", "TEST", "viscosity"),
            rule("固含", "TEST", "solidContent"), rule("测试|结果", "TEST", "testResult")
    );
    private FieldRule fieldRule(String label) {
        if (label == null || label.isBlank()) return null;
        return FIELD_RULES.stream().filter(rule -> rule.pattern().matcher(label).find()).findFirst().orElse(null);
    }
    private static FieldRule rule(String regex, String category, String code) {
        return new FieldRule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), category, code);
    }
}
