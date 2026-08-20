package com.jsd.aird.tpl.api;

import java.util.ArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Component;

/** Single server-side rule for required template fields used by formal records. */
@Component
public class RequiredFieldValidator {

    public void validate(JsonNode schema, JsonNode data) {
        var missing = new ArrayList<Field>();
        var fields = schema == null ? null : schema.path("x-jsd-field-model").path("fields");
        if (fields == null || !fields.isArray()) return;
        for (var field : fields) {
            if (!field.path("required").asBoolean(false) || field.path("candidate").asBoolean(false)) continue;
            var path = field.path("dataPath").asText("");
            var name = field.path("name").asText(field.path("fieldCode").asText(path));
            if (path.isBlank() || !hasValue(data, path)) missing.add(new Field(name, path));
        }
        if (!missing.isEmpty()) {
            throw new ApiException(ApiErrorCode.TEMPLATE_REQUIRED_VALUE_MISSING,
                    "请填写必填字段：" + missing.stream().map(Field::name).reduce((a, b) -> a + "、" + b).orElse(""),
                    java.util.List.copyOf(missing));
        }
    }

    private boolean hasValue(JsonNode root, String path) {
        return hasValue(root, path.split("/"), 0);
    }

    private boolean hasValue(JsonNode node, String[] parts, int index) {
        while (index < parts.length && parts[index].isBlank()) index++;
        if (index >= parts.length) return scalar(node);
        if (node == null || node.isNull() || node.isMissingNode()) return false;
        var segment = parts[index].replace("~1", "/").replace("~0", "~");
        if ("*".equals(segment)) {
            if (!node.isArray() || node.isEmpty()) return false;
            for (var item : node) if (!hasValue(item, parts, index + 1)) return false;
            return true;
        }
        if (node.isArray()) {
            try { return hasValue(node.path(Integer.parseInt(segment)), parts, index + 1); }
            catch (NumberFormatException ignored) { return false; }
        }
        return hasValue(node.path(segment), parts, index + 1);
    }

    private boolean scalar(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return false;
        if (node.isTextual()) return !node.asText().trim().isEmpty();
        if (node.isArray() || node.isObject()) return !node.isEmpty();
        return true;
    }

    public record Field(String name, String path) { }
}
