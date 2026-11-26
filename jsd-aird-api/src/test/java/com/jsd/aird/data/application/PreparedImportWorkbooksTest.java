package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.*;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class PreparedImportWorkbooksTest {
    final ObjectMapper json=new ObjectMapper();
    final UUID org=UUID.randomUUID(), file=UUID.randomUUID(), template=UUID.randomUUID();
    DataRepository.Job job(String parser) {
        return new DataRepository.Job(UUID.randomUUID(),file,"hash","source.xlsx","XLSX",template,
                "WAITING_CONFIRM",100,"READY",parser,null,Instant.EPOCH,Instant.EPOCH);
    }
    static class Storage implements ObjectStorage {
        final Map<String,byte[]> values=new ConcurrentHashMap<>();
        public void put(String key,InputStream source,long size,String type) { try{values.put(key,source.readAllBytes());}catch(IOException e){throw new RuntimeException(e);} }
        public StoredObject get(String key) { var bytes=values.get(key);if(bytes==null)throw new IllegalArgumentException("missing");return new StoredObject(new ByteArrayInputStream(bytes),bytes.length,"application/gzip"); }
        public void delete(String key){values.remove(key);}
    }
    JsonNode snapshot() { return json.createObjectNode().set("sheets",json.createObjectNode().set("s",json.createObjectNode().put("name","完整工作表").put("formula","=Other!A1"))); }
    @Test void reusesImmutableArtifactAndIsolatesCallerCorrectionsAndIdentities() {
        var storage=new Storage();var cache=new PreparedImportWorkbooks(storage,json);var job=job("v1");var count=new AtomicInteger();
        var first=cache.getOrBuild(org,job,()->{count.incrementAndGet();return snapshot();});
        ((com.fasterxml.jackson.databind.node.ObjectNode)first.path("sheets").path("s")).put("formula","changed");
        assertThat(cache.getOrBuild(org,job,()->{throw new AssertionError("reparsed");}).at("/sheets/s/formula").asText()).isEqualTo("=Other!A1");
        assertThat(count).hasValue(1);
        assertThat(cache.key(org,job,"v1")).isNotEqualTo(cache.key(org,job,"v2")).isNotEqualTo(cache.key(UUID.randomUUID(),job,"v1"));
    }
    @Test void mergesConcurrentBuildsAndDoesNotCacheFailures() throws Exception {
        var cache=new PreparedImportWorkbooks(new Storage(),json);var job=job("v1");var builds=new AtomicInteger();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->cache.getOrBuild(org,job,()->{builds.incrementAndGet();entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}return snapshot();}));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var second=executor.submit(()->cache.getOrBuild(org,job,()->{builds.incrementAndGet();return snapshot();}));
            release.countDown();assertThat(first.get(5,TimeUnit.SECONDS)).isEqualTo(second.get(5,TimeUnit.SECONDS));
            assertThat(builds).hasValue(1);
        }
        var failed=job("broken");
        assertThatThrownBy(()->cache.getOrBuild(org,failed,()->{throw new IllegalStateException("failed");})).hasMessage("failed");
        assertThat(cache.getOrBuild(org,failed,this::snapshot).path("sheets").size()).isEqualTo(1);
        assertThatThrownBy(()->cache.getOrBuild(org,job("empty"),json::createObjectNode)).isInstanceOf(com.jsd.aird.shared.error.ApiException.class);
    }

    @Test void expiredWaiterDoesNotCancelOrReplaceTheSharedBuild() throws Exception {
        var cache=new PreparedImportWorkbooks(new Storage(),json);var job=job("bounded");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var owner=executor.submit(()->cache.getOrBuild(org,job,()->{
                entered.countDown();
                try { release.await(); } catch(InterruptedException e) { throw new RuntimeException(e); }
                return snapshot();
            }));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                try(var budget=com.jsd.aird.shared.resource.OperationBudget.open(
                        com.jsd.aird.shared.resource.OperationBudget.Kind.HEAVY,0)) {
                    assertThatThrownBy(()->cache.getOrBuild(org,job,()->{throw new AssertionError("duplicate build");}))
                            .isInstanceOf(com.jsd.aird.shared.error.ApiException.class);
                }
                assertThat(owner.isDone()).isFalse();
            } finally { release.countDown(); }
            assertThat(owner.get(5,TimeUnit.SECONDS)).isEqualTo(snapshot());
            assertThat(cache.getOrBuild(org,job,()->{throw new AssertionError("lost artifact");})).isEqualTo(snapshot());
        }
    }
}
