package com.jsd.aird.ai.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.application.BusinessDataService;
import com.jsd.aird.ai.application.BusinessComputeService;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Version-checked text originals for native Markdown/sidebar links. */
@RestController
@RequestMapping("/api/v1/agents/business")
public class BusinessSourceController {
    private final BusinessDataService data;
    private final BusinessComputeService compute;
    private final AuthorizationService authorization;
    private final ObjectMapper json;
    public BusinessSourceController(BusinessDataService data, BusinessComputeService compute, AuthorizationService authorization, ObjectMapper json) {
        this.data = data; this.compute = compute; this.authorization = authorization; this.json = json;
    }
    @GetMapping("/records/{type}/{id}")
    public ResponseEntity<String> record(@PathVariable String type, @PathVariable UUID id, @RequestParam String ref) {
        requireUse(); var reference = object(ref);
        if (!type.equals(reference.path("type").asText()) || !id.toString().equals(reference.path("id").asText())) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        return original(data.invoke("business_read", json.createObjectNode().set("ref", reference)).path("candidates").path(0));
    }
    @GetMapping("/query")
    public ResponseEntity<String> query(@RequestParam String q, @RequestParam String snapshot_hash) {
        requireUse(); var result = data.invoke("business_query", object(q));
        if (!snapshot_hash.equals(result.path("snapshot_hash").asText())) throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "查询对应的数据快照已变化");
        return original(result.path("candidates").path(0));
    }
    @GetMapping("/compute/{kind}/{id}")
    public ResponseEntity<String> compute(@PathVariable String kind, @PathVariable UUID id, @RequestParam String revision) {
        requireUse(); var result = compute.result(kind, id); var candidate = result.path("candidates").path(0);
        if (!revision.equals(candidate.path("source").path("version_id").asText())) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        return original(candidate);
    }
    private ResponseEntity<String> original(com.fasterxml.jackson.databind.JsonNode candidate) {
        if (!candidate.hasNonNull("text")) throw new ApiException(ApiErrorCode.NOT_FOUND);
        String path = candidate.path("source").path("anchor").path("path").asText();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
            .body(candidate.path("text").asText() + (path.startsWith("/") && !path.startsWith("//") ? "\n\n业务页面：" + path : ""));
    }
    private ObjectNode object(String text) {
        if (text.length() > 16000) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        try { if (json.readTree(text) instanceof ObjectNode node) return node; }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new ApiException(ApiErrorCode.BAD_REQUEST); }
        throw new ApiException(ApiErrorCode.BAD_REQUEST);
    }
    private void requireUse() {
        var actor = ActorContext.required(); authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), "ai.use", "AI", null, "READ"));
    }
}
