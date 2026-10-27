package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import com.jsd.aird.tpl.api.ProjectTemplateFacade;
import com.jsd.aird.tpl.application.port.TabularStructureParser;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Service;

@Service
public class TemplateDataImportFacadeImpl implements TemplateDataImportFacade, ProjectTemplateFacade {

    private final TemplateRepository repository;
    private final FileObjectRepository files;
    private final ObjectStorage storage;
    private final TabularStructureParser parser;
    private final StandardFieldService standardFieldService;
    private final SnapshotWorkbookExporter workbookExporter;
    private final ObjectMapper objectMapper;
    private final com.jsd.aird.shared.excel.WorkbookInstanceParser workbookInstanceParser;

    public TemplateDataImportFacadeImpl(
            TemplateRepository repository,
            FileObjectRepository files,
            ObjectStorage storage,
            TabularStructureParser parser,
            StandardFieldService standardFieldService,
            SnapshotWorkbookExporter workbookExporter,
            ObjectMapper objectMapper
    ) {
        this(repository,files,storage,parser,standardFieldService,workbookExporter,objectMapper,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TemplateDataImportFacadeImpl(TemplateRepository repository,FileObjectRepository files,ObjectStorage storage,
            TabularStructureParser parser,StandardFieldService standardFieldService,SnapshotWorkbookExporter workbookExporter,
            ObjectMapper objectMapper,com.jsd.aird.shared.excel.WorkbookInstanceParser workbookInstanceParser) {
        this.repository = repository;
        this.files = files;
        this.storage = storage;
        this.parser = parser;
        this.standardFieldService = standardFieldService;
        this.workbookExporter = workbookExporter;
        this.objectMapper = objectMapper;
        this.workbookInstanceParser=workbookInstanceParser;
    }

    @Override
    public List<DataTemplateOption> listPublished(UUID organizationId) {
        var items = repository.findPublishedDataTemplates(organizationId);
        var contracts = repository.findImportContracts(organizationId,
                items.stream().map(TemplateRepository.TemplateListItem::versionId).toList());
        boolean unified=items.stream().anyMatch(item->java.util.Set.of(UnifiedLabTemplateCatalog.TEST,UnifiedLabTemplateCatalog.SYNTHESIS).contains(item.templateCode()));
        return items.stream()
                .map(item -> option(item, contracts.get(item.versionId())))
                .filter(item->!unified||!"EXPERIMENT_DATA".equals(item.templateUsage())||java.util.Set.of(UnifiedLabTemplateCatalog.TEST,UnifiedLabTemplateCatalog.SYNTHESIS).contains(item.templateCode()))
                .toList();
    }

    private DataTemplateOption option(TemplateRepository.TemplateListItem item,
                                      TemplateRepository.ImportContract stored) {
        var contract = stored == null ? null : stored.contract();
        var experimentImport = contract == null ? null : contract.path("experimentImport");
        var recordMode = experimentImport == null ? "" : experimentImport.path("recordMode").asText("");
        var identityTypes = new java.util.LinkedHashSet<String>();
        if (experimentImport != null && experimentImport.path("identities").isArray()) {
            experimentImport.path("identities").forEach(identity -> {
                var type = identity.path("identityType").asText("");
                if (!type.isBlank()) identityTypes.add(type);
            });
        }
        var version = stored == null ? 0 : stored.importContractVersion();
        var usage = isExperimentContract(stored)
                ? contract.path("templateUsage").asText("GENERAL_DATA") : "GENERAL_DATA";
        var ready = "EXPERIMENT_DATA".equals(usage)
                && ("SINGLE_FILE".equals(recordMode) || !identityTypes.isEmpty());
        return new DataTemplateOption(item.templateId(), item.versionId(), item.templateCode(), item.name(),
                item.category(), item.versionNo(), item.format().name(), version, usage, ready,
                recordMode.isBlank() ? null : recordMode, List.copyOf(identityTypes),
                experimentImport == null ? 0 : experimentImport.path("identities").size());
    }

    @Override
    public List<ProjectTemplateOption> listPublishedForProject(UUID organizationId) {
        // Reuse the same published-template query as the data-center adapter;
        // project detail only changes the public entry point and permission
        // boundary, not the template selection rules.
        return listPublished(organizationId).stream()
                .map(item -> new ProjectTemplateOption(
                        item.templateId(), item.versionId(), item.templateCode(), item.name(),
                        item.category(), item.versionNo(), item.format()))
                .toList();
    }

    @Override
    public ProjectTemplateEditModel getPublishedEditModel(UUID organizationId, UUID versionId) {
        var workspace = repository.findPublishedDataTemplate(organizationId, versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "项目模板不存在或未发布"));
        return new ProjectTemplateEditModel(
                workspace.templateId(), workspace.versionId(), workspace.templateCode(), workspace.name(),
                workspace.format().name(), readSnapshot(organizationId, workspace), workspace.snapshotHash());
    }

    @Override
    public DataTemplateDefinition getPublished(UUID organizationId, UUID templateVersionId) {
        var workspace = repository.findPublishedDataTemplate(organizationId, templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据中心模板不存在或未发布"));
        var definition=definition(organizationId, workspace);
        if(definition.importContract()!=null&&"EXPERIMENT_DATA".equals(definition.importContract().path("templateUsage").asText()))requireCurrentLabTemplate(organizationId,workspace.templateCode());
        return definition;
    }

    @Override
    public PublishedExperimentTemplate getPublishedExperimentTemplate(UUID organizationId, String templateCode) {
        if (templateCode == null || templateCode.isBlank()) {
            throw new ApiException(ApiErrorCode.EXPERIMENT_TEMPLATE_REQUIRED, "AI任务未配置实验模板编码");
        }
        requireCurrentLabTemplate(organizationId,templateCode.strip());
        var item = repository.findPublishedDataTemplates(organizationId).stream()
                .filter(candidate -> templateCode.strip().equals(candidate.templateCode()))
                .findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.EXPERIMENT_TEMPLATE_REQUIRED,
                        "未找到已发布的实验模板：" + templateCode.strip()));
        var workspace = repository.findPublishedDataTemplate(organizationId, item.versionId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.EXPERIMENT_TEMPLATE_REQUIRED,
                        "实验模板当前发布版本不可用：" + templateCode.strip()));
        return experimentTemplate(organizationId, workspace, templateCode.strip());
    }

    @Override
    public PublishedExperimentTemplate getExperimentTemplateVersion(UUID organizationId, UUID templateVersionId) {
        var workspace = repository.findWorkspace(organizationId, templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.EXPERIMENT_TEMPLATE_REQUIRED,
                        "导入任务引用的实验模板版本不存在"));
        return experimentTemplate(organizationId, workspace, workspace.templateCode());
    }

    private PublishedExperimentTemplate experimentTemplate(UUID organizationId,
                                                           TemplateRepository.TemplateWorkspace workspace,
                                                           String templateCode) {
        var stored = repository.findImportContract(organizationId, workspace.versionId()).orElse(null);
        var contract = stored == null ? null : stored.contract();
        var validExperimentTemplate = workspace.format() == TemplateFormat.XLSX
                && isExperimentContract(stored);
        if (!validExperimentTemplate) {
            throw new ApiException(ApiErrorCode.EXPERIMENT_TEMPLATE_REQUIRED,
                    "模板未发布为可生成实验草稿的V9实验模板：" + templateCode);
        }
        if (workspace.snapshotHash() == null || workspace.snapshotHash().isBlank()) {
            throw new ApiException(ApiErrorCode.EXPERIMENT_TEMPLATE_REQUIRED,
                    "已发布实验模板缺少不可变快照哈希：" + templateCode);
        }
        return new PublishedExperimentTemplate(workspace.templateId(), workspace.versionId(),
                workspace.templateCode(), workspace.name(), workspace.versionNo(), workspace.snapshotHash(),
                readSnapshot(organizationId, workspace).deepCopy(), workspace.mapping().deepCopy(),
                contract.deepCopy());
    }

    @Override
    public DataTemplateDefinition getVersion(UUID organizationId, UUID templateVersionId) {
        var workspace = repository.findWorkspace(organizationId, templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务引用的模板版本不存在"));
        return definition(organizationId, workspace);
    }

    @Override
    public List<ImportBinding> getPublishedBindings(UUID organizationId, UUID templateVersionId) {
        var workspace = repository.findPublishedDataTemplate(organizationId, templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据中心模板不存在或未发布"));
        return bindings(organizationId, workspace);
    }

    @Override
    public List<ImportBinding> getBindings(UUID organizationId, UUID templateVersionId) {
        var workspace = repository.findWorkspace(organizationId, templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务引用的模板版本不存在"));
        return bindings(organizationId, workspace);
    }

    private List<ImportBinding> bindings(UUID organizationId, TemplateRepository.TemplateWorkspace workspace) {
        var storedContract = repository.findImportContract(organizationId, workspace.versionId()).orElse(null);
        var contract = storedContract == null ? null : storedContract.contract();
        var sourceBindings = isExperimentContract(storedContract)
                ? contractBindings(contract) : workspace.mapping();
        return bindings(sourceBindings,contract);
    }
    private List<ImportBinding> bindings(JsonNode sourceBindings,JsonNode contract) {
        var result = new ArrayList<ImportBinding>();
        if (sourceBindings == null || !sourceBindings.isArray()) return result;
        var componentByBinding = componentByBinding(contract);
        for (JsonNode item : sourceBindings) {
            var fieldCode = firstText(item, "fieldCode", "field_code");
            if (fieldCode.isBlank() && !item.path("mappingKind").asText("").contains("REGION")) continue;
            var valueSource = firstText(item, "valueSource", "value_source", "INPUT");
            var bindingId = firstText(item, "bindingId", "binding_id");
            var locator = importLocator(item);
            if (locator instanceof ObjectNode target && target.path("componentId").asText("").isBlank()) {
                var componentId = componentByBinding.getOrDefault(bindingId,
                        componentByBinding.getOrDefault(firstText(item, "parentBindingId", "parent_binding_id"), ""));
                if (!componentId.isBlank()) target.put("componentId", componentId);
            }
            result.add(new ImportBinding(
                    bindingId, fieldCode,
                    firstText(item, "dataPath", "data_path", fieldCode),
                    firstText(item, "mappingKind", "mapping_kind", "SCALAR"),
                    firstText(item, "parentBindingId", "parent_binding_id"),
                    firstText(item, "repeatAxis", "repeat_axis"),
                    Math.max(1, item.path("recordHeight").asInt(1)),
                    Math.max(1, item.path("recordWidth").asInt(1)),
                    Math.max(1, item.path("recordStride").asInt(1)),
                    item.path("terminationRule").isMissingNode()
                            ? item.path("termination_jsonb").isMissingNode()
                            ? item.path("termination") : item.path("termination_jsonb")
                            : item.path("terminationRule"),
                    locator,
                    item.path("required").asBoolean(false), item.path("identity").asBoolean(false),
                    item.path("trainingEligible").asBoolean(!"FORMULA".equalsIgnoreCase(valueSource)),
                    valueSource, firstText(item, "valueType", "dataType", "TEXT"),
                    firstText(item, "unit", "defaultUnit", ""),
                    labelPath(item), labelPathSegments(item), firstText(item, "trainingRole", "training_role",
                            item.path("trainingEligible").asBoolean(true) ? "FEATURE" : "EXCLUDE"),
                    item.path("ragEligible").asBoolean(true),
                    item.path("experimentField").isObject() ? item.path("experimentField").deepCopy() : null,
                    firstText(item, "targetPath", "target_path")));
        }
        return List.copyOf(result);
    }

    /**
     * Older published contracts may carry a V9 marker because recognition
     * mistook a batch number for an experiment identity. Treat a contract as
     * experimental only when it contains an explicit experiment identity or a
     * structured list projection; a batch-only contract remains ordinary data.
     */
    private boolean isExperimentContract(TemplateRepository.ImportContract stored) {
        if (stored == null
                || stored.importContractVersion() < TemplateImportContractCompiler.EXPERIMENT_IMPORT_CONTRACT_VERSION
                || stored.contract() == null
                || !"EXPERIMENT_DATA".equals(stored.contract().path("templateUsage").asText(""))) {
            return false;
        }
        var experimentImport = stored.contract().path("experimentImport");
        var projections = stored.contract().path("listProjections");
        if (projections.isArray() && !projections.isEmpty()) return true;
        var identities = experimentImport.path("identities");
        if (!identities.isArray() || identities.isEmpty()) return true;
        for (var identity : identities) {
            if (!"BATCH_NO".equals(identity.path("identityType").asText(""))) return true;
        }
        return false;
    }

    private JsonNode contractBindings(JsonNode contract) {
        var result = objectMapper.createArrayNode();
        if (contract == null || !contract.path("components").isArray()) return result;
        for (var component : contract.path("components")) {
            var componentId = component.path("componentId").asText("");
            for (var binding : component.path("bindings")) {
                var copy = binding.deepCopy();
                if (copy instanceof ObjectNode object && !componentId.isBlank()) {
                    object.withObject("locator").put("componentId", componentId);
                }
                result.add(copy);
            }
        }
        return result;
    }

    private LinkedHashMap<String, String> componentByBinding(JsonNode contract) {
        var result = new LinkedHashMap<String, String>();
        if (contract == null || !contract.path("components").isArray()) return result;
        for (var component : contract.path("components")) {
            var componentId = component.path("componentId").asText("");
            if (componentId.isBlank()) continue;
            for (var binding : component.path("bindings")) {
                var bindingId = binding.path("bindingId").asText("");
                if (!bindingId.isBlank()) result.put(bindingId, componentId);
            }
        }
        return result;
    }

    private String labelPath(JsonNode item) {
        var direct = firstText(item, "labelPath", "label_path");
        if (!direct.isBlank()) return direct;
        var diagnostic = item.path("diagnostic");
        if (diagnostic.path("labelPath").isArray()) {
            var labels = new ArrayList<String>();
            diagnostic.path("labelPath").forEach(value -> {
                if (!value.asText("").isBlank()) labels.add(value.asText());
            });
            if (!labels.isEmpty()) return String.join(" > ", labels);
        }
        return firstText(item, "fieldName", "displayName", "fieldCode");
    }

    private List<String> labelPathSegments(JsonNode item) {
        var result = new ArrayList<String>();
        var source = item.path("labelPathSegments");
        if (!source.isArray()) source = item.path("diagnostic").path("labelPathSegments");
        if (source.isArray()) source.forEach(value -> {
            var segment = value.asText("").strip();
            if (!segment.isBlank()) result.add(segment);
        });
        if (result.isEmpty()) {
            var path = labelPath(item);
            if (!path.isBlank()) {
                for (var segment : path.split("\\s*>\\s*")) {
                    if (!segment.isBlank()) result.add(segment.strip());
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * The published template stores geometry on the binding and the richer
     * projection contract in diagnostic metadata. Keep both available to the
     * data importer without changing the public ImportBinding shape.
     */
    private JsonNode importLocator(JsonNode item) {
        JsonNode source = item.path("locator").isObject() ? item.path("locator") : item.path("locator_jsonb");
        ObjectNode locator = source.isObject()
                ? (ObjectNode) source.deepCopy()
                : objectMapper.createObjectNode();
        JsonNode diagnostic = item.path("diagnostic");
        for (var key : List.of("tableModel", "columns", "kind", "blockType", "valueType", "role",
                "groupName", "displayName", "title")) {
            if (!locator.has(key) && diagnostic.isObject() && diagnostic.has(key)) {
                locator.set(key, diagnostic.path(key).deepCopy());
            }
        }
        for (var key : List.of("componentId", "sheetId", "sheet", "headerRange", "dataRange",
                "recordAxis", "semanticMode", "repeatAxis", "valueMode")) {
            if (!locator.has(key) && item.has(key)) locator.set(key, item.path(key).deepCopy());
        }
        return locator;
    }

    @Override
    public ParsedTabularFile parse(UUID organizationId, UUID templateVersionId, UUID fileId) {
        getVersion(organizationId, templateVersionId);
        var file = files.find(organizationId, fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY, "数据源文件不存在"));
        try (var stored = storage.get(file.objectKey())) {
            return parser.parse(stored.stream(), file.originalName());
        } catch (Exception exception) {
            if (exception instanceof ApiException apiException) throw apiException;
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "数据源文件解析失败：" + exception.getMessage());
        }
    }

    @Override
    public ImportExecution prepareImport(UUID organizationId,UUID versionId,UUID fileId) {
        var workspace=repository.findWorkspace(organizationId,versionId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"模板版本不存在"));
        if(!workspace.schema().path("x-jsd-lab-template").isObject()) {
            if(workbookInstanceParser!=null) {
                var file=files.find(organizationId,fileId).orElseThrow(()->new ApiException(ApiErrorCode.FILE_NOT_READY,"数据源文件不存在"));
                if(file.originalName().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx"))try(var stored=storage.get(file.objectKey())) {
                    var snapshot=workbookInstanceParser.parseInstance(stored.stream()).snapshot();
                    var instance=com.jsd.aird.shared.excel.WorkbookStructure.instance(snapshot);
                    if(instance.isObject())return prepareWorkbookImport(organizationId,versionId,snapshot,instance);
                }catch(ApiException ex){throw ex;}catch(Exception ex){throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"工作簿结构读取失败："+ex.getMessage());}
            }
            return TemplateDataImportFacade.super.prepareImport(organizationId,versionId,fileId);
        }
        var file=files.find(organizationId,fileId).orElseThrow(()->new ApiException(ApiErrorCode.FILE_NOT_READY,"数据源文件不存在"));
        try(var stored=storage.get(file.objectKey())) {
            var source=workbookInstanceParser.parseInstance(stored.stream()).snapshot();
            var prepared=com.jsd.aird.shared.lab.LabWorkbook.prepare(objectMapper,source,readSnapshot(organizationId,workspace),fileId.toString());
            // Recompute mass totals from the raw g inputs. POI cannot evaluate all of
            // Univer's array formulas, and an uploaded formula cache is not evidence.
            com.jsd.aird.shared.lab.LabWorkbook.extract(objectMapper,prepared,objectMapper.createObjectNode(),fileId.toString());
            var pack=new UnifiedLabTemplateCatalog(objectMapper).describe(workspace.templateCode(),prepared,objectMapper.createObjectNode()).payload();
            var fields=new LinkedHashMap<String,FieldDefinition>();collectSchemaFields(pack.path("schema"),fields);
            var contract=pack.path("contract");
            var definition=new DataTemplateDefinition(workspace.templateId(),versionId,workspace.templateCode(),workspace.name(),null,workspace.versionNo(),"XLSX",pack.path("schema"),pack.path("mapping"),List.copyOf(fields.values()),9,contract.path("layoutStructureVersion").asInt(),contract.path("contractHash").asText(),contract);
            var tabularSnapshot=prepared.deepCopy();
            var totalRow=com.jsd.aird.shared.lab.LabWorkbook.definition(prepared).path("formulaTotalRow").asInt();
            var totalSheet=com.jsd.aird.shared.lab.LabWorkbook.sheet(tabularSnapshot);
            for(var cell:totalSheet.path("cellData").path(""+totalRow))if(cell instanceof ObjectNode object)object.remove("f");
            var bytes=workbookExporter.export(tabularSnapshot,objectMapper.createArrayNode(),objectMapper.createObjectNode(),null).content();
            var parsed=parser.parse(new java.io.ByteArrayInputStream(bytes),"experiment.xlsx");
            for(var sheet:parsed.sheets())for(var cell:sheet.layoutIr().path("cells")) {
                if(cell.path("row").asInt()!=totalRow+1||cell.path("column").asInt()<3)continue;
                ((ObjectNode)cell).put("valueSource","FORMULA").put("calculationSource","UNIFIED_LAB_RECALCULATED")
                        .put("calculationStatus","VALID").put("formulaTrustStatus","TRUSTED_RECALCULATED");
            }
            parsed=instanceParsedSheets(parsed,prepared);
            return new ImportExecution(definition,bindings(contractBindings(contract),contract),parsed);
        }catch(ApiException ex){throw ex;}catch(Exception ex){throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"实验文件解析失败："+ex.getMessage());}
    }

    @Override
    public FieldRequest requestField(UUID organizationId, UUID templateVersionId, FieldRequestCommand command) {
        getPublished(organizationId, templateVersionId);
        var request = standardFieldService.request(new StandardFieldService.RequestCommand(
                templateVersionId, command.fieldId(), command.displayName(), command.valueType(),
                command.uiType(), command.groupCode(), command.description()));
        return new FieldRequest(request.id(), request.templateVersionId(), request.displayName(), request.valueType(), request.status());
    }

    @Override
    public ImportExecution prepareWorkbookImport(UUID organizationId,UUID versionId,JsonNode source,JsonNode provided) {
        var workspace=repository.findWorkspace(organizationId,versionId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"模板版本不存在"));
        var snapshot=(ObjectNode)source.deepCopy();
        var instance=com.jsd.aird.shared.excel.WorkbookStructure.validate(objectMapper,snapshot,provided);
        if(com.jsd.aird.shared.lab.LabWorkbook.supported(snapshot)) {
            snapshot=com.jsd.aird.shared.lab.LabWorkbook.prepare(objectMapper,snapshot,readSnapshot(organizationId,workspace),versionId.toString());
            com.jsd.aird.shared.lab.LabWorkbook.extract(objectMapper,snapshot,objectMapper.createObjectNode(),versionId.toString());
        }
        var schema=(ObjectNode)workspace.schema().deepCopy();schema.set("x-jsd-field-model",instance.path("model").deepCopy());schema.set("x-jsd-workbook-instance",instance.deepCopy());
        if(instance.path("execution").isObject())schema.set("x-jsd-experiment-import",instance.path("execution").deepCopy());
        var mappings=objectMapper.createArrayNode();var valid=new java.util.HashSet<String>();
        for(var binding:instance.path("mapping")) {
            var locator=binding.path("locator");
            if(!"VALID".equals(binding.path("bindingStatus").asText())||com.jsd.aird.shared.excel.WorkbookStructure.first(locator,"valueRange","address","dataRange","range").isBlank())continue;
            boolean pending=false;
            for(var field:instance.path("model").path("fields"))if(field.path("bindingId").equals(binding.path("bindingId"))&&(java.util.Set.of("NEEDS_CONFIRMATION","ISSUE").contains(field.path("reviewStatus").asText())||(instance.path("execution").path("templateUsage").asText().equals("EXPERIMENT_DATA")&&field.path("experimentSemanticStatus").asText().equals("NEEDS_REVIEW")))){pending=true;break;}
            if(pending)continue;
            var copy=(ObjectNode)binding.deepCopy();
            for(var field:instance.path("model").path("fields"))if(field.path("bindingId").asText().equals(binding.path("bindingId").asText())) {
                copy.put("unit",field.path("unit").asText()).put("valueType",field.path("valueType").asText("string")).put("required",field.path("required").asBoolean());
                if(field.path("experimentField").isObject())copy.set("experimentField",field.path("experimentField").deepCopy());
                boolean publicField=false;
                if(workspace.mapping()!=null)for(var standard:workspace.mapping())if(standard.path("bindingId").equals(binding.path("bindingId"))&&standard.path("fieldCode").equals(binding.path("fieldCode"))){publicField=true;break;}
                if(!publicField||"INSTANCE_LOCAL".equals(field.path("fieldOrigin").asText()))copy.put("trainingEligible",false).put("trainingRole","EXCLUDE");
                copy.withObject("/diagnostic").put("displayName",field.path("name").asText());
            }
            mappings.add(copy);valid.add(binding.path("bindingId").asText());
        }
        var executableFields=objectMapper.createArrayNode();
        for(var f:instance.path("model").path("fields"))if(valid.contains(f.path("bindingId").asText()))executableFields.add(f.deepCopy());
        schema.withObject("/x-jsd-field-model").set("fields",executableFields);
        var canonical=new com.jsd.aird.shared.json.JsonCanonicalizer(objectMapper);
        var compiled=new TemplateImportContractCompiler(objectMapper,canonical).compile(objectMapper.createObjectNode().put("structureVersion",7),schema,mappings);
        var fields=new LinkedHashMap<String,FieldDefinition>();collectSchemaFields(schema,fields);
        // Current instance properties override legacy schema properties and
        // parent-column copies, which may still retain the field's initial name.
        for(var field:executableFields) {
            var code=field.path("fieldCode").asText();if(code.isBlank()||field.path("displayRole").asText().equals("REGION"))continue;
            fields.put(code,new FieldDefinition(code,field.path("name").asText(code),field.path("valueType").asText("string"),field.path("unit").asText(""),field.path("required").asBoolean(),field.path("identity").asBoolean(),strings(field.path("aliases")),field.path("dataPath").asText(code)));
        }
        var definition=new DataTemplateDefinition(workspace.templateId(),versionId,workspace.templateCode(),workspace.name(),null,workspace.versionNo(),"XLSX",schema,mappings,List.copyOf(fields.values()),compiled.importContractVersion(),compiled.layoutStructureVersion(),compiled.contractHash(),compiled.contract());
        var tabular=snapshot.deepCopy();
        int totalRow=-1;
        if(com.jsd.aird.shared.lab.LabWorkbook.supported(tabular)) {
            totalRow=com.jsd.aird.shared.lab.LabWorkbook.definition(tabular).path("formulaTotalRow").asInt();
            for(var cell:com.jsd.aird.shared.lab.LabWorkbook.sheet(tabular).path("cellData").path(""+totalRow))if(cell instanceof ObjectNode object)object.remove("f");
        }
        try {
            var bytes=workbookExporter.export(tabular,objectMapper.createArrayNode(),objectMapper.createObjectNode(),null).content();
            var parsed=parser.parse(new java.io.ByteArrayInputStream(bytes),"workbook.xlsx");
            for(var sheet:parsed.sheets())for(var cell:sheet.layoutIr().path("cells"))if(totalRow>=0&&cell.path("row").asInt()==totalRow+1&&cell.path("column").asInt()>=3&&cell instanceof ObjectNode object)
                object.put("valueSource","FORMULA").put("calculationSource","UNIFIED_LAB_RECALCULATED").put("calculationStatus","VALID").put("formulaTrustStatus","TRUSTED_RECALCULATED");
            parsed=instanceParsedSheets(parsed,snapshot);
            return new ImportExecution(definition,bindings(contractBindings(compiled.contract()),compiled.contract()),parsed);
        }catch(Exception ex){if(ex instanceof ApiException api)throw api;throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"工作簿草稿解析失败："+ex.getMessage());}
    }

    private ParsedTabularFile instanceParsedSheets(ParsedTabularFile parsed,JsonNode snapshot) {
        return new ParsedTabularFile(parsed.format(),parsed.parserVersion(),parsed.sheets().stream().filter(s->!s.sheetName().startsWith("_JSD")).map(s->{
            String instanceSheetId=s.sheetId();
            for(var actual:snapshot.path("sheets"))if(actual.path("name").asText().equals(s.sheetName())){instanceSheetId=actual.path("id").asText(s.sheetId());break;}
            return new ParsedSheet(instanceSheetId,s.sheetName(),s.sheetOrder(),s.firstRow(),s.lastRow(),s.firstColumn(),s.lastColumn(),s.headerCandidates(),s.suggestedHeaderRow(),s.suggestedDataStartRow(),s.rows(),s.layoutIr(),s.structureFingerprint());
        }).toList());
    }

    @Override
    public WorkbookExport exportPublishedWorkbook(UUID organizationId, UUID templateVersionId,
                                                   JsonNode data, UUID revisionId) {
        var workspace = repository.findPublishedDataTemplate(organizationId, templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据中心模板不存在或未发布"));
        if (workspace.format() != TemplateFormat.XLSX) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "数据中心批量导出首期仅支持 XLSX 模板");
        }
        var result = workbookExporter.export(
                readSnapshot(organizationId, workspace), workspace.mapping(),
                data == null ? objectMapper.createObjectNode() : data,
                new SnapshotWorkbookExporter.Manifest(
                        workspace.versionId().toString(), workspace.schemaHash(), workspace.mappingHash(),
                        "DATA_CENTER_EXPORT", revisionId == null ? null : revisionId.toString()));
        return new WorkbookExport(result.content(), result.warnings().stream()
                .map(item -> new ExportWarning(item.code(), item.bindingId(), item.dataPath(), item.message()))
                .toList());
    }

    private DataTemplateDefinition definition(UUID organizationId, TemplateRepository.TemplateWorkspace workspace) {
        var fields = new LinkedHashMap<String, FieldDefinition>();
        collectSchemaFields(workspace.schema(), fields);
        if (workspace.mapping().isArray()) {
            for (JsonNode mapping : workspace.mapping()) {
                var code = text(mapping, "fieldCode", "field_code");
                if (code == null || code.isBlank()) continue;
                fields.putIfAbsent(code, new FieldDefinition(
                        code,
                        firstText(mapping, "fieldName", "name", "displayName", "fieldCode"),
                        firstText(mapping, "valueType", "dataType", "type", "TEXT"),
                        firstText(mapping, "unit", "defaultUnit", ""),
                        mapping.path("required").asBoolean(false),
                        mapping.path("identity").asBoolean(false),
                        strings(mapping.path("aliases")),
                        firstText(mapping, "dataPath", "fieldCode")));
            }
        }
        var contract = repository.findImportContract(organizationId, workspace.versionId()).orElse(null);
        var effectiveContractVersion = contract == null ? 0
                : isExperimentContract(contract)
                ? contract.importContractVersion()
                : Math.min(contract.importContractVersion(), TemplateImportContractCompiler.BASE_IMPORT_CONTRACT_VERSION);
        return new DataTemplateDefinition(
                workspace.templateId(), workspace.versionId(), workspace.templateCode(), workspace.name(),
                null, workspace.versionNo(), workspace.format().name(), workspace.schema(),
                workspace.mapping(), List.copyOf(fields.values()),
                effectiveContractVersion,
                contract == null ? 0 : contract.layoutStructureVersion(),
                contract == null ? null : contract.contractHash(),
                contract == null ? null : contract.contract());
    }


    private JsonNode readSnapshot(UUID organizationId, TemplateRepository.TemplateWorkspace workspace) {
        var inline = workspace.inlineSnapshot();
        if (inline != null && inline.isObject() && !inline.isEmpty()) return inline;
        if (workspace.snapshotFileId() == null) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "模板工作簿快照不存在");
        }
        var file = files.find(organizationId, workspace.snapshotFileId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY, "模板工作簿快照文件不存在"));
        try (var stored = storage.get(file.objectKey())) {
            return objectMapper.readTree(stored.stream());
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "模板工作簿快照读取失败");
        }
    }

    private void requireCurrentLabTemplate(UUID organizationId,String code) {
        var standard=java.util.Set.of(UnifiedLabTemplateCatalog.TEST,UnifiedLabTemplateCatalog.SYNTHESIS);
        if(!standard.contains(code)&&repository.findPublishedDataTemplates(organizationId).stream().anyMatch(item->standard.contains(item.templateCode())))
            throw new ApiException(ApiErrorCode.EXPERIMENT_TEMPLATE_REQUIRED,"新实验请使用统一测试实验或合成实验模板；历史记录仍可按原版本查看");
    }

    private void collectSchemaFields(JsonNode schema, LinkedHashMap<String, FieldDefinition> fields) {
        if (schema == null || schema.isMissingNode()) return;
        if (schema.isArray()) {
            schema.forEach(item -> collectSchemaFields(item, fields));
            return;
        }
        if (schema.isObject()) {
            var code = text(schema, "fieldCode", "code");
            if (code != null && !code.isBlank()) {
                fields.putIfAbsent(code, new FieldDefinition(
                        code, firstText(schema, "displayName", "name", "label", code),
                        firstText(schema, "dataType", "valueType", "type", "TEXT"),
                        firstText(schema, "defaultUnit", "unit", ""),
                        schema.path("required").asBoolean(false), schema.path("identity").asBoolean(false),
                        strings(schema.path("aliases")), firstText(schema, "dataPath", code)));
            }
            var entries = schema.fields();
            while (entries.hasNext()) collectSchemaFields(entries.next().getValue(), fields);
        }
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        var result = new ArrayList<String>();
        node.forEach(item -> { if (item.isTextual()) result.add(item.asText()); });
        return List.copyOf(result);
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            var value = text(node, name);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            var value = node.path(name);
            if (value.isTextual()) return value.asText();
        }
        return null;
    }

}
