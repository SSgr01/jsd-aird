package com.jsd.aird.kb.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.jsd.aird.kb.api.DocumentParser.ParseContext;
import com.jsd.aird.kb.api.OcrMode;

/** Coalesces independent Worker jobs into actual multi-file provider requests. */
final class MineruBatcher implements AutoCloseable {
    record Request(Path file, String fileName, ParseContext context, String key, String tier,
                   OcrMode mode, boolean nativeDocument, String resumeGroup) {
        Request(Path file, String fileName, ParseContext context, String key, String tier, OcrMode mode, boolean nativeDocument) {
            this(file,fileName,context,key,tier,mode,nativeDocument,null);
        }
    }
    static final class Item {
        final Request request;
        final CompletableFuture<MineruTasksClient.Downloaded> future = new CompletableFuture<>();
        final long bytes;
        final CompletableFuture<Void> dispatched = new CompletableFuture<>();
        boolean resumedPartition;
        Item(Request request, long bytes) { this.request = request; this.bytes = bytes; }
        void success(MineruTasksClient.Downloaded result) {
            if (!future.complete(result)) delete(result.zip());
        }
        void fail(Throwable error) { future.completeExceptionally(error); }
    }
    private final int size, capacity;
    private final long byteLimit, windowMillis;
    private final Duration deadline;
    private final Consumer<List<Item>> handler;
    private final LinkedHashMap<String, ArrayDeque<Item>> pending = new LinkedHashMap<>();
    private final ExecutorService workers;
    private final ScheduledExecutorService timer;
    private int active, outstanding;
    private final int concurrent;
    private boolean closed;

    MineruBatcher(int size, int concurrent, Duration window, Duration deadline, long byteLimit,
                  Consumer<List<Item>> handler) {
        if (size < 1 || size > 100 || concurrent < 1 || concurrent > 16 || window.isNegative() || byteLimit < 1)
            throw new IllegalArgumentException("Invalid MinerU batch limits");
        this.size = size; this.concurrent = concurrent; this.capacity = size * concurrent * 2;
        this.windowMillis = Math.max(1, window.toMillis()); this.deadline = deadline;
        this.byteLimit = byteLimit; this.handler = handler;
        workers = Executors.newFixedThreadPool(concurrent, Thread.ofVirtual().name("mineru-batch-", 0).factory());
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("mineru-batch-flush").factory());
        timer.scheduleWithFixedDelay(() -> dispatch(true), windowMillis, windowMillis, TimeUnit.MILLISECONDS);
    }

    MineruTasksClient.Downloaded submit(Request request) {
        Path owned = null;
        Item item = null;
        try {
            long bytes = Files.size(request.file());
            if (bytes > byteLimit) throw MineruException.contract("MinerU 单文件超过批次字节上限", null);
            synchronized (this) {
                long admissionDeadline = System.nanoTime() + deadline.toNanos();
                while (!closed && outstanding >= capacity) {
                    long left = admissionDeadline - System.nanoTime();
                    if (left <= 0) throw new MineruException("MinerU 批量队列等待超时", null, "BATCH_BUSY", null, true, false, null);
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                }
                if (closed) throw new IllegalStateException("MinerU batcher closed");
                outstanding++;
            }
            try {
                owned = Files.createTempFile("mineru-batch-source-", ".bin");
                Files.copy(request.file(), owned, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                item = new Item(new Request(owned, request.fileName(), request.context(), request.key(), request.tier(), request.mode(), request.nativeDocument(), request.resumeGroup()), bytes);
                String group = String.valueOf(request.context() == null ? null : request.context().organizationId())
                        + "|" + request.tier() + "|" + request.mode() + "|" + request.nativeDocument() + "|" + request.resumeGroup();
                synchronized (this) {
                    if (closed) throw new IllegalStateException("MinerU batcher closed");
                    pending.computeIfAbsent(group, ignored -> new ArrayDeque<>()).add(item);
                }
            } catch (Throwable error) {
                synchronized (this) { outstanding--; notifyAll(); }
                delete(owned);
                throw error;
            }
            dispatch(false);
            // Queue admission and remote execution have independent deadlines.
            item.dispatched.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            return item.future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            if (item != null) item.future.cancel(false);
            Thread.currentThread().interrupt();
            throw new MineruException("MinerU 批量等待被中断，远程任务检查点已保留", null, "INTERRUPTED", null, true, false, error);
        } catch (TimeoutException error) {
            if (item != null) item.future.cancel(false);
            throw new MineruException("MinerU 批量等待超时，远程任务检查点已保留", null, "TIMEOUT", null, true, false, error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(error.getCause());
        } catch (IOException error) { throw MineruException.adapter("MinerU 批量文件暂存失败", error); }
    }

    private synchronized void dispatch(boolean flush) {
        if (closed) return;
        var groups = pending.entrySet().iterator();
        while (active < concurrent && groups.hasNext()) {
            var queue = groups.next().getValue();
            if (!flush && queue.size() < size) continue;
            var batch = new ArrayList<Item>();
            long bytes = 0;
            while (!queue.isEmpty() && batch.size() < size) {
                Item next = queue.peek();
                if (!batch.isEmpty() && bytes + next.bytes > byteLimit) break;
                queue.remove();
                if (next.future.isCancelled()) { delete(next.request.file()); outstanding--; notifyAll(); continue; }
                batch.add(next); bytes += next.bytes;
            }
            if (queue.isEmpty()) groups.remove();
            if (batch.isEmpty()) continue;
            active++;
            workers.submit(() -> {
                batch.forEach(item -> item.dispatched.complete(null));
                try { handler.accept(List.copyOf(batch)); }
                catch (Throwable error) { batch.forEach(item -> item.fail(error)); }
                finally {
                    batch.forEach(item -> { item.fail(new IllegalStateException("MinerU 未返回单文件结果")); delete(item.request.file()); });
                    synchronized (this) { active--; outstanding -= batch.size(); notifyAll(); }
                    dispatch(true);
                }
            });
        }
    }
    @Override public synchronized void close() {
        closed = true;
        notifyAll();
        timer.shutdownNow(); workers.shutdownNow();
        pending.values().forEach(queue -> queue.forEach(item -> {
            item.dispatched.completeExceptionally(new IllegalStateException("MinerU Worker 正在停止"));
            item.fail(new IllegalStateException("MinerU Worker 正在停止")); delete(item.request.file());
        }));
        pending.clear();
    }
    private static void delete(Path path) { if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) { } }
}
