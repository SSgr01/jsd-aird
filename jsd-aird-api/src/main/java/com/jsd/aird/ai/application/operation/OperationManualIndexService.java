package com.jsd.aird.ai.application.operation;

import com.jsd.aird.ai.application.port.OperationManualIndexRepository;
import com.jsd.aird.ai.application.port.OperationManualIndexRepository.IndexKey;
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OperationManualIndexService {
    private static final Logger log = LoggerFactory.getLogger(OperationManualIndexService.class);
    private final OperationManual manual;
    private final OperationManualIndexRepository repository;
    private final KnowledgeEmbeddingFacade embeddings;
    private final IndexKey key;

    public OperationManualIndexService(OperationManual manual, OperationManualIndexRepository repository,
                                      KnowledgeEmbeddingFacade embeddings,
                                      @Value("${app.ai.embedding.model:}") String model,
                                      @Value("${app.ai.embedding.dimension:1024}") int dimension) {
        this.manual = manual;
        this.repository = repository;
        this.embeddings = embeddings;
        this.key = new IndexKey(manual.version(), model, dimension);
    }

    public boolean synchronize() {
        if (key.model().isBlank() || key.dimension() <= 0) {
            log.warn("operation_manual_index status=NOT_CONFIGURED version={} keywordFallback=true", key.manualVersion());
            return true; // Configuration requires a restart; do not repeatedly call an absent provider.
        }
        long started = System.nanoTime();
        try {
            if (repository.ready(key)) return true;
            var ready = repository.synchronize(key, manual.chunks(), embeddings::embedVectors);
            log.info("operation_manual_index status={} version={} model={} dimension={} chunks={} elapsedMs={}",
                    ready ? "READY" : "LOCKED", key.manualVersion(), key.model(), key.dimension(),
                    manual.chunks().size(), (System.nanoTime() - started) / 1_000_000);
            return ready;
        } catch (Exception exception) {
            // Persist a bounded, credential-free reason; provider messages can contain endpoint information.
            try { repository.failed(key, "向量索引生成失败，请检查向量模型配置或服务状态"); }
            catch (Exception ignored) { /* A database failure must not disable the packaged lexical handbook. */ }
            log.warn("operation_manual_index status=FAILED version={} errorType={} keywordFallback=true",
                    key.manualVersion(), exception.getClass().getSimpleName());
            return false;
        }
    }

    public IndexKey key() { return key; }
}
