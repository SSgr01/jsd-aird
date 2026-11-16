package com.jsd.aird.kb.infrastructure;
import static org.assertj.core.api.Assertions.*;
import com.jsd.aird.platform.web.IsolatedPostgres;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_DATASOURCE_URL",matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*")
class DualModelHalfvecPostgresTest {
 String vector(int n){return "[1"+",0".repeat(n-1)+"]";}
 @Test void migratesPreservesAndIndependentlySearchesBothModels() throws Exception {
  try(var db=new IsolatedPostgres(Map.ofEntries(Map.entry("kb.document_bibliography","bibliography"),Map.entry("kb.metadata_search_text","metadata_search_text"),Map.entry("kb.metadata_extraction_cache","metadata_cache"),Map.entry("kb.document_processing_step","steps"),Map.entry("iam.organization","organization"),Map.entry("kb.document","document"),Map.entry("kb.document_chunk","chunk"),Map.entry("kb.document_chunk_embedding","embedding"),Map.entry("kb.document_version","version"),Map.entry("kb.publication","publication"),Map.entry("ops.file_object","file"),Map.entry("kb.document_ai_grant","grant"),Map.entry("ai.operation_manual_chunk","manual")))) {
   db.table("organization","id uuid PRIMARY KEY");
   db.table("steps","step_key varchar(32)");
   db.table("bibliography","accepted_jsonb jsonb DEFAULT '{}'::jsonb");
   db.table("document","id uuid PRIMARY KEY,organization_id uuid,lifecycle_status text,current_publication_id uuid,category_id uuid,title text");
   db.table("version","id uuid PRIMARY KEY,file_object_id uuid,original_name text");
   db.table("publication","id uuid PRIMARY KEY,document_version_id uuid,review_revision_id uuid,status text,metadata_snapshot_jsonb jsonb DEFAULT '{}'::jsonb");
   db.table("file","id uuid PRIMARY KEY,organization_id uuid,status text");
   db.table("grant","document_id uuid PRIMARY KEY,organization_id uuid,status text");
   db.table("chunk","id uuid PRIMARY KEY,document_id uuid,document_version_id uuid,review_revision_id uuid,chunk_role text,embedding vector,embedding_model text,page_no int,section text,content text,chunk_no int");
   db.table("manual","embedding vector,embedding_dimension int,CHECK(vector_dims(embedding)=embedding_dimension)");
   UUID org=UUID.randomUUID(),doc=UUID.randomUUID(),version=UUID.randomUUID(),pub=UUID.randomUUID(),review=UUID.randomUUID(),file=UUID.randomUUID(),chunk=UUID.randomUUID();
   db.jdbc.update("INSERT INTO iam.organization VALUES (?)",org);
   db.jdbc.update("INSERT INTO kb.document VALUES (?,?,'ACTIVE',?,NULL,'Sample')",doc,org,pub);
   db.jdbc.update("INSERT INTO kb.document_version VALUES (?,?,'sample.pdf')",version,file);
   db.jdbc.update("INSERT INTO kb.publication(id,document_version_id,review_revision_id,status) VALUES (?,?,?,'CURRENT')",pub,version,review);
   db.jdbc.update("INSERT INTO ops.file_object VALUES (?,?,'ACTIVE')",file,org);
   db.jdbc.update("INSERT INTO kb.document_ai_grant VALUES (?,?,'APPROVED')",doc,org);
   db.jdbc.update("INSERT INTO kb.document_chunk VALUES (?,?,?,?,'CHILD',?::vector,'cloud',1,'main','Original evidence',1)",chunk,doc,version,review,vector(1024));
   try(var connection=db.source.getConnection()){KnowledgeMigrationTestSupport.executeWhole(connection);}
   var repo=new JdbcKnowledgeRepository(db.jdbc);
   repo.updateChunkEmbedding(org,chunk,vector(2560),"qwen");
   assertThat(db.jdbc.queryForObject("SELECT count(*) FROM kb.document_chunk_embedding",Integer.class)).isEqualTo(2);
   assertThat(repo.vectorSearch(org,vector(1024),true,List.of(),null,10,1024,"cloud")).hasSize(1);
   assertThat(repo.vectorSearch(org,vector(2560),true,List.of(),null,10,2560,"qwen")).hasSize(1);
   assertThat(repo.vectorSearch(org,vector(2560),true,List.of(),null,10,2560,"cloud")).isEmpty();
   assertThat(repo.vectorSearch(UUID.randomUUID(),vector(2560),true,List.of(),null,10,2560,"qwen")).isEmpty();
   assertThat(repo.vectorSearch(org,vector(2560),true,List.of(),Set.of(UUID.randomUUID()),10,2560,"qwen")).isEmpty();
   assertThat(repo.chunksForEmbedding(org,doc,review,"cloud",1024)).isEmpty();
   assertThat(repo.chunksForEmbedding(org,doc,review,"other",1024)).hasSize(1);
   repo.updateChunkEmbedding(org,chunk,vector(1024),"cloud");
   assertThat(db.jdbc.queryForObject("SELECT count(*) FROM kb.document_chunk_embedding",Integer.class)).isEqualTo(2);
   var status=new JdbcBibliographyRepository(db.jdbc,new ObjectMapper());
   ReflectionTestUtils.setField(status,"targetModel","cloud");
   ReflectionTestUtils.setField(status,"autoModel","qwen");
   ReflectionTestUtils.setField(status,"autoDimension",2560);
   var state=status.vectorStatus(org,doc,version);
   assertThat(state.path("embedded").asInt()).isEqualTo(1);assertThat(state.path("models").size()).isEqualTo(2);assertThat(state.path("autoBuild").path("status").asText()).isEqualTo("PRESENT");
   var random=new Random(20261116);var probes=new ArrayList<String>();
   for(int n=0;n<400;n++) {
    UUID id=UUID.randomUUID();var values=new ArrayList<String>();
    for(int i=0;i<16;i++)values.add(Float.toString(random.nextFloat()*2-1));
    String value="["+String.join(",",values)+",0".repeat(2560-16)+"]";if(n<10)probes.add(value);
    db.jdbc.update("INSERT INTO kb.document_chunk VALUES (?,?,?,?,'CHILD',NULL,NULL,1,'main','Benchmark evidence',?)",id,doc,version,review,n+2);
    repo.updateChunkEmbedding(org,id,value,"qwen");
   }
   int matched=0;long annMs=0,exactMs=0;
   for(String probe:probes) {
    long started=System.nanoTime();var approximate=repo.vectorSearch(org,probe,true,List.of(),null,10,2560,"qwen");annMs+=(System.nanoTime()-started)/1000000;
    started=System.nanoTime();var exact=db.tx.execute(tx->{
     db.jdbc.execute("SET LOCAL enable_indexscan=off");db.jdbc.execute("SET LOCAL enable_bitmapscan=off");
     return repo.vectorSearch(org,probe,true,List.of(),null,10,2560,"qwen").stream().map(hit->hit.chunkId()).toList();
    });exactMs+=(System.nanoTime()-started)/1000000;
    for(var hit:approximate)if(exact.contains(hit.chunkId()))matched++;
   }
   System.out.println("isolated_halfvec_benchmark rows=401 queries=10 Recall@10="+(matched/100.0)+" annTotalMs="+annMs+" exactTotalMs="+exactMs);
   assertThat(matched).isGreaterThanOrEqualTo(90);
   db.tx.executeWithoutResult(tx->{
    db.jdbc.execute("SET LOCAL enable_seqscan=off");
    String plan=String.join("\n",db.jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) SELECT chunk_id FROM kb.document_chunk_embedding WHERE embedding_dimension=2560 ORDER BY embedding::halfvec(2560) <=> ?::halfvec(2560) LIMIT 10",String.class,vector(2560)));
    assertThat(plan).contains("kb_chunk_embedding_2560");
   });
   db.jdbc.update("UPDATE kb.document_ai_grant SET status='REVOKED'");
   assertThat(repo.vectorSearch(org,vector(1024),true,List.of(),null,10,1024,"cloud")).isEmpty();
   repo.clearDocumentEmbeddings(org,doc);repo.updateChunkEmbedding(org,chunk,vector(2560),"qwen");
   assertThat(db.jdbc.queryForObject("SELECT count(*) FROM kb.document_chunk_embedding",Integer.class)).isZero();
  }
 }
 @Test void workersSkipOtherModelsAndUnroutedLegacyJobsBeforeTakingALease() {
  try(var db=new IsolatedPostgres(Map.of("ops.async_job","jobs"))) {
   db.table("jobs","id uuid PRIMARY KEY,organization_id uuid,job_type text,priority int DEFAULT 1,next_attempt_at timestamptz DEFAULT now(),created_at timestamptz DEFAULT now(),status text DEFAULT 'READY',lease_expires_at timestamptz,lease_owner text,lease_token uuid,lease_generation bigint DEFAULT 0,progress int DEFAULT 0,current_stage text,attempt_count int DEFAULT 0,max_attempts int DEFAULT 5,started_at timestamptz,updated_at timestamptz,payload_jsonb jsonb");
   UUID org=UUID.randomUUID(),cloud=UUID.randomUUID(),qwen=UUID.randomUUID(),legacy=UUID.randomUUID();
   db.jdbc.update("INSERT INTO ops.async_job(id,organization_id,job_type,payload_jsonb) VALUES (?,?,'KB_BUILD_KNOWLEDGE_VECTOR',?::jsonb)",qwen,org,"{\"expectedEmbeddingModel\":\"qwen\",\"expectedEmbeddingDimension\":2560}");
   db.jdbc.update("INSERT INTO ops.async_job(id,organization_id,job_type,payload_jsonb) VALUES (?,?,'KB_BUILD_KNOWLEDGE_VECTOR',?::jsonb)",cloud,org,"{\"expectedEmbeddingModel\":\"cloud\",\"expectedEmbeddingDimension\":1024}");
   db.jdbc.update("INSERT INTO ops.async_job(id,organization_id,job_type,payload_jsonb) VALUES (?,?,'KB_BUILD_KNOWLEDGE_VECTOR','{}')",legacy,org);
   var work=new com.jsd.aird.ops.infrastructure.PostgresWorkRepository(db.jdbc,new ObjectMapper());
   ReflectionTestUtils.setField(work,"workerEmbeddingModel","cloud");
   assertThat(work.claimJob("local",java.time.Duration.ofSeconds(60)).orElseThrow().id()).isEqualTo(cloud);
   assertThat(work.claimJob("local",java.time.Duration.ofSeconds(60))).isEmpty();
   assertThat(db.jdbc.queryForObject("SELECT attempt_count FROM ops.async_job WHERE id=?",Integer.class,qwen)).isZero();
   ReflectionTestUtils.setField(work,"workerEmbeddingModel","qwen");ReflectionTestUtils.setField(work,"workerEmbeddingDimension",2560);
   assertThat(work.claimJob("server",java.time.Duration.ofSeconds(60)).orElseThrow().id()).isEqualTo(qwen);
   assertThat(db.jdbc.queryForObject("SELECT attempt_count FROM ops.async_job WHERE id=?",Integer.class,legacy)).isZero();
  }
 }

}
