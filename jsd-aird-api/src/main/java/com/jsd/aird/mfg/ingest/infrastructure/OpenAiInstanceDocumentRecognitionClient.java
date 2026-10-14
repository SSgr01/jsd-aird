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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenAiInstanceDocumentRecognitionClient implements InstanceDocumentRecognitionClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiInstanceDocumentRecognitionClient.class);
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
        return request(schema, mapping, images, null);
    }

    @Override
    public Result recognizeText(JsonNode schema, JsonNode mapping, String recognizedText) {
        if (recognizedText == null || recognizedText.isBlank()) {
            throw new IllegalArgumentException("MinerU 未识别到生产单文字");
        }
        return request(schema, mapping, List.of(), recognizedText);
    }

    private Result request(JsonNode schema, JsonNode mapping, List<ImageSource> images, String recognizedText) {
        if (!isConfigured()) throw new IllegalStateException("Instance photo recognition is not configured");
        var body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0d);
        body.set("response_format", objectMapper.createObjectNode().put("type", "json_object"));
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt(recognizedText != null));
        var content = messages.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", userPrompt(schema, mapping));
        if (recognizedText != null) content.addObject().put("type", "text")
                .put("text", "以下是 MinerU 从生产单图片识别的内容，仅依据这些内容抽取，不要猜测：\n" + recognizedText);
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
            var repairedPaths = 0;
            for (var item : root.path("items")) {
                var checked = validateItem(item, bindings, itemKeys, images.size(), recognizedText != null);
                if (checked.repairedPath()) repairedPaths++;
                items.add(new ValueItem(
                        item.path("itemKey").asText(), item.path("itemKind").asText("SCALAR"),
                        item.path("bindingId").asText(), item.path("fieldCode").asText(),
                        checked.dataPath(), item.path("recordKey").asText(null),
                        checked.recordIndex(),
                        item.path("rawValue").deepCopy(), item.path("normalizedValue").deepCopy(),
                        item.path("sourceLocator").deepCopy(), item.path("confidence").asDouble(0d),
                        item.path("handwritten").asBoolean(false)));
            }
            if (repairedPaths > 0) {
                // The binding owns the location, so the value still lands where the
                // template expects; the count only records that the model answered
                // with a reshaped path instead of the template's own.
                log.warn("字段抽取返回了 {} 个与模板 binding 不一致的 dataPath，已按模板路径纠正", repairedPaths);
            }
            return new Result(root.path("data").deepCopy(), List.copyOf(items), model);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "INSTANCE_VALUE_EXTRACTION 响应不符合协议：" + exception.getMessage(), exception);
        }
    }

    CheckedItem validateItem(
            JsonNode item,
            HashMap<String, JsonNode> bindings,
            HashSet<String> itemKeys,
            int imageCount, boolean textSource
    ) {
        var itemKey = item.path("itemKey").asText("");
        if (itemKey.isBlank() || !itemKeys.add(itemKey)) {
            throw new IllegalArgumentException("itemKey is missing or duplicated");
        }
        var binding = bindings.get(item.path("bindingId").asText(""));
        if (binding == null) throw new IllegalArgumentException("item bindingId is not in selected template");
        var confidence = item.path("confidence").asDouble(-1d);
        if (confidence < 0d || confidence > 1d) throw new IllegalArgumentException("confidence out of range");
        var source = item.path("sourceLocator");
        var imageIndex = source.path("imageIndex").asInt(-1);
        var bbox = source.path("bbox");
        if (!textSource && (imageIndex < 0 || imageIndex >= imageCount || !bbox.isArray() || bbox.size() != 4)) {
            throw new IllegalArgumentException("source evidence is missing");
        }
        for (var coordinate : textSource ? List.<JsonNode>of() : bbox) {
            if (!coordinate.isNumber() || coordinate.asDouble() < 0d || coordinate.asDouble() > 1d) {
                throw new IllegalArgumentException("source bbox is invalid");
            }
        }
        // The binding owns the location: a repeat field is addressed by the
        // template's own path (with its * segment) plus a record index.  A model
        // that echoes a concrete index or a reshaped path is corrected here rather
        // than failing the whole extraction, because losing every other field of a
        // production order over one path is much worse than re-targeting this one.
        var expectedPath = binding.path("dataPath").asText("");
        var actualPath = item.path("dataPath").asText("");
        var recordIndex = item.has("recordIndex") && !item.path("recordIndex").isNull()
                ? item.path("recordIndex").asInt() : null;
        if (expectedPath.equals(actualPath)) {
            return new CheckedItem(expectedPath, recordIndex, false);
        }
        if (recordIndex == null && wildcardPathMatches(expectedPath, actualPath)) {
            // A concrete index in the answer identifies the repeat record, so keep
            // it instead of dropping the record the value was read from.
            recordIndex = firstConcreteIndex(expectedPath, actualPath);
        }
        return new CheckedItem(expectedPath, recordIndex, true);
    }

    /**
     * A binding addresses repeat regions with {@code *}, so an item may answer with
     * one or more concrete record indexes, including as the last segment.  Every
     * other segment still has to be identical.
     */
    private boolean wildcardPathMatches(String expected, String actual) {
        var expectedSegments = expected.split("/", -1);
        var actualSegments = actual.split("/", -1);
        if (expectedSegments.length != actualSegments.length) return false;
        for (var index = 0; index < expectedSegments.length; index++) {
            if ("*".equals(expectedSegments[index])) {
                if (!actualSegments[index].matches("[0-9]+")) return false;
            } else if (!expectedSegments[index].equals(actualSegments[index])) {
                return false;
            }
        }
        return true;
    }

    /** The first repeat index the model made concrete, so its record is not lost. */
    private Integer firstConcreteIndex(String expected, String actual) {
        var expectedSegments = expected.split("/", -1);
        var actualSegments = actual.split("/", -1);
        if (expectedSegments.length != actualSegments.length) return null;
        for (var index = 0; index < expectedSegments.length; index++) {
            if ("*".equals(expectedSegments[index]) && actualSegments[index].matches("[0-9]+")) {
                return Integer.valueOf(actualSegments[index]);
            }
        }
        return null;
    }

    /** One validated item, addressed the way the template addresses it. */
    record CheckedItem(String dataPath, Integer recordIndex, boolean repairedPath) {
    }

    private String systemPrompt(boolean textSource) {
        return """
                你是生产单实例数据抽取器，只抽取图片中已填写的真实数据，不分析或修改模板结构。
                必须只输出 JSON 对象：data 为按 dataPath 组装的对象；items 为逐值证据数组。
                items 每项必须包含 itemKey,itemKind,bindingId,fieldCode,dataPath,rawValue,
                normalizedValue,sourceLocator,confidence,handwritten；明细或矩阵还要 recordIndex/recordKey。
                dataPath 必须原样照抄 Mapping 中该 bindingId 对应的 dataPath（含 * 段），不要改成具体下标，
                也不得增删层级；重复记录用 recordIndex 标明是第几条。
                data 按这些 dataPath 组装，重复区域写成数组，例如 {"records":[{"name":"A"},{"name":"B"}]}。
                sourceLocator 必须包含 imageIndex 和 bbox[x,y,width,height]，坐标为 0..1。
                无法确认的值用 null 和低置信度，禁止猜测。手写内容 handwritten=true。
                """ + (textSource ? "当前输入是 MinerU 识别文本，sourceLocator 使用 {\"provider\":\"MINERU\",\"text\":\"原文片段\"}，不得编造图片坐标。" : "");
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