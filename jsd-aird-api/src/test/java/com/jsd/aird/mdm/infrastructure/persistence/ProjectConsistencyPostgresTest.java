package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.application.command.ProjectCommands;
import com.jsd.aird.mdm.application.query.ProjectQuery;
import com.jsd.aird.mdm.application.service.ProjectService;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.shared.UuidTypeHandler;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import com.jsd.aird.tpl.infrastructure.JdbcTemplateVersionReviewRepository;
import com.jsd.aird.tpl.infrastructure.JdbcTemplateRepository;
import com.jsd.aird.tpl.application.TemplateVersionReviewService;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.domain.TemplateStatus;
import java.lang.reflect.*;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.assertj.core.api.Assertions.*;

/** Real database CAS and isolation tests. Every statement is rebased to a new,
 * disposable schema; the application's mdm/tpl tables are never touched. */
@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST",matches="true")
class ProjectConsistencyPostgresTest {
    private static final String SCHEMA="consistency_test_"+UUID.randomUUID().toString().replace("-","");
    private static final UUID ORG=UUID.randomUUID(), OTHER_ORG=UUID.randomUUID(), USER=UUID.randomUUID();
    private static JdbcTemplate jdbc;
    private static MyBatisProjectRepository repository;
    private static ProjectService service;
    private static JdbcTemplateVersionReviewRepository reviews;
    private static DataSource dataSource;

