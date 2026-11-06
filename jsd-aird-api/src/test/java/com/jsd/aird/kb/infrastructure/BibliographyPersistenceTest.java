package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.*;
import com.jsd.aird.kb.application.port.BibliographyRepository;
import com.jsd.aird.kb.application.port.BibliographyRepository.Record;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.api.ScholarlyLiteratureFacade;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionDecision;
import com.jsd.aird.ops.application.port.*;
import com.jsd.aird.platform.web.IsolatedPostgres;
import com.jsd.aird.shared.security.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_DATASOURCE_URL",matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*")
class BibliographyPersistenceTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final UUID org=UUID.randomUUID(),owner=UUID.randomUUID();
    private IsolatedPostgres database() throws Exception {
        var db=new IsolatedPostgres(Map.ofEntries(
            Map.entry("iam.organization","organization"),Map.entry("iam.app_user","app_user"),
            Map.entry("kb.document","document"),Map.entry("kb.document_version","document_version"),
            Map.entry("kb.document_parse_run","parse_run"),Map.entry("kb.document_source_node","source_node"),
            Map.entry("ops.file_object","file_object"),Map.entry("kb.document_bibliography","bibliography"),
            Map.entry("kb.document_bibliography_revision","bibliography_revision"),Map.entry("kb.bibliography_task","task"),Map.entry("kb.bibliography_task_item","item")));
        db.table("organization","id uuid PRIMARY KEY");db.table("app_user","id uuid PRIMARY KEY");
        db.table("document","id uuid PRIMARY KEY,organization_id uuid,current_version_no int DEFAULT 1,category_id uuid,created_at timestamptz DEFAULT now(),lifecycle_status text DEFAULT 'ACTIVE'");
        db.table("document_version","id uuid PRIMARY KEY,document_id uuid,file_object_id uuid,extracted_text_sha256 text,version_no int DEFAULT 1");
        db.table("file_object","id uuid PRIMARY KEY,status text");
        db.table("parse_run","id uuid PRIMARY KEY,document_version_id uuid,status text,run_no int");
        db.table("source_node","source_node_key uuid,parse_run_id uuid,node_no int,node_type text,raw_text text,source_anchor_jsonb jsonb");
        db.jdbc.update("INSERT INTO iam.organization VALUES (?)",org);db.jdbc.update("INSERT INTO iam.app_user VALUES (?)",owner);
        try(var conn=db.source.getConnection()) {ScriptUtils.executeSqlScript(conn,new ClassPathResource("db/migration/V99__knowledge_bibliography.sql"));}
        return db;
    }
    private UUID[] document(IsolatedPostgres db,int n) {
        var doc=new UUID(0,n);var version=UUID.randomUUID();var file=UUID.randomUUID();
        // Avoid depending on the relative precision of the Windows JVM and PostgreSQL clocks.
        db.jdbc.update("INSERT INTO kb.document(id,organization_id,created_at) VALUES (?,?,'2020-01-01T00:00:00Z')",doc,org);
        db.jdbc.update("INSERT INTO ops.file_object VALUES (?,'ACTIVE')",file);
        db.jdbc.update("INSERT INTO kb.document_version(id,document_id,file_object_id,extracted_text_sha256) VALUES (?,?,?,?)",version,doc,file,"fingerprint");return new UUID[]{doc,version};
    }
    @Test void manualSqlEnforcesRevisionHistoryOwnershipIdempotencyAndStableTraversalBeyond1000() throws Exception {
        try(var db=database()) {
            var repo=new JdbcBibliographyRepository(db.jdbc,json);
            for(int n=1;n<=1005;n++)document(db,n);
            int count=0;UUID cursor=null;Instant before=Instant.now();
            while(true) {var page=repo.targets(org,null,List.of(),cursor,before,200);count+=page.size();if(page.size()<200)break;cursor=page.getLast().documentId();}
            assertThat(count).isEqualTo(1005);assertThat(repo.targets(UUID.randomUUID(),null,List.of(),null,before,200)).isEmpty();
            var target=repo.targets(org,null,List.of(),null,before,1).getFirst();
            var v=new Record(target.documentId(),target.versionId(),1,"ACCEPTED","PAPER",json.createObjectNode().put("title","Original title"),json.createObjectNode(),json.createArrayNode(),json.createArrayNode(),null,"hash",null);
            assertThat(repo.save(org,owner,v,0)).isTrue();assertThat(repo.save(org,owner,v,0)).isFalse();
            var v2=new Record(v.documentId(),v.versionId(),2,"ACCEPTED","PAPER",json.createObjectNode().put("title","Revised title"),v.provenance(),v.candidates(),v.manualFields(),null,"hash",null);
            assertThat(repo.save(org,owner,v2,1)).isTrue();assertThat(repo.find(org,v.versionId(),1).orElseThrow().accepted().path("title").asText()).isEqualTo("Original title");
            assertThat(repo.find(UUID.randomUUID(),v.versionId(),1)).isEmpty();
            var currentKey=new BibliographyRepository.Lookup(v.versionId(),null);
            var historicKey=new BibliographyRepository.Lookup(v.versionId(),1);
            var batch=repo.findAll(org,List.of(currentKey,historicKey,currentKey));
            assertThat(batch).hasSize(2);
            assertThat(batch.get(currentKey).accepted().path("title").asText()).isEqualTo("Revised title");
            assertThat(batch.get(historicKey).accepted().path("title").asText()).isEqualTo("Original title");
            assertThat(repo.findAll(UUID.randomUUID(),List.of(currentKey,historicKey))).isEmpty();
            var task=repo.createTask(org,owner,"request","same");assertThat(repo.createTask(org,owner,"request","same").id()).isEqualTo(task.id());
            assertThatThrownBy(()->repo.createTask(org,owner,"request","different")).isInstanceOf(RuntimeException.class);
            assertThat(repo.task(org,UUID.randomUUID(),task.id())).isEmpty();
            var item=repo.addItem(task.id(),target);assertThat(repo.addItem(task.id(),target).id()).isEqualTo(item.id());
            var at=Instant.now().plusSeconds(300);repo.updateItem(item.id(),0,"WAITING_PROVIDER","OPENALEX_FREE_BUDGET_EXHAUSTED",at,false);
            var resumed=repo.retryItem(item.id(),0);assertThat(resumed.status()).isEqualTo("WAITING_PROVIDER");assertThat(resumed.generation()).isEqualTo(1);
            assertThat(repo.updateItem(item.id(),0,"ACCEPTED",null,null,false)).isFalse();
            var async=mock(OpsAsyncFacade.class);var pending=new ArrayDeque<com.fasterxml.jackson.databind.JsonNode>();
            var deliveries=new HashSet<UUID>();var admissions=new java.util.concurrent.atomic.AtomicInteger();
            when(async.queueCapacity(org)).thenReturn(new OpsAsyncFacade.QueueCapacityView(15,20,15,100,5,2));
            when(async.enqueueAt(eq(org),eq(BibliographyService.JOB_TYPE),any(),anyString(),eq(0),any())).thenAnswer(call->{
                com.fasterxml.jackson.databind.JsonNode payload=call.getArgument(2);admissions.incrementAndGet();
                if(payload.path("dispatch").asBoolean())pending.add(payload.deepCopy());
                else assertThat(deliveries.add(UUID.fromString(payload.path("itemId").asText()))).isTrue();
                return UUID.randomUUID();
            });
            var auth=mock(AuthorizationService.class);when(auth.check(any())).thenReturn(new PermissionDecision(true,"test","ALLOW","ALL","TEST"));
            var service=new BibliographyService(repo,mock(KnowledgeRepository.class),new BibliographyExtractor(json),mock(ScholarlyLiteratureFacade.class),
                auth,async,mock(AuditLogFacade.class),mock(FileStorageFacade.class),json,new DataSourceTransactionManager(db.source));
            ActorContext.set(new Actor(org,owner,"fixture","USER"));
            try {
                service.create(new BibliographyService.Selection(List.of(),null,"bulk-1005"));
                assertThat(admissions.get()).isEqualTo(1);assertThat(deliveries).isEmpty();
                while(!pending.isEmpty()) {
                    var dispatch=pending.remove();admissions.set(0);service.process(dispatch,null);
                    assertThat(admissions.get()).isLessThanOrEqualTo(5);
                }
                assertThat(deliveries).hasSize(1005);
                when(async.queueCapacity(org)).thenReturn(new OpsAsyncFacade.QueueCapacityView(20,20,20,100,0,2));
                var busy=json.createObjectNode().put("organizationId",org.toString()).put("actorId",owner.toString()).put("taskId",task.id().toString()).put("dispatch",true);
                assertThatThrownBy(()->service.process(busy,null)).isInstanceOf(com.jsd.aird.shared.error.ApiException.class);
            } finally {ActorContext.clear();}
        }
    }
    @Test void workerKeepsHumanFieldsAndUnknownDocumentsLocalAndQuotaResumesWithoutReparsing() throws Exception {
        try(var db=database()) {
            var repo=new JdbcBibliographyRepository(db.jdbc,json);var ids=document(db,1);var doc=ids[0];var version=ids[1];var parse=UUID.randomUUID();
            db.jdbc.update("INSERT INTO kb.document_parse_run VALUES (?,?,'SUCCEEDED',1)",parse,version);
            String text="Research Article\nPrecise Hydrogel Experimental Methods\nJane Smith\nPublished online: 2018\nDOI: 10.1234/hydrogel\nAbstract\nA paper.";
            db.jdbc.update("INSERT INTO kb.document_source_node VALUES (?,?,0,'heading',?,?::jsonb)",UUID.randomUUID(),parse,text,"{\"page\":1}");
            var kb=mock(KnowledgeRepository.class);var versionRow=mock(KnowledgeRepository.VersionRow.class);var docRow=mock(KnowledgeRepository.DocumentRow.class);
            when(versionRow.id()).thenReturn(version);when(versionRow.documentId()).thenReturn(doc);when(versionRow.status()).thenReturn("READY");
            when(docRow.currentVersionId()).thenReturn(version);when(docRow.lifecycleStatus()).thenReturn("ACTIVE");
            when(kb.findVersion(org,version)).thenReturn(Optional.of(versionRow));when(kb.findDocument(org,doc)).thenReturn(Optional.of(docRow));
            var auth=mock(AuthorizationService.class);when(auth.check(any())).thenReturn(new PermissionDecision(true,"test","ALLOW","ALL","TEST"));
            var scholarly=mock(ScholarlyLiteratureFacade.class);var async=mock(OpsAsyncFacade.class);var audit=mock(AuditLogFacade.class);
            var service=new BibliographyService(repo,kb,new BibliographyExtractor(json),scholarly,auth,async,audit,mock(FileStorageFacade.class),json,new DataSourceTransactionManager(db.source));
            ActorContext.set(new Actor(org,owner,"fixture","USER"));
            try {
                var task=service.create(new BibliographyService.Selection(List.of(doc),null,"worker"));var item=repo.items(task,null,10).getFirst();
                var payload=json.createObjectNode().put("organizationId",org.toString()).put("actorId",owner.toString()).put("taskId",task.toString()).put("itemId",item.id().toString()).put("generation",0);
                when(scholarly.lookup(any())).thenReturn(json.createObjectNode().put("error","OPENALEX_FREE_BUDGET_EXHAUSTED").put("retry_after_seconds",4000));
                service.process(payload,null);var waiting=repo.item(task,item.id()).orElseThrow();assertThat(waiting.status()).isEqualTo("WAITING_PROVIDER");
                verify(async,atLeastOnce()).enqueueAt(eq(org),eq(BibliographyService.JOB_TYPE),any(),anyString(),eq(0),any());
                var response=json.createObjectNode();response.putArray("results").addObject().put("work_id","W123").put("id","https://openalex.org/W123").put("title","Precise Hydrogel Experimental Methods").put("doi","10.1234/hydrogel").put("publication_year",2018);
                when(scholarly.lookup(any())).thenReturn(response);
                service.process(payload.deepCopy().put("generation",waiting.generation()),null);
                var accepted=repo.find(org,version,null).orElseThrow();assertThat(accepted.status()).isEqualTo("ACCEPTED");
                db.jdbc.update("UPDATE kb.document_source_node SET raw_text=? WHERE parse_run_id=?",text.replace("Published online: 2018","Published online 17 March 2018"),parse);
                db.jdbc.update("UPDATE kb.document_version SET extracted_text_sha256='dated' WHERE id=?",version);
                ((com.fasterxml.jackson.databind.node.ObjectNode)response.path("results").get(0)).put("publication_date","2018-03-01");
                var dated=service.create(new BibliographyService.Selection(List.of(doc),null,"date-conflict"));var dateItem=repo.items(dated,null,10).getFirst();
                service.process(payload.deepCopy().put("taskId",dated.toString()).put("itemId",dateItem.id().toString()).put("generation",0),null);
                var datedRecord=repo.find(org,version,null).orElseThrow();assertThat(datedRecord.status()).isEqualTo("ACCEPTED");
                assertThat(datedRecord.accepted().path("publication_date").asText()).isEqualTo("2018-03-01");
                assertThat(datedRecord.provenance().path("publication_date").path("source").asText()).isEqualTo("OpenAlex");
                var supplemental=json.createObjectNode().put("publication_date","2018-03-17").put("venue","Original Journal").put("pages","1–6");
                supplemental.putArray("authors").addObject().put("name","Jane Smith").putArray("institutions").addObject().put("name","Original Institute");
                service.confirm(doc,version,new BibliographyService.Confirmation(datedRecord.revision(),"PAPER",supplemental));
                var external=(com.fasterxml.jackson.databind.node.ObjectNode)response.path("results").get(0);
                external.put("source","Abbreviated Journal").putObject("biblio").put("first_page","1").put("last_page","7");
                external.putArray("authors").addObject().put("name","Jane Smith").putArray("institutions").addObject().put("name","Another Institute");
                db.jdbc.update("UPDATE kb.document_version SET extracted_text_sha256='supplemental' WHERE id=?",version);
                var supplements=service.create(new BibliographyService.Selection(List.of(doc),null,"minor-differences"));var supplementItem=repo.items(supplements,null,10).getFirst();
                service.process(payload.deepCopy().put("taskId",supplements.toString()).put("itemId",supplementItem.id().toString()).put("generation",0),null);
                var retained=repo.find(org,version,null).orElseThrow();assertThat(retained.status()).isEqualTo("ACCEPTED");
                assertThat(retained.accepted().path("venue").asText()).isEqualTo("Abbreviated Journal");
                assertThat(retained.accepted().path("pages").asText()).isEqualTo("1–7");
                assertThat(retained.accepted().path("authors")).isEqualTo(external.path("authors"));
                var manual=service.confirm(doc,version,new BibliographyService.Confirmation(retained.revision(),"PAPER",json.createObjectNode().put("title","Customer approved title")));
                assertThatThrownBy(()->service.confirm(doc,version,new BibliographyService.Confirmation(accepted.revision(),"PAPER",json.createObjectNode()))).isInstanceOf(RuntimeException.class);
                db.jdbc.update("UPDATE kb.document_parse_run SET run_no=2 WHERE id=?",parse);
                db.jdbc.update("UPDATE kb.document_version SET extracted_text_sha256='changed' WHERE id=?",version);
                var another=service.create(new BibliographyService.Selection(List.of(doc),null,"after-reparse"));var next=repo.items(another,null,10).getFirst();
                service.process(payload.deepCopy().put("taskId",another.toString()).put("itemId",next.id().toString()).put("generation",0),null);
                var kept=repo.find(org,version,null).orElseThrow();assertThat(kept.accepted().path("title").asText()).isEqualTo("Precise Hydrogel Experimental Methods");assertThat(kept.status()).isEqualTo("ACCEPTED");
                db.jdbc.update("UPDATE kb.document_source_node SET raw_text='Confidential customer report with 10.1234/hydrogel' WHERE parse_run_id=?",parse);
                db.jdbc.update("UPDATE kb.document_version SET extracted_text_sha256='internal' WHERE id=?",version);clearInvocations(scholarly);
                var local=service.create(new BibliographyService.Selection(List.of(doc),null,"internal"));var localItem=repo.items(local,null,10).getFirst();
                service.process(payload.deepCopy().put("taskId",local.toString()).put("itemId",localItem.id().toString()).put("generation",0),null);
                verifyNoInteractions(scholarly);assertThat(repo.find(org,version,null).orElseThrow().documentType()).isEqualTo("PAPER");
                assertThat(repo.find(org,version,manual.revision()).orElseThrow().accepted().path("title").asText()).isEqualTo("Customer approved title");
            } finally {ActorContext.clear();}
        }
    }
}
