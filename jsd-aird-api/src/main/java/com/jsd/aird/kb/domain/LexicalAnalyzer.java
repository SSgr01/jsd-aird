package com.jsd.aird.kb.domain;

import java.util.Map;

public interface LexicalAnalyzer {

    String version();

    Analysis analyzeDocument(String text);

    Analysis analyzeQuery(String text);

    record Analysis(Map<String, Integer> frequencies, int documentLength) {
        public Analysis {
            frequencies = frequencies == null ? Map.of() : Map.copyOf(frequencies);
            if (documentLength < 0) throw new IllegalArgumentException("documentLength 不能为负数");
        }
    }
}
