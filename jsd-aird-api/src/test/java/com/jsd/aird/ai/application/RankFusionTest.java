package com.jsd.aird.ai.application;
import java.util.*;
import com.jsd.aird.shared.retrieval.RankFusion;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RankFusionTest {
    record Hit(String id,double raw) { }
    @Test void usesRanksAndDeduplicatesWithinChannelsWithoutMixingRawScores() {
        var a=new Hit("a",.01);var b=new Hit("b",1000000);
        var result=RankFusion.fuse(List.of(new RankFusion.Channel<>("bm25",1,List.of(a,a,b)),new RankFusion.Channel<>("vector",1,List.of(a)),
            new RankFusion.Channel<>("metadata",1,List.of(b))),Hit::id,60);
        assertThat(result.getFirst().value()).isEqualTo(a);assertThat(result.getFirst().score()).isCloseTo(2d/61,within(.00000001));
        assertThat(result.get(1).score()).isLessThan(result.getFirst().score());
    }
}
