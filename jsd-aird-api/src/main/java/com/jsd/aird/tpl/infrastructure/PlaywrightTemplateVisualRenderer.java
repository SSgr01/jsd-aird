package com.jsd.aird.tpl.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.tpl.application.port.TemplateVisualRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Uses the existing frontend render route as a browser contract. If Node,
 * Playwright, Chrome, or the dev server is unavailable, recognition continues
 * with deterministic JSON facts.
 */
@Component
public class PlaywrightTemplateVisualRenderer implements TemplateVisualRenderer {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightTemplateVisualRenderer.class);

    private final ObjectStorage objectStorage;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String frontendBaseUrl;
    private final String nodeCommand;
    private final String captureScript;
    private final String browserPath;
    private final Duration timeout;

    public PlaywrightTemplateVisualRenderer(
            ObjectStorage objectStorage,
            ObjectMapper objectMapper,
            @Value("${app.render.enabled:true}") boolean enabled,
            @Value("${app.render.frontend-base-url:http://localhost:5173}") String frontendBaseUrl,
            @Value("${app.render.node-command:node}") String nodeCommand,
            @Value("${app.render.capture-script:../jsd-aird-web/scripts/capture-template-render.mjs}") String captureScript,
            @Value("${app.render.browser-path:}") String browserPath,
            @Value("${app.render.timeout:20s}") Duration timeout
    ) {
        this.objectStorage = objectStorage;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.frontendBaseUrl = stripTrailingSlash(frontendBaseUrl);
        this.nodeCommand = nodeCommand == null || nodeCommand.isBlank() ? "node" : nodeCommand.strip();
        this.captureScript = captureScript == null ? "" : captureScript.strip();
        this.browserPath = browserPath == null ? "" : browserPath.strip();
        this.timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
    }

    @Override
    public RenderResult render(UUID importJobId) {
        if (!enabled) return RenderResult.unavailable("DISABLED", "未启用浏览器渲染");
        if (captureScript.isBlank()) return RenderResult.unavailable("UNAVAILABLE", "未配置截图脚本");

        var output = temporaryPng();
        var url = frontendBaseUrl + "/render/import/" + importJobId;
        try {
            var command = new java.util.ArrayList<String>();
            command.add(nodeCommand);
            command.add(captureScript);
            command.add("--url");
            command.add(url);
            command.add("--output");
            command.add(output.toString());
            command.add("--timeout-ms");
            command.add(String.valueOf(Math.max(1000, timeout.toMillis())));
            var processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
            if (!browserPath.isBlank()) processBuilder.environment().put("JSD_AIRD_RENDER_BROWSER_PATH", browserPath);
            var process = processBuilder.start();
            if (!process.waitFor(Math.max(1, timeout.toSeconds() + 5), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return unavailable(url, "截图进程超时");
            }
            var outputLog = new String(
                    process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8
            );
            if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                return unavailable(url, "截图进程失败：" + compact(outputLog));
            }
            var bytes = Files.readAllBytes(output);
            var objectKey = "template-render/" + importJobId + "/workbook.png";
            try (var source = new java.io.ByteArrayInputStream(bytes)) {
                objectStorage.put(objectKey, source, bytes.length, "image/png");
            }
            var dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
            var metadata = parseMetadata(outputLog);
            log.info("template_visual_rendered importJobId={} size={} width={} height={} objectKey={}",
                    importJobId, bytes.length, metadata.width(), metadata.height(), objectKey);
            return new RenderResult(
                    "RENDERED", url, objectKey, dataUri, bytes.length,
                    metadata.width(), metadata.height(), "浏览器渲染完成"
            );
        } catch (Exception exception) {
            log.warn("template_visual_render_unavailable importJobId={} reason={}",
                    importJobId, exception.getMessage());
            return unavailable(url, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        } finally {
            try {
                Files.deleteIfExists(output);
            } catch (IOException ignored) {
                // Temporary files are best-effort cleanup only.
            }
        }
    }

    private RenderResult unavailable(String url, String detail) {
        return new RenderResult("UNAVAILABLE", url, "", "", 0, 0, 0, detail);
    }

    private Path temporaryPng() {
        try {
            return Files.createTempFile("jsd-aird-template-render-", ".png");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建模板截图临时文件", exception);
        }
    }

    private Metadata parseMetadata(String output) {
        try {
            var node = objectMapper.readTree(output.lines().reduce((first, second) -> second).orElse(""));
            return new Metadata(node.path("width").asInt(0), node.path("height").asInt(0));
        } catch (Exception ignored) {
            return new Metadata(0, 0);
        }
    }

    private String compact(String value) {
        var normalized = value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ").strip();
        return normalized.isBlank() ? "无进程输出"
                : normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private static String stripTrailingSlash(String value) {
        return value == null ? "" : value.strip().replaceAll("/+$", "");
    }

    private record Metadata(int width, int height) {
    }
}
