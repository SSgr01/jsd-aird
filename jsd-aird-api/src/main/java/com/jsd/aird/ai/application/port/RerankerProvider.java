package com.jsd.aird.ai.application.port;

import java.util.List;

public interface RerankerProvider {

    boolean isConfigured();

    RerankOutcome rerank(String query, List<RankCandidate> candidates);

    record RankCandidate(String id, String content, double retrievalScore, double rrfScore) {
    }

    record RankedDocument(String id, double score, int position, String provider, String model) {
    }

    record RerankOutcome(List<RankedDocument> documents, String status, String provider, String model,
                         Integer httpStatus, String error, long elapsedMs, int inputCount, int outputCount) {
        public RerankOutcome {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }

        public static RerankOutcome skipped(String status, int inputCount) {
            return new RerankOutcome(List.of(), status, "", "", null, "", 0, inputCount, 0);
        }
    }
}
