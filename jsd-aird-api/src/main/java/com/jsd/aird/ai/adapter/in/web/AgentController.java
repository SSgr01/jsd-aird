package com.jsd.aird.ai.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.application.AgentDelegation;
import com.jsd.aird.ai.application.LiteratureResearchService;
import com.jsd.aird.ai.application.ResearchArtifactService;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {
    private final LiteratureResearchService research;
    private final AgentDelegation delegation;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private final String controlUrl;
    private final boolean enabled;
    private final ResearchArtifactService artifacts;

    public AgentController(LiteratureResearchService research, AgentDelegation delegation, ObjectMapper mapper, ResearchArtifactService artifacts,
            @Value("${app.ai.harness.control-url:http://127.0.0.1:8093}") String controlUrl,
            @Value("${app.ai.harness.enabled:false}") boolean enabled) {
        this.research = research; this.delegation = delegation; this.mapper = mapper;
        this.artifacts = artifacts;
        this.controlUrl = controlUrl.replaceAll("/$", ""); this.enabled = enabled;
    }

    @GetMapping("/capabilities")
    public Object capabilities() {
        research.requireUse();
        var value = mapper.createObjectNode().put("enabled", enabled).put("engine", "pi-v1").put("available", false);
        if (enabled) try {
            var request = HttpRequest.newBuilder(URI.create(controlUrl + "/internal/v1/harness/capabilities"))
                .timeout(Duration.ofSeconds(5)).header("X-Jsd-Agent-Ticket", delegation.issue(ActorContext.required())).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var capabilities = mapper.readTree(response.body());
                for (var name : java.util.List.of("available", "sandbox", "rcs", "source_kinds", "subagents", "profiles", "protocol_version"))
                    if (capabilities.has(name)) value.set(name, capabilities.get(name));
            }
        } catch (Exception error) { if (error instanceof InterruptedException) Thread.currentThread().interrupt(); }
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    @GetMapping("/research/{kind}")
    public Object list(@PathVariable String kind, @RequestParam(required=false) UUID topicId) {
        requireEnabled();
        return ResponseFactory.success(research.list(kind, topicId), RequestIdHolder.currentOrUnknown());
    }

    @GetMapping("/research/{kind}/{id}")
    public Object get(@PathVariable String kind, @PathVariable UUID id) {
        requireEnabled();
        return ResponseFactory.success(research.get(kind, id), RequestIdHolder.currentOrUnknown());
    }

    @PostMapping("/research/{kind}")
    public Object create(@PathVariable String kind, @RequestBody ObjectNode body, @RequestHeader("Idempotency-Key") String key) {
        requireEnabled();
        if (!java.util.Set.of("topic", "annotation", "bibliography").contains(kind)) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        return ResponseFactory.success(research.create(kind, body, key), RequestIdHolder.currentOrUnknown());
    }

    @PutMapping("/research/topic/{id}")
    public Object update(@PathVariable UUID id, @RequestBody ObjectNode body) {
        requireEnabled();
        return ResponseFactory.success(research.updateTopic(id, body, body.path("revision").asLong()), RequestIdHolder.currentOrUnknown());
    }

    @PostMapping("/research/card/{id}/verify")
    public Object verify(@PathVariable UUID id, @RequestBody ObjectNode body) {
        requireEnabled();
        return ResponseFactory.success(research.verify(id, body.path("revision").asLong(), body.path("name").asText(), body.path("status").asText()), RequestIdHolder.currentOrUnknown());
    }

    @RequestMapping(value={"/packs", "/runs", "/runs/{id}", "/runs/{id}/{operation}", "/conversations", "/conversations/{id}", "/conversations/{id}/messages", "/conversations/{id}/queue"},
        method={RequestMethod.GET, RequestMethod.POST})
    public Object proxy(HttpServletRequest request, @RequestBody(required=false) JsonNode body) throws Exception {
        requireEnabled();
        var suffix = request.getRequestURI().substring("/api/v1/agents".length());
        if (!suffix.equals("/packs") && !suffix.matches("/(runs|conversations)(/[0-9a-f-]{36})?(/(messages|queue|resume|cancel|workspace))?"))
            throw new ApiException(ApiErrorCode.BAD_REQUEST);
        if ("/runs".equals(suffix) && "POST".equals(request.getMethod()) && body != null && !body.hasNonNull("topic_id") && !body.hasNonNull("profile")) {
            var task = body.path("request").asText().strip();
            var key = request.getHeader("Idempotency-Key");
            if (task.isBlank() || task.length() > 12000 || key == null || key.length() > 90 || body.hasNonNull("conversation_id"))
                throw new ApiException(ApiErrorCode.BAD_REQUEST);
            var topic = research.create("topic", mapper.createObjectNode().put("title", task.substring(0, Math.min(48, task.length())))
                .put("goal", task).put("scope_mode", "AUTOMATIC").set("documents", mapper.createArrayNode()), "task-topic:" + key);
            var runBody = (ObjectNode) body.deepCopy();
            runBody.put("topic_id", topic.path("id").asText());
            body = runBody;
        }
        var builder = HttpRequest.newBuilder(URI.create(controlUrl + "/internal/v1/harness" + suffix))
            .timeout(Duration.ofSeconds(30)).header("X-Jsd-Agent-Ticket", delegation.issue(ActorContext.required()));
        var key = request.getHeader("Idempotency-Key");
        if (key != null) builder.header("Idempotency-Key", key);
        builder.header("Content-Type", "application/json").method(request.getMethod(), body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) return ResponseEntity.status(response.statusCode()).body(mapper.readTree(response.body()));
        return ResponseFactory.success(mapper.readTree(response.body()), RequestIdHolder.currentOrUnknown());
    }

    @GetMapping(value="/runs/{id}/events", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id, @RequestHeader(value="Last-Event-ID",defaultValue="0") String cursor) {
        requireEnabled();
        var ticket = delegation.issue(ActorContext.required());
        if (!cursor.matches("[0-9]{1,19}")) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var emitter = new SseEmitter(0L);
        var upstream = new java.util.concurrent.atomic.AtomicReference<InputStream>();
        Runnable close = () -> { try { var stream = upstream.get(); if (stream != null) stream.close(); } catch (IOException ignored) { } };
        emitter.onCompletion(close); emitter.onTimeout(close); emitter.onError(error -> close.run());
        Thread.ofVirtual().start(() -> {
            try {
                var request = HttpRequest.newBuilder(URI.create(controlUrl + "/internal/v1/harness/runs/" + id + "/events"))
                    .header("X-Jsd-Agent-Ticket", ticket).header("Last-Event-ID", cursor).GET().build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                upstream.set(response.body());
                if (response.statusCode() != 200) throw new IOException("Agent event stream unavailable");
                try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line, seq = null, event = null; var data = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("id: ")) seq = line.substring(4);
                        else if (line.startsWith("event: ")) event = line.substring(7);
                        else if (line.startsWith("data: ")) data.append(line.substring(6));
                        else if (line.isEmpty() && !data.isEmpty()) {
                            emitter.send(SseEmitter.event().id(seq).name(event).data(mapper.readTree(data.toString())));
                            seq = null; event = null; data.setLength(0);
                        }
                    }
                }
                emitter.complete();
            } catch (Exception error) { emitter.completeWithError(error); }
            finally { close.run(); }
        });
        return emitter;
    }

    @GetMapping("/research/report/{id}/word")
    public ResponseEntity<byte[]> word(@PathVariable UUID id) throws Exception {
        requireEnabled();
        var report = research.get("report", id);
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(report.path("title").asText("研究报告"));
            document.createParagraph().createRun().setText("候选研究报告：AI 抽取内容待核对；引用固定到生成时的来源版本。");
            for (var line : report.path("text").asText().split("\\R")) document.createParagraph().createRun().setText(line);
            document.createParagraph().createRun().setText("参考来源与证据");
            report.path("evidence").fields().forEachRemaining(entry -> {
                var source = entry.getValue().path("source");
                document.createParagraph().createRun().setText("[E:" + entry.getKey() + "] " + source.path("title").asText() + "；发布版本 " + source.path("publication_id").asText() + "；位置 " + source.path("anchor"));
            });
            document.write(output);
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=research-report.docx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).body(output.toByteArray());
        }
    }

    @GetMapping("/research/artifact/{id}/content")
    public ResponseEntity<byte[]> artifact(@PathVariable UUID id) throws Exception {
        requireEnabled();
        try (var file = artifacts.open(id)) {
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                org.springframework.http.ContentDisposition.attachment().filename(file.originalName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").contentType(MediaType.APPLICATION_OCTET_STREAM).body(file.stream().readNBytes(10 * 1024 * 1024 + 1));
        }
    }

    @GetMapping("/research/topic/{id}/comparison")
    public ResponseEntity<byte[]> comparison(@PathVariable UUID id) throws Exception {
        requireEnabled();
        var cards = research.list("card", id);
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("证据比较");
            var header = sheet.createRow(0); var columns = new String[]{"研究实例", "目标", "字段", "原文值", "标准值", "单位", "限定条件", "核对状态", "来源版本", "原文位置"};
            for (int c = 0; c < columns.length; c++) header.createCell(c).setCellValue(columns[c]);
            int rowNo = 1;
            for (var card : cards) for (var field : card.path("fields")) {
                var row = sheet.createRow(rowNo++);
                var values = new String[]{card.path("instance").asText(), card.path("target").asText(), field.path("name").asText(), field.path("original").asText(), field.path("value").asText(), field.path("unit").asText(), field.path("qualifiers").toString(), field.path("status").asText(), field.path("source").path("publication_id").asText(), field.path("source").path("anchor").toString()};
                for (int c = 0; c < values.length; c++) row.createCell(c).setCellValue(values[c]);
            }
            sheet.createFreezePane(2, 1);
            var names = new java.util.LinkedHashSet<String>();
            for (var card : cards) for (var field : card.path("fields")) names.add(field.path("name").asText());
            var metrics = java.util.List.copyOf(names);
            var matrix = workbook.createSheet("实例横向比较");
            var matrixHeader = matrix.createRow(0);
            matrixHeader.createCell(0).setCellValue("具体研究实例"); matrixHeader.createCell(1).setCellValue("目标");
            for (int index = 0; index < metrics.size(); index++) matrixHeader.createCell(index + 2).setCellValue(metrics.get(index));
            int matrixRow = 1;
            for (var card : cards) {
                var row = matrix.createRow(matrixRow++);
                row.createCell(0).setCellValue(card.path("instance").asText()); row.createCell(1).setCellValue(card.path("target").asText());
                for (int index = 0; index < metrics.size(); index++) {
                    String text = "未提取";
                    for (var field : card.path("fields")) if (metrics.get(index).equals(field.path("name").asText())) {
                        text = field.path("value").asText(field.path("original").asText("未报告")) + " " + field.path("unit").asText()
                            + "；" + field.path("status").asText("AI_EXTRACTED") + "；限定条件 " + field.path("qualifiers")
                            + "；来源发布 " + field.path("source").path("publication_id").asText() + "；位置 " + field.path("source").path("anchor");
                    }
                    row.createCell(index + 2).setCellValue(text.length() > 32760 ? text.substring(0, 32740) + "（过长，请查看在线证据）" : text);
                }
            }
            matrix.createFreezePane(2, 1); workbook.write(output);
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=evidence-comparison.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(output.toByteArray());
        }
    }

    private void requireEnabled() {
        research.requireUse();
        if (!enabled) throw new ApiException(ApiErrorCode.AI_NOT_CONFIGURED, "Agent 工作区尚未启用");
    }
}
