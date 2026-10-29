package com.jsd.aird.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.spc.application.SpectrumService;
import com.jsd.aird.spc.infrastructure.JdbcSpectrumRepository;
import com.jsd.aird.ops.application.port.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST",matches="true")
class SpectrumConcurrencyPostgresTest {
    private static IsolatedPostgres db;
    private JdbcSpectrumRepository repository;
    private SpectrumService service;
    private AuditLogFacade audit;
    private UUID id,category,emptyCategory;
    private static final UUID ORG=UUID.randomUUID(),USER=UUID.randomUUID();
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    @BeforeAll static void setup(){
        db=new IsolatedPostgres(Map.of("spc.chart_category","category","spc.chart_asset","chart","core.project_resource_link","link","core.project_reference","reference"));
        db.table("category","id uuid PRIMARY KEY,organization_id uuid,code text,name text,description text,analysis_hint text,fields_jsonb jsonb DEFAULT '[]'::jsonb,sort_order integer DEFAULT 0,system_category boolean DEFAULT false,created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now(),created_by uuid,UNIQUE(organization_id,code)");
        db.table("chart","id uuid PRIMARY KEY,organization_id uuid,category_id uuid,file_object_id uuid,title text,original_name text DEFAULT 'fixture.png',content_type text DEFAULT 'image/png',size_bytes bigint DEFAULT 1,sha256 text DEFAULT 'hash',sample_name text,batch_no text,test_conditions text,metadata_jsonb jsonb DEFAULT '{}'::jsonb,page_count integer DEFAULT 1,status text DEFAULT 'READY',created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now()");
        db.table("link","organization_id uuid,resource_type text,resource_id uuid");
        db.table("reference","organization_id uuid,resource_type text,resource_id uuid,status text,title text");
    }
    @AfterAll static void cleanup(){if(db!=null)db.close();}
    @BeforeEach void seed(){
        db.clear();ActorContext.set(new Actor(ORG,USER,"test"));id=UUID.randomUUID();category=UUID.randomUUID();emptyCategory=UUID.randomUUID();
        db.jdbc.update("INSERT INTO "+db.table("category")+"(id,organization_id,code,name) VALUES(?,?,'A','category A'),(?,?,'B','category B')",category,ORG,emptyCategory,ORG);
        db.jdbc.update("INSERT INTO "+db.table("chart")+"(id,organization_id,category_id,title) VALUES(?,?,?,'original')",id,ORG,category);
        repository=spy(new JdbcSpectrumRepository(db.jdbc));
        // External project/report reference checks have separate unit coverage;
        // this isolated fixture intentionally has no such references.
        doReturn(false).when(repository).hasActiveProjectReference(any(),any());doReturn(false).when(repository).hasAnalysisReportReference(any(),any());
        audit=mock(AuditLogFacade.class);service=new SpectrumService(repository,mock(FileStorageFacade.class),JSON,audit);
    }
    @AfterEach void clearActor(){ActorContext.clear();}
    private SpectrumService.UpdateChartCommand edit(String title){return new SpectrumService.UpdateChartCommand(title,null,null,null,JSON.createObjectNode(),null);}
    private List<Boolean> race(java.util.function.IntConsumer action)throws Exception{
        var barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)){
            var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++){final int index=i;futures.add(pool.submit(()->{ActorContext.set(new Actor(ORG,USER,"test"));try{barrier.await(5,TimeUnit.SECONDS);db.tx.executeWithoutResult(status->action.accept(index));return true;}catch(ApiException error){assertThat(error.errorCode()).isIn(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,ApiErrorCode.NOT_FOUND);return false;}finally{ActorContext.clear();}}));}
            return List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS));
        }
    }
    @Test void twoChartEditorsHaveOneWinner()throws Exception{
        var revision=service.chart(id).getRevision();assertThat(race(i->service.updateChart(id,edit("editor-"+i),revision))).containsExactlyInAnyOrder(true,false);
    }
    @Test void movingAndEditingCannotBothUseTheOriginalChart()throws Exception{
        var revision=service.chart(id).getRevision();assertThat(race(i->{if(i==0)service.assignChartCategory(id,emptyCategory,revision);else service.updateChart(id,edit("changed"),revision);})).containsExactlyInAnyOrder(true,false);
    }
    @Test void deleteAndEditCannotBothUseTheOriginalChart()throws Exception{
        var revision=service.chart(id).getRevision();assertThat(race(i->{if(i==0)service.deleteChart(id,revision);else service.updateChart(id,edit("changed"),revision);})).containsExactlyInAnyOrder(true,false);
    }
    @Test void twoCategoryEditorsCannotOverwriteAnOriginalToken()throws Exception{
        var revision=service.categories().stream().filter(item->item.id().equals(emptyCategory)).findFirst().orElseThrow().getRevision();
        assertThat(race(i->service.updateCategory(emptyCategory,new SpectrumService.CategoryCommand("B","category-"+i,null,null,JSON.createArrayNode()),revision))).containsExactlyInAnyOrder(true,false);
    }
    @Test void missingRevisionCannotTurnIntoAnUnconditionalWrite(){
        assertThatThrownBy(()->db.tx.execute(status->service.updateChart(id,edit("bad"),null))).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.VALIDATION_ERROR));
        assertThat(service.chart(id).title()).isEqualTo("original");
    }
    @Test void deletedChartIsNotReadableAndCannotBeDeletedAgain(){
        var revision=service.chart(id).getRevision();db.tx.executeWithoutResult(status->service.deleteChart(id,revision));
        assertThatThrownBy(()->service.chart(id)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.NOT_FOUND));
        assertThatThrownBy(()->db.tx.execute(status->{service.deleteChart(id,revision);return null;})).isInstanceOf(ApiException.class);
    }
    @Test void foreignOrganizationCannotModifyAValidOriginalRevision(){
        var revision=service.chart(id).getRevision();ActorContext.set(new Actor(UUID.randomUUID(),USER,"test"));
        assertThatThrownBy(()->db.tx.execute(status->service.updateChart(id,edit("foreign"),revision))).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.NOT_FOUND));
    }
    @Test void failedAuditRollsBackChartMetadataAndRevision(){
        var revision=service.chart(id).getRevision();doThrow(new IllegalStateException("injected audit failure")).when(audit).append(any(),any(),any(),any(),any(),any());
        assertThatThrownBy(()->db.tx.execute(status->service.updateChart(id,edit("unsaved"),revision))).isInstanceOf(IllegalStateException.class);
        assertThat(service.chart(id).getRevision()).isEqualTo(revision);
    }
}
