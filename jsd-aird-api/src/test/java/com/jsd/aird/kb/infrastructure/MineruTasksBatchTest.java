package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.jsd.aird.kb.api.DocumentParser.ParseContext;
import com.jsd.aird.kb.api.OcrMode;
import com.jsd.aird.kb.application.port.MineruTaskStore;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MineruTasksBatchTest {
    private final ObjectMapper json=new ObjectMapper();
    static class Store implements MineruTaskStore {
        final Map<String,Task> rows=new ConcurrentHashMap<>();
        public Optional<Task> find(UUID org,UUID version,String key) { return Optional.ofNullable(rows.get(org+":"+version+":"+key)); }
        public void save(UUID org,UUID version,String key,Task task) { rows.put(org+":"+version+":"+key,task); }
        public Optional<Task> latest(UUID org,UUID version) { return rows.entrySet().stream().filter(e -> e.getKey().startsWith(org+":"+version+":")).map(Map.Entry::getValue).findFirst(); }
    }
    @Test void sendsEightFilesInOneRequestAndRetriesOnlyTheFailedFileAfterRestart() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var submits=new AtomicInteger(); var uploadCount=new AtomicInteger();
        var uploaded=ConcurrentHashMap.<String>newKeySet();
        var tasks=new ConcurrentHashMap<String,JsonNode>();
        var requestedSizes=new CopyOnWriteArrayList<Integer>();
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/", exchange -> {
            try {
                String path=exchange.getRequestURI().getPath();
                if (path.equals("/v1/tasks") && exchange.getRequestMethod().equals("POST")) {
                    var body=json.readTree(exchange.getRequestBody());
                    assertThat(body.path("tier").asText()).isEqualTo("advanced");
                    assertThat(body.path("ocr_mode").asText()).isEqualTo("ocr");
                    int number=submits.incrementAndGet(); requestedSizes.add(body.path("files").size());
                    var task=json.createObjectNode().put("task_id","job_"+number).put("status","waiting-file");
                    var files=task.putArray("files"); int index=0;
                    for (var file:body.path("files")) {
                        String id="file_"+number+"_"+index++;
                        var item=files.addObject().put("file_id",id).put("file_name",file.path("file_name").asText()).put("status","waiting-file").put("bytes",0);
                        item.putObject("upload").put("url",base+"/upload/"+id).putObject("headers").put("X-Upload","required");
                    }
                    // Reverse to prove mapping uses per-file identity instead of response order.
                    var reversed=json.createArrayNode(); for (int i=files.size()-1;i>=0;i--) reversed.add(files.get(i)); task.set("files",reversed);
                    tasks.put("job_"+number,task);
                    respond(exchange,200,json.writeValueAsBytes(task));
                } else if (path.startsWith("/upload/")) {
                    assertThat(exchange.getRequestHeaders().getFirst("X-Upload")).isEqualTo("required");
                    exchange.getRequestBody().readAllBytes();
                    uploaded.add(path.substring("/upload/".length())); uploadCount.incrementAndGet(); respond(exchange,200,new byte[0]);
                } else if (path.startsWith("/v1/tasks/")) {
                    var task=tasks.get(path.substring("/v1/tasks/".length())).deepCopy();
                    for (var item:task.path("files")) {
                        String id=item.path("file_id").asText();
                        if (uploaded.contains(id)) {
                            var node=(com.fasterxml.jackson.databind.node.ObjectNode)item;
                            node.put("bytes", 3);
                            if (id.equals("file_1_7")) node.put("status","failed").putObject("error").put("code","BAD_FILE").put("message","one failed");
                            else node.put("status","completed").put("result_url",base+"/result/"+id).putObject("parse").put("tier","advanced");
                        }
                    }
                    respond(exchange,200,json.writeValueAsBytes(task));
                } else if (path.startsWith("/result/")) respond(exchange,200,new byte[]{1,2,3});
                else respond(exchange,404,new byte[0]);
            } finally { exchange.close(); }
        });
        server.start(); var store=new Store(); var org=UUID.randomUUID();
        Path source=Files.createTempFile("batch-test", ".pdf"); Files.writeString(source,"PDF");
        var requests=new ArrayList<MineruBatcher.Request>();
        for(int i=0;i<8;i++) requests.add(new MineruBatcher.Request(source,"same.pdf",
                new ParseContext(org,UUID.randomUUID(),UUID.randomUUID(),"application/pdf",3,OcrMode.ON,false,UUID.randomUUID(),UUID.randomUUID()),
                MineruTasksClient.fingerprint("file-"+i),"advanced",OcrMode.ON,false));
        int failed=-1;
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            try(var batcher=new MineruBatcher(8,2,Duration.ofSeconds(30),Duration.ofSeconds(30),1024,
                    new MineruTasksClient(base,json,Duration.ofMillis(1),Duration.ofSeconds(20),Duration.ofSeconds(10),store)::parseBatch)) {
                var futures=requests.stream().map(r -> executor.submit(() -> batcher.submit(r))).toList();
                for(int i=0;i<futures.size();i++) {
                    try { var result=futures.get(i).get(40,TimeUnit.SECONDS); assertThat(result.taskId()).isEqualTo("job_1"); Files.delete(result.zip()); }
                    catch(ExecutionException error) { assertThat(error.getCause()).isInstanceOf(MineruException.class); assertThat(failed).isEqualTo(-1); failed=i; }
                }
            }
            assertThat(failed).isNotEqualTo(-1);
            assertThat(requestedSizes).containsExactly(8); assertThat(uploadCount).hasValue(8);
            // New client and coordinator simulate a Worker restart. Completed siblings are not resubmitted.
            try(var batcher=new MineruBatcher(8,2,Duration.ofMillis(5),Duration.ofSeconds(60),1024,
                    new MineruTasksClient(base,json,Duration.ofMillis(1),Duration.ofSeconds(20),Duration.ofSeconds(10),store)::parseBatch)) {
                var retried=batcher.submit(requests.get(failed)); Files.delete(retried.zip());
                int succeeded=failed==0?1:0;
                var resumed=batcher.submit(requests.get(succeeded)); Files.delete(resumed.zip());
            }
            assertThat(requestedSizes).containsExactly(8,1); assertThat(uploadCount).hasValue(9);
        } finally { server.stop(0); Files.deleteIfExists(source); }
    }
    @Test void ambiguousCreateIsNotAutomaticallyResubmitted() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var posts=new AtomicInteger();
        server.createContext("/v1/tasks", exchange -> { posts.incrementAndGet(); respond(exchange,503,"unavailable".getBytes()); exchange.close(); }); server.start();
        var store=new Store(); var source=Files.createTempFile("batch-unknown", ".pdf");
        var request=new MineruBatcher.Request(source,"a.pdf",new ParseContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"application/pdf",0,OcrMode.ON,false,UUID.randomUUID(),UUID.randomUUID()),
                MineruTasksClient.fingerprint("a"),"advanced",OcrMode.ON,false);
        try(var batcher=new MineruBatcher(8,2,Duration.ofMillis(5),Duration.ofSeconds(60),1024,
                new MineruTasksClient("http://127.0.0.1:"+server.getAddress().getPort(),json,Duration.ofMillis(1),Duration.ofSeconds(20),Duration.ofSeconds(10),store)::parseBatch)) {
            assertThatThrownBy(() -> batcher.submit(request)).isInstanceOf(MineruException.class);
            assertThatThrownBy(() -> batcher.submit(request)).hasMessageContaining("不明确");
            assertThat(posts).hasValue(1);
        } finally { server.stop(0); Files.deleteIfExists(source); }
    }
    @Test void retainsKnownTaskWhenCreateResponseOmitsFileDescriptors() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var posts=new AtomicInteger();
        var wireName=new java.util.concurrent.atomic.AtomicReference<String>();
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/", exchange -> {
            try {
                if (exchange.getRequestMethod().equals("POST")) {
                    posts.incrementAndGet();
                    wireName.set(json.readTree(exchange.getRequestBody()).path("files").get(0).path("file_name").asText());
                    respond(exchange,200,"{\"task_id\":\"job_recover\",\"files\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else if (exchange.getRequestURI().getPath().equals("/v1/tasks/job_recover")) {
                    var task=json.createObjectNode().put("task_id","job_recover");
                    task.putArray("files").addObject().put("file_id","file_recover").put("file_name",wireName.get())
                            .put("status","completed").put("result_url",base+"/result");
                    respond(exchange,200,json.writeValueAsBytes(task));
                } else respond(exchange,200,new byte[]{1,2,3});
            } finally { exchange.close(); }
        });
        server.start();
        var source=Files.createTempFile("batch-descriptor", ".pdf");
        var store=new Store();
        var request=new MineruBatcher.Request(source,"a.pdf",new ParseContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                "application/pdf",0,OcrMode.ON,false,UUID.randomUUID(),UUID.randomUUID()),"descriptor","advanced",OcrMode.ON,false);
        try (var batcher=new MineruBatcher(8,2,Duration.ofMillis(5),Duration.ofSeconds(60),1024,
                new MineruTasksClient(base,json,Duration.ofMillis(1),Duration.ofSeconds(20),Duration.ofSeconds(10),store)::parseBatch)) {
            assertThatThrownBy(() -> batcher.submit(request)).hasMessageContaining("文件数量不匹配");
            var result=batcher.submit(request);
            assertThat(result.taskId()).isEqualTo("job_recover");
            Files.deleteIfExists(result.zip());
            assertThat(posts).hasValue(1);
        } finally { server.stop(0); Files.deleteIfExists(source); }
    }
    @Test void resumesUploadedPartitionDespiteRegeneratedPdfByteLength() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var source = Files.createTempFile("batch-partition", ".pdf");
        Files.writeString(source, "regenerated PDF bytes");
        var context = new ParseContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "application/pdf", 20, OcrMode.ON, false, UUID.randomUUID(), UUID.randomUUID());
        var input = new MineruBatcher.Item(new MineruBatcher.Request(source, "book.part-001.pdf",
                context, "stable-source-and-page-range", "advanced", OcrMode.ON, false), Files.size(source));
        var store = new Store();
        store.save(context.organizationId(), context.documentVersionId(), input.request.key(),
                new MineruTaskStore.Task("job_saved", null, true, "pending", "self-hosted",
                        MineruTasksClient.fingerprint("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                        "file_saved", Map.of()));
        server.createContext("/", exchange -> {
            try {
                if (exchange.getRequestURI().getPath().equals("/result")) respond(exchange, 200, new byte[]{1});
                else if (exchange.getRequestMethod().equals("GET")) {
                    var task = json.createObjectNode().put("task_id", "job_saved").put("status", "completed");
                    task.putArray("files").addObject().put("file_id", "file_saved")
                            .put("file_name", MineruTasksClient.wireName(input)).put("bytes", 10)
                            .put("status", "completed").put("result_url", "/result");
                    respond(exchange, 200, json.writeValueAsBytes(task));
                } else respond(exchange, 400, new byte[0]);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new MineruTasksClient("http://127.0.0.1:" + server.getAddress().getPort(), json,
                    Duration.ofMillis(1), Duration.ofSeconds(3), Duration.ofSeconds(2), store);
            client.parseBatch(List.of(input));
            Files.deleteIfExists(input.future.get(3, TimeUnit.SECONDS).zip());
        } finally { server.stop(0); Files.deleteIfExists(source); }
    }

    @Test void fullQueueWaitsForCapacityInsteadOfRejectingDocument() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var source = Files.createTempFile("batch-backpressure", ".pdf");
        Files.writeString(source, "PDF");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var batcher = new MineruBatcher(1, 1, Duration.ofMillis(1), Duration.ofSeconds(5), 1024, batch -> {
                 entered.countDown();
                 try { release.await(); }
                 catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                 batch.forEach(item -> item.success(new MineruTasksClient.Downloaded(source, "job", Map.of())));
             })) {
            var request = new MineruBatcher.Request(source, "file.pdf", null, "key", "advanced", OcrMode.ON, false);
            var first = executor.submit(() -> batcher.submit(request));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> batcher.submit(request));
            var third = executor.submit(() -> batcher.submit(request));
            assertThatThrownBy(() -> third.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS).taskId()).isEqualTo("job");
            assertThat(second.get(3, TimeUnit.SECONDS).taskId()).isEqualTo("job");
            assertThat(third.get(3, TimeUnit.SECONDS).taskId()).isEqualTo("job");
        } finally { release.countDown(); Files.deleteIfExists(source); }
    }

    @Test void duplicateVersionRequestsShareOneRemoteFileAndOwnSeparateResults() throws Exception {
        exerciseRecovery(false);
    }

    @Test void definitivelyExpiredUploadIsReplacedWithoutPollingItForever() throws Exception {
        exerciseRecovery(true);
    }

    private void exerciseRecovery(boolean expired) throws Exception {
        var source = Files.createTempFile("mineru-recovery", ".pdf");
        Files.writeString(source, "PDF");
        var context = new ParseContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "application/pdf", 3, OcrMode.ON, false, UUID.randomUUID(), UUID.randomUUID());
        var request = new MineruBatcher.Request(source, "same.pdf", context, "stable", "advanced", OcrMode.ON, false);
        var first = new MineruBatcher.Item(request, 3);
        var second = new MineruBatcher.Item(request, 3);
        var store = new Store();
        var posts = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        if (expired) store.save(context.organizationId(), context.documentVersionId(), "stable",
                new MineruTaskStore.Task("job_old", null, false, "pending", "self-hosted",
                        MineruTasksClient.fingerprint(base + "/"), "file_old", Map.of()));
        server.createContext("/", exchange -> {
            try {
                if (exchange.getRequestURI().getPath().equals("/result")) {
                    respond(exchange, 200, new byte[]{1, 2, 3}); return;
                }
                if (exchange.getRequestMethod().equals("POST")) {
                    posts.incrementAndGet();
                    assertThat(json.readTree(exchange.getRequestBody()).path("files").size()).isEqualTo(1);
                }
                boolean old = exchange.getRequestURI().getPath().equals("/v1/tasks/job_old");
                var task = json.createObjectNode().put("task_id", old ? "job_old" : "job_new");
                var file = task.putArray("files").addObject().put("file_id", old ? "file_old" : "file_new")
                        .put("file_name", MineruTasksClient.wireName(first)).put("bytes", 3)
                        .put("status", old ? "failed" : "completed");
                if (old) file.putObject("error").put("code", "upload_expired").put("message", "Upload expired");
                else file.put("result_url", base + "/result");
                respond(exchange, 200, json.writeValueAsBytes(task));
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new MineruTasksClient(base, json, Duration.ofMillis(1), Duration.ofSeconds(20),
                    Duration.ofSeconds(15), store);
            client.parseBatch(expired ? List.of(first) : List.of(first, second));
            var result = first.future.get(30, TimeUnit.SECONDS);
            assertThat(result.taskId()).isEqualTo("job_new");
            assertThat(Files.readAllBytes(result.zip())).containsExactly((byte)1, (byte)2, (byte)3);
            Files.delete(result.zip());
            if (!expired) {
                var other = second.future.get(30, TimeUnit.SECONDS);
                assertThat(other.zip()).isNotEqualTo(result.zip());
                assertThat(Files.readAllBytes(other.zip())).hasSize(3);
                Files.delete(other.zip());
            }
            assertThat(posts).hasValue(1);
        } finally { server.stop(0); Files.deleteIfExists(source); }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,int status,byte[] bytes) throws java.io.IOException {
        exchange.sendResponseHeaders(status,bytes.length==0 ? -1 : bytes.length);
        if(bytes.length>0)exchange.getResponseBody().write(bytes);
    }
}
