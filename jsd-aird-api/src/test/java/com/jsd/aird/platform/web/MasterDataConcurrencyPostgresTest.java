package com.jsd.aird.platform.web;

import com.jsd.aird.mdm.infrastructure.persistence.*;
import com.jsd.aird.shared.UuidTypeHandler;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/** Runs production MyBatis statements; no handwritten imitation of CAS SQL. */
@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST",matches="true")
class MasterDataConcurrencyPostgresTest {
    enum Module {
        CUSTOMER(BusinessPartnerMapper.class,"update","softDelete","partner"),
        CONTACT(BusinessPartnerMapper.class,"updateContact","softDeleteContact","contact"),
        MATERIAL(MaterialMapper.class,"update","delete","material"),
        MEETING(MeetingMinutesMapper.class,"update","delete","meeting"),
        COMMUNICATION(CommunicationRecordMapper.class,"updateCommunication","deleteCommunication","communication"),
        REQUIREMENT(CustomerRequirementMapper.class,"updateRequirement","deleteRequirement","requirement"),
        STAGE(ProjectStageMapper.class,"update","softDelete","stage"),
        TASK(ProjectWorkMapper.class,"updateTask","deleteTask","task");
        final Class<?> mapper; final String update,delete,table;
        Module(Class<?> mapper,String update,String delete,String table){this.mapper=mapper;this.update=update;this.delete=delete;this.table=table;}
        String statement(boolean remove){return mapper.getName()+"."+(remove?delete:update);}
    }
    private static IsolatedPostgres db;
    private static SqlSessionTemplate session;
    private UUID id;
    @BeforeAll static void setup(){
        db=new IsolatedPostgres(Map.of("mdm.business_partner","partner","mdm.partner_contact","contact","mdm.material","material","mdm.meeting_minutes","meeting","mdm.communication_record","communication","mdm.customer_requirement","requirement","mdm.project_stage","stage","mdm.project_task","task"));
        var fields="id uuid PRIMARY KEY,partner_id uuid,project_id uuid,stage_id uuid,partner_code text,name text,normalized_name text,industry text,address text,remark text,customer_level text,cooperation_status text,main_business text,custom_fields jsonb,department text,title text,phone text,email text,assigned_project_ids jsonb,members text,wechat text,category text,source_category text,source_module text,stage text,contact_person text,status text,description text,attendees jsonb,summary text,occurred_at timestamptz,archived_to_kb boolean,communicated_at timestamptz,internal_participants text,communication_method text,content text,raw_requirement text,urgency text,raised_at date,delivery_date date,custom_status_name text,stage_code text,owner text,priority text,planned_start date,planned_end date,actual_start timestamptz,actual_end timestamptz,planned_date date,version bigint DEFAULT 0,updated_at timestamptz DEFAULT now(),updated_by text,deleted boolean DEFAULT false,deleted_at timestamptz,deleted_by text";
        for(var module:Module.values())db.table(module.table,fields);
        var config=new Configuration(new Environment("isolated",new SpringManagedTransactionFactory(),db.source));
        config.getTypeHandlerRegistry().register(UUID.class,UuidTypeHandler.class);
        for(var type:Arrays.stream(Module.values()).map(module->module.mapper).distinct().toList())config.addMapper(type);
        session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
    }
    @AfterAll static void cleanup(){if(db!=null)db.close();}
    @BeforeEach void empty(){db.clear();id=UUID.randomUUID();}
    private void seed(Module module){db.jdbc.update("INSERT INTO "+db.table(module.table)+"(id,partner_id,name,title) VALUES(?,?,'original','original')",id,id);}
    private Map<String,Object> parameters(long version,String text){
        var p=new HashMap<String,Object>();
        for(var field:List.of("code","normalized","industry","address","remark","customerLevel","cooperationStatus","mainBusiness","department","phone","email","members","wechat","category","sourceCategory","sourceModule","stage","contactPerson","description","internalParticipants","communicationMethod","content","rawRequirement","urgency","customStatusName","stageCode","owner","priority"))p.put(field,null);
        p.put("id",id);p.put("contactId",id);p.put("taskId",id);p.put("partnerId",id);p.put("projectId",null);p.put("stageId",null);
        p.put("name",text);p.put("title",text);p.put("summary",text);p.put("status","ACTIVE");p.put("customFields","{}");p.put("assignedProjectIds","[]");p.put("attendees","[]");
        p.put("now",Instant.now());p.put("updatedAt",Instant.now());p.put("occurredAt",Instant.now());p.put("communicatedAt",Instant.now());p.put("archivedToKb",false);
        p.put("raisedAt",LocalDate.now());p.put("deliveryDate",null);p.put("plannedDate",null);p.put("plannedStart",null);p.put("plannedEnd",null);p.put("actualStart",null);p.put("actualEnd",null);
        p.put("version",version);p.put("currentVersion",version);p.put("operator","test");
        var row=new HashMap<>(p);row.put("version",version+1);p.put("row",row);return p;
    }
    private List<Integer> race(java.util.function.IntUnaryOperator work)throws Exception{
        var barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)){
            var futures=new ArrayList<Future<Integer>>();for(int i=0;i<2;i++){final int index=i;futures.add(pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return db.tx.execute(status->work.applyAsInt(index));}));}
            return List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS));
        }
    }
    @ParameterizedTest @EnumSource(Module.class) void twoEditorsHaveExactlyOneDatabaseWinner(Module module)throws Exception{
        seed(module);assertThat(race(i->session.update(module.statement(false),parameters(0,"editor-"+i)))).containsExactlyInAnyOrder(1,0);
        assertThat(db.jdbc.queryForObject("SELECT version FROM "+db.table(module.table)+" WHERE id=?",Long.class,id)).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(Module.class) void saveAndDeleteCannotBothUseTheOriginalVersion(Module module)throws Exception{
        seed(module);assertThat(race(i->session.update(module.statement(i==1),parameters(0,"editor")))).containsExactlyInAnyOrder(1,0);
    }
    @ParameterizedTest @EnumSource(Module.class) void staleDeleteCannotRemoveANewerEditAndDeleteIsNotRepeated(Module module){
        seed(module);assertThat(session.update(module.statement(false),parameters(0,"new"))).isEqualTo(1);
        assertThat(session.update(module.statement(true),parameters(0,"old"))).isZero();
        assertThat(session.update(module.statement(true),parameters(1,"current"))).isEqualTo(1);
        assertThat(session.update(module.statement(true),parameters(1,"replay"))).isZero();
    }
    @ParameterizedTest @EnumSource(Module.class) void failureAfterUpdateRollsBackTheVersionAndContent(Module module){
        seed(module);assertThatThrownBy(()->db.tx.execute(status->{session.update(module.statement(false),parameters(0,"unsaved"));throw new IllegalStateException("injected failure");})).isInstanceOf(IllegalStateException.class);
        assertThat(db.jdbc.queryForObject("SELECT version FROM "+db.table(module.table)+" WHERE id=?",Long.class,id)).isZero();
        assertThat(db.jdbc.queryForObject("SELECT title FROM "+db.table(module.table)+" WHERE id=?",String.class,id)).isEqualTo("original");
    }
}