    @BeforeAll static void setup(){
        var env=System.getenv(); var url=env.getOrDefault("JSD_AIRD_DATASOURCE_URL","jdbc:postgresql://localhost:5432/jsd_aird");
        if(!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*"))throw new IllegalStateException("Consistency tests require a loopback database");
        var original=new DriverManagerDataSource(url,env.getOrDefault("JSD_AIRD_DATASOURCE_USERNAME","jsd_aird"),env.getOrDefault("JSD_AIRD_DATASOURCE_PASSWORD","jsd_aird_dev"));
        dataSource=new RebasedDataSource(original); jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA "+SCHEMA);
        jdbc.execute("CREATE TABLE "+SCHEMA+".project (id uuid PRIMARY KEY,organization_id uuid NOT NULL,project_code text,name text,partner_id uuid,partner_name text,owner text,start_date date,end_date date,priority text,status text,team_size integer,background text,custom_fields jsonb,team_members jsonb,version bigint NOT NULL DEFAULT 0,created_at timestamptz,updated_at timestamptz,created_by text,updated_by text,deleted boolean NOT NULL DEFAULT false)");
        jdbc.execute("CREATE TABLE "+SCHEMA+".template_version_review (version_id uuid PRIMARY KEY,organization_id uuid NOT NULL,review_status text NOT NULL DEFAULT 'NOT_SUBMITTED',submitted_by uuid,submitted_at timestamptz,reviewed_by uuid,reviewed_at timestamptz,review_comment text,lock_version bigint NOT NULL DEFAULT 0,updated_at timestamptz)");
        jdbc.execute("CREATE TABLE "+SCHEMA+".template_version_review_event (id uuid PRIMARY KEY,version_id uuid,organization_id uuid,from_status text,to_status text,actor_id uuid,comment text)");
        jdbc.execute("CREATE TABLE "+SCHEMA+".template (id uuid PRIMARY KEY,organization_id uuid NOT NULL)");
        jdbc.execute("CREATE TABLE "+SCHEMA+".template_version (id uuid PRIMARY KEY,template_id uuid,lock_version bigint NOT NULL DEFAULT 0)");
        var config=new Configuration(new Environment("isolated",new SpringManagedTransactionFactory(),dataSource));
        config.setArgNameBasedConstructorAutoMapping(true); config.getTypeHandlerRegistry().register(UUID.class,UuidTypeHandler.class);config.addMapper(ProjectMapper.class);
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        repository=new MyBatisProjectRepository(session.getMapper(ProjectMapper.class));service=new ProjectService(repository);reviews=new JdbcTemplateVersionReviewRepository(jdbc);
    }
    @AfterAll static void cleanup(){
        if(jdbc!=null&&SCHEMA.matches("consistency_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA IF EXISTS "+SCHEMA+" CASCADE");
    }
    @BeforeEach void empty(){
        ActorContext.set(new Actor(ORG,USER,"qa"));
        jdbc.execute("TRUNCATE "+SCHEMA+".project, "+SCHEMA+".template_version_review, "+SCHEMA+".template_version_review_event, "+SCHEMA+".template, "+SCHEMA+".template_version");
    }
    @AfterEach void clearActor(){ActorContext.clear();}

    @Test void twoEditorsOfTheSameVersionHaveExactlyOneWinner() throws Exception {
        var id=insert("P-1",ProjectStatus.IN_PROGRESS);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->edit(id,"A",barrier));var b=pool.submit(()->edit(id,"B",barrier));
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("saved","conflict");
        }
        assertThat(repository.findById(id).orElseThrow().version()).isEqualTo(1);
    }
    @Test void staleDeleteDoesNotRemoveTheOtherEditorsSavedData(){
        var id=insert("P-1",ProjectStatus.IN_PROGRESS);service.update(id,command("A",0));
        assertCode(()->service.delete(id,0),ApiErrorCode.RESOURCE_CONFLICT);
        assertThat(service.get(id).name()).isEqualTo("A"); service.delete(id,1);
        assertCode(()->service.delete(id,1),ApiErrorCode.NOT_FOUND);
    }
    @Test void completedStateIsEnforcedInsideTheDeleteStatement(){
        var id=insert("P-1",ProjectStatus.COMPLETED);
        assertThat(repository.softDelete(id,0,"qa")).isFalse();assertThat(service.get(id).status()).isEqualTo(ProjectStatus.COMPLETED);
    }
    @Test void organizationScopeAppliesToReadsUpdatesAndDeletion(){
        var own=insert("P-1",ProjectStatus.IN_PROGRESS);
        ActorContext.set(new Actor(OTHER_ORG,USER,"other")); var other=insert("P-2",ProjectStatus.IN_PROGRESS);
        ActorContext.set(new Actor(ORG,USER,"qa"));
        var query=new ProjectQuery(null,null,null,null,null,null,null,1,20);
        assertThat(repository.count(query)).isEqualTo(1);assertThat(repository.findPage(query)).extracting(Project::id).containsExactly(own);
        assertThat(repository.findById(other)).isEmpty();assertThat(repository.findAllByIds(List.of(other))).isEmpty();
        assertThat(repository.softDelete(other,0,"qa")).isFalse();assertCode(()->service.update(other,command("x",0)),ApiErrorCode.NOT_FOUND);
    }
    @Test void oldReviewCycleCannotApproveANewSubmissionWithTheSameStatus(){
        var id=UUID.randomUUID();reviews.ensure(ORG,id);
        jdbc.update("UPDATE "+SCHEMA+".template_version_review SET review_status='SUBMITTED',lock_version=5 WHERE version_id=?",id);
        assertCode(()->reviews.transition(ORG,id,1,"SUBMITTED","APPROVED",USER,null),ApiErrorCode.REVIEW_STATE_CONFLICT);
        assertThat(reviews.find(ORG,id).orElseThrow().status()).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".template_version_review_event",Long.class)).isZero();
    }
    @Test void competingReviewersCannotBothApproveOrRejectTheSameRevision() throws Exception {
        var id=UUID.randomUUID();reviews.ensure(ORG,id);jdbc.update("UPDATE "+SCHEMA+".template_version_review SET review_status='SUBMITTED' WHERE version_id=?",id);
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->review(id,"APPROVED",barrier));var b=pool.submit(()->review(id,"REJECTED",barrier));
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("saved","conflict");
        }
        assertThat(reviews.find(ORG,id).orElseThrow().lockVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".template_version_review_event",Long.class)).isEqualTo(1);
    }

    @Test void approvalWaitsForConcurrentDraftCommitThenRejectsTheUnseenRevision() throws Exception {
        var templateId=UUID.randomUUID();var id=UUID.randomUUID();
        jdbc.update("INSERT INTO "+SCHEMA+".template VALUES(?,?)",templateId,ORG);
        jdbc.update("INSERT INTO "+SCHEMA+".template_version VALUES(?,?,0)",id,templateId);
        reviews.ensure(ORG,id);
        jdbc.update("UPDATE "+SCHEMA+".template_version_review SET review_status='SUBMITTED' WHERE version_id=?",id);
        var actual=new JdbcTemplateRepository(jdbc,new com.fasterxml.jackson.databind.ObjectMapper());
        var templates=org.mockito.Mockito.mock(TemplateRepository.class);
        org.mockito.Mockito.doAnswer(call->{actual.lockWorkspace(ORG,id);return null;}).when(templates).lockWorkspace(ORG,id);
        org.mockito.Mockito.when(templates.findWorkspace(ORG,id)).thenAnswer(call->{
            var workspace=org.mockito.Mockito.mock(TemplateRepository.TemplateWorkspace.class);
            org.mockito.Mockito.when(workspace.status()).thenReturn(TemplateStatus.DRAFT);
            org.mockito.Mockito.when(workspace.lockVersion()).thenReturn(jdbc.queryForObject("SELECT lock_version FROM "+SCHEMA+".template_version WHERE id=?",Long.class,id));
            return Optional.of(workspace);
        });
        var reviewService=new TemplateVersionReviewService(templates,reviews);
        var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var reviewing=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var save=pool.submit(()->tx.execute(status->{
                actual.lockWorkspace(ORG,id);
                jdbc.update("UPDATE "+SCHEMA+".template_version SET lock_version=lock_version+1 WHERE id=?",id);
                locked.countDown();
                try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Draft release timeout");}
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                return null;
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var approval=pool.submit(()->{
                ActorContext.set(new Actor(ORG,USER,"reviewer"));
                try{return tx.execute(status->{reviewing.countDown();reviewService.approve(id,0,0,null);return "saved";});}
                catch(ApiException conflict){assertThat(conflict.errorCode()).isEqualTo(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT);return "conflict";}
                finally{ActorContext.clear();}
            });
            try{
                assertThat(reviewing.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->approval.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            }finally{release.countDown();}
            save.get(5,TimeUnit.SECONDS);
            assertThat(approval.get(5,TimeUnit.SECONDS)).isEqualTo("conflict");
        }
        assertThat(reviews.find(ORG,id).orElseThrow().status()).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".template_version_review_event",Long.class)).isZero();
    }
    private String edit(UUID id,String name,CyclicBarrier barrier) throws Exception {
        ActorContext.set(new Actor(ORG,USER,name));try{barrier.await(5,TimeUnit.SECONDS);service.update(id,command(name,0));return "saved";}
        catch(ApiException conflict){assertThat(conflict.errorCode()).isEqualTo(ApiErrorCode.RESOURCE_CONFLICT);return "conflict";}finally{ActorContext.clear();}
    }
    private String review(UUID id,String status,CyclicBarrier barrier) throws Exception {
        barrier.await(5,TimeUnit.SECONDS);try{reviews.transition(ORG,id,0,"SUBMITTED",status,USER,null);return "saved";}
        catch(ApiException conflict){assertThat(conflict.errorCode()).isEqualTo(ApiErrorCode.REVIEW_STATE_CONFLICT);return "conflict";}
    }
    private UUID insert(String code,ProjectStatus status){
        var id=UUID.randomUUID(); repository.insert(new Project(id,code,"original",null,null,"qa",null,null,ProjectPriority.MEDIUM,status,1,null,null,null,0,Instant.now(),Instant.now()),"qa");return id;
    }
    private ProjectCommands.SaveProject command(String name,long version){return new ProjectCommands.SaveProject("P-1",name,null,null,"qa",null,null,ProjectPriority.MEDIUM,ProjectStatus.IN_PROGRESS,1,null,null,null,version);}
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,ApiErrorCode code){assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(code));}

    private static class RebasedDataSource extends AbstractDataSource {
        private final DataSource original;RebasedDataSource(DataSource original){this.original=original;}
        @Override public Connection getConnection() throws java.sql.SQLException{return wrap(original.getConnection());}
        @Override public Connection getConnection(String user,String password)throws java.sql.SQLException{return wrap(original.getConnection(user,password));}
        private Connection wrap(Connection connection){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(args!=null&&args.length>0&&args[0] instanceof String sql&&method.getName().startsWith("prepare")){
                args=args.clone();args[0]=sql.replace("mdm.project",SCHEMA+".project").replace("tpl.template_version_review",SCHEMA+".template_version_review").replace("tpl.template_version",SCHEMA+".template_version").replace("tpl.template",SCHEMA+".template");
            }
            try{return method.invoke(connection,args);}catch(InvocationTargetException error){throw error.getCause();}
        });}
    }
}
