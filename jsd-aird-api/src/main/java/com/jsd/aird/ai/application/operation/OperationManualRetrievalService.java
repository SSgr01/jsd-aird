package com.jsd.aird.ai.application.operation;

import com.jsd.aird.ai.application.port.OperationManualIndexRepository;
import com.jsd.aird.ai.application.operation.OperationManual.Access;
import com.jsd.aird.ai.application.operation.OperationManual.Chunk;
import com.jsd.aird.ai.application.operation.OperationManual.Section;
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OperationManualRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(OperationManualRetrievalService.class);
    private static final java.util.Set<String> QUERY_FILLER_TERMS = java.util.Set.of(
            "怎么", "怎样", "如何", "哪里", "在哪", "从哪", "请问", "我", "想", "要", "帮", "请",
            "一下", "一下子", "的", "了", "呢", "吗", "么", "可以", "能否", "系统", "里面");
    private final OperationManual manual;
    private final OperationManualIndexRepository repository;
    private final OperationManualIndexService index;
    private final KnowledgeEmbeddingFacade embeddings;
    private final ExecutorService vectorExecutor;
    private final Duration timeout;
    private final ByteBuffersDirectory directory = new ByteBuffersDirectory();
    private final SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer();
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final Map<String, Chunk> chunks = new LinkedHashMap<>();

    public OperationManualRetrievalService(OperationManual manual, OperationManualIndexRepository repository,
            OperationManualIndexService index, KnowledgeEmbeddingFacade embeddings,
            @Qualifier("operationManualVectorExecutor") ExecutorService executor,
            @Value("${app.ai.operation-assistant.embedding-query-timeout:3s}") Duration timeout) throws IOException {
        this.manual = manual;
        this.repository = repository;
        this.index = index;
        this.embeddings = embeddings;
        this.vectorExecutor = executor;
        this.timeout = timeout;
        var sections = new HashMap<String, Section>();
        manual.sections().forEach(section -> sections.put(section.id(), section));
        try (var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer).setSimilarity(new BM25Similarity()))) {
            for (var chunk : manual.chunks()) {
                chunks.put(chunk.id(), chunk);
                var document = new Document();
                document.add(new StoredField("chunkId", chunk.id()));
                document.add(new StringField("sectionId", chunk.sectionId(), Field.Store.NO));
                document.add(new TextField("title", chunk.title(), Field.Store.NO));
                document.add(new TextField("keywords", String.join(" ", sections.get(chunk.sectionId()).keywords()), Field.Store.NO));
                document.add(new TextField("content", chunk.content(), Field.Store.NO));
                writer.addDocument(document);
            }
        }
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity());
    }

    public Retrieval retrieve(String question, String currentPageId, List<HistoryMessage> history, Access access) {
        var started = System.nanoTime();
        var query = contextualQuery(question, currentPageId, history, access);
        var lexical = lexical(query, access);
        var vector = new ArrayList<String>();
        String fallback = "";
        CompletableFuture<java.util.Optional<String>> pending = null;
        try {
            if (!index.key().model().isBlank() && repository.ready(index.key())) {
                pending = CompletableFuture.supplyAsync(() -> embeddings.embedVector(query), vectorExecutor);
                var embedding = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (embedding.isPresent()) {
                    repository.search(index.key(), embedding.get(), access.sectionIds(), 20)
                            .forEach(hit -> vector.add(hit.chunkId()));
                } else fallback = "EMBEDDING_UNAVAILABLE";
            } else fallback = "INDEX_NOT_READY";
        } catch (Exception exception) {
            if (pending != null) pending.cancel(true);
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            fallback = "VECTOR_UNAVAILABLE";
        }
        var scores = new HashMap<String, Double>();
        fuse(scores, lexical);
        fuse(scores, vector);
        var permitted = new LinkedHashMap<String, Section>();
        access.sections().forEach(section -> permitted.put(section.id(), section));
        var ranked = scores.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).toList();
        var selected = new LinkedHashMap<String, Chunk>();
        for (var rank : ranked) {
            var chunk = chunks.get(rank.getKey());
            if (chunk != null && permitted.containsKey(chunk.sectionId())) selected.putIfAbsent(chunk.sectionId(), chunk);
            if (selected.size() == 6) break;
        }
        var evidence = new ArrayList<Evidence>();
        int ordinal = 1;
        for (var chunk : selected.values()) evidence.add(new Evidence("H" + ordinal++, permitted.get(chunk.sectionId()), chunk.content()));
        log.info("operation_manual_retrieval version={} lexicalHits={} vectorHits={} sections={} fallback={} elapsedMs={}",
                manual.version(), lexical.size(), vector.size(), evidence.size(), fallback,
                (System.nanoTime() - started) / 1_000_000);
        return new Retrieval(List.copyOf(evidence), vector.isEmpty() ? "KEYWORD" : "HYBRID", fallback);
    }

    static String contextualQuery(String question, String pageId, List<HistoryMessage> history, Access access) {
        var text = question.strip();
        if (text.length() <= 40 && text.matches("(?s).*(下一步|接下来|然后|这个|那个|怎么继续|如何继续|怎么办|继续操作).*")) {
            var previous = history.reversed().stream().filter(message -> "USER".equals(message.role())).findFirst();
            if (previous.isPresent()) text += " " + previous.get().content();
            var page = pageId == null ? null : access.pages().get(pageId);
            if (page != null) text += " " + page.title();
        }
        return text;
    }

    List<String> lexical(String text, Access access) {
        if (access.sections().isEmpty()) return List.of();
        try {
            var tokens = new LinkedHashSet<String>();
            try (var stream = analyzer.tokenStream("content", text)) {
                var term = stream.addAttribute(CharTermAttribute.class);
                stream.reset();
                while (stream.incrementToken() && tokens.size() < 80) {
                    var token = term.toString();
                    if (!QUERY_FILLER_TERMS.contains(token)) tokens.add(token);
                }
                stream.end();
            }
            if (tokens.isEmpty()) return List.of();
            var terms = new BooleanQuery.Builder();
            for (var token : tokens) {
                terms.add(new BoostQuery(new TermQuery(new Term("title", token)), 3), BooleanClause.Occur.SHOULD);
                terms.add(new BoostQuery(new TermQuery(new Term("keywords", token)), 4), BooleanClause.Occur.SHOULD);
                terms.add(new TermQuery(new Term("content", token)), BooleanClause.Occur.SHOULD);
            }
            terms.setMinimumNumberShouldMatch(1);
            var query = new BooleanQuery.Builder().add(terms.build(), BooleanClause.Occur.MUST)
                    .add(new TermInSetQuery("sectionId", access.sectionIds().stream().map(BytesRef::new).toList()), BooleanClause.Occur.FILTER).build();
            var result = new ArrayList<String>();
            for (var hit : searcher.search(query, 20).scoreDocs) result.add(searcher.storedFields().document(hit.doc).get("chunkId"));
            return result;
        } catch (IOException exception) { throw new IllegalStateException("操作手册关键词检索失败", exception); }
    }

    static void fuse(Map<String, Double> scores, List<String> ranks) {
        for (int i = 0; i < ranks.size(); i++) scores.merge(ranks.get(i), 1d / (60d + i + 1), Double::sum);
    }

    @PreDestroy
    public void close() throws IOException { reader.close(); directory.close(); analyzer.close(); }

    public record HistoryMessage(String role, String content) { }
    public record Evidence(String ref, Section section, String content) {
        public Evidence(String ref, Section section) { this(ref, section, section.content()); }
    }
    public record Retrieval(List<Evidence> evidence, String mode, String fallback) { }
}
