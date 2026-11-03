package com.jsd.aird.shared.retrieval;

import java.util.*;
import java.util.function.Function;

/** Rank-based fusion only: raw scores from different engines are never added together. */
public final class RankFusion {
    private RankFusion() { }
    public record Channel<T>(String name, double weight, List<T> hits) { }
    public record Ranked<T>(T value, double score) { }
    public static double contribution(int k, int oneBasedRank) { return 1d / (k + oneBasedRank); }
    public static <T> List<Ranked<T>> fuse(List<Channel<T>> channels, Function<T, String> identity, int k) {
        if (k < 1) throw new IllegalArgumentException("RRF k must be positive");
        var values = new LinkedHashMap<String, T>();
        var scores = new HashMap<String, Double>();
        for (var channel : channels) {
            var seen = new HashSet<String>();
            int rank = 0;
            for (var hit : channel.hits()) {
                var key = identity.apply(hit);
                if (!seen.add(key)) continue;
                rank++;
                values.putIfAbsent(key, hit);
                scores.merge(key, channel.weight() * contribution(k, rank), Double::sum);
            }
        }
        return values.entrySet().stream().sorted(Comparator
                .<Map.Entry<String,T>>comparingDouble(entry -> scores.get(entry.getKey())).reversed()
                .thenComparing(Map.Entry::getKey))
            .map(entry -> new Ranked<>(entry.getValue(), scores.get(entry.getKey()))).toList();
    }
}
