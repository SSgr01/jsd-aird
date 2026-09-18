package com.jsd.aird.ai.rnd.modeling;

import com.jsd.aird.ai.rnd.eligibility.EligibilityService;
import com.jsd.aird.ai.rnd.training.TrainingPolicyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.jsd.aird.ai.rnd.modeling.ModelingContracts.*;

@Service
public class ModelingConfigurationService {

    private final JdbcModelingConfigurationRepository repository;
    private final ConfigurationHashing hashing;
    private final ObjectMapper mapper;
    private final AuditLogFacade audit;
    private EligibilityService eligibilityService;

    public ModelingConfigurationService(JdbcModelingConfigurationRepository repository,
                                        ConfigurationHashing hashing, ObjectMapper mapper,
                                        AuditLogFacade audit) {
        this.repository = repository;
        this.hashing = hashing;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Autowired(required = false)
    public void setEligibilityService(EligibilityService eligibilityService) { this.eligibilityService = eligibilityService; }

    public PageResponse<TargetSummary> targets(String keyword, String status, String valueType,
                                               String category, int page, int size) {
        return repository.targets(actor().organizationId(), keyword, upper(status), upper(valueType), category,
                page(page), size(size));
    }

    public TargetSummary target(UUID id) { return targetRequired(actor().organizationId(), id); }
    public List<TargetVersionView> targetVersions(UUID id) { target(id); return repository.targetVersions(actor().organizationId(),id); }
    public List<SourceMappingView> sourceMappings(UUID id) { target(id); return repository.sourceMappings(actor().organizationId(),id); }
    public SourceMappingSuggestions sourceMappingSuggestions(UUID id,UUID targetVersionId) {
        var a=actor();var target=targetRequired(a.organizationId(),id);
        var version=targetVersionId==null?(target.currentVersionId()==null?repository.targetVersions(a.organizationId(),id).stream().findFirst().orElseThrow(()->notFound("目标版本不存在")):targetVersionRequired(a.organizationId(),target.currentVersionId())):targetVersionRequired(a.organizationId(),targetVersionId);
        if(!version.targetId().equals(id))throw validation("目标版本不属于当前Y");
        var detected=detectSources(a.organizationId(),target,version);var dataSamples=new LinkedHashSet<String>();var experimentSamples=new LinkedHashSet<String>();var suggestions=new ArrayList<SourceMappingSuggestion>();
        for(var candidate:detected.values()){dataSamples.addAll(candidate.dataSamples);experimentSamples.addAll(candidate.experimentSamples);var samples=candidate.samples.stream().limit(3).toList();suggestions.add(new SourceMappingSuggestion(candidate.key,candidate.fieldName,candidate.unit,candidate.testMethod,candidate.load,candidate.substrate,candidate.stage,candidate.dataSamples.size(),candidate.experimentSamples.size(),candidate.confidence,candidate.confidence<90,samples));}
        suggestions.sort(java.util.Comparator.comparingInt(SourceMappingSuggestion::confidence).reversed().thenComparing(SourceMappingSuggestion::fieldName));
        return new SourceMappingSuggestions(id,version.id(),dataSamples.size(),experimentSamples.size(),(int)suggestions.stream().filter(SourceMappingSuggestion::confirmationRequired).count(),suggestions);
    }
    public List<InputSchemeView> inputSchemes(UUID id) { target(id); return repository.inputSchemes(actor().organizationId(),id); }

    /** Discover X from the immutable facts currently available to this
     * organization.  The result is a suggestion only: a field must already
     * have a confirmed standard mapping and a published X version before it
     * can be selected for a model scheme. */
    public InputSuggestionPage inputSuggestions(UUID targetId, UUID targetVersionId, String keyword, int page, int size) {
        var a=actor();var target=targetRequired(a.organizationId(),targetId);
        var version=targetVersionId==null?(target.currentVersionId()==null?null:targetVersionRequired(a.organizationId(),target.currentVersionId())):targetVersionRequired(a.organizationId(),targetVersionId);
        if(version==null||!version.targetId().equals(targetId))throw validation("目标版本不存在或不属于当前Y");
        var found=new LinkedHashMap<String,DiscoveredInput>();
        for(var row:repository.currentSampleFacts(a.organizationId())){
            scanInputs(row.composition(),"composition",row,found);scanInputs(row.process(),"process",row,found);scanInputs(row.conditions(),"conditions",row,found);scanInputs(row.facts(),"facts",row,found);
        }
        var values=found.values().stream().filter(x->keyword==null||keyword.isBlank()||x.name.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))||x.key.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))).sorted(java.util.Comparator.comparing((DiscoveredInput x)->-x.total()).thenComparing(x->x.name)).toList();
        var out=new ArrayList<InputSuggestion>();
        var existingFields=repository.inputFields(a.organizationId(),null,null,null,1,200).items();
        for(var x:values){var matches=existingFields.stream().filter(f->normalize(f.name()).equals(normalize(x.name))||normalize(f.code()).equals(normalize(x.key))).findFirst().orElse(null);var conflicts=new ArrayList<String>();if(x.units.size()>1)conflicts.add("同一字段出现多个单位："+String.join("、",x.units));var suggestedType="STRING".equals(x.valueType)&&x.structuredHint&&x.observedValues.size()>1&&x.observedValues.size()<=20?"CATEGORY":x.valueType;if(matches!=null&&!matches.valueType().equals(suggestedType))conflicts.add("已有X类型为 "+matches.valueType()+"，发现类型为 "+suggestedType);var formalMapping=matches!=null&&matches.currentVersionId()!=null&&"ACTIVE".equals(matches.status());if(!formalMapping)conflicts.add("尚未确认标准数据字段映射，不能直接加入模型方案");var modelEligible=formalMapping&&!"STRING".equals(suggestedType)&&conflicts.stream().noneMatch(c->c.contains("类型")||c.contains("单位"));var matchStatus=matches==null?"NEW_CANDIDATE":(conflicts.isEmpty()?"REUSABLE":"CONFLICT");out.add(new InputSuggestion(x.key,x.name,suggestedType,x.units.size()==1?x.units.iterator().next():null,"PRE_EXPERIMENT",x.dataSamples.size(),x.experimentSamples.size(),x.total(),x.observedValues.stream().limit(12).toList(),range(x),x.paths.stream().limit(8).toList(),matches==null?null:matches.id(),matches==null?null:matches.currentVersionId(),matchStatus,conflicts,modelEligible,modelEligible?x.total():0));}
        var from=Math.min((page-1)*size,out.size());var to=Math.min(from+size,out.size());var content=from>=to?List.<InputSuggestion>of():out.subList(from,to);return new InputSuggestionPage(content,page,size,out.isEmpty()?0:(out.size()+size-1)/size,out.size());
    }

    private void scanInputs(JsonNode node,String root,JdbcModelingConfigurationRepository.SampleFactRow row,Map<String,DiscoveredInput> found){if(node==null||node.isNull()||isPostExperimentPath(root))return;if("composition".equals(root)&&node.isObject()&&(node.path("items").isArray()||node.has("value"))){addComposition(found,row);return;}if(node.isObject()){if(hasValue(node)){var label=first(node,"fieldName","name","label","fieldCode","code");if(label.isBlank())label=root+"."+root;var value=firstValue(node);addInput(found,label,root+"."+label,value,node,row,root.startsWith("process")||root.startsWith("conditions"));return;}node.fields().forEachRemaining(e->{if(isPostExperimentPath(root+"."+e.getKey()))return;if(e.getValue().isValueNode())addInput(found,e.getKey(),root+"."+e.getKey(),e.getValue(),e.getValue(),row,root.startsWith("process")||root.startsWith("conditions"));else scanInputs(e.getValue(),root+"."+e.getKey(),row,found);});}else if(node.isArray())for(var item:node)scanInputs(item,root,row,found);}
    private void addComposition(Map<String,DiscoveredInput> found,JdbcModelingConfigurationRepository.SampleFactRow row){var x=found.computeIfAbsent("formulacomposition",k->new DiscoveredInput(k,"配方组成","COMPOSITION"));x.structuredHint=true;x.paths.add("/composition/items");if("DATA_CENTER".equals(row.sourceType()))x.dataSamples.add(row.logicalSampleKey());else if("EXPERIMENT".equals(row.sourceType()))x.experimentSamples.add(row.logicalSampleKey());}
    private boolean isPostExperimentPath(String path){var normalized=normalize(path);return normalized.contains("testreport")||normalized.contains("observation")||normalized.contains("measured")||normalized.contains("result")||normalized.contains("targetvalue")||normalized.contains("actualvalue")||normalized.contains("conclusion")||normalized.contains("outcome");}
    private boolean hasValue(JsonNode n){return n.hasNonNull("value")||n.hasNonNull("effectiveValue")||n.hasNonNull("parsedValue")||n.hasNonNull("rawValue")||n.hasNonNull("result");}
    private JsonNode firstValue(JsonNode n){for(var k:List.of("effectiveValue","parsedValue","value","result","rawValue"))if(n.hasNonNull(k))return n.get(k);return n;}
    private String first(JsonNode n,String... keys){for(var k:keys)if(n.hasNonNull(k)&&n.get(k).isValueNode()&&!n.get(k).asText().isBlank())return n.get(k).asText().strip();return "";}
    private void addInput(Map<String,DiscoveredInput> found,String name,String path,JsonNode value,JsonNode metadata,JdbcModelingConfigurationRepository.SampleFactRow row,boolean structuredHint){var normalized=normalize(name);if(normalized.isBlank()||Set.of("title","id","sampleid","logicalsamplekey").contains(normalized)||isPostExperimentPath(path))return;var type=value.isBoolean()?"BOOLEAN":value.isNumber()?"NUMBER":"STRING";var key=normalized;var x=found.computeIfAbsent(key,k->new DiscoveredInput(k,name,type));x.structuredHint|=structuredHint;x.paths.add(path);if(row.sourceType().equals("DATA_CENTER"))x.dataSamples.add(row.logicalSampleKey());else if(row.sourceType().equals("EXPERIMENT"))x.experimentSamples.add(row.logicalSampleKey());if(value.isValueNode()&&!x.observedValues.contains(value.asText())&&x.observedValues.size()<32)x.observedValues.add(value.asText());var unit=first(metadata,"unit","normalizedUnit","rawUnit","sourceUnit");if(!unit.isBlank())x.units.add(unit);if(type.equals("NUMBER")){var number=value.asDouble();x.min=Math.min(x.min,number);x.max=Math.max(x.max,number);}}
    private ObjectNode range(DiscoveredInput x){var out=mapper.createObjectNode();if(x.min!=Double.POSITIVE_INFINITY){out.put("minimum",x.min);out.put("maximum",x.max);}return out;}
    private static final class DiscoveredInput{final String key,name,valueType;boolean structuredHint;final Set<String> dataSamples=new LinkedHashSet<>(),experimentSamples=new LinkedHashSet<>(),units=new LinkedHashSet<>();final List<String> observedValues=new ArrayList<>(),paths=new ArrayList<>();double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;DiscoveredInput(String k,String n,String t){key=k;name=n;valueType=t;}long total(){var all=new HashSet<String>(dataSamples);all.addAll(experimentSamples);return all.size();}}

    public PageResponse<InputFieldSummary> inputFields(String keyword, String status, String valueType,
                                                       int page, int size) {
        return repository.inputFields(actor().organizationId(),keyword,upper(status),upper(valueType),page(page),size(size));
    }
    public InputFieldSummary inputField(UUID id) { return inputFieldRequired(actor().organizationId(),id); }
    public List<InputFieldVersionView> inputFieldVersions(UUID id) { inputField(id); return repository.inputFieldVersions(actor().organizationId(),id); }
    public List<TrainingPolicyView> trainingPolicies(UUID targetId) { if(targetId!=null)target(targetId); return repository.trainingPolicies(actor().organizationId(),targetId); }
    public List<ReferenceField> referenceFields(String keyword) { return repository.referenceFields(keyword); }
    public List<MaterialReference> materials(String keyword) { return repository.materials(keyword); }
    public List<MaterialAliasView> materialAliases(String keyword) { return repository.materialAliases(actor().organizationId(),keyword); }
    public List<MaterialDictionaryView> materialDictionaries() { return repository.materialDictionaries(actor().organizationId()); }

    @Transactional
    public TargetSummary createTarget(TargetCommand command,String key){
        validateTargetCommand(command,false);
        return idempotent("CREATE_TARGET",key,command,TargetSummary.class,()->{
             var a=actor();var id=UUID.randomUUID();var version=UUID.randomUUID();var definition=object(command.definition());
             var semantics=object(command.observationSemantics());var classes=list(command.classes());
             var resultFieldId=standardFieldId(command.resultStandardFieldCode());
            var hash=targetHash(command.valueType(),command.unit(),classes,definition,semantics);
            try{
                repository.jdbc().update("""
                        INSERT INTO ai.prediction_target(id,organization_id,target_code,name,performance_project,value_type,status,created_by,updated_by)
                        VALUES(?,?,?,?,?,?,'DRAFT',?,?)
                        """,id,a.organizationId(),required(command.code(),"目标编码"),required(command.name(),"目标名称"),
                        required(command.category(),"性能分类"),upperRequired(command.valueType(),"结果类型"),a.userId(),a.userId());
                repository.jdbc().update("""
                        INSERT INTO ai.target_version(id,organization_id,target_id,version_no,status,value_type,unit,classes_jsonb,
                          definition_jsonb,observation_semantics_jsonb,config_hash,result_standard_field_dictionary_id,catalog_proposal_id,created_by)
                        VALUES(?,?,?,1,'DRAFT',?,?,?,?,?,?,?, ?,?)
                        """,version,a.organizationId(),id,upperRequired(command.valueType(),"结果类型"),nullable(command.unit()),
                        repository.pg(mapper.valueToTree(classes)),repository.pg(definition),repository.pg(semantics),hash,resultFieldId,command.catalogProposalId(),a.userId());
            }catch(DataIntegrityViolationException e){throw conflict("目标编码已存在");}
            audit(a,"AI_TARGET_CREATED","AI_PREDICTION_TARGET",id,mapper.valueToTree(command));return targetRequired(a.organizationId(),id);
        });
    }

    @Transactional
    public TargetVersionView createTargetVersion(UUID targetId,TargetCommand command,String key){
        validateTargetCommand(command,false);
        return idempotent("CREATE_TARGET_VERSION:"+targetId,key,command,TargetVersionView.class,()->{
            var a=actor();var target=targetRequired(a.organizationId(),targetId);lock(target.revision(),value(command.expectedRevision()));
            if(!target.code().equals(required(command.code(),"目标编码")))throw validation("新版本不能修改目标编码");
            var next=repository.jdbc().queryForObject("SELECT coalesce(max(version_no),0)+1 FROM ai.target_version WHERE organization_id=? AND target_id=?",Integer.class,a.organizationId(),targetId);
             var id=UUID.randomUUID();var definition=object(command.definition());var semantics=object(command.observationSemantics());var classes=list(command.classes());
             var resultFieldId=standardFieldId(command.resultStandardFieldCode());
            repository.jdbc().update("""
                    INSERT INTO ai.target_version(id,organization_id,target_id,version_no,status,value_type,unit,classes_jsonb,
                      definition_jsonb,observation_semantics_jsonb,config_hash,result_standard_field_dictionary_id,catalog_proposal_id,created_by)
                    VALUES(?,?,?,?,'DRAFT',?,?,?,?,?,?,?, ?,?)
                    """,id,a.organizationId(),targetId,next,upperRequired(command.valueType(),"结果类型"),nullable(command.unit()),
                    repository.pg(mapper.valueToTree(classes)),repository.pg(definition),repository.pg(semantics),
                    targetHash(command.valueType(),command.unit(),classes,definition,semantics),resultFieldId,command.catalogProposalId(),a.userId());
            updateTargetProjection(a,targetId,command,target.revision());audit(a,"AI_TARGET_VERSION_CREATED","AI_TARGET_VERSION",id,mapper.valueToTree(command));
            return repository.targetVersion(a.organizationId(),id).orElseThrow();
        });
    }

    @Transactional
    public TargetVersionView updateTargetVersion(UUID versionId,TargetCommand command,String key){
        validateTargetCommand(command,false);
        return idempotent("UPDATE_TARGET_VERSION:"+versionId,key,command,TargetVersionView.class,()->{
            var a=actor();var old=targetVersionRequired(a.organizationId(),versionId);if(!"DRAFT".equals(old.status()))throw immutable();
            var target=targetRequired(a.organizationId(),old.targetId());lock(target.revision(),value(command.expectedRevision()));
            if(!target.code().equals(required(command.code(),"目标编码")))throw validation("目标编码不可修改");
            var definition=object(command.definition());var semantics=object(command.observationSemantics());var classes=list(command.classes());
            var resultFieldId=standardFieldId(command.resultStandardFieldCode());
            repository.jdbc().update("""
                    UPDATE ai.target_version SET value_type=?,unit=?,classes_jsonb=?,definition_jsonb=?,observation_semantics_jsonb=?,config_hash=?,result_standard_field_dictionary_id=?,catalog_proposal_id=?
                    WHERE organization_id=? AND id=? AND status='DRAFT'
                    """,upperRequired(command.valueType(),"结果类型"),nullable(command.unit()),repository.pg(mapper.valueToTree(classes)),
                    repository.pg(definition),repository.pg(semantics),targetHash(command.valueType(),command.unit(),classes,definition,semantics),resultFieldId,command.catalogProposalId(),
                    a.organizationId(),versionId);
            updateTargetProjection(a,old.targetId(),command,target.revision());audit(a,"AI_TARGET_VERSION_UPDATED","AI_TARGET_VERSION",versionId,mapper.valueToTree(command));
            return repository.targetVersion(a.organizationId(),versionId).orElseThrow();
        });
    }

    @Transactional
    public TargetSummary publishTargetVersion(UUID versionId,VersionCommand command,String key){
        return idempotent("PUBLISH_TARGET_VERSION:"+versionId,key,command,TargetSummary.class,()->{
            var a=actor();var version=targetVersionRequired(a.organizationId(),versionId);if(!"DRAFT".equals(version.status()))throw immutable();
            var target=targetRequired(a.organizationId(),version.targetId());lock(target.revision(),command.expectedRevision());validatePublishable(version);
            var mappings=repository.jdbc().queryForObject("SELECT count(*) FROM ai.source_mapping_version WHERE organization_id=? AND target_version_id=? AND status='PUBLISHED'",Long.class,a.organizationId(),versionId);
            if(mappings==null||mappings==0)throw new ApiException(ApiErrorCode.SOURCE_MAPPING_REQUIRED);
            repository.jdbc().update("UPDATE ai.target_version SET status='PUBLISHED',published_at=now() WHERE organization_id=? AND id=?",a.organizationId(),versionId);
            var updated=repository.jdbc().update("""
                    UPDATE ai.prediction_target SET current_version_id=?,status='ACTIVE',value_type=?,revision=revision+1,
                      updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=?
                    """,versionId,version.valueType(),a.userId(),a.organizationId(),version.targetId(),target.revision());
            if(updated!=1)throw versionConflict();
            audit(a,"AI_TARGET_VERSION_PUBLISHED","AI_TARGET_VERSION",versionId,mapper.valueToTree(command));return targetRequired(a.organizationId(),version.targetId());
        });
    }

    @Transactional
    public TargetSummary retireTarget(UUID targetId,RetireCommand command,String key){
        return idempotent("RETIRE_TARGET:"+targetId,key,command,TargetSummary.class,()->{
            var a=actor();var target=targetRequired(a.organizationId(),targetId);lock(target.revision(),command.expectedRevision());required(command.reason(),"停用原因");
            var updated=repository.jdbc().update("UPDATE ai.prediction_target SET status='RETIRED',revision=revision+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",a.userId(),a.organizationId(),targetId,target.revision());
            if(updated!=1)throw versionConflict();
            audit(a,"AI_TARGET_RETIRED","AI_PREDICTION_TARGET",targetId,mapper.valueToTree(command));return targetRequired(a.organizationId(),targetId);
        });
    }

    @Transactional
    public SourceMappingView createSourceMapping(UUID targetId,SourceMappingCommand command,String key){
        return idempotent("CREATE_SOURCE_MAPPING:"+targetId,key,command,SourceMappingView.class,()->{
            var a=actor();var target=targetRequired(a.organizationId(),targetId);lock(target.revision(),value(command.expectedRevision()));
            var version=targetVersionRequired(a.organizationId(),command.targetVersionId());if(!version.targetId().equals(targetId))throw validation("来源映射与目标版本不一致");
            var source=upperRequired(command.sourceType(),"来源类型");if(!List.of("DATA_CENTER","EXPERIMENT").contains(source))throw validation("来源类型仅支持DATA_CENTER或EXPERIMENT");
            var next=repository.jdbc().queryForObject("SELECT coalesce(max(version_no),0)+1 FROM ai.source_mapping_version WHERE organization_id=? AND target_version_id=? AND source_type=?",Integer.class,a.organizationId(),version.id(),source);
            var id=UUID.randomUUID();var mapping=object(command.mapping());
            repository.jdbc().update("""
                    INSERT INTO ai.source_mapping_version(id,organization_id,target_id,target_version_id,source_type,version_no,status,mapping_jsonb,mapping_hash,created_by)
                    VALUES(?,?,?,?,?,?,'DRAFT',?,?,?)
                    """,id,a.organizationId(),targetId,version.id(),source,next,repository.pg(mapping),hashing.hash(mapping),a.userId());
            bumpTarget(a,targetId,target.revision());audit(a,"AI_SOURCE_MAPPING_CREATED","AI_SOURCE_MAPPING",id,mapper.valueToTree(command));return sourceMappingRequired(a.organizationId(),id);
        });
    }

    @Transactional
    public SourceMappingView publishSourceMapping(UUID id,VersionCommand command,String key){
        return idempotent("PUBLISH_SOURCE_MAPPING:"+id,key,command,SourceMappingView.class,()->{
            var a=actor();var mapping=sourceMappingRequired(a.organizationId(),id);if(!"DRAFT".equals(mapping.status()))throw immutable();
            var target=targetRequired(a.organizationId(),mapping.targetId());lock(target.revision(),command.expectedRevision());
            if(mapping.mapping()==null||!mapping.mapping().isObject()||mapping.mapping().size()==0)throw validation("来源映射不能为空");
            if(blank(mapping.mapping(),"targetFieldCode"))throw validation("来源映射必须指定目标值标准字段编码");
            repository.jdbc().update("UPDATE ai.source_mapping_version SET status='PUBLISHED',published_at=now() WHERE organization_id=? AND id=?",a.organizationId(),id);
            bumpTarget(a,target.id(),target.revision());audit(a,"AI_SOURCE_MAPPING_PUBLISHED","AI_SOURCE_MAPPING",id,mapper.valueToTree(command));return sourceMappingRequired(a.organizationId(),id);
        });
    }

    @Transactional
    public ConfirmSourceMappingResult confirmSourceMapping(UUID targetId,ConfirmSourceMappingCommand command,String key){
        return idempotent("CONFIRM_SOURCE_MAPPING:"+targetId,key,command,ConfirmSourceMappingResult.class,()->{
            var a=actor();var target=targetRequired(a.organizationId(),targetId);lock(target.revision(),command.expectedRevision());
            var suggestions=sourceMappingSuggestions(targetId,command.targetVersionId());var selected=suggestions.suggestions().stream().filter(x->x.candidateKey().equals(command.candidateKey())).findFirst().orElseThrow(()->validation("数据来源候选已变化，请刷新后重新确认"));
            var targetFieldCode=repository.jdbc().query("""
                    SELECT s.dictionary_code
                    FROM ai.target_version tv
                    JOIN tpl.standard_field_dictionary s ON s.id=tv.result_standard_field_dictionary_id
                    WHERE tv.organization_id=? AND tv.id=?
                    """,(rs,n)->rs.getString(1),a.organizationId(),command.targetVersionId())
                    .stream().findFirst().orElse("TEST.RESULT.VALUE");
            var mapping=mapper.createObjectNode().put("targetFieldCode",targetFieldCode).put("sourceFieldCode",selected.fieldName());
            if(selected.unit()!=null&&!selected.unit().isBlank())mapping.put("sourceUnit",selected.unit());
            var aliases=mapping.putArray("sourceAliases");aliases.add(selected.fieldName());aliases.add(target.name());aliases.add(target.code());
            var match=mapping.putObject("match");putIfText(match,"testMethod",selected.testMethod());putIfText(match,"load",selected.load());putIfText(match,"substrate",selected.substrate());putIfText(match,"stage",selected.stage());
            var created=new ArrayList<SourceMappingView>();
            for(var source:List.of("DATA_CENTER","EXPERIMENT")){
                // Source mappings are immutable.  A confirmation creates a new
                // published version and leaves the prior version available for
                // historical snapshots; attempting to retire it in place would
                // violate the immutable boundary.
                var next=repository.jdbc().queryForObject("SELECT coalesce(max(version_no),0)+1 FROM ai.source_mapping_version WHERE organization_id=? AND target_version_id=? AND source_type=?",Integer.class,a.organizationId(),suggestions.targetVersionId(),source);
                var id=UUID.randomUUID();repository.jdbc().update("""
                    INSERT INTO ai.source_mapping_version(id,organization_id,target_id,target_version_id,source_type,version_no,status,mapping_jsonb,mapping_hash,published_at,created_by)
                    VALUES(?,?,?,?,?,?,'PUBLISHED',?,?,now(),?)
                    """,id,a.organizationId(),targetId,suggestions.targetVersionId(),source,next,repository.pg(mapping),hashing.hash(mapping),a.userId());
                created.add(sourceMappingRequired(a.organizationId(),id));
            }
            bumpTarget(a,targetId,target.revision());String recompute="DEFERRED";UUID runId=null;
            // Mapping confirmation is the durable configuration action.  The
            // qualification recompute is deliberately scheduled after this
            // transaction by the configuration/outbox flow; invoking it from
            // the same transaction can mark the mapping transaction rollback
            // only when a not-ready configuration is encountered.  Keep the
            // response explicit until the worker accepts the follow-up run.
            recompute="DEFERRED";
            audit(a,"AI_SOURCE_MAPPING_CONFIRMED","AI_PREDICTION_TARGET",targetId,mapper.valueToTree(command));return new ConfirmSourceMappingResult(created,recompute,runId);
        });
    }

    @Transactional
    public InputFieldSummary createInputField(InputFieldCommand command,String key){
        validateInputField(command);
        return idempotent("CREATE_INPUT_FIELD",key,command,InputFieldSummary.class,()->{
            var a=actor();var id=UUID.randomUUID();var version=UUID.randomUUID();var type=upperRequired(command.valueType(),"字段类型");var stage=upperRequired(command.availabilityStage(),"可用时点");
            var definition=object(command.definition());var preprocessing=object(command.preprocessing());
            var standardFieldId="COMPOSITION".equals(type)?null:(command.standardFieldDictionaryId()!=null?command.standardFieldDictionaryId():recommendStandardField(command));
            try{
                repository.jdbc().update("""
                        INSERT INTO ai.input_field(id,organization_id,field_code,name,value_type,availability_stage,status,created_by,updated_by)
                        VALUES(?,?,?,?,?,?,'DRAFT',?,?)
                        """,id,a.organizationId(),required(command.code(),"字段编码"),required(command.name(),"字段名称"),type,stage,a.userId(),a.userId());
                repository.jdbc().update("""
                        INSERT INTO ai.input_field_version(id,organization_id,input_field_id,version_no,status,value_type,unit,availability_stage,
                          standard_field_dictionary_id,definition_jsonb,preprocessing_jsonb,config_hash,created_by)
                        VALUES(?,?,?,1,'DRAFT',?,?,?,?,?,?,?,?)
                        """,version,a.organizationId(),id,type,nullable(command.unit()),stage,standardFieldId,
                        repository.pg(definition),repository.pg(preprocessing),fieldHash(command,standardFieldId),a.userId());
            }catch(DataIntegrityViolationException e){throw conflict("字段编码或标准字段引用无效");}
            audit(a,"AI_INPUT_FIELD_CREATED","AI_INPUT_FIELD",id,mapper.valueToTree(command));return inputFieldRequired(a.organizationId(),id);
        });
    }

    @Transactional
    public InputFieldVersionView createInputFieldVersion(UUID fieldId,InputFieldCommand command,String key){
        validateInputField(command);
        return idempotent("CREATE_INPUT_FIELD_VERSION:"+fieldId,key,command,InputFieldVersionView.class,()->{
            var a=actor();var field=inputFieldRequired(a.organizationId(),fieldId);lock(field.revision(),value(command.expectedRevision()));
            if(!field.code().equals(required(command.code(),"字段编码")))throw validation("新版本不能修改字段编码");
            var next=repository.jdbc().queryForObject("SELECT coalesce(max(version_no),0)+1 FROM ai.input_field_version WHERE organization_id=? AND input_field_id=?",Integer.class,a.organizationId(),fieldId);
            var id=UUID.randomUUID();var type=upperRequired(command.valueType(),"字段类型");var stage=upperRequired(command.availabilityStage(),"可用时点");
            var standardFieldId="COMPOSITION".equals(type)?null:(command.standardFieldDictionaryId()!=null?command.standardFieldDictionaryId():recommendStandardField(command));
            repository.jdbc().update("""
                    INSERT INTO ai.input_field_version(id,organization_id,input_field_id,version_no,status,value_type,unit,availability_stage,
                      standard_field_dictionary_id,definition_jsonb,preprocessing_jsonb,config_hash,created_by)
                    VALUES(?,?,?,?,'DRAFT',?,?,?,?,?,?,?,?)
                    """,id,a.organizationId(),fieldId,next,type,nullable(command.unit()),stage,standardFieldId,
                    repository.pg(object(command.definition())),repository.pg(object(command.preprocessing())),fieldHash(command,standardFieldId),a.userId());
            var updated=repository.jdbc().update("UPDATE ai.input_field SET name=?,value_type=?,availability_stage=?,revision=revision+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",
                    required(command.name(),"字段名称"),type,stage,a.userId(),a.organizationId(),fieldId,field.revision());
            if(updated!=1)throw versionConflict();
            audit(a,"AI_INPUT_FIELD_VERSION_CREATED","AI_INPUT_FIELD_VERSION",id,mapper.valueToTree(command));return inputFieldVersionRequired(a.organizationId(),id);
        });
    }

    @Transactional
    public InputFieldSummary publishInputFieldVersion(UUID versionId,VersionCommand command,String key){
        return idempotent("PUBLISH_INPUT_FIELD_VERSION:"+versionId,key,command,InputFieldSummary.class,()->{
            var a=actor();var version=inputFieldVersionRequired(a.organizationId(),versionId);if(!"DRAFT".equals(version.status()))throw immutable();
            var field=inputFieldRequired(a.organizationId(),version.inputFieldId());lock(field.revision(),command.expectedRevision());validateFieldPublishable(version);
            repository.jdbc().update("UPDATE ai.input_field_version SET status='PUBLISHED',published_at=now() WHERE organization_id=? AND id=?",a.organizationId(),versionId);
            var updated=repository.jdbc().update("""
                    UPDATE ai.input_field SET current_version_id=?,status='ACTIVE',value_type=?,availability_stage=?,revision=revision+1,
                      updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=?
                    """,versionId,version.valueType(),version.availabilityStage(),a.userId(),a.organizationId(),field.id(),field.revision());
            if(updated!=1)throw versionConflict();
            audit(a,"AI_INPUT_FIELD_VERSION_PUBLISHED","AI_INPUT_FIELD_VERSION",versionId,mapper.valueToTree(command));return inputFieldRequired(a.organizationId(),field.id());
        });
    }

    @Transactional
    public InputSchemeView createInputScheme(UUID targetId,InputSchemeCommand command,String key){
        return idempotent("CREATE_INPUT_SCHEME:"+targetId,key,command,InputSchemeView.class,()->{
            var a=actor();var target=targetRequired(a.organizationId(),targetId);lock(target.revision(),value(command.expectedRevision()));
            var targetVersion=targetVersionRequired(a.organizationId(),command.targetVersionId());if(!targetVersion.targetId().equals(targetId))throw validation("输入方案与目标版本不一致");
            if(!"PUBLISHED".equals(targetVersion.status()))throw validation("输入方案必须绑定已发布的Y版本");
            var fields=list(command.fields());if(fields.isEmpty())throw validation("输入方案至少选择一个字段");validateUniqueSchemeFields(fields);
            var dictionary=command.materialDictionaryVersionId()==null?null:repository.materialDictionary(a.organizationId(),command.materialDictionaryVersionId()).orElseThrow(()->notFound("材料字典版本不存在"));
            var compositionIncluded=false;
            for(var field:fields){
                if(!field.required())throw validation("当前版本选入字段必须设为必需");
                var version=inputFieldVersionRequired(a.organizationId(),field.inputFieldVersionId());
                if(!"PUBLISHED".equals(version.status()))throw validation("输入方案只能引用已发布的X版本");
                if("POST_EXPERIMENT".equals(version.availabilityStage()))throw validation("实验后字段不能进入实验前预测方案");
                if("COMPOSITION".equals(version.valueType()))compositionIncluded=true;
            }
            if(dictionary!=null&&!"FROZEN".equals(dictionary.status()))throw validation("输入方案只能绑定已冻结的材料字典版本");
            if(compositionIncluded&&dictionary==null)throw validation("配方组成字段必须绑定已冻结的材料字典版本");
            var next=repository.jdbc().queryForObject("SELECT coalesce(max(version_no),0)+1 FROM ai.input_scheme WHERE organization_id=? AND target_id=? AND scheme_code=?",Integer.class,a.organizationId(),targetId,required(command.code(),"方案编码"));
            var id=UUID.randomUUID();var preprocessing=object(command.preprocessing());var hash=schemeHash(command,dictionary==null?null:dictionary.dictionaryHash());
            repository.jdbc().update("""
                    INSERT INTO ai.input_scheme(id,organization_id,target_id,target_version_id,material_dictionary_version_id,scheme_code,name,version_no,status,
                      preprocessing_jsonb,config_hash,revision,created_by,updated_by)
                    VALUES(?,?,?,?,?,?,?,?,'DRAFT',?,?,0,?,?)
                    """,id,a.organizationId(),targetId,targetVersion.id(),command.materialDictionaryVersionId(),command.code(),required(command.name(),"方案名称"),next,
                    repository.pg(preprocessing),hash,a.userId(),a.userId());
            for(var field:fields)repository.jdbc().update("""
                    INSERT INTO ai.input_scheme_field(organization_id,input_scheme_id,input_field_version_id,required,ordinal,override_jsonb)
                    VALUES(?,?,?,?,?,?)
                    """,a.organizationId(),id,field.inputFieldVersionId(),field.required(),field.ordinal(),repository.pg(object(field.override())));
            bumpTarget(a,targetId,target.revision());audit(a,"AI_INPUT_SCHEME_CREATED","AI_INPUT_SCHEME",id,mapper.valueToTree(command));return repository.inputScheme(a.organizationId(),id).orElseThrow();
        });
    }

    public CoveragePreview previewInputScheme(UUID id){
        var scheme=repository.inputScheme(actor().organizationId(),id).orElseThrow(()->notFound("输入方案不存在"));
        var issues=validateScheme(scheme);return new CoveragePreview("NOT_EVALUATED","DATA_PIPELINE_NOT_READY",issues,
                scheme.configHash(),null,null,null,null,List.of());
    }

    @Transactional
    public FreezeResult freezeInputScheme(UUID id,VersionCommand command,String key){
        return idempotent("FREEZE_INPUT_SCHEME:"+id,key,command,FreezeResult.class,()->{
            var a=actor();var scheme=repository.inputScheme(a.organizationId(),id).orElseThrow(()->notFound("输入方案不存在"));
            if("FROZEN".equals(scheme.status())){var t=targetRequired(a.organizationId(),scheme.targetId());return new FreezeResult(id,"FROZEN","DEFERRED",t.currentInputSchemeId(),t.revision());}
            if(!"DRAFT".equals(scheme.status()))throw immutable();if(scheme.revision()!=command.expectedRevision())throw versionConflict();
            var target=targetRequired(a.organizationId(),scheme.targetId());var issues=validateScheme(scheme);if(!issues.isEmpty())throw new ApiException(ApiErrorCode.DATA_SCHEMA_INVALID,"输入方案不能冻结",issues);
            var schemeUpdated=repository.jdbc().update("UPDATE ai.input_scheme SET status='FROZEN',frozen_at=now(),revision=revision+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",a.userId(),a.organizationId(),id,scheme.revision());
            if(schemeUpdated!=1)throw versionConflict();
            var targetUpdated=repository.jdbc().update("UPDATE ai.prediction_target SET current_input_scheme_id=?,revision=revision+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",id,a.userId(),a.organizationId(),target.id(),target.revision());
            if(targetUpdated!=1)throw versionConflict();
            var updated=targetRequired(a.organizationId(),target.id());
            if (eligibilityService != null) eligibilityService.scheduleForTarget(a.organizationId(), target.id());
            audit(a,"AI_INPUT_SCHEME_FROZEN","AI_INPUT_SCHEME",id,mapper.valueToTree(command));return new FreezeResult(id,"FROZEN","DEFERRED",id,updated.revision());
        });
    }

    @Transactional
    public TrainingPolicyView createTrainingPolicy(TrainingPolicyCommand command,String key){
        return idempotent("CREATE_TRAINING_POLICY:"+command.targetId(),key,command,TrainingPolicyView.class,()->{
            var a=actor();var target=targetRequired(a.organizationId(),command.targetId());lock(target.revision(),value(command.expectedRevision()));
            var next=repository.jdbc().queryForObject("SELECT coalesce(max(version_no),0)+1 FROM ai.modeling_policy_version WHERE organization_id=? AND target_id=? AND kind='TRAINING'",Integer.class,a.organizationId(),target.id());
            var id=UUID.randomUUID();var q=object(command.qualification());var v=object(command.validation());var t=object(command.training());
            var hash=hashing.hash(Map.of("qualification",q,"validation",v,"training",t));
            repository.jdbc().update("""
                    INSERT INTO ai.modeling_policy_version(id,organization_id,target_id,version_no,status,kind,qualification_jsonb,validation_jsonb,training_jsonb,configuration_jsonb,policy_hash,created_by)
                    VALUES(?,?,?,?,'DRAFT','TRAINING',?,?,?,?,?,?)
                    """,id,a.organizationId(),target.id(),next,repository.pg(q),repository.pg(v),repository.pg(t),repository.pg(mapper.valueToTree(Map.of("qualification",q,"validation",v,"training",t))),hash,a.userId());
            bumpTarget(a,target.id(),target.revision());audit(a,"AI_TRAINING_POLICY_CREATED","AI_TRAINING_POLICY",id,mapper.valueToTree(command));return policyRequired(a.organizationId(),id);
        });
    }

    @Transactional
    public TrainingPolicyView publishTrainingPolicy(UUID id,VersionCommand command,String key){
        return idempotent("PUBLISH_TRAINING_POLICY:"+id,key,command,TrainingPolicyView.class,()->{
            var a=actor();var policy=policyRequired(a.organizationId(),id);if(!"DRAFT".equals(policy.status()))throw immutable();
            var target=targetRequired(a.organizationId(),policy.targetId());lock(target.revision(),command.expectedRevision());
            var valueType = repository.jdbc().queryForObject("""
                    SELECT tv.value_type FROM ai.prediction_target t
                    JOIN ai.target_version tv ON tv.organization_id=t.organization_id AND tv.id=t.current_version_id
                    WHERE t.organization_id=? AND t.id=?
                    """, String.class, a.organizationId(), target.id());
            var issues = TrainingPolicyRules.validate(valueType, policy.qualification(), policy.validation(), policy.training());
            if(!issues.isEmpty()) throw new ApiException(ApiErrorCode.TRAINING_POLICY_NOT_APPROVED,"训练策略尚未完整配置",issues);
            repository.jdbc().update("UPDATE ai.modeling_policy_version SET status='PUBLISHED',published_at=now(),published_by=? WHERE organization_id=? AND id=? AND kind='TRAINING'",a.userId(),a.organizationId(),id);
            bumpTarget(a,target.id(),target.revision());audit(a,"AI_TRAINING_POLICY_PUBLISHED","AI_TRAINING_POLICY",id,mapper.valueToTree(command));return policyRequired(a.organizationId(),id);
        });
    }

    @Transactional
    public MaterialAliasView createMaterialAlias(MaterialAliasCommand command,String key){
        return idempotent("CREATE_MATERIAL_ALIAS",key,command,MaterialAliasView.class,()->{
            var a=actor();requireMaterial(command.materialId());var alias=required(command.alias(),"材料别名");var normalized=ConfigurationHashing.normalizeAlias(alias);
            if(normalized.isBlank())throw validation("材料别名不能为空");var id=UUID.randomUUID();
            try{repository.jdbc().update("""
                    INSERT INTO mdm.material_alias(id,organization_id,material_id,normalized_alias,display_alias,status,revision,created_by,updated_by)
                    VALUES(?,?,?,?,?,'ACTIVE',0,?,?)
                    """,id,a.organizationId(),command.materialId(),normalized,alias.strip(),a.userId(),a.userId());}
            catch(DataIntegrityViolationException e){throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"该材料别名已映射",Map.of("normalizedAlias",normalized));}
            audit(a,"AI_MATERIAL_ALIAS_CREATED","MATERIAL_ALIAS",id,mapper.valueToTree(command));return repository.materialAlias(a.organizationId(),id).orElseThrow();
        });
    }

    @Transactional
    public MaterialAliasView retireMaterialAlias(UUID id,RetireCommand command,String key){
        return idempotent("RETIRE_MATERIAL_ALIAS:"+id,key,command,MaterialAliasView.class,()->{
            var a=actor();var alias=repository.materialAlias(a.organizationId(),id).orElseThrow(()->notFound("材料别名不存在"));
            lock(alias.revision(),command.expectedRevision());required(command.reason(),"停用原因");var updated=repository.jdbc().update("""
                    UPDATE mdm.material_alias SET status='RETIRED',revision=revision+1,updated_by=?,updated_at=now()
                    WHERE organization_id=? AND id=? AND revision=?
                    """,a.userId(),a.organizationId(),id,alias.revision());if(updated!=1)throw versionConflict();audit(a,"AI_MATERIAL_ALIAS_RETIRED","MATERIAL_ALIAS",id,mapper.valueToTree(command));return repository.materialAlias(a.organizationId(),id).orElseThrow();
        });
    }

    @Transactional
    public MaterialDictionaryView createMaterialDictionary(MaterialDictionaryCommand command,String key){
        return idempotent("CREATE_MATERIAL_DICTIONARY:"+command.code(),key,command,MaterialDictionaryView.class,()->{
            var a=actor();var code=required(command.code(),"字典编码");var items=list(command.items());if(items.isEmpty())throw validation("材料字典不能为空");
            var ids=new HashSet<UUID>();var ordinals=new HashSet<Integer>();var tokens=new HashSet<String>();for(var item:items){if(!ids.add(item.materialId())||!ordinals.add(item.ordinal())||!tokens.add(required(item.token(),"材料编码令牌")))throw validation("材料、编码顺序或令牌重复");requireMaterial(item.materialId());required(item.role(),"材料角色");}
            for(int ordinal=0;ordinal<items.size();ordinal++)if(!ordinals.contains(ordinal))throw validation("材料编码顺序必须从0连续排列");
            var next=repository.jdbc().queryForObject("SELECT coalesce(max(version_no),0)+1 FROM ai.material_dictionary_version WHERE organization_id=? AND dictionary_code=?",Integer.class,a.organizationId(),code);
            var id=UUID.randomUUID();var encoder=object(command.encoder());var vocabulary=mapper.valueToTree(items);var hash=hashing.hash(Map.of("items",vocabulary,"encoder",encoder));
            try{repository.jdbc().update("""
                    INSERT INTO ai.material_dictionary_version(id,organization_id,dictionary_code,version_no,status,vocabulary_jsonb,encoder_jsonb,dictionary_hash,created_by)
                    VALUES(?,?,?,?,'DRAFT',?,?,?,?)
                    """,id,a.organizationId(),code,next,repository.pg(vocabulary),repository.pg(encoder),hash,a.userId());}
            catch(DataIntegrityViolationException e){throw conflict("相同内容的材料字典版本已存在");}
            audit(a,"AI_MATERIAL_DICTIONARY_CREATED","AI_MATERIAL_DICTIONARY",id,mapper.valueToTree(command));return repository.materialDictionary(a.organizationId(),id).orElseThrow();
        });
    }

    @Transactional
    public MaterialDictionaryView freezeMaterialDictionary(UUID id,VersionCommand command,String key){
        return idempotent("FREEZE_MATERIAL_DICTIONARY:"+id,key,command,MaterialDictionaryView.class,()->{
            var a=actor();var d=repository.materialDictionary(a.organizationId(),id).orElseThrow(()->notFound("材料字典不存在"));
            if(!"DRAFT".equals(d.status()))throw immutable();lock(d.revision(),command.expectedRevision());
            var updated=repository.jdbc().update("UPDATE ai.material_dictionary_version SET status='FROZEN',revision=revision+1,frozen_at=now() WHERE organization_id=? AND id=? AND revision=?",a.organizationId(),id,d.revision());
            if(updated!=1)throw versionConflict();
            audit(a,"AI_MATERIAL_DICTIONARY_FROZEN","AI_MATERIAL_DICTIONARY",id,mapper.valueToTree(command));return repository.materialDictionary(a.organizationId(),id).orElseThrow();
        });
    }

    @Transactional
    public MaterialDictionaryView retireMaterialDictionary(UUID id,RetireCommand command,String key){
        return idempotent("RETIRE_MATERIAL_DICTIONARY:"+id,key,command,MaterialDictionaryView.class,()->{
            var a=actor();var d=repository.materialDictionary(a.organizationId(),id).orElseThrow(()->notFound("材料字典不存在"));
            if("RETIRED".equals(d.status()))throw immutable();lock(d.revision(),command.expectedRevision());required(command.reason(),"停用原因");
            var updated=repository.jdbc().update("UPDATE ai.material_dictionary_version SET status='RETIRED',revision=revision+1 WHERE organization_id=? AND id=? AND revision=?",a.organizationId(),id,d.revision());
            if(updated!=1)throw versionConflict();
            audit(a,"AI_MATERIAL_DICTIONARY_RETIRED","AI_MATERIAL_DICTIONARY",id,mapper.valueToTree(command));return repository.materialDictionary(a.organizationId(),id).orElseThrow();
        });
    }

    private List<ValidationIssue> validateScheme(InputSchemeView scheme){
        var issues=new java.util.ArrayList<ValidationIssue>();var target=repository.targetVersion(actor().organizationId(),scheme.targetVersionId()).orElse(null);
        if(target==null||!"PUBLISHED".equals(target.status()))issues.add(new ValidationIssue("TARGET_VERSION_UNPUBLISHED",null,"输入方案必须绑定已发布的Y版本"));
        if(scheme.fields().isEmpty())issues.add(new ValidationIssue("INPUT_SCHEME_EMPTY",null,"输入方案至少选择一个字段"));
        var compositionIncluded=false;
        for(var field:scheme.fields()){
            var version=repository.inputFieldVersion(actor().organizationId(),field.inputFieldVersionId()).orElse(null);
            if(version==null||!"PUBLISHED".equals(version.status()))issues.add(new ValidationIssue("INPUT_FIELD_VERSION_UNPUBLISHED",field.fieldCode(),"字段版本尚未发布"));
            else {
                if("POST_EXPERIMENT".equals(version.availabilityStage()))issues.add(new ValidationIssue("POST_EXPERIMENT_FIELD_NOT_ALLOWED",field.fieldCode(),"实验后字段不能进入实验前预测方案"));
                if("COMPOSITION".equals(version.valueType()))compositionIncluded=true;
            }
            if(!field.required())issues.add(new ValidationIssue("OPTIONAL_FIELD_NOT_SUPPORTED",field.fieldCode(),"当前版本选入字段必须设为必需"));
        }
        if(compositionIncluded){
            var dictionary=scheme.materialDictionaryVersionId()==null?null:repository.materialDictionary(actor().organizationId(),scheme.materialDictionaryVersionId()).orElse(null);
            if(dictionary==null)issues.add(new ValidationIssue("MATERIAL_DICTIONARY_REQUIRED","FORMULA","配方组成字段必须绑定材料字典版本"));
            else if(!"FROZEN".equals(dictionary.status()))issues.add(new ValidationIssue("MATERIAL_DICTIONARY_NOT_FROZEN","FORMULA","材料字典版本尚未冻结"));
        }
        return List.copyOf(issues);
    }

    private void validatePublishable(TargetVersionView version){
        var d=version.definition();var problems=new java.util.ArrayList<String>();
        if(version.resultStandardFieldDictionaryId()==null || !activeStandardField(version.resultStandardFieldDictionaryId()))problems.add("resultStandardField");
        if(blank(d,"testMethod"))problems.add("testMethod");if(blank(d,"sopCode"))problems.add("sopCode");
        if(blank(d,"testStage"))problems.add("testStage");if(blank(d,"pretreatment"))problems.add("pretreatment");
        if(blank(d,"optimizationDirection"))problems.add("optimizationDirection");
        if("CONTINUOUS".equals(version.valueType())){if(version.unit()==null||version.unit().isBlank())problems.add("unit");var domain=d.path("valueDomain");if(!domain.isObject()||!domain.has("minimum")||!domain.has("maximum")||!domain.path("minimum").isNumber()||!domain.path("maximum").isNumber()||domain.path("minimum").decimalValue().compareTo(domain.path("maximum").decimalValue())>=0)problems.add("valueDomain");}
        if("BINARY".equals(version.valueType())){if(version.classes().size()!=2)problems.add("classes");if(blank(d,"positiveClass")||!version.classes().contains(d.path("positiveClass").asText()))problems.add("positiveClass");}
        if("ORDINAL".equals(version.valueType())&&version.classes().size()<2)problems.add("orderedClasses");
        if("CATEGORICAL".equals(version.valueType())&&version.classes().size()<2)problems.add("classes");
        if(new HashSet<>(version.classes()).size()!=version.classes().size())problems.add("duplicateClasses");
        if(!problems.isEmpty())throw new ApiException(ApiErrorCode.DATA_SCHEMA_INVALID,"Y定义尚不完整",Map.of("missingOrInvalid",problems));
    }

    private void validateFieldPublishable(InputFieldVersionView version){
        if("COMPOSITION".equals(version.valueType())){
            var codes=version.definition().path("standardFieldCodes");if(!codes.isArray()||codes.size()<2)throw validation("配方字段必须绑定材料编码与比例标准字段");
            var requiredCodes=Set.of("FORMULA.ITEM.MATERIAL_CODE","FORMULA.ITEM.RATIO");var actualCodes=new HashSet<String>();for(var code:codes)actualCodes.add(code.asText());
            if(!actualCodes.containsAll(requiredCodes))throw validation("配方字段必须同时绑定FORMULA.ITEM.MATERIAL_CODE与FORMULA.ITEM.RATIO");
            for(var code:codes)if(!activeStandardFieldCode(code.asText()))throw validation("配方字段引用了无效标准字段: "+code.asText());
        }else if(version.standardFieldDictionaryId()==null||!activeStandardField(version.standardFieldDictionaryId()))throw validation("X发布前必须绑定有效标准字段");
    }

    private void validateTargetCommand(TargetCommand c,boolean publish){required(c.code(),"目标编码");required(c.name(),"目标名称");required(c.category(),"性能分类");var type=upperRequired(c.valueType(),"结果类型");if(!List.of("CONTINUOUS","ORDINAL","BINARY","CATEGORICAL").contains(type))throw validation("不支持的结果类型");}
    private void validateInputField(InputFieldCommand c){required(c.code(),"字段编码");required(c.name(),"字段名称");if(!List.of("NUMBER","STRING","BOOLEAN","CATEGORY","COMPOSITION").contains(upperRequired(c.valueType(),"字段类型")))throw validation("不支持的字段类型");if(!List.of("PRE_EXPERIMENT","POST_EXPERIMENT").contains(upperRequired(c.availabilityStage(),"可用时点")))throw validation("不支持的字段可用时点");}
    private void validateUniqueSchemeFields(List<SchemeFieldCommand> fields){var ids=new HashSet<UUID>();var ordinals=new HashSet<Integer>();for(var f:fields){if(f.inputFieldVersionId()==null||!ids.add(f.inputFieldVersionId())||f.ordinal()<0||!ordinals.add(f.ordinal()))throw validation("输入方案字段或顺序重复");}for(int ordinal=0;ordinal<fields.size();ordinal++)if(!ordinals.contains(ordinal))throw validation("输入方案字段顺序必须从0连续排列");}

    private void updateTargetProjection(Actor a,UUID id,TargetCommand c,long revision){var n=repository.jdbc().update("UPDATE ai.prediction_target SET name=?,performance_project=?,value_type=?,revision=revision+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",c.name().strip(),c.category().strip(),c.valueType().toUpperCase(Locale.ROOT),a.userId(),a.organizationId(),id,revision);if(n!=1)throw versionConflict();}
    private void bumpTarget(Actor a,UUID id,long revision){var n=repository.jdbc().update("UPDATE ai.prediction_target SET revision=revision+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",a.userId(),a.organizationId(),id,revision);if(n!=1)throw versionConflict();}
    private boolean activeStandardField(UUID id){var n=repository.jdbc().queryForObject("SELECT count(*) FROM tpl.standard_field_dictionary WHERE id=? AND status='ACTIVE'",Long.class,id);return n!=null&&n>0;}
    private boolean activeStandardFieldCode(String code){var n=repository.jdbc().queryForObject("SELECT count(*) FROM tpl.standard_field_dictionary WHERE dictionary_code=? AND status='ACTIVE'",Long.class,code);return n!=null&&n>0;}
    private UUID recommendStandardField(InputFieldCommand command){
        if(command.name()==null||command.name().isBlank())return null;var unit=nullable(command.unit());
        return repository.jdbc().query("""
            SELECT id FROM tpl.standard_field_dictionary
            WHERE status='ACTIVE' AND lower(display_name)=lower(?)
              AND (?::text IS NULL OR default_unit IS NULL OR lower(default_unit)=lower(?))
            ORDER BY CASE WHEN ?::text IS NOT NULL AND lower(default_unit)=lower(?) THEN 0 ELSE 1 END,version_no DESC,id LIMIT 1
            """,(rs,n)->rs.getObject(1,UUID.class),command.name().strip(),unit,unit,unit,unit).stream().findFirst().orElse(null);
    }
    private void requireMaterial(UUID id){if(id==null)throw validation("材料ID不能为空");var n=repository.jdbc().queryForObject("SELECT count(*) FROM mdm.material WHERE id=? AND status<>'RETIRED'",Long.class,id);if(n==null||n==0)throw notFound("材料不存在或已停用");}

    private Map<String,DetectedSource> detectSources(UUID organizationId,TargetSummary target,TargetVersionView version){
        var result=new LinkedHashMap<String,DetectedSource>();var targetNeedle=normalize(target.name());var codeNeedle=normalize(target.code());var expectedMethod=version.definition().path("testMethod").asText("");
        repository.jdbc().query("""
            SELECT ss.source_type,ts.logical_sample_key,sr.observations_jsonb,sr.facts_jsonb,sr.source_coordinates_jsonb
            FROM ai.training_sample ts
            JOIN ai.sample_revision sr ON sr.organization_id=ts.organization_id AND sr.id=ts.current_sample_revision_id
            JOIN ai.sample_source ss ON ss.organization_id=sr.organization_id AND ss.id=sr.sample_source_id
            WHERE ts.organization_id=? AND ts.status IN ('ACTIVE','TAKEN_OVER') AND sr.status='CURRENT'
            ORDER BY ts.logical_sample_key LIMIT 5000
            """,rs->{while(rs.next()){
                var source=rs.getString("source_type");var sample=rs.getString("logical_sample_key");var observations=repository.json(rs,"observations_jsonb");var facts=repository.json(rs,"facts_jsonb");var coordinates=repository.json(rs,"source_coordinates_jsonb");
                scanObservation(observations,"",(node,path)->{
                    var field=firstText(node,"targetName","name","label","labelPath","fieldName","targetCode","fieldCode","testMethod");var normalized=normalize(field);if(normalized.isBlank())return;
                    var confidence=semanticConfidence(normalized,targetNeedle,codeNeedle);
                    var method=firstText(node,"testMethod","method");if(!expectedMethod.isBlank()&&normalize(method).contains(normalize(expectedMethod)))confidence=Math.min(100,confidence+10);
                    if(confidence<60)return;var unit=firstText(node,"unit","sourceUnit");var load=firstText(node,"load","loadCondition");var substrate=firstText(node,"substrate","substrateType");var stage=firstText(node,"stage","testStage");
                    var semantic=mapper.createObjectNode().put("field",field).put("unit",unit).put("testMethod",method).put("load",load).put("substrate",substrate).put("stage",stage);var key=hashing.hash(semantic);var found=result.get(key);if(found==null){found=new DetectedSource(key,field,unit,method,load,substrate,stage,confidence);result.put(key,found);}found.confidence=Math.max(found.confidence,confidence);(source.equals("DATA_CENTER")?found.dataSamples:found.experimentSamples).add(sample);if(found.samples.size()<3)found.samples.add(new SourceSamplePreview(source,sample,displayValue(node),displayLocation(coordinates,path)));
                });
                // R03 facts are stored as a flat field-code map.  Keep the same
                // detector for both the normalized observation projection and
                // the immutable facts projection so source suggestions work for
                // Data Center and Experiment uploads alike.
                scanObservation(facts,"/facts",(node,path)->{
                    var field=firstText(node,"targetName","name","label","labelPath","fieldName","targetCode","fieldCode","testMethod");var normalized=normalize(field);if(normalized.isBlank())return;
                    var confidence=semanticConfidence(normalized,targetNeedle,codeNeedle);
                    var method=firstText(node,"testMethod","method");if(!expectedMethod.isBlank()&&!method.isBlank()&&normalize(method).contains(normalize(expectedMethod)))confidence=Math.min(100,confidence+10);
                    if(confidence<60)return;var unit=firstText(node,"unit","normalizedUnit","rawUnit","sourceUnit");var load=firstText(node,"load","loadCondition");var substrate=firstText(node,"substrate","substrateType");var stage=firstText(node,"stage","testStage");
                    var semantic=mapper.createObjectNode().put("field",field).put("unit",unit).put("testMethod",method).put("load",load).put("substrate",substrate).put("stage",stage);var key=hashing.hash(semantic);var found=result.get(key);if(found==null){found=new DetectedSource(key,field,unit,method,load,substrate,stage,confidence);result.put(key,found);}found.confidence=Math.max(found.confidence,confidence);(source.equals("DATA_CENTER")?found.dataSamples:found.experimentSamples).add(sample);if(found.samples.size()<3)found.samples.add(new SourceSamplePreview(source,sample,displayValue(node),displayLocation(coordinates,path)));
                });
            }return null;},organizationId);
        return result;
    }
    private void scanObservation(JsonNode node,String path,java.util.function.BiConsumer<JsonNode,String> consumer){
        if(node==null||node.isNull())return;if(node.isObject()){if(hasObservationValue(node))consumer.accept(node,path);node.fields().forEachRemaining(e->scanObservation(e.getValue(),path+"/"+e.getKey(),consumer));}else if(node.isArray())for(int i=0;i<node.size();i++)scanObservation(node.get(i),path+"/"+i,consumer);
    }
    private boolean hasObservationValue(JsonNode node){for(var key:List.of("value","result","parsedValue","effectiveValue","rawValue"))if(node.hasNonNull(key)&&node.path(key).isValueNode())return true;return false;}
    private String displayValue(JsonNode node){for(var key:List.of("value","result","parsedValue","effectiveValue","rawValue"))if(node.hasNonNull(key))return node.path(key).asText()+nullableSuffix(node.path("unit").asText(""));return "";}
    private String displayLocation(JsonNode coordinates,String fallback){var sheet=firstText(coordinates,"sheetName","sheet","page","fileName");return sheet.isBlank()?fallback:sheet+fallback;}
    private String firstText(JsonNode node,String... names){for(var name:names)if(node!=null&&node.hasNonNull(name)&&node.path(name).isValueNode()&&!node.path(name).asText().isBlank())return node.path(name).asText().strip();return "";}
    private String normalize(String value){if(value==null)return "";return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-/:：()（）]+","");}
    /** Match the stable semantic part of a customer label while ignoring
     * conditions such as angle, load, substrate and result suffixes.  The
     * import facts use labels like “漆膜固化的表干性” while a Y is named “UV表干”,
     * so exact string containment is insufficient for source suggestions. */
    private int semanticConfidence(String field,String target,String code){
        if(field.isBlank())return 0;
        if(field.equals(target)||field.equals(code))return 100;
        if(field.contains(target)||target.contains(field)||field.contains(code)||code.contains(field))return 85;
        var best=0;
        for(int i=0;i<target.length();i++)for(int j=i+2;j<=target.length();j++){
            var token=target.substring(i,j);if(field.contains(token))best=Math.max(best,token.length());
        }
        // Two-character Chinese semantic tokens (光泽、表干、硬度、附着力等)
        // are enough to form a reviewable suggestion; details remain in the
        // candidate's method/load/stage and still require confirmation.
        if(best<2)return 0;
        return Math.min(84,60+best*4);
    }
    private String nullableSuffix(String value){return value==null||value.isBlank()?"":" "+value;}
    private void putIfText(ObjectNode target,String name,String value){if(value!=null&&!value.isBlank())target.put(name,value);}
    private static final class DetectedSource{
        final String key,fieldName,unit,testMethod,load,substrate,stage;int confidence;final Set<String> dataSamples=new LinkedHashSet<>(),experimentSamples=new LinkedHashSet<>();final List<SourceSamplePreview> samples=new ArrayList<>();
        DetectedSource(String key,String fieldName,String unit,String testMethod,String load,String substrate,String stage,int confidence){this.key=key;this.fieldName=fieldName;this.unit=unit;this.testMethod=testMethod;this.load=load;this.substrate=substrate;this.stage=stage;this.confidence=confidence;}
    }

    private TargetSummary targetRequired(UUID org,UUID id){return repository.target(org,id).orElseThrow(()->notFound("预测目标不存在"));}
    private TargetVersionView targetVersionRequired(UUID org,UUID id){return repository.targetVersion(org,id).orElseThrow(()->notFound("目标版本不存在"));}
    private SourceMappingView sourceMappingRequired(UUID org,UUID id){return repository.sourceMapping(org,id).orElseThrow(()->notFound("来源映射不存在"));}
    private InputFieldSummary inputFieldRequired(UUID org,UUID id){return repository.inputField(org,id).orElseThrow(()->notFound("输入字段不存在"));}
    private InputFieldVersionView inputFieldVersionRequired(UUID org,UUID id){return repository.inputFieldVersion(org,id).orElseThrow(()->notFound("输入字段版本不存在"));}
    private TrainingPolicyView policyRequired(UUID org,UUID id){return repository.trainingPolicy(org,id).orElseThrow(()->notFound("训练策略不存在"));}

    private <T>T idempotent(String operation,String key,Object request,Class<T> type,Supplier<T> action){
        var a=actor();var normalized=required(key,"Idempotency-Key");var requestHash=hashing.hash(request);repository.lockCommand(a.organizationId(),operation,normalized);
        var receipt=repository.commandReceipt(a.organizationId(),operation,normalized);if(receipt.isPresent()){
            if(!requestHash.equals(receipt.get().get("requestHash")))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            try{return mapper.treeToValue((JsonNode)receipt.get().get("response"),type);}catch(Exception e){throw new IllegalStateException("幂等结果无法恢复",e);}
        }
        var response=action.get();var responseJson=mapper.valueToTree(response);UUID resourceId=responseJson.hasNonNull("id")?UUID.fromString(responseJson.path("id").asText()):null;
        repository.jdbc().update("""
                INSERT INTO ai.configuration_command_receipt(id,organization_id,operation,idempotency_key,request_hash,resource_type,resource_id,response_jsonb,created_by)
                VALUES(?,?,?,?,?,?,?,?,?)
                """,UUID.randomUUID(),a.organizationId(),operation,normalized,requestHash,type.getSimpleName(),resourceId,repository.pg(responseJson),a.userId());return response;
    }

    private void audit(Actor a,String action,String type,UUID id,JsonNode detail){audit.append(a.organizationId(),a.userId(),action,type,id,detail);}
    private Actor actor(){return ActorContext.required();}
    private String targetHash(String type,String unit,List<String> classes,JsonNode definition,JsonNode semantics){return hashing.hash(Map.of("valueType",upperRequired(type,"结果类型"),"unit",unit==null?"":unit,"classes",classes,"definition",definition,"observationSemantics",semantics));}

    private UUID standardFieldId(String code){
        if(code==null||code.isBlank())return null;
        return repository.jdbc().query("SELECT id FROM tpl.standard_field_dictionary WHERE dictionary_code=? AND status='ACTIVE' ORDER BY version_no DESC LIMIT 1",
                (rs,n)->rs.getObject(1,UUID.class),code.strip().toUpperCase(Locale.ROOT)).stream().findFirst().orElseThrow(()->validation("结果数据字段不存在或未启用: "+code));
    }

    private String fieldHash(InputFieldCommand c){return fieldHash(c,c.standardFieldDictionaryId());}
    private String fieldHash(InputFieldCommand c,UUID standardFieldId){return hashing.hash(Map.of("valueType",upperRequired(c.valueType(),"字段类型"),"unit",c.unit()==null?"":c.unit(),"availabilityStage",upperRequired(c.availabilityStage(),"可用时点"),"standardFieldDictionaryId",standardFieldId==null?"":standardFieldId,"definition",object(c.definition()),"preprocessing",object(c.preprocessing())));}
    private String schemeHash(InputSchemeCommand c,String dictionaryHash){return hashing.hash(Map.of("targetVersionId",c.targetVersionId(),"materialDictionaryHash",dictionaryHash==null?"":dictionaryHash,"code",c.code(),"fields",list(c.fields()),"preprocessing",object(c.preprocessing())));}
    private ObjectNode object(JsonNode node){return node!=null&&node.isObject()?(ObjectNode)node.deepCopy():mapper.createObjectNode();}
    private <T>List<T> list(List<T> values){return values==null?List.of():List.copyOf(values);}
    private boolean blank(JsonNode node,String field){return node==null||!node.hasNonNull(field)||node.path(field).asText().isBlank();}
    private String required(String value,String field){if(value==null||value.isBlank())throw validation(field+"不能为空");return value.strip();}
    private String upperRequired(String value,String field){return required(value,field).toUpperCase(Locale.ROOT);}
    private String upper(String value){return value==null||value.isBlank()?null:value.strip().toUpperCase(Locale.ROOT);}
    private String nullable(String value){return value==null||value.isBlank()?null:value.strip();}
    private long value(Long v){return v==null?0:v;}
    private int page(int value){return Math.max(1,value);}
    private int size(int value){return Math.min(100,Math.max(1,value));}
    private void lock(long actual,long expected){if(actual!=expected)throw versionConflict();}
    private ApiException validation(String message){return new ApiException(ApiErrorCode.VALIDATION_ERROR,message);}
    private ApiException conflict(String message){return new ApiException(ApiErrorCode.RESOURCE_CONFLICT,message);}
    private ApiException notFound(String message){return new ApiException(ApiErrorCode.NOT_FOUND,message);}
    private ApiException immutable(){return new ApiException(ApiErrorCode.VERSION_CONFLICT,"已发布或冻结的配置不可修改");}
    private ApiException versionConflict(){return new ApiException(ApiErrorCode.VERSION_CONFLICT);}
}
