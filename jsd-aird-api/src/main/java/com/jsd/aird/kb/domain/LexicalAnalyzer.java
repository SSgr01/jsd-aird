package com.jsd.aird.kb.domain;

import java.util.Map;
import java.util.List;

public interface LexicalAnalyzer {

    String version();

    Analysis analyzeDocument(String text);

    Analysis analyzeQuery(String text);

    default List<QueryTermFamily> analyzeCompatibleQuery(String text) {
        var terms = analyzeQuery(text).frequencies().keySet().stream().toList();
        var result = new java.util.ArrayList<QueryTermFamily>();
        for (var index = 0; index < terms.size(); index++) {
            result.add(new QueryTermFamily(index, List.of(new QueryAlternative(version(), terms.get(index)))));
        }
        return List.copyOf(result);
    }

    record Analysis(Map<String, Integer> frequencies, int documentLength) {
        public Analysis {
            frequencies = frequencies == null ? Map.of() : Map.copyOf(frequencies);
            if (documentLength < 0) throw new IllegalArgumentException("documentLength 不能为负数");
        }
    }

    record QueryAlternative(String analyzerVersion, String term) { }
    record QueryTermFamily(int ordinal, List<QueryAlternative> alternatives) {
        public QueryTermFamily {
            alternatives = alternatives == null ? List.of() : alternatives.stream().distinct().toList();
        }
    }
}
