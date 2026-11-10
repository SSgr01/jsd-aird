package com.jsd.aird.kb.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.*;
import com.jsd.aird.kb.application.port.*;
import com.jsd.aird.kb.domain.LexicalAnalyzer;
import com.jsd.aird.ops.application.port.*;
import com.jsd.aird.shared.resource.OperationBudget;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(8)
class KnowledgeRetrievalBudgetTest {
    final KnowledgeRepository repository=mock(KnowledgeRepository.class);
    final KnowledgeEmbeddingFacade embeddings=mock(KnowledgeEmbeddingFacade.class);
    final UUID org=UUID.randomUUID();

    @SuppressWarnings("unchecked")
    KnowledgeService service(Executor executor) {
        var provider=(ObjectProvider<KnowledgeEmbeddingFacade>)mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(embeddings);
        when(embeddings.embedVectors(anyList())).thenReturn(List.of(Optional.of("[0.1,0.2]")));
        var analyzer=mock(LexicalAnalyzer.class);
        when(analyzer.analyzeCompatibleQuery(anyString())).thenReturn(List.of(new LexicalAnalyzer.QueryTermFamily(0,
            List.of(new LexicalAnalyzer.QueryAlternative("material-smartcn-v2","resin")))));
        var mapper=new ObjectMapper();
        var service=new KnowledgeService(repository,mock(KnowledgeGovernanceRepository.class),mock(FileStorageFacade.class),
            mock(OpsAsyncFacade.class),mock(AuditLogFacade.class),mapper,new StructuredDocumentCodec(mapper),List.of(),
            mock(FileSafetyScanner.class),provider,List.of(),"embedding-model",2,Duration.ofMinutes(15),
            new BlockAwareChunker(mapper),analyzer,executor);
        ReflectionTestUtils.setField(service,"channelTimeoutMs",250L);
        ReflectionTestUtils.setField(service,"recallTimeoutMs",1500L);
        return service;
    }
    KnowledgeSearchFacade.SearchRequest request(){return new KnowledgeSearchFacade.SearchRequest(org,"resin",true,12,
        List.of(),List.of(),List.of(),List.of(),"resin",new KnowledgeSearchFacade.RecallOptions(true,true,false));}

    @Test void timedOutBm25KeepsCompletedVectorEvidenceWithoutStartingPhraseFallback() throws Exception {
        var stopped=new CountDownLatch(1);
        var row=new KnowledgeRepository.SearchRow(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Resin","resin.pdf",1,"Results","Measured result",.9,1);
        when(repository.batchBm25Rank(any(),anyList(),anyBoolean(),anyList(),anyInt())).thenAnswer(call->{
            assertThat(OperationBudget.kind()).isEqualTo(OperationBudget.Kind.READ);
            try{new CountDownLatch(1).await();return List.of();}finally{stopped.countDown();}
        });
        when(repository.batchVectorRank(any(),anyList(),anyBoolean(),anyList(),anyInt(),anyInt()))
            .thenReturn(List.of(new KnowledgeRepository.RankedChunk(0,row.chunkId(),.9,1)));
        when(repository.loadSearchRows(eq(org),anyList())).thenReturn(List.of(row));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var result=service(executor).search(request());
            assertThat(result.hits()).extracting(KnowledgeSearchFacade.SearchHit::content).containsExactly("Measured result");
            assertThat(result.trace().fallbacks()).contains("BM25_TIMEOUT");
            assertThat(stopped.await(1,TimeUnit.SECONDS)).isTrue();
            verify(repository,never()).fullTextSearch(any(),anyString(),anyBoolean(),anyList(),anyInt());
            assertThat(OperationBudget.active()).isFalse();
        }
    }

    @Test void expiredQueuedChannelsNeverReadDatabaseWhenEventuallyDequeued() {
        var queued=new ArrayList<Runnable>();
        var service=service(queued::add);
        ReflectionTestUtils.setField(service,"channelTimeoutMs",50L);
        var result=service.recall(request());
        assertThat(result.trace().fallbacks()).contains("BM25_TIMEOUT","VECTOR_TIMEOUT");
        queued.forEach(Runnable::run);
        verify(repository,never()).batchBm25Rank(any(),anyList(),anyBoolean(),anyList(),anyInt());
        verify(embeddings,never()).embedVectors(anyList());
    }

    @Test void sqlTimeoutDoesNotLaunchMorePhraseQueriesEvenWithTimeRemaining() {
        when(repository.batchBm25Rank(any(),anyList(),anyBoolean(),anyList(),anyInt()))
            .thenThrow(new org.springframework.dao.QueryTimeoutException("statement timed out"));
        var result=service(Runnable::run).recall(request());
        assertThat(result.trace().fallbacks()).contains("BM25_TIMEOUT");
        verify(repository,never()).fullTextSearch(any(),anyString(),anyBoolean(),anyList(),anyInt());
    }

    @Test void workerInterruptCompletingBeforeAwaitStillPreventsFallback() {
        when(repository.batchBm25Rank(any(),anyList(),anyBoolean(),anyList(),anyInt()))
            .thenAnswer(call -> { throw new InterruptedException("Retrieval deadline reached"); });
        var result=service(Runnable::run).recall(request());
        assertThat(result.trace().fallbacks()).contains("BM25_TIMEOUT").doesNotContain("BM25_ERROR","BM25_EMPTY");
        verify(repository,never()).fullTextSearch(any(),anyString(),anyBoolean(),anyList(),anyInt());
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
