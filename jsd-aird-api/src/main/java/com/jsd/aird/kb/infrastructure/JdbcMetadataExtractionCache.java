package com.jsd.aird.kb.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.port.MetadataExtractionCache;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMetadataExtractionCache implements MetadataExtractionCache {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcMetadataExtractionCache(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    @Override public Optional<Entry> find(UUID org, String key) {
        return jdbc.query("SELECT status,response_jsonb,owner_token,updated_at FROM kb.metadata_extraction_cache WHERE organization_id=? AND cache_key=?",
                (rs,n)->new Entry(rs.getString(1),read(rs.getString(2)),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant()),org,key).stream().findFirst();
    }
    @Override public boolean claim(UUID org,String key,UUID owner,String model,String policy) {
        return jdbc.update("""
            INSERT INTO kb.metadata_extraction_cache(organization_id,cache_key,policy_hash,model,owner_token,status)
            VALUES (?,?,?,?,?,'RUNNING') ON CONFLICT DO NOTHING
            """,org,key,policy,model,owner)==1;
    }
    @Override public void succeed(UUID org,String key,UUID owner,JsonNode response) {
        jdbc.update("""
            UPDATE kb.metadata_extraction_cache SET status='SUCCEEDED',response_jsonb=?::jsonb,updated_at=now()
            WHERE organization_id=? AND cache_key=? AND owner_token=? AND status='RUNNING'
            """,response.toString(),org,key,owner);
    }
    @Override public void fail(UUID org,String key,UUID owner,String status) {
        if(!java.util.Set.of("FAILED","UNKNOWN").contains(status))throw new IllegalArgumentException("Invalid extraction status");
        jdbc.update("UPDATE kb.metadata_extraction_cache SET status=?,updated_at=now() WHERE organization_id=? AND cache_key=? AND owner_token=? AND status='RUNNING'",
                status,org,key,owner);
    }
    @Override public boolean reset(UUID org,String key) {
        return jdbc.update("""
            DELETE FROM kb.metadata_extraction_cache WHERE organization_id=? AND cache_key=?
            AND (status IN ('FAILED','UNKNOWN') OR (status='RUNNING' AND updated_at < now()-interval '15 minutes'))
            """,org,key)==1;
    }
    private JsonNode read(String text) {
        try {return text==null?null:json.readTree(text);} catch(Exception error){throw new IllegalStateException("Invalid extraction cache",error);}
    }
}
