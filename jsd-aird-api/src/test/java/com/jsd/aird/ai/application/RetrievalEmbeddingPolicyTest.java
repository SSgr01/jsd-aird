package com.jsd.aird.ai.application;

import com.jsd.aird.ai.infrastructure.EmbeddingQueryInstructions;
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RetrievalEmbeddingPolicyTest {
    @Test void queryProfileVersionAndBothInstructionBodiesInvalidateRetrievalFingerprint() {
        var facade = mock(KnowledgeEmbeddingFacade.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("embedding", facade);
        var policy = new RetrievalPolicy();
        policy.embeddingQueries(beans.getBeanProvider(KnowledgeEmbeddingFacade.class));
        var baseline = new EmbeddingQueryInstructions("generic", "v1", "find evidence", "find steps").describe();
        when(facade.queryPolicy()).thenReturn(baseline);
        var hash = policy.fingerprint();
        // Map iteration order must not affect cache identity across JVMs/replicas.
        var reversed = new LinkedHashMap<String, Object>();
        baseline.keySet().stream().sorted(java.util.Comparator.reverseOrder()).forEach(k -> reversed.put(k, baseline.get(k)));
        when(facade.queryPolicy()).thenReturn(reversed);
        assertThat(policy.fingerprint()).isEqualTo(hash);
        for (var changed : java.util.List.of(
                new EmbeddingQueryInstructions("none", "v1", "find evidence", "find steps"),
                new EmbeddingQueryInstructions("generic", "v2", "find evidence", "find steps"),
                new EmbeddingQueryInstructions("generic", "v1", "other evidence", "find steps"),
                new EmbeddingQueryInstructions("generic", "v1", "find evidence", "other steps"))) {
            when(facade.queryPolicy()).thenReturn(changed.describe());
            assertThat(policy.fingerprint()).isNotEqualTo(hash);
        }
    }
}
