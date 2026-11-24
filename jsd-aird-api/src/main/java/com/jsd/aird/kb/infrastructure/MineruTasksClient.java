package com.jsd.aird.kb.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.DocumentParser.ParseContext;
import com.jsd.aird.kb.api.OcrMode;
import com.jsd.aird.kb.application.port.MineruTaskStore;
import com.jsd.aird.kb.application.port.MineruTaskStore.Task;
import com.jsd.aird.shared.resource.BoundedStreams;
import com.jsd.aird.shared.resource.OperationBudget;

/** The self-hosted Tasks protocol. No cloud credentials or cloud fallback are used here. */
final class MineruTasksClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MineruTasksClient.class);
    private final URI base;
    private final String endpoint;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration poll, wait, timeout;
    private final MineruTaskStore store;

    MineruTasksClient(String baseUrl, ObjectMapper mapper, Duration poll, Duration wait, Duration timeout,
                      MineruTaskStore store) {
        base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("Invalid self-hosted MinerU URL");
        }
        endpoint = fingerprint(base.toString());
        this.mapper = mapper;
        this.poll = poll;
        this.wait = wait;
        this.timeout = timeout;
        this.store = store;
        http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    void parseBatch(java.util.List<MineruBatcher.Item> inputs) {
        var fresh = new java.util.ArrayList<MineruBatcher.Item>();
        var resumed = new LinkedHashMap<String, java.util.List<MineruBatcher.Item>>();
        var saved = new java.util.HashMap<MineruBatcher.Item, Task>();
        var snapshots = new java.util.HashMap<String, JsonNode>();
        for (var input : inputs) {
            try {
                Task checkpoint = find(input);
                if (checkpoint != null && Set.of("submitting", "submission-unknown").contains(checkpoint.state()))
                    throw failure("上次创建任务的结果不明确，已停止重复提交，请核对 MinerU 任务", "SUBMISSION_UNKNOWN", checkpoint.batchId(), false, null);
                if (checkpoint == null || Set.of("failed", "error", "canceled").contains(checkpoint.state())) { fresh.add(input); continue; }
                JsonNode snapshot = snapshots.get(checkpoint.batchId());
                if (snapshot == null) {
                    try { snapshot = json("GET", "v1/tasks/" + identifier(checkpoint.batchId()), null, checkpoint.batchId()); }
                    catch (MineruException error) {
                        if (error.httpStatus() != null && (error.httpStatus() == 404 || error.httpStatus() == 410)) { fresh.add(input); continue; }
                        throw error;
                    }
                    snapshots.put(checkpoint.batchId(), snapshot);
                }
                saved.put(input, checkpoint);
                resumed.computeIfAbsent(checkpoint.batchId(), ignored -> new java.util.ArrayList<>()).add(input);
            } catch (RuntimeException error) { input.fail(error); }
        }
        // Resume old batches independently. Only positively expired/failed files enter a new request.
        for (var group : resumed.entrySet()) {
            try { pollBatch(group.getKey(), snapshots.get(group.getKey()), group.getValue(), saved); }
            catch (RuntimeException error) { group.getValue().forEach(i -> i.fail(error)); }
        }
        if (fresh.isEmpty()) return;
        String taskId = null;
        try {
            var request = new LinkedHashMap<String, Object>();
            var files = new java.util.ArrayList<Map<String, Object>>();
            for (var input : fresh) files.add(Map.of("file_name", wireName(input), "bytes", Files.size(input.request.file())));
            request.put("files", files);
            var first = fresh.getFirst().request;
            if (!first.nativeDocument()) { request.put("tier", first.tier()); request.put("ocr_mode", wireMode(first.mode())); }
            request.put("output_formats", java.util.List.of("default"));
            // Persist every file before the non-idempotent POST. A lost response must not duplicate the batch.
            for (var input : fresh) save(input, new Task("", null, false, "submitting", "self-hosted", endpoint, null, Map.of()));
            JsonNode task;
            try { task = json("POST", "v1/tasks", request, null); }
            catch (MineruException error) {
                String state = error.httpStatus() != null && error.httpStatus() >= 400 && error.httpStatus() < 500 ? "failed" : "submission-unknown";
                for (var input : fresh) save(input, new Task("", null, false, state, "self-hosted", endpoint, null, Map.of()));
                throw error;
            }
            taskId = task.path("task_id").asText();
            identifier(taskId);
            // Once the server identifies a task, retain it even if an individual
            // upload descriptor is malformed. GET can recover its full file list.
            for (var input : fresh) save(input, new Task(taskId, null, false, "pending", "self-hosted", endpoint, null, Map.of()));
            log.info("MinerU self-hosted batch submitted: taskId={} files={} tier={} ocr={}", taskId, fresh.size(), first.tier(), first.mode());
            if (!task.path("files").isArray() || task.path("files").size() != fresh.size())
                throw MineruException.contract("MinerU 批次文件数量不匹配", taskId);
            for (var input : fresh) {
                var item = file(task, input, null);
                var checkpoint = descriptor(item, taskId, false);
                saved.put(input, checkpoint); save(input, checkpoint);
            }
            pollBatch(taskId, task, fresh, saved);
        } catch (IOException error) {
            var failure = failure("读取批量文件失败", "FILE_IO", taskId, false, error);
            fresh.forEach(i -> i.fail(failure));
        } catch (RuntimeException error) { fresh.forEach(i -> i.fail(error)); }
    }

    private void pollBatch(String id, JsonNode task, java.util.List<MineruBatcher.Item> inputs,
                           Map<MineruBatcher.Item, Task> saved) {
        var remaining = new java.util.ArrayList<>(inputs);
        long deadline = System.nanoTime() + wait.toNanos();
        while (!remaining.isEmpty()) {
            OperationBudget.check();
            if (!id.equals(task.path("task_id").asText())) throw MineruException.contract("MinerU 任务标识发生变化", id);
            for (var iterator = remaining.iterator(); iterator.hasNext();) {
                var input = iterator.next();
                try {
                    var checkpoint = saved.get(input);
                    var item = file(task, input, checkpoint == null ? null : checkpoint.remoteFileId());
                    if (item.has("bytes") && item.path("bytes").asLong(-1) != input.bytes)
                        throw MineruException.contract("MinerU 远程文件大小与提交不匹配", id);
                    String status = item.path("status").asText();
                    if (status.equals("completed")) {
                        save(input, descriptor(item, id, true));
                        var metadata = new LinkedHashMap<String, Object>();
                        metadata.put("provider", "self-hosted"); metadata.put("endpointFingerprint", endpoint);
                        metadata.put("tier", item.path("parse").path("tier").asText(input.request.nativeDocument() ? "flash" : input.request.tier()));
                        metadata.put("requestedOcrMode", input.request.mode().name());
                        metadata.put("ocrMode", input.request.nativeDocument() ? "native" : wireMode(input.request.mode()));
                        metadata.put("providerParserVersion", item.path("parse").path("parser_version").asText());
                        metadata.put("providerDurationMs", item.path("parse").path("duration_ms").asLong());
                        metadata.put("remoteFileId", item.path("file_id").asText());
                        metadata.put("batchFileCount", task.path("files").size());
                        Path zip = Files.createTempFile("mineru-tasks-", ".zip");
                        try { download(item.path("result_url").asText(), zip, id); input.success(new Downloaded(zip, id, metadata)); }
                        catch (Throwable error) { Files.deleteIfExists(zip); throw error; }
                        iterator.remove();
                    } else if (Set.of("failed", "canceled", "error").contains(status)
                            || Set.of("failed", "canceled", "error").contains(task.path("status").asText())) {
                        save(input, descriptor(item, id, true));
                        var error = item.hasNonNull("error") ? item.get("error") : task.path("error");
                        throw failure("MinerU 文件解析失败：" + error.path("message").asText(error.toString()), error.path("code").asText("PARSE_FAILED"), id, false, null);
                    } else if (status.equals("waiting-file") && (checkpoint == null || !checkpoint.uploaded())) {
                        var pending = descriptor(item, id, false); save(input, pending);
                        upload(pending, input.request.file());
                        var uploaded = new Task(id, pending.uploadUrl(), true, "pending", "self-hosted", endpoint, pending.remoteFileId(), pending.uploadHeaders());
                        saved.put(input, uploaded); save(input, uploaded);
                    }
                } catch (Exception error) { input.fail(error); iterator.remove(); }
            }
            if (remaining.isEmpty()) return;
            if (System.nanoTime() >= deadline) throw failure("MinerU 自部署任务等待超时", "TIMEOUT", id, true, null);
            pause(poll, id);
            task = json("GET", "v1/tasks/" + identifier(id), null, id);
        }
    }

    private JsonNode file(JsonNode task, MineruBatcher.Item input, String fileId) {
        JsonNode found = null;
        for (var item : task.path("files")) {
            if ((fileId == null || fileId.equals(item.path("file_id").asText())) && wireName(input).equals(item.path("file_name").asText())) {
                if (found != null) throw MineruException.contract("MinerU 返回了重复文件", task.path("task_id").asText());
                found = item;
            }
        }
        if (found == null) throw MineruException.contract("MinerU 批次未包含原文件", task.path("task_id").asText());
        return found;
    }
    static String wireName(MineruBatcher.Item input) {
        var context=input.request.context();
        String identity=input.request.key()+":"+(context==null ? "" : context.documentVersionId());
        String name=input.request.fileName().replace('\\','_').replace('/','_');
        if (name.length()>180) name=name.substring(0,140)+name.substring(name.length()-30);
        return fingerprint(identity).substring(0,16)+"-"+name;
    }
    private Task descriptor(JsonNode item, String id, boolean uploaded) {
        var headers = new LinkedHashMap<String, String>();
        item.path("upload").path("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
        String remote = item.path("file_id").asText(null);
        identifier(remote);
        return new Task(id, item.path("upload").path("url").asText(null), uploaded, item.path("status").asText(),
                "self-hosted", endpoint, remote, headers);
    }
    private Task find(MineruBatcher.Item input) {
        var context = input.request.context();
        if (store == null || context == null || context.documentVersionId() == null) return null;
        var saved = store.find(context.organizationId(), context.documentVersionId(), input.request.key()).orElse(null);
        return saved != null && "self-hosted".equals(saved.provider()) && endpoint.equals(saved.endpointFingerprint()) ? saved : null;
    }
    private void save(MineruBatcher.Item input, Task task) {
        var context = input.request.context();
        if (store != null && context != null && context.documentVersionId() != null)
            store.save(context.organizationId(), context.documentVersionId(), input.request.key(), task);
    }

    private JsonNode json(String method, String path, Object body, String taskId) {
        try {
            var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body));
            var request = HttpRequest.newBuilder(resolve(path)).timeout(timeout).header("Content-Type", "application/json").method(method, publisher).build();
            try (var stream = send(request, taskId, !method.equals("POST"))) {
                var raw = new java.io.ByteArrayOutputStream();
                BoundedStreams.copy(stream, raw, 4L * 1024 * 1024, null);
                return mapper.readTree(raw.toByteArray());
            }
        } catch (IOException e) { throw failure("MinerU 任务响应读取失败", "IO", taskId, true, e); }
    }

    private void upload(Task task, Path source) {
        try {
            var builder = HttpRequest.newBuilder(resolve(task.uploadUrl())).timeout(timeout).PUT(HttpRequest.BodyPublishers.ofFile(source));
            for (var h : task.uploadHeaders().entrySet()) {
                if (!Set.of("host", "content-length", "connection", "authorization", "cookie").contains(h.getKey().toLowerCase(java.util.Locale.ROOT))) builder.setHeader(h.getKey(), h.getValue());
            }
            builder.setHeader("Content-Type", "application/octet-stream");
            try (var response = send(builder.build(), task.batchId(), true)) { response.readNBytes(16384); }
        } catch (IOException e) { throw failure("MinerU 文件上传失败", "UPLOAD", task.batchId(), true, e); }
    }

    private void download(String url, Path destination, String id) throws IOException {
        var request = HttpRequest.newBuilder(resolve(url)).timeout(timeout).GET().build();
        try (var response = send(request, id, true); var out = Files.newOutputStream(destination)) {
            BoundedStreams.copy(response, out, MineruDocumentParser.MAX_DOWNLOAD_BYTES, null);
        }
    }

    private java.io.InputStream send(HttpRequest request, String id, boolean retry) {
        for (int attempt = 0; ; attempt++) {
            OperationBudget.check();
            try {
                var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 200 && status < 300) return response.body();
                String detail;
                try (var stream = response.body()) { detail = new String(stream.readNBytes(16384), StandardCharsets.UTF_8); }
                boolean transientFailure = status == 429 || status >= 500;
                if (retry && transientFailure && attempt < 2) { pause(Duration.ofMillis(250L << attempt), id); continue; }
                throw new MineruException("MinerU HTTP " + status + ": " + detail, status, "HTTP", id, transientFailure, false, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure("MinerU 请求已中断", "INTERRUPTED", id, true, e);
            } catch (IOException e) {
                if (retry && attempt < 2) { pause(Duration.ofMillis(250L << attempt), id); continue; }
                throw failure("MinerU 请求失败", "IO", id, true, e);
            }
        }
    }

    private URI resolve(String value) {
        if (value == null || value.isBlank()) throw MineruException.contract("MinerU 返回了空地址", null);
        var target = base.resolve(value);
        if (!base.getScheme().equalsIgnoreCase(target.getScheme()) || !base.getHost().equalsIgnoreCase(target.getHost())
                || port(base) != port(target) || target.getUserInfo() != null || target.getFragment() != null) {
            throw MineruException.contract("MinerU 自部署地址跨越了配置的服务来源", null);
        }
        return target;
    }

    private int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : uri.getScheme().equalsIgnoreCase("https") ? 443 : 80; }
    private String identifier(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw MineruException.contract("MinerU 任务 ID 无效", id);
        return id;
    }
    private void pause(Duration duration, String id) {
        try { Thread.sleep(Math.max(1, duration.toMillis())); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw failure("MinerU 等待已中断", "INTERRUPTED", id, true, e); }
    }
    static String wireMode(OcrMode mode) { return switch (mode) { case ON -> "ocr"; case OFF -> "txt"; case AUTO -> "auto"; }; }
    static String fingerprint(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private MineruException failure(String message, String code, String id, boolean retry, Throwable cause) {
        return new MineruException(message, null, code, id, retry, false, cause);
    }
    record Downloaded(Path zip, String taskId, Map<String, Object> metadata) { }
}
