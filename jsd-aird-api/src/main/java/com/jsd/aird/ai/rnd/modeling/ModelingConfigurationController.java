package com.jsd.aird.ai.rnd.modeling;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.jsd.aird.ai.rnd.modeling.ModelingContracts.*;

@RestController
@RequestMapping("/api/v1/ai/rnd")
public class ModelingConfigurationController {
    private final ModelingConfigurationService service;

    public ModelingConfigurationController(ModelingConfigurationService service) { this.service=service; }

    @GetMapping("/targets")
    public ApiResponse<?> targets(@RequestParam(required=false)String keyword,@RequestParam(required=false)String status,
                                  @RequestParam(required=false)String valueType,@RequestParam(required=false)String category,
                                  @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){
        return ok(service.targets(keyword,status,valueType,category,page,size));
    }
    @PostMapping("/targets") public ApiResponse<?> createTarget(@RequestBody TargetCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.createTarget(body,key));}
    @GetMapping("/targets/{id}") public ApiResponse<?> target(@PathVariable UUID id){return ok(service.target(id));}
    @GetMapping("/targets/{id}/versions") public ApiResponse<?> targetVersions(@PathVariable UUID id){return ok(service.targetVersions(id));}
    @PostMapping("/targets/{id}/versions") public ApiResponse<?> createTargetVersion(@PathVariable UUID id,@RequestBody TargetCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.createTargetVersion(id,body,key));}
    @PatchMapping("/target-versions/{id}") public ApiResponse<?> updateTargetVersion(@PathVariable UUID id,@RequestBody TargetCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.updateTargetVersion(id,body,key));}
    @PostMapping("/target-versions/{id}/publish") public ApiResponse<?> publishTargetVersion(@PathVariable UUID id,@RequestBody VersionCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.publishTargetVersion(id,body,key));}
    @PostMapping("/targets/{id}/retire") public ApiResponse<?> retireTarget(@PathVariable UUID id,@RequestBody RetireCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.retireTarget(id,body,key));}
    @GetMapping("/input-fields") public ApiResponse<?> inputFields(@RequestParam(required=false)String keyword,@RequestParam(required=false)String status,@RequestParam(required=false)String valueType,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.inputFields(keyword,status,valueType,page,size));}
    @PostMapping("/input-fields") public ApiResponse<?> createInputField(@RequestBody InputFieldCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.createInputField(body,key));}
    @GetMapping("/input-fields/{id}") public ApiResponse<?> inputField(@PathVariable UUID id){return ok(service.inputField(id));}
    @GetMapping("/input-fields/{id}/versions") public ApiResponse<?> inputFieldVersions(@PathVariable UUID id){return ok(service.inputFieldVersions(id));}
    @PostMapping("/input-fields/{id}/versions") public ApiResponse<?> createInputFieldVersion(@PathVariable UUID id,@RequestBody InputFieldCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.createInputFieldVersion(id,body,key));}
    @PostMapping("/input-field-versions/{id}/publish") public ApiResponse<?> publishInputFieldVersion(@PathVariable UUID id,@RequestBody VersionCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.publishInputFieldVersion(id,body,key));}

    @GetMapping("/targets/{id}/source-mappings") public ApiResponse<?> mappings(@PathVariable UUID id){return ok(service.sourceMappings(id));}
    @GetMapping("/targets/{id}/source-mapping-suggestions") public ApiResponse<?> mappingSuggestions(@PathVariable UUID id,@RequestParam(required=false)UUID targetVersionId){return ok(service.sourceMappingSuggestions(id,targetVersionId));}
    @PostMapping("/targets/{id}/source-mappings/confirm") public ApiResponse<?> confirmMapping(@PathVariable UUID id,@RequestBody ConfirmSourceMappingCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.confirmSourceMapping(id,body,key));}
    @PostMapping("/targets/{id}/source-mappings") public ApiResponse<?> createMapping(@PathVariable UUID id,@RequestBody SourceMappingCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.createSourceMapping(id,body,key));}
    @PostMapping("/source-mappings/{id}/publish") public ApiResponse<?> publishMapping(@PathVariable UUID id,@RequestBody VersionCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.publishSourceMapping(id,body,key));}

    @GetMapping("/targets/{id}/input-schemes") public ApiResponse<?> schemes(@PathVariable UUID id){return ok(service.inputSchemes(id));}
    @GetMapping("/targets/{id}/input-suggestions") public ApiResponse<?> inputSuggestions(@PathVariable UUID id,@RequestParam(required=false)UUID targetVersionId,@RequestParam(required=false)String keyword,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="30")int size){return ok(service.inputSuggestions(id,targetVersionId,keyword,page,size));}
    @PostMapping("/targets/{id}/input-schemes") public ApiResponse<?> createScheme(@PathVariable UUID id,@RequestBody InputSchemeCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.createInputScheme(id,body,key));}
    @PostMapping("/input-schemes/{id}/preview") public ApiResponse<?> previewScheme(@PathVariable UUID id,@RequestHeader("Idempotency-Key")String ignoredKey){return ok(service.previewInputScheme(id));}
    @PostMapping("/input-schemes/{id}/freeze") public ApiResponse<?> freezeScheme(@PathVariable UUID id,@RequestBody VersionCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.freezeInputScheme(id,body,key));}

    @GetMapping("/training-policies") public ApiResponse<?> policies(@RequestParam(required=false)UUID targetId){return ok(service.trainingPolicies(targetId));}
    @PostMapping("/training-policies") public ApiResponse<?> createPolicy(@RequestBody TrainingPolicyCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.createTrainingPolicy(body,key));}
    @PostMapping("/training-policies/{id}/publish") public ApiResponse<?> publishPolicy(@PathVariable UUID id,@RequestBody VersionCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.publishTrainingPolicy(id,body,key));}

    @GetMapping("/reference/standard-fields") public ApiResponse<?> standardFields(@RequestParam(required=false)String keyword){return ok(service.referenceFields(keyword));}
    @GetMapping("/reference/materials") public ApiResponse<?> materials(@RequestParam(required=false)String keyword){return ok(service.materials(keyword));}
    @GetMapping("/material-aliases") public ApiResponse<?> aliases(@RequestParam(required=false)String keyword){return ok(service.materialAliases(keyword));}
    @PostMapping("/material-aliases") public ApiResponse<?> createAlias(@RequestBody MaterialAliasCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.createMaterialAlias(body,key));}
    @PostMapping("/material-aliases/{id}/retire") public ApiResponse<?> retireAlias(@PathVariable UUID id,@RequestBody RetireCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.retireMaterialAlias(id,body,key));}
    @GetMapping("/material-dictionaries") public ApiResponse<?> dictionaries(){return ok(service.materialDictionaries());}
    @PostMapping("/material-dictionaries") public ApiResponse<?> createDictionary(@RequestBody MaterialDictionaryCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.createMaterialDictionary(body,key));}
    @PostMapping("/material-dictionaries/{id}/freeze") public ApiResponse<?> freezeDictionary(@PathVariable UUID id,@RequestBody VersionCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.freezeMaterialDictionary(id,body,key));}
    @PostMapping("/material-dictionaries/{id}/retire") public ApiResponse<?> retireDictionary(@PathVariable UUID id,@RequestBody RetireCommand body,@RequestHeader("Idempotency-Key")String key){return ok(service.retireMaterialDictionary(id,body,key));}

    private static <T>ApiResponse<T> ok(T data){return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());}
}
