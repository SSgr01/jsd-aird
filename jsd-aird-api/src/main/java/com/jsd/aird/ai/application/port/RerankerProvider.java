package com.jsd.aird.ai.application.port;

import java.util.List;

public interface RerankerProvider {

    boolean isConfigured();

    List<RankedDocument> rerank(String query, List<RankCandidate> candidates);

    record RankCandidate(String id, String content, double retrievalScore, double rrfScore) {
    }

    record RankedDocument(String id, double score, int position, String provider, String model) {
    }
}
