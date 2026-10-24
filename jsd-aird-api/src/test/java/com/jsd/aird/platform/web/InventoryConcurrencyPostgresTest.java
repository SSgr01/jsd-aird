package com.jsd.aird.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.inventory.application.InventoryService;
import com.jsd.aird.mfg.inventory.domain.InventoryModels.*;
import com.jsd.aird.mfg.inventory.infrastructure.JdbcInventoryRepository;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.quality.application.port.QualityDataStore;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST",matches="true")
class InventoryConcurrencyPostgresTest {
    private static IsolatedPostgres db;
    private static JdbcInventoryRepository repository;
    private InventoryService service;
    private AuditLogFacade audit;
    private static final UUID ORG=UUID.randomUUID(),USER=UUID.randomUUID(),PRODUCT=UUID.randomUUID();
    @BeforeAll static void setup() {
        db=new IsolatedPostgres(Map.of("mfg.inventory_product","product","mfg.inventory_stock_balance","balance","mfg.inventory_policy","policy",
            "mfg.inventory_transaction","movement","mfg.inventory_sample_dispatch","sample","mfg.inventory_shipment","shipment","iam.app_user","app_user","mdm.business_partner","customer"));
        db.table("product","id uuid PRIMARY KEY,organization_id uuid,code text,name text,category text,inventory_scope text,status text DEFAULT 'ACTIVE',updated_at timestamptz DEFAULT now()");
        db.table("app_user","id uuid PRIMARY KEY,display_name text");
        db.table("customer","id uuid PRIMARY KEY,name text,status text");
        db.table("balance","id uuid PRIMARY KEY,organization_id uuid,product_id uuid,inventory_scope text,quantity_kg numeric DEFAULT 0,version bigint DEFAULT 0,updated_at timestamptz DEFAULT now(),UNIQUE(organization_id,product_id,inventory_scope)");
        db.table("policy","id uuid PRIMARY KEY,organization_id uuid,product_id uuid,inventory_scope text,package_kg numeric,low_stock_kg numeric,created_by uuid,updated_by uuid,updated_at timestamptz DEFAULT now(),UNIQUE(organization_id,product_id,inventory_scope)");
        db.table("movement","id uuid PRIMARY KEY,organization_id uuid,product_id uuid,inventory_scope text,direction text,reason text,quantity_kg numeric,before_kg numeric,after_kg numeric,business_date date,document_no text,business_type text,business_id uuid,reversal_of uuid UNIQUE,idempotency_key text,note text,actor_id uuid,actor_name text,created_at timestamptz DEFAULT now(),UNIQUE(organization_id,idempotency_key),UNIQUE(organization_id,document_no,business_type)");
        db.table("shipment","id uuid PRIMARY KEY,organization_id uuid,shipment_no text,business_date date,product_id uuid,inventory_scope text,quantity_kg numeric,customer_id uuid,customer_name text,sender_name text,note text,inventory_transaction_id uuid,actor_id uuid,created_at timestamptz DEFAULT now()");
        db.table("sample","id uuid PRIMARY KEY,organization_id uuid,dispatch_no text,business_date date,product_id uuid,inventory_scope text,quantity_kg numeric,customer_id uuid,customer_name text,recipient_name text,recipient_phone text,sample_address text,courier_company text,tracking_no text,client_contact text,client_contact_phone text,sender_name text,customer_requirement text,customer_feedback text,inventory_transaction_id uuid,actor_id uuid,created_at timestamptz DEFAULT now()");
        repository=new JdbcInventoryRepository(db.jdbc);
    }
    @AfterAll static void cleanup(){if(db!=null)db.close();}
    @BeforeEach void seed() {
        db.clear(); ActorContext.set(new Actor(ORG,USER,"test"));
        db.jdbc.update("INSERT INTO "+db.table("product")+"(id,organization_id,code,name,category,inventory_scope) VALUES(?,?,'P','product','test','RND')",PRODUCT,ORG);
        db.jdbc.update("INSERT INTO "+db.table("app_user")+" VALUES(?,'tester')",USER);
        db.jdbc.update("INSERT INTO "+db.table("customer")+" VALUES(?,'customer','ACTIVE')",USER);
        audit=mock(AuditLogFacade.class); service=new InventoryService(repository,audit,new ObjectMapper().findAndRegisterModules(),mock(QualityDataStore.class));
    }
    @AfterEach void clearActor(){ActorContext.clear();}
    private Movement movement(Direction direction,int quantity,String number){return new Movement(PRODUCT,Scope.RND,direction,"TEST",BigDecimal.valueOf(quantity),LocalDate.now(),number,"MANUAL",null,null,null);}
    private void stock(int quantity){db.tx.executeWithoutResult(status->{repository.lockBalance(ORG,PRODUCT,Scope.RND,USER);repository.updateBalance(ORG,PRODUCT,Scope.RND,BigDecimal.valueOf(quantity));});}
    private BigDecimal quantity(){return db.jdbc.queryForObject("SELECT quantity_kg FROM "+db.table("balance"),BigDecimal.class);}
    private long movements(){return db.jdbc.queryForObject("SELECT count(*) FROM "+db.table("movement"),Long.class);}
    private List<String> race(java.util.function.IntConsumer action) throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<Future<String>>();
            for(int i=0;i<2;i++){final int index=i;futures.add(pool.submit(()->{ActorContext.set(new Actor(ORG,USER,"test"));try{barrier.await(5,TimeUnit.SECONDS);db.tx.executeWithoutResult(status->action.accept(index));return "saved";}catch(ApiException error){assertThat(error.errorCode()).isIn(ApiErrorCode.RESOURCE_CONFLICT,ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT);return "conflict";}finally{ActorContext.clear();}}));}
            return List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS));
        }
    }
    @Test void competingOutboundsCannotOverspendTheBalance() throws Exception {
        stock(10);assertThat(race(i->service.move(movement(Direction.OUTBOUND,7,"OUT-"+i),"out-"+i))).containsExactlyInAnyOrder("saved","conflict");
        assertThat(quantity()).isEqualByComparingTo("3");assertThat(movements()).isEqualTo(1);
    }
    @Test void concurrentDuplicateRequestsChangeStockAtMostOnce() throws Exception {
        stock(10);var outcomes=race(i->service.move(movement(Direction.INBOUND,2,"DUP"),"same-key"));
        assertThat(outcomes).contains("saved");assertThat(quantity()).isEqualByComparingTo("12");assertThat(movements()).isEqualTo(1);
        var replay=db.tx.execute(status->service.move(movement(Direction.INBOUND,2,"DUP"),"same-key"));
        assertThat(replay).isNotNull();assertThat(quantity()).isEqualByComparingTo("12");
    }
    @Test void twoReversalsCannotRestoreOrDeductStockTwice() throws Exception {
        var original=db.tx.execute(status->service.move(movement(Direction.INBOUND,10,"IN"),"in"));
        assertThat(race(i->service.reverse(original.id(),"reverse","reverse-"+i,original.getRevision()))).containsExactlyInAnyOrder("saved","conflict");
        assertThat(quantity()).isEqualByComparingTo("0");assertThat(movements()).isEqualTo(2);
    }
    @Test void editAndReverseCannotBothConsumeTheSameTransactionRevision() throws Exception {
        var original=db.tx.execute(status->service.move(movement(Direction.INBOUND,10,"IN"),"in"));
        assertThat(race(i->{if(i==0)service.reverse(original.id(),"reverse","reverse",original.getRevision());else service.updateTransaction(original.id(),new InventoryService.TransactionUpdateInput(original.getRevision(),LocalDate.now(),"EDITED","TEST","changed",null));}))
            .containsExactlyInAnyOrder("saved","conflict");
        assertThat(movements()).isBetween(1L,2L);
    }
    @Test void concurrentPolicyUpdatesRejectTheSecondOriginalRevision() throws Exception {
        stock(0);var original=service.balance(PRODUCT,Scope.RND).getPolicyRevision();
        assertThat(race(i->service.updatePolicy(PRODUCT,Scope.RND,new InventoryService.PolicyInput(original,null,BigDecimal.valueOf(i+1)))))
            .containsExactlyInAnyOrder("saved","conflict");
    }
    @Test void concurrentShipmentFormsCannotOverwriteEachOther() throws Exception {
        var id=UUID.randomUUID();db.jdbc.update("INSERT INTO "+db.table("shipment")+"(id,organization_id,product_id,inventory_scope,shipment_no,business_date,quantity_kg,actor_id) VALUES(?,?,?,'RND','SHIP',current_date,1,?)",id,ORG,PRODUCT,USER);
        var original=repository.findShipment(ORG,id).orElseThrow().getRevision();
        assertThat(race(i->service.updateShipment(id,new InventoryService.ShipmentUpdateInput(original,"SHIP",LocalDate.now(),USER,"sender","edit-"+i))))
            .containsExactlyInAnyOrder("saved","conflict");
    }
    @Test void concurrentSampleFormsCannotOverwriteEachOther() throws Exception {
        var id=UUID.randomUUID();db.jdbc.update("INSERT INTO "+db.table("sample")+"(id,organization_id,product_id,inventory_scope,dispatch_no,business_date,quantity_kg,actor_id) VALUES(?,?,?,'RND','SAMPLE',current_date,1,?)",id,ORG,PRODUCT,USER);
        var original=repository.findSample(ORG,id).orElseThrow().getRevision();
        assertThat(race(i->service.updateSample(id,new InventoryService.SampleUpdateInput(original,"SAMPLE",LocalDate.now(),USER,"recipient","123",null,null,null,null,null,null,null,"edit-"+i))))
            .containsExactlyInAnyOrder("saved","conflict");
    }
    @Test void failureAfterLedgerWriteRollsBackLedgerAndBalanceTogether() {
        stock(10);doThrow(new IllegalStateException("injected audit failure")).when(audit).append(any(),any(),any(),any(),any(),any());
        assertThatThrownBy(()->db.tx.execute(status->service.move(movement(Direction.OUTBOUND,3,"ROLLBACK"),"rollback"))).isInstanceOf(IllegalStateException.class);
        assertThat(quantity()).isEqualByComparingTo("10");assertThat(movements()).isZero();
    }
    @Test void foreignOrganizationCannotReadOrEditTheOriginalTransaction() {
        var original=db.tx.execute(status->service.move(movement(Direction.INBOUND,10,"IN"),"in"));
        ActorContext.set(new Actor(UUID.randomUUID(),USER,"test"));
        assertThatThrownBy(()->db.tx.execute(status->service.reverse(original.id(),"reverse","x",original.getRevision())))
            .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.NOT_FOUND));
        assertThat(movements()).isEqualTo(1);assertThat(quantity()).isEqualByComparingTo("10");
    }
}
