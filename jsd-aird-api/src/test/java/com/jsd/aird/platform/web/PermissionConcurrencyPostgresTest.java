package com.jsd.aird.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.iam.infrastructure.JdbcIamStore;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST",matches="true")
class PermissionConcurrencyPostgresTest {
    enum Kind { ROLE, USER }
    private static IsolatedPostgres db;
    private static JdbcIamStore store;
    private static final UUID ORG=UUID.randomUUID();
    private UUID id;
    @BeforeAll static void setup(){
        db=new IsolatedPostgres(Map.of("iam.role","role","iam.app_user","app_user","iam.role_permission_binding","role_binding","iam.user_permission_override","user_binding"));
        db.table("role","id uuid PRIMARY KEY,organization_id uuid,policy_version bigint DEFAULT 0,updated_at timestamptz");
        db.table("app_user","id uuid PRIMARY KEY,organization_id uuid,auth_version bigint DEFAULT 0");
        var binding="id uuid PRIMARY KEY,organization_id uuid,permission_code text CHECK(permission_code <> 'injected-invalid'),effect text,scope_type text,target_ids jsonb";
        db.table("role_binding",binding+",role_id uuid");db.table("user_binding",binding+",user_id uuid");
        store=new JdbcIamStore(db.jdbc,new ObjectMapper().findAndRegisterModules());
    }
    @AfterAll static void cleanup(){if(db!=null)db.close();}
    @BeforeEach void empty(){db.clear();id=UUID.randomUUID();}
    private String parent(Kind kind){return kind==Kind.ROLE?"role":"app_user";}
    private String binding(Kind kind){return kind==Kind.ROLE?"role_binding":"user_binding";}
    private long version(Kind kind){return db.jdbc.queryForObject("SELECT "+(kind==Kind.ROLE?"policy_version":"auth_version")+" FROM "+db.table(parent(kind))+" WHERE id=?",Long.class,id);}
    private void seed(Kind kind){db.jdbc.update("INSERT INTO "+db.table(parent(kind))+"(id,organization_id) VALUES(?,?)",id,ORG);}
    private void save(Kind kind,UUID org,long expected,String permission){
        var values=permission==null?List.<IamStore.Binding>of():List.of(new IamStore.Binding(permission,"ALLOW","ALL",List.of()));
        if(kind==Kind.ROLE)store.replaceRoleBindings(org,id,expected,values);else store.replaceUserOverrides(org,id,expected,values);
    }
    @ParameterizedTest @EnumSource(Kind.class) void competingAdministratorsCannotReplaceEachOthersBindings(Kind kind)throws Exception{
        seed(kind);var barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)){
            var futures=new ArrayList<Future<String>>();for(int i=0;i<2;i++){final int index=i;futures.add(pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{db.tx.executeWithoutResult(status->save(kind,ORG,0,"permission-"+index));return "saved";}catch(IamStore.PolicyVersionConflict error){return "conflict";}}));}
            assertThat(List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("saved","conflict");
        }
        assertThat(version(kind)).isEqualTo(1);assertThat(db.jdbc.queryForObject("SELECT count(*) FROM "+db.table(binding(kind)),Long.class)).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(Kind.class) void failedReplacementRestoresOldBindingsAndVersion(Kind kind){
        seed(kind);db.tx.executeWithoutResult(status->save(kind,ORG,0,"original"));
        assertThatThrownBy(()->db.tx.execute(status->{save(kind,ORG,1,"injected-invalid");return null;})).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(version(kind)).isEqualTo(1);assertThat(db.jdbc.queryForObject("SELECT permission_code FROM "+db.table(binding(kind)),String.class)).isEqualTo("original");
    }
    @ParameterizedTest @EnumSource(Kind.class) void anotherOrganizationCannotClearBindings(Kind kind){
        seed(kind);db.tx.executeWithoutResult(status->save(kind,ORG,0,"original"));
        assertThatThrownBy(()->db.tx.execute(status->{save(kind,UUID.randomUUID(),1,null);return null;})).isInstanceOf(IamStore.PolicyVersionConflict.class);
        assertThat(version(kind)).isEqualTo(1);assertThat(db.jdbc.queryForObject("SELECT permission_code FROM "+db.table(binding(kind)),String.class)).isEqualTo("original");
    }
    @ParameterizedTest @EnumSource(Kind.class) void explicitClearConsumesOneVersionAndStaleReplayCannotRestorePermissions(Kind kind){
        seed(kind);db.tx.executeWithoutResult(status->save(kind,ORG,0,"original"));db.tx.executeWithoutResult(status->save(kind,ORG,1,null));
        assertThatThrownBy(()->db.tx.execute(status->{save(kind,ORG,1,"stale");return null;})).isInstanceOf(IamStore.PolicyVersionConflict.class);
        assertThat(version(kind)).isEqualTo(2);assertThat(db.jdbc.queryForObject("SELECT count(*) FROM "+db.table(binding(kind)),Long.class)).isZero();
    }
}
