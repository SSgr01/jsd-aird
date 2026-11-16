package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.platform.web.IsolatedPostgres;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_DATASOURCE_URL",matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*")
class MetadataExtractionCachePostgresTest {
    @Test void reservationsAreAtomicScopedAndOnlyExplicitFailedOrStaleCallsCanBeReset() throws Exception {
        try(var db=new IsolatedPostgres(Map.of("iam.organization","organization","kb.metadata_extraction_cache","metadata_cache"))) {
            db.table("organization","id uuid PRIMARY KEY");
            try(var connection=db.source.getConnection()){KnowledgeMigrationTestSupport.executeSection(connection,"metadata-cache");}
            UUID org=UUID.randomUUID(),other=UUID.randomUUID();db.jdbc.update("INSERT INTO iam.organization VALUES (?),(?)",org,other);
            var cache=new JdbcMetadataExtractionCache(db.jdbc,new ObjectMapper());var key="a".repeat(64);var policy="b".repeat(64);
            try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
                var start=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
                for(int i=0;i<5;i++)futures.add(workers.submit(()->{start.await();return cache.claim(org,key,UUID.randomUUID(),"model",policy);}));
                start.countDown();int winners=0;for(var future:futures)if(future.get(10,TimeUnit.SECONDS))winners++;
                assertThat(winners).isEqualTo(1);
            }
            assertThat(cache.find(other,key)).isEmpty();assertThat(cache.reset(org,key)).isFalse();
            var owner=cache.find(org,key).orElseThrow().owner();cache.fail(org,key,UUID.randomUUID(),"FAILED");assertThat(cache.find(org,key).orElseThrow().status()).isEqualTo("RUNNING");
            cache.fail(org,key,owner,"UNKNOWN");assertThat(cache.reset(org,key)).isTrue();
            var newOwner=UUID.randomUUID();assertThat(cache.claim(org,key,newOwner,"model",policy)).isTrue();
            cache.succeed(org,key,owner,new ObjectMapper().createObjectNode());assertThat(cache.find(org,key).orElseThrow().status()).isEqualTo("RUNNING");
            cache.succeed(org,key,newOwner,new ObjectMapper().createObjectNode().putArray("fields"));assertThat(cache.reset(org,key)).isFalse();
            String stale="c".repeat(64);cache.claim(org,stale,UUID.randomUUID(),"model",policy);
            db.jdbc.update("UPDATE kb.metadata_extraction_cache SET updated_at=now()-interval '16 minutes' WHERE organization_id=? AND cache_key=?",org,stale);
            assertThat(cache.reset(other,stale)).isFalse();assertThat(cache.reset(org,stale)).isTrue();
        }
    }
}
