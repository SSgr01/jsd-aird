package com.jsd.aird.mfg.ingest.infrastructure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.ingest.application.port.InstanceDocumentRecognitionClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenAiInstanceDocumentRecognitionClient implements InstanceDocumentRecognitionClient {

    private static final String PROMPT_VERSION = "instance-photo-v1";
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public OpenAiInstanceDocumentRecognitionClient(
            ObjectMapper objectMapper,
            @Value("${app.model.base-url:}") String baseUrl,
            @Value("${app.model.api-key:}") String apiKey,
            @Value("${app.model.model:}") String model
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = strip(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(5));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public boolean isConfigured() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    @Override
    public Result recognize(JsonNode schema, JsonNode mapping, List<ImageSource> images) {
        if (!isConfigured()) throw new IllegalStateException("Instance photo recognition is not configured");
        var body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0d);
        body.set("response_format", objectMapper.createObjectNode().put("type", "json_object"));
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt());
        var content = messages.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", userPrompt(schema, mapping));
        for (var image : images) {
            content.addObject().put("type", "image_url").set("image_url",
                    objectMapper.createObjectNode().put("url", "data:" + image.contentType()
                            + ";base64," + Base64.getEncoder().encodeToString(image.content())));
        }
        var response = restClient.post().uri(baseUrl + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey).body(body).retrieve().body(JsonNode.class);
        var text = response == null ? "" : response.path("choices").path(0)
                .path("message").path("content").asText("");
        try {
            var root = objectMapper.readTree(stripFence(text));
            if (!root.path("data").isObject()) throw new IllegalArgumentException("data must be object");
            var items = new ArrayList<ValueItem>();
            var bindings = new HashMap<String, JsonNode>();
            mapping.forEach(binding -> bindings.put(binding.path("bindingId").asText(), binding));
            var itemKeys = new HashSet<String>();
            for (var item : root.path("items")) {
                validateItem(item, bindings, itemKeys, images.size());
                items.add(new ValueItem(
                        item.path("itemKey").asText(), item.path("itemKind").asText("SCALAR"),
                        item.path("bindingId").asText(), item.path("fieldCode").asText(),
                        item.path("dataPath").asText(), item.path("recordKey").asText(null),
                        item.has("recordIndex") ? item.path("recordIndex").asInt() : null,
                        item.path("rawValue").deepCopy(), item.path("normalizedValue").deepCopy(),
                        item.path("sourceLocator").deepCopy(), item.path("confidence").asDouble(0d),
                        item.path("handwritten").asBoolean(false)));
            }
            return new Result(root.path("data").deepCopy(), List.copyOf(items), model);
        } catch (Exception exception) {
            throw new IllegalArgumentException("INSTANCE_VALUE_EXTRACTION 响应不符合协议", exception);
        }
    }

    private void validateItem(
            JsonNode item,
            HashMap<String, JsonNode> bindings,
            HashSet<String> itemKeys,
            int imageCount
    ) {
        var itemKey = item.path("itemKey").asText("");
        if (itemKey.isBlank() || !itemKeys.add(itemKey)) {
            throw new IllegalArgumentException("itemKey is missing or duplicated");
        }
        var binding = bindings.get(item.path("bindingId").asText(""));
        if (binding == null) throw new IllegalArgumentException("item bindingId is not in selected template");
        var expectedPath = binding.path("dataPath").asText("");
        var actualPath = item.path("dataPath").asText("");
        var pathMatches = expectedPath.equals(actualPath) || concreteWildcardPath(expectedPath, actualPath);
        if (!pathMatches) throw new IllegalArgumentException("item dataPath does not match binding");
        var confidence = item.path("confidence").asDouble(-1d);
        if (confidence < 0d || confidence > 1d) throw new IllegalArgumentException("confidence out of range");
        var source = item.path("sourceLocator");
        var imageIndex = source.path("imageIndex").asInt(-1);
        var bbox = source.path("bbox");
        if (imageIndex < 0 || imageIndex >= imageCount || !bbox.isArray() || bbox.size() != 4) {
            throw new IllegalArgumentException("source evidence is missing");
        }
        for (var coordinate : bbox) {
            if (!coordinate.isNumber() || coordinate.asDouble() < 0d || coordinate.asDouble() > 1d) {
                throw new IllegalArgumentException("source bbox is invalid");
            }
        }
    }

    private boolean concreteWildcardPath(String expected, String actual) {
        var wildcard = expected.indexOf("/*/");
        if (wildcard < 0) return false;
        var prefix = expected.substring(0, wildcard);
        var suffix = expected.substring(wildcard + 2);
        if (!actual.startsWith(prefix + "/") || !actual.endsWith(suffix)) return false;
        var index = actual.substring(prefix.length() + 1, actual.length() - suffix.length());
        return index.matches("[0-9]+");
    }

    private String systemPrompt() {
        return """
                你是生产单实例数据抽取器，只抽取图片中已填写的真实数据，不分析或修改模板结构。
                必须只输出 JSON 对象：data 为按 dataPath 组装的对象；items 为逐值证据数组。
                items 每项必须包含 itemKey,itemKind,bindingId,fieldCode,dataPath,rawValue,
                normalizedValue,sourceLocator,confidence,handwritten；明细或矩阵还要 recordIndex/recordKey。
                sourceLocator 必须包含 imageIndex 和 bbox[x,y,width,height]，坐标为 0..1。
                无法确认的值用 null 和低置信度，禁止猜测。手写内容 handwritten=true。
                """;
    }

    private String userPrompt(JsonNode schema, JsonNode mapping) {
        return "协议=" + PROMPT_VERSION + "\n已选择模板 Schema=" + schema
                + "\n已选择模板 Mapping=" + mapping
                + "\n请对齐这些字段，抽取普通字段、明细记录和矩阵成员记录。";
    }

    private String stripFence(String value) {
        var result = value == null ? "" : value.strip();
        if (result.startsWith("```")) {
            result = result.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return result;
    }

    private String strip(String value) {
        var result = value == null ? "" : value.strip();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
