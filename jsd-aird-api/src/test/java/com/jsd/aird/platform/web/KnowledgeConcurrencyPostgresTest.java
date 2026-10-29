package com.jsd.aird.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.core.api.ProjectResourceFacade;
import com.jsd.aird.kb.application.*;
import com.jsd.aird.kb.application.port.*;
import com.jsd.aird.kb.domain.*;
import com.jsd.aird.kb.infrastructure.*;
import com.jsd.aird.ops.application.port.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST",matches="true")
class KnowledgeConcurrencyPostgresTest {
    private static IsolatedPostgres db;
    private static JdbcKnowledgeRepository repository;
    private static JdbcKnowledgeGovernanceRepository reviews;
    private KnowledgeService service;
    private AuditLogFacade audit;
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final UUID ORG=UUID.randomUUID(),USER=UUID.randomUUID();
    private UUID id,vid,rid;
    @BeforeAll static void setup(){
        db=new IsolatedPostgres(Map.ofEntries(Map.entry("kb.document_review_revision","review"),Map.entry("kb.document_version","version"),Map.entry("kb.document_category","category"),Map.entry("kb.document_ai_grant","grant"),Map.entry("kb.document_tag","document_tag"),Map.entry("kb.tag","tag"),Map.entry("kb.publication","publication"),Map.entry("kb.document","document")));
        db.table("document","id uuid PRIMARY KEY,organization_id uuid,title text,status text DEFAULT 'READY',scan_status text DEFAULT 'PASSED',current_version_no integer DEFAULT 1,parse_error text,created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now(),library_scope text DEFAULT 'INTERNAL',category_id uuid,lifecycle_status text DEFAULT 'ACTIVE',current_publication_id uuid");
        db.table("version","id uuid PRIMARY KEY,document_id uuid,version_no integer DEFAULT 1,original_name text DEFAULT 'fixture.pdf',content_type text DEFAULT 'application/pdf',size_bytes bigint DEFAULT 1,sha256 text DEFAULT 'hash',review_status text DEFAULT 'PENDING_REVIEW',review_revision integer DEFAULT 0,review_reason text,reviewed_by uuid,reviewed_at timestamptz,updated_at timestamptz DEFAULT now()");
        db.table("category","id uuid PRIMARY KEY,organization_id uuid,scope text,name text,description text,sort_order integer DEFAULT 0,created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now(),created_by uuid");
        db.table("publication","id uuid PRIMARY KEY,document_id uuid,status text,publication_no integer");db.table("grant","document_id uuid,status text");
        db.table("review","id uuid PRIMARY KEY,document_id uuid,document_version_id uuid,status text DEFAULT 'DRAFT',revision_no integer DEFAULT 1,lock_version integer DEFAULT 0,base_publication_id uuid,confirmed_document_jsonb jsonb DEFAULT '{}'::jsonb,excluded_review_node_ids jsonb,updated_by uuid,updated_at timestamptz DEFAULT now(),failure_reason text,confirmed_text text");
        db.table("tag","id uuid PRIMARY KEY,organization_id uuid,normalized_name text,display_name text,created_by uuid,UNIQUE(organization_id,normalized_name)");db.table("document_tag","document_id uuid,tag_id uuid,PRIMARY KEY(document_id,tag_id)");
        repository=new JdbcKnowledgeRepository(db.jdbc);reviews=new JdbcKnowledgeGovernanceRepository(db.jdbc,JSON,new StructuredDocumentCodec(JSON));
    }
    @AfterAll static void cleanup(){if(db!=null)db.close();}
    @BeforeEach @SuppressWarnings("unchecked") void seed(){
        db.clear();ActorContext.set(new Actor(ORG,USER,"test"));id=UUID.randomUUID();vid=UUID.randomUUID();rid=UUID.randomUUID();
        db.jdbc.update("INSERT INTO "+db.table("document")+"(id,organization_id,title) VALUES(?,?,'original')",id,ORG);
        db.jdbc.update("INSERT INTO "+db.table("version")+"(id,document_id) VALUES(?,?)",vid,id);db.jdbc.update("INSERT INTO "+db.table("review")+"(id,document_id,document_version_id) VALUES(?,?,?)",rid,id,vid);
        audit=mock(AuditLogFacade.class);
        service=new KnowledgeService(repository,mock(KnowledgeGovernanceRepository.class),mock(FileStorageFacade.class),mock(OpsAsyncFacade.class),audit,JSON,new StructuredDocumentCodec(JSON),List.of(),mock(FileSafetyScanner.class),mock(ObjectProvider.class),List.of(),"embedding",1024,Duration.ofMinutes(15),new BlockAwareChunker(JSON),mock(LexicalAnalyzer.class),new SystemParsingPolicy("AUTO",false),Runnable::run,mock(ProjectResourceFacade.class));
    }
    @AfterEach void clearActor(){ActorContext.clear();}
    private List<Boolean> race(java.util.function.IntPredicate action)throws Exception{
        var barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)){
            var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++){final int index=i;futures.add(pool.submit(()->{ActorContext.set(new Actor(ORG,USER,"test"));try{barrier.await(5,TimeUnit.SECONDS);return db.tx.execute(status->action.test(index));}catch(ApiException error){assertThat(error.errorCode()).isIn(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,ApiErrorCode.NOT_FOUND,ApiErrorCode.RESOURCE_CONFLICT);return false;}finally{ActorContext.clear();}}));}
            return List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS));
        }
    }
    @Test void twoMetadataEditorsCannotOverwriteTheOriginalRevision()throws Exception{
        var original=service.get(id).getRevision();assertThat(race(i->{service.renameDocument(id,"editor-"+i,original);return true;})).containsExactlyInAnyOrder(true,false);
    }
    @Test void deleteAndRenameCannotBothUseTheOriginalDocumentRevision()throws Exception{
        var original=service.get(id).getRevision();assertThat(race(i->{if(i==0)service.deleteDocument(id,original);else service.renameDocument(id,"changed",original);return true;})).containsExactlyInAnyOrder(true,false);
    }
    @Test void failedAuditRollsBackMetadataAndItsRevision(){
        var original=service.get(id).getRevision();doThrow(new IllegalStateException("injected audit error")).when(audit).append(any(),any(),any(),any(),any(),any());
        assertThatThrownBy(()->db.tx.execute(status->service.renameDocument(id,"unsaved",original))).isInstanceOf(IllegalStateException.class);
        assertThat(service.get(id).title()).isEqualTo("original");assertThat(service.get(id).getRevision()).isEqualTo(original);
    }
    @Test void foreignOrganizationCannotUseAValidDocumentRevision(){
        var original=service.get(id).getRevision();ActorContext.set(new Actor(UUID.randomUUID(),USER,"test"));
        assertThatThrownBy(()->db.tx.execute(status->service.renameDocument(id,"foreign",original))).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.NOT_FOUND));
    }
    private KnowledgeGovernanceRepository.ReviewUpdate edit(){return new KnowledgeGovernanceRepository.ReviewUpdate(id,vid,rid,0,null,"changed","INTERNAL",null,List.of(),JSON.createObjectNode().put("text","changed"),List.of(),List.of());}
    @Test void reviewSaveAndPublicationReservationHaveOneWinner()throws Exception{
        assertThat(race(i->i==0?reviews.saveReview(ORG,USER,edit()):reviews.reservePublication(ORG,id,vid,rid,0,null,"confirmed"))).containsExactlyInAnyOrder(true,false);
    }
    @Test void rejectAndPublicationReservationHaveOneWinner()throws Exception{
        assertThat(race(i->i==0?reviews.reject(ORG,USER,id,vid,rid,0,"reason"):reviews.reservePublication(ORG,id,vid,rid,0,null,"confirmed"))).containsExactlyInAnyOrder(true,false);
    }
    @Test void changingPublicationBaseRejectsTheOldReservation(){
        db.jdbc.update("UPDATE "+db.table("document")+" SET current_publication_id=? WHERE id=?",UUID.randomUUID(),id);
        var reserved=db.tx.execute(status->reviews.reservePublication(ORG,id,vid,rid,0,null,"stale"));
        assertThat(reserved).isFalse();
        assertThat(db.jdbc.queryForObject("SELECT status FROM "+db.table("review"),String.class)).isEqualTo("DRAFT");
    }
    @Test void categoryRenameRetainsTheOriginalTokenUnderConcurrency()throws Exception{
        var category=UUID.randomUUID();db.jdbc.update("INSERT INTO "+db.table("category")+"(id,organization_id,scope,name) VALUES(?,?,'INTERNAL','category')",category,ORG);
        var token=repository.findCategory(ORG,category).orElseThrow().getRevision();
        assertThat(race(i->{service.renameCategory(category,"category-"+i,null,token);return true;})).containsExactlyInAnyOrder(true,false);
    }
}
