package com.jsd.aird.mfg.inventory.infrastructure;

import com.jsd.aird.mfg.inventory.domain.InventoryModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.mfg.inventory.application.port.InventoryRepository;

@Repository
public class JdbcInventoryRepository implements InventoryRepository {
    private final JdbcTemplate jdbc;
    public JdbcInventoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lockTransaction(UUID org,UUID id){
        if(jdbc.query("SELECT id FROM mfg.inventory_transaction WHERE organization_id=? AND id=? FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),org,id).isEmpty())
            throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.NOT_FOUND,"库存记录不存在");
    }
    public boolean productExists(UUID org, UUID productId, Scope scope) {
        return jdbc.queryForObject("SELECT count(*) FROM mfg.inventory_product WHERE organization_id=? AND id=? AND inventory_scope=? AND status='ACTIVE'", Integer.class, org, productId, scope.name()) > 0;
    }

    public List<ProductOption> productOptions(UUID org,Scope scope,String keyword){var sql=new StringBuilder("SELECT id,code,name,category,status FROM mfg.inventory_product WHERE organization_id=? AND status='ACTIVE'");var args=new ArrayList<Object>();args.add(org);if(scope!=null){sql.append(" AND inventory_scope=?");args.add(scope.name());}if(keyword!=null&&!keyword.isBlank()){sql.append(" AND (lower(code) LIKE ? OR lower(name) LIKE ? OR lower(category) LIKE ?)");var q="%"+keyword.trim().toLowerCase()+"%";args.add(q);args.add(q);args.add(q);}sql.append(" ORDER BY name LIMIT 500");return jdbc.query(sql.toString(),(rs,n)->new ProductOption(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getString("category"),rs.getString("status")),args.toArray());}

    public ProductOption createProduct(UUID org,String name,Scope scope,UUID actor){var id=UUID.randomUUID();var code=(scope==Scope.RND?"RD":"PD")+"-"+id.toString().substring(0,8).toUpperCase();var category=scope==Scope.RND?"研发产品":"生产产品";jdbc.update("INSERT INTO mfg.inventory_product(organization_id,id,code,name,category,inventory_scope,created_by) VALUES(?,?,?,?,?,?,?)",org,id,code,name,category,scope.name(),actor);return new ProductOption(id,code,name,category,"ACTIVE");}
    public List<ProductOption> productOptionsExact(UUID org,Scope scope,String name){return jdbc.query("SELECT id,code,name,category,status FROM mfg.inventory_product WHERE organization_id=? AND inventory_scope=? AND status='ACTIVE' AND (name=? OR code=?)",(rs,n)->new ProductOption(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getString("category"),rs.getString("status")),org,scope.name(),name,name);}

    public BalancePage balances(UUID org, String keyword, Scope scope, AlertStatus alert, int page, int size, String sort) {
        var where = new StringBuilder(" WHERE p.organization_id=? AND p.status='ACTIVE'"); var args = new ArrayList<Object>(); args.add(org);
        if (keyword != null && !keyword.isBlank()) { where.append(" AND (lower(p.name) LIKE ? OR lower(p.code) LIKE ? OR lower(p.category) LIKE ?)"); var q="%"+keyword.trim().toLowerCase()+"%"; args.add(q);args.add(q);args.add(q); }
        if (scope != null) { where.append(" AND p.inventory_scope = ?"); args.add(scope.name()); }
        var base = """
            FROM mfg.inventory_product p
            LEFT JOIN mfg.inventory_stock_balance b ON b.organization_id=p.organization_id AND b.product_id=p.id AND b.inventory_scope=p.inventory_scope
            LEFT JOIN mfg.inventory_policy ip ON ip.organization_id=p.organization_id AND ip.product_id=p.id AND ip.inventory_scope=p.inventory_scope
            """;
        var allArgs = new ArrayList<Object>(args);
        var alertSql = "CASE WHEN coalesce(b.quantity_kg,0)=0 THEN 'OUT_OF_STOCK' WHEN coalesce(b.quantity_kg,0)<coalesce(ip.low_stock_kg,0) THEN 'LOW_STOCK' ELSE 'NORMAL' END";
        if (alert != null) { where.append(" AND ").append(alertSql).append(" = ?"); allArgs.add(alert.name()); }
        long total = jdbc.queryForObject("SELECT count(*) "+base+where, Long.class, allArgs.toArray());
        var orderBy = "p.name";
        if (sort != null && !sort.isBlank()) {
            var parts = sort.split(",", 2);
            var col = switch (parts[0].trim()) {
                case "updatedAt" -> "updated_at";
                case "quantityKg" -> "quantity_kg";
                default -> "p.name";
            };
            var dir = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()) ? " ASC" : " DESC";
            orderBy = col + dir;
        }
        var pageArgs = new ArrayList<>(allArgs); pageArgs.add(size); pageArgs.add((page-1)*size);
        var items = jdbc.query("SELECT p.id,p.code,p.name,p.category,p.inventory_scope scope,coalesce(b.quantity_kg,0) quantity_kg,ip.package_kg,coalesce(ip.low_stock_kg,0) low_stock_kg,"+alertSql+" alert_status,coalesce(b.version,0) version,coalesce(b.updated_at,p.updated_at) updated_at "+base+where+" ORDER BY "+orderBy+" LIMIT ? OFFSET ?",
                (rs,n)->new Balance(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getString("category"),Scope.valueOf(rs.getString("scope")),rs.getBigDecimal("quantity_kg"),rs.getBigDecimal("package_kg"),rs.getBigDecimal("low_stock_kg"),AlertStatus.valueOf(rs.getString("alert_status")),rs.getLong("version"),instant(rs.getTimestamp("updated_at"))), pageArgs.toArray());
        var summaryWhere=new StringBuilder(" WHERE p.organization_id=? AND p.status='ACTIVE'");var summaryArgs=new ArrayList<Object>();summaryArgs.add(org);if(scope!=null){summaryWhere.append(" AND p.inventory_scope=?");summaryArgs.add(scope.name());}
        var summary = jdbc.queryForObject("SELECT coalesce(sum(coalesce(b.quantity_kg,0)),0) total, count(*) product_count, count(*) FILTER(WHERE coalesce(b.quantity_kg,0)>0 AND coalesce(b.quantity_kg,0)<coalesce(ip.low_stock_kg,0)) low_count, count(*) FILTER(WHERE coalesce(b.quantity_kg,0)=0) zero_count "+base+summaryWhere,
                (rs,n)->new Summary(rs.getBigDecimal("total"),rs.getLong("product_count"),rs.getLong("low_count"),rs.getLong("zero_count")),summaryArgs.toArray());
        return new BalancePage(items,page,size,total,(total+size-1)/size,summary);
    }

    public Optional<Balance> balance(UUID org, UUID productId, Scope scope) {
        var result=balances(org,null,scope,null,1,10000,null).items().stream().filter(x->x.productId().equals(productId)).findFirst(); return result;
    }

    public void upsertPolicy(UUID org,UUID productId,Scope scope,BigDecimal packageKg,BigDecimal lowStockKg,UUID actor){jdbc.update("""
        INSERT INTO mfg.inventory_policy(id,organization_id,product_id,inventory_scope,package_kg,low_stock_kg,created_by,updated_by)
        VALUES(?,?,?,?,?,?,?,?)
        ON CONFLICT(organization_id,product_id,inventory_scope) DO UPDATE SET package_kg=excluded.package_kg,low_stock_kg=excluded.low_stock_kg,updated_by=excluded.updated_by,updated_at=now()
        """,UUID.randomUUID(),org,productId,scope.name(),packageKg,lowStockKg,actor,actor);}

    public BigDecimal lockBalance(UUID org, UUID product, Scope scope, UUID actor) {
        jdbc.update("INSERT INTO mfg.inventory_stock_balance(id,organization_id,product_id,inventory_scope) VALUES(?,?,?,?) ON CONFLICT(organization_id,product_id,inventory_scope) DO NOTHING",UUID.randomUUID(),org,product,scope.name());
        return jdbc.queryForObject("SELECT quantity_kg FROM mfg.inventory_stock_balance WHERE organization_id=? AND product_id=? AND inventory_scope=? FOR UPDATE",BigDecimal.class,org,product,scope.name());
    }

    public void updateBalance(UUID org, UUID product, Scope scope, BigDecimal quantity) {
        jdbc.update("UPDATE mfg.inventory_stock_balance SET quantity_kg=?,version=version+1,updated_at=now() WHERE organization_id=? AND product_id=? AND inventory_scope=?",quantity,org,product,scope.name());
    }

    public void insertTransaction(UUID org, Transaction tx, String idempotencyKey) {
        jdbc.update("""
            INSERT INTO mfg.inventory_transaction(id,organization_id,product_id,inventory_scope,direction,reason,quantity_kg,before_kg,after_kg,business_date,document_no,business_type,business_id,reversal_of,idempotency_key,note,actor_id,actor_name)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,tx.id(),org,tx.productId(),tx.scope().name(),tx.direction().name(),tx.reason(),tx.quantityKg(),tx.beforeKg(),tx.afterKg(),tx.businessDate(),tx.documentNo(),tx.businessType(),tx.businessId(),tx.reversalOf(),idempotencyKey,tx.note(),tx.actorId(),tx.actorName());
    }
    public int updateTransaction(UUID org,UUID id,LocalDate businessDate,String documentNo,String reason,String note,String actorName){
        return jdbc.update("UPDATE mfg.inventory_transaction SET business_date=?,document_no=?,reason=?,note=?,actor_name=? WHERE organization_id=? AND id=?",businessDate,documentNo,reason,note,actorName,org,id);
    }

    public List<String> transactionReasons(UUID org,Scope scope){
        if(scope==null)return jdbc.queryForList("SELECT reason FROM mfg.inventory_transaction WHERE organization_id=? GROUP BY reason ORDER BY max(created_at) DESC",String.class,org);
        return jdbc.queryForList("SELECT reason FROM mfg.inventory_transaction WHERE organization_id=? AND inventory_scope=? GROUP BY reason ORDER BY max(created_at) DESC",String.class,org,scope.name());
    }

    public Optional<Transaction> findTransaction(UUID org, UUID id) {
        return jdbc.query(txSelect()+" WHERE t.organization_id=? AND t.id=?",this::mapTx,org,id).stream().findFirst();
    }
    public Optional<Transaction> findByIdempotency(UUID org,String key) {
        if(key==null||key.isBlank()) return Optional.empty(); return jdbc.query(txSelect()+" WHERE t.organization_id=? AND t.idempotency_key=?",this::mapTx,org,key).stream().findFirst();
    }
    public Optional<Transaction> findByDocumentNo(UUID org,String documentNo,String businessType) {
        if(documentNo==null||documentNo.isBlank()||businessType==null||businessType.isBlank()) return Optional.empty();
        return jdbc.query(txSelect()+" WHERE t.organization_id=? AND t.document_no=? AND t.business_type=?",this::mapTx,org,documentNo,businessType).stream().findFirst();
    }
    public Page<Transaction> transactions(UUID org, Scope scope, String keyword, String direction, String reason, int page, int size) {
        var where=new StringBuilder(" WHERE t.organization_id=?");var args=new ArrayList<Object>();args.add(org);
        if(scope!=null){where.append(" AND t.inventory_scope=?");args.add(scope.name());} if(direction!=null&&!direction.isBlank()){where.append(" AND t.direction=?");args.add(direction);}
        if(reason!=null&&!reason.isBlank()){where.append(" AND t.reason=?");args.add(reason);} if(keyword!=null&&!keyword.isBlank()){where.append(" AND (lower(t.document_no) LIKE ? OR lower(p.name) LIKE ? OR lower(coalesce(t.note,'')) LIKE ?)");var q="%"+keyword.toLowerCase()+"%";args.add(q);args.add(q);args.add(q);}
        long total=jdbc.queryForObject("SELECT count(*) FROM mfg.inventory_transaction t JOIN mfg.inventory_product p ON p.organization_id=t.organization_id AND p.id=t.product_id"+where,Long.class,args.toArray());
        var pa=new ArrayList<>(args);pa.add(size);pa.add((page-1)*size);var items=jdbc.query(txSelect()+where+" ORDER BY t.business_date DESC,t.created_at DESC LIMIT ? OFFSET ?",this::mapTx,pa.toArray());
        return new Page<>(items,page,size,total,(total+size-1)/size);
    }

    public void insertSample(UUID org, Sample x, UUID actor) { jdbc.update("INSERT INTO mfg.inventory_sample_dispatch(id,organization_id,dispatch_no,business_date,product_id,specification,inventory_scope,quantity_kg,package_quantity,customer_id,customer_name,recipient_name,recipient_phone,sample_address,courier_company,tracking_no,client_contact,client_contact_phone,sender_name,customer_requirement,customer_feedback,status,inventory_transaction_id,actor_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",x.id(),org,x.dispatchNo(),x.businessDate(),x.productId(),x.specification(),x.scope().name(),x.quantityKg(),x.packageQuantity(),x.customerId(),x.customerName(),x.recipientName(),x.recipientPhone(),x.sampleAddress(),x.courierCompany(),x.trackingNo(),x.clientContact(),x.clientContactPhone(),x.senderName(),x.customerRequirement(),x.customerFeedback(),x.status(),x.transactionId(),actor); }
    public void insertShipment(UUID org, Shipment x, UUID actor) { jdbc.update("INSERT INTO mfg.inventory_shipment(id,organization_id,shipment_no,product_id,inventory_scope,quantity_kg,customer_id,customer_name,sender_name,batch_no,specification,package_quantity,total_quantity_kg,recipient_name,recipient_phone,shipping_address,courier_company,tracking_no,note,coa_no,status,inventory_transaction_id,actor_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",x.id(),org,x.shipmentNo(),x.productId(),x.scope().name(),x.quantityKg(),x.customerId(),x.customerName(),x.senderName(),x.batchNo(),x.specification(),x.packageQuantity(),x.totalQuantityKg(),x.recipientName(),x.recipientPhone(),x.shippingAddress(),x.courierCompany(),x.trackingNo(),x.note(),x.coaNo(),x.status(),x.transactionId(),actor); }
    public Optional<String> activeCustomerName(UUID customerId){return jdbc.query("SELECT name FROM mdm.business_partner WHERE id=? AND status='ACTIVE'",(rs,n)->rs.getString(1),customerId).stream().findFirst();}
    public Optional<RecipientPreference> recipientPreference(UUID customerId){return jdbc.query("SELECT recipient_name,recipient_phone,sample_address FROM mfg.sample_recipient_preference WHERE customer_id=?",(rs,n)->new RecipientPreference(rs.getString(1),rs.getString(2),rs.getString(3)),customerId).stream().findFirst();}
    public Optional<RecipientPreference> latestSampleRecipient(UUID customerId){return jdbc.query("SELECT recipient_name,recipient_phone,sample_address FROM mfg.inventory_sample_dispatch WHERE customer_id=? AND recipient_name IS NOT NULL AND recipient_phone IS NOT NULL AND sample_address IS NOT NULL AND btrim(sample_address)<>'' ORDER BY created_at DESC,id DESC LIMIT 1",(rs,n)->new RecipientPreference(rs.getString(1),rs.getString(2),rs.getString(3)),customerId).stream().findFirst();}
    public void upsertRecipientPreference(UUID customerId,String recipientName,String recipientPhone,String sampleAddress){jdbc.update("INSERT INTO mfg.sample_recipient_preference(customer_id,recipient_name,recipient_phone,sample_address) VALUES(?,?,?,?) ON CONFLICT(customer_id) DO UPDATE SET recipient_name=excluded.recipient_name,recipient_phone=excluded.recipient_phone,sample_address=excluded.sample_address,updated_at=now()",customerId,recipientName,recipientPhone,sampleAddress);}
    public Page<Sample> samples(UUID org,String keyword,String status,LocalDate startDate,LocalDate endDate,int page,int size){
        var from=" FROM mfg.inventory_sample_dispatch s JOIN mfg.inventory_product p ON p.organization_id=s.organization_id AND p.id=s.product_id JOIN iam.app_user u ON u.id=s.actor_id";
        var where=new StringBuilder(" WHERE s.organization_id=?");var args=new ArrayList<Object>();args.add(org);
        if(keyword!=null&&!keyword.isBlank()){
            where.append(" AND (lower(s.dispatch_no) LIKE ? OR lower(p.code) LIKE ? OR lower(p.name) LIKE ? OR lower(coalesce(s.customer_name,'')) LIKE ? OR lower(coalesce(s.recipient_name,'')) LIKE ? OR lower(coalesce(s.recipient_phone,'')) LIKE ? OR lower(coalesce(s.sample_address,'')) LIKE ? OR lower(coalesce(s.courier_company,'')) LIKE ? OR lower(coalesce(s.tracking_no,'')) LIKE ? OR lower(coalesce(s.sender_name,'')) LIKE ? OR lower(coalesce(s.client_contact,'')) LIKE ? OR lower(coalesce(s.client_contact_phone,'')) LIKE ? OR lower(coalesce(s.customer_requirement,'')) LIKE ? OR lower(coalesce(s.customer_feedback,'')) LIKE ?)");
            var q="%"+keyword.trim().toLowerCase()+"%";for(int i=0;i<14;i++)args.add(q);
        }
        if(status!=null&&!status.isBlank()){where.append(" AND s.status=?");args.add(status);}
        if(startDate!=null){where.append(" AND s.business_date>=?");args.add(startDate);}
        if(endDate!=null){where.append(" AND s.business_date<=?");args.add(endDate);}
        long total=jdbc.queryForObject("SELECT count(*)"+from+where,Long.class,args.toArray());
        var pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((page-1)*size);
        var list=jdbc.query("SELECT s.*,p.name product_name,u.display_name operator"+from+where+" ORDER BY s.business_date DESC,s.created_at DESC LIMIT ? OFFSET ?",this::mapSample,pageArgs.toArray());
        return new Page<>(list,page,size,total,(total+size-1)/size);
    }
    public Page<Shipment> shipments(UUID org,String keyword,int page,int size){
        var from=" FROM mfg.inventory_shipment s JOIN mfg.inventory_shipment_header h ON h.organization_id=s.organization_id AND h.shipment_no=s.shipment_no JOIN mfg.inventory_product p ON p.organization_id=s.organization_id AND p.id=s.product_id JOIN iam.app_user u ON u.id=s.actor_id";
        var where=new StringBuilder(" WHERE s.organization_id=?");var args=new ArrayList<Object>();args.add(org);
        if(keyword!=null&&!keyword.isBlank()){
            where.append(" AND (lower(s.shipment_no) LIKE ? OR lower(p.code) LIKE ? OR lower(p.name) LIKE ? OR lower(coalesce(s.customer_name,'')) LIKE ? OR lower(coalesce(s.sender_name,'')) LIKE ? OR lower(coalesce(s.batch_no,'')) LIKE ? OR lower(coalesce(s.recipient_name,'')) LIKE ? OR lower(coalesce(s.courier_company,'')) LIKE ? OR lower(coalesce(s.note,'')) LIKE ?)");
            var q="%"+keyword.trim().toLowerCase()+"%";for(int i=0;i<9;i++)args.add(q);
        }
        long total=jdbc.queryForObject("SELECT count(*)"+from+where,Long.class,args.toArray());
        var pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((page-1)*size);
        var list=jdbc.query("SELECT s.*,h.business_date header_business_date,p.name product_name,u.display_name operator"+from+where+" ORDER BY h.business_date DESC,s.created_at DESC LIMIT ? OFFSET ?",this::mapShipment,pageArgs.toArray());
        return new Page<>(list,page,size,total,(total+size-1)/size);
    }
    public Page<Shipment> shipmentDetails(UUID org,String shipmentNo,int page,int size){
        var from=" FROM mfg.inventory_shipment s JOIN mfg.inventory_shipment_header h ON h.organization_id=s.organization_id AND h.shipment_no=s.shipment_no JOIN mfg.inventory_product p ON p.organization_id=s.organization_id AND p.id=s.product_id JOIN iam.app_user u ON u.id=s.actor_id WHERE s.organization_id=? AND s.shipment_no=?";
        long total=jdbc.queryForObject("SELECT count(*)"+from,Long.class,org,shipmentNo);
        var items=jdbc.query("SELECT s.*,h.business_date header_business_date,p.name product_name,u.display_name operator"+from+" ORDER BY s.created_at,s.id LIMIT ? OFFSET ?",this::mapShipment,org,shipmentNo,size,(page-1)*size);
        return new Page<>(items,page,size,total,(total+size-1)/size);
    }
    public java.util.List<UUID> pendingShipmentIds(UUID org,String shipmentNo){return jdbc.queryForList(
            "SELECT id FROM mfg.inventory_shipment WHERE organization_id=? AND shipment_no=? AND status='PENDING' ORDER BY id",
            UUID.class,org,shipmentNo);}
    public Page<ShipmentHeader> shipmentHeaders(UUID org,String keyword,String status,LocalDate startDate,LocalDate endDate,int page,int size){
        var where=new StringBuilder(" WHERE h.organization_id=?");var args=new ArrayList<Object>();args.add(org);
        if(keyword!=null&&!keyword.isBlank()){where.append(" AND lower(h.shipment_no) LIKE ?");args.add("%"+keyword.trim().toLowerCase()+"%");}
        if("COMPLETED".equals(status)){
            where.append(" AND EXISTS (SELECT 1 FROM mfg.inventory_shipment sx WHERE sx.organization_id=h.organization_id AND sx.shipment_no=h.shipment_no)");
            where.append(" AND NOT EXISTS (SELECT 1 FROM mfg.inventory_shipment sx WHERE sx.organization_id=h.organization_id AND sx.shipment_no=h.shipment_no AND sx.status<>'COMPLETED')");
        }else if("PENDING".equals(status)){
            where.append(" AND (NOT EXISTS (SELECT 1 FROM mfg.inventory_shipment sx WHERE sx.organization_id=h.organization_id AND sx.shipment_no=h.shipment_no)");
            where.append(" OR EXISTS (SELECT 1 FROM mfg.inventory_shipment sx WHERE sx.organization_id=h.organization_id AND sx.shipment_no=h.shipment_no AND sx.status<>'COMPLETED'))");
        }
        if(startDate!=null){where.append(" AND h.business_date>=?");args.add(startDate);}
        if(endDate!=null){where.append(" AND h.business_date<=?");args.add(endDate);}
        long total=jdbc.queryForObject("SELECT count(*) FROM mfg.inventory_shipment_header h"+where,Long.class,args.toArray());
        var pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((page-1)*size);
        var items=jdbc.query("SELECT h.id,h.shipment_no,h.business_date,h.note,count(s.id) detail_count,"+
                "count(s.id) FILTER (WHERE s.status='COMPLETED') completed_count,"+
                "coalesce(sum(s.quantity_kg),0) total_quantity_kg FROM mfg.inventory_shipment_header h "+
                "LEFT JOIN mfg.inventory_shipment s ON s.organization_id=h.organization_id AND s.shipment_no=h.shipment_no"+
                where+" GROUP BY h.id,h.shipment_no,h.business_date,h.note,h.created_at ORDER BY h.created_at DESC,h.id DESC LIMIT ? OFFSET ?",
                this::mapShipmentHeader,pageArgs.toArray());
        return new Page<>(items,page,size,total,(total+size-1)/size);
    }
    public Optional<ShipmentHeader> shipmentHeader(UUID org,String shipmentNo){return jdbc.query(
            "SELECT h.id,h.shipment_no,h.business_date,h.note,count(s.id) detail_count,"+
            "count(s.id) FILTER (WHERE s.status='COMPLETED') completed_count,"+
            "coalesce(sum(s.quantity_kg),0) total_quantity_kg FROM mfg.inventory_shipment_header h "+
            "LEFT JOIN mfg.inventory_shipment s ON s.organization_id=h.organization_id AND s.shipment_no=h.shipment_no "+
            "WHERE h.organization_id=? AND h.shipment_no=? GROUP BY h.id,h.shipment_no,h.business_date,h.note",
            this::mapShipmentHeader,org,shipmentNo).stream().findFirst();}
    public ShipmentHeader insertShipmentHeader(UUID org,String shipmentNo,LocalDate businessDate,String note,UUID actor){
        jdbc.update("INSERT INTO mfg.inventory_shipment_header(id,organization_id,shipment_no,business_date,note,actor_id) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID(),org,shipmentNo,businessDate,note,actor);
        return shipmentHeader(org,shipmentNo).orElseThrow();
    }
    public int updateShipmentHeaderDate(UUID org,String shipmentNo,LocalDate businessDate){
        var count=jdbc.update("UPDATE mfg.inventory_shipment_header SET business_date=? WHERE organization_id=? AND shipment_no=?",
                businessDate,org,shipmentNo);
        return count;
    }
    public int updateShipmentHeader(UUID org,String shipmentNo,LocalDate businessDate,String note){
        return jdbc.update("UPDATE mfg.inventory_shipment_header SET business_date=?,note=? WHERE organization_id=? AND shipment_no=?",
                businessDate,note,org,shipmentNo);
    }
    public int deletePendingShipmentDetailsByHeader(UUID org,String shipmentNo){
        return jdbc.update("DELETE FROM mfg.inventory_shipment WHERE organization_id=? AND shipment_no=? AND status='PENDING' AND inventory_transaction_id IS NULL",
                org,shipmentNo);
    }
    public int deleteShipmentHeader(UUID org,String shipmentNo){
        return jdbc.update("DELETE FROM mfg.inventory_shipment_header WHERE organization_id=? AND shipment_no=?",org,shipmentNo);
    }
    public int deletePendingSample(UUID org,UUID id){
        return jdbc.update("DELETE FROM mfg.inventory_sample_dispatch WHERE organization_id=? AND id=? AND status='PENDING' AND inventory_transaction_id IS NULL",org,id);
    }
    private ShipmentHeader mapShipmentHeader(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new ShipmentHeader(
            rs.getObject("id",UUID.class),rs.getString("shipment_no"),rs.getObject("business_date",LocalDate.class),
            rs.getString("note"),rs.getLong("detail_count"),rs.getLong("completed_count"),rs.getBigDecimal("total_quantity_kg"));}
    public Optional<Sample> sampleByTransaction(UUID org,UUID tx){return jdbc.query("SELECT s.*,p.name product_name,u.display_name operator FROM mfg.inventory_sample_dispatch s JOIN mfg.inventory_product p ON p.organization_id=s.organization_id AND p.id=s.product_id JOIN iam.app_user u ON u.id=s.actor_id WHERE s.organization_id=? AND s.inventory_transaction_id=?",this::mapSample,org,tx).stream().findFirst();}
    public Optional<Shipment> shipmentByTransaction(UUID org,UUID tx){return jdbc.query("SELECT s.*,h.business_date header_business_date,p.name product_name,u.display_name operator FROM mfg.inventory_shipment s JOIN mfg.inventory_shipment_header h ON h.organization_id=s.organization_id AND h.shipment_no=s.shipment_no JOIN mfg.inventory_product p ON p.organization_id=s.organization_id AND p.id=s.product_id JOIN iam.app_user u ON u.id=s.actor_id WHERE s.organization_id=? AND s.inventory_transaction_id=?",this::mapShipment,org,tx).stream().findFirst();}
    public Optional<Sample> findSample(UUID org,UUID id){return jdbc.query("SELECT s.*,p.name product_name,u.display_name operator FROM mfg.inventory_sample_dispatch s JOIN mfg.inventory_product p ON p.organization_id=s.organization_id AND p.id=s.product_id JOIN iam.app_user u ON u.id=s.actor_id WHERE s.organization_id=? AND s.id=?",this::mapSample,org,id).stream().findFirst();}
    public Optional<Sample> lockSample(UUID org,UUID id){return jdbc.query("SELECT s.*,p.name product_name,u.display_name operator FROM mfg.inventory_sample_dispatch s JOIN mfg.inventory_product p ON p.organization_id=s.organization_id AND p.id=s.product_id JOIN iam.app_user u ON u.id=s.actor_id WHERE s.organization_id=? AND s.id=? FOR UPDATE OF s",this::mapSample,org,id).stream().findFirst();}
    public Optional<Sample> findSampleByDispatchNo(UUID org,String dispatchNo){return jdbc.query("SELECT s.*,p.name product_name,u.display_name operator FROM mfg.inventory_sample_dispatch s JOIN mfg.inventory_product p ON p.organization_id=s.organization_id AND p.id=s.product_id JOIN iam.app_user u ON u.id=s.actor_id WHERE s.organization_id=? AND s.dispatch_no=?",this::mapSample,org,dispatchNo).stream().findFirst();}
    public int completeSample(UUID org,UUID id,UUID transactionId){return jdbc.update("UPDATE mfg.inventory_sample_dispatch SET status='COMPLETED',inventory_transaction_id=? WHERE organization_id=? AND id=? AND status='PENDING' AND inventory_transaction_id IS NULL",transactionId,org,id);}
    public Optional<Shipment> findShipment(UUID org,UUID id){return jdbc.query("SELECT s.*,h.business_date header_business_date,p.name product_name,u.display_name operator FROM mfg.inventory_shipment s JOIN mfg.inventory_shipment_header h ON h.organization_id=s.organization_id AND h.shipment_no=s.shipment_no JOIN mfg.inventory_product p ON p.organization_id=s.organization_id AND p.id=s.product_id JOIN iam.app_user u ON u.id=s.actor_id WHERE s.organization_id=? AND s.id=?",this::mapShipment,org,id).stream().findFirst();}
    public Optional<Shipment> lockShipment(UUID org,UUID id){return jdbc.query("SELECT s.*,h.business_date header_business_date,p.name product_name,u.display_name operator FROM mfg.inventory_shipment s JOIN mfg.inventory_shipment_header h ON h.organization_id=s.organization_id AND h.shipment_no=s.shipment_no JOIN mfg.inventory_product p ON p.organization_id=s.organization_id AND p.id=s.product_id JOIN iam.app_user u ON u.id=s.actor_id WHERE s.organization_id=? AND s.id=? FOR UPDATE OF s",this::mapShipment,org,id).stream().findFirst();}
    public Optional<Shipment> findShipmentByNo(UUID org,String shipmentNo){return jdbc.query("SELECT s.*,h.business_date header_business_date,p.name product_name,u.display_name operator FROM mfg.inventory_shipment s JOIN mfg.inventory_shipment_header h ON h.organization_id=s.organization_id AND h.shipment_no=s.shipment_no JOIN mfg.inventory_product p ON p.organization_id=s.organization_id AND p.id=s.product_id JOIN iam.app_user u ON u.id=s.actor_id WHERE s.organization_id=? AND s.shipment_no=? ORDER BY s.created_at LIMIT 1",this::mapShipment,org,shipmentNo).stream().findFirst();}
    public int completeShipment(UUID org,UUID id,UUID transactionId){return jdbc.update("UPDATE mfg.inventory_shipment SET status='COMPLETED',inventory_transaction_id=? WHERE organization_id=? AND id=? AND status='PENDING' AND inventory_transaction_id IS NULL",transactionId,org,id);}
    public int deletePendingShipment(UUID org,UUID id){return jdbc.update("DELETE FROM mfg.inventory_shipment WHERE organization_id=? AND id=? AND status='PENDING' AND inventory_transaction_id IS NULL",org,id);}
    public int updateSample(UUID org,UUID id,LocalDate businessDate,String dispatchNo,String specification,java.math.BigDecimal packageQuantity,UUID customerId,String customerName,String recipientName,String recipientPhone,String sampleAddress,String courierCompany,String trackingNo,String clientContact,String clientContactPhone,String senderName,String customerRequirement,String customerFeedback,String status,java.math.BigDecimal quantityKg){return jdbc.update("UPDATE mfg.inventory_sample_dispatch SET business_date=?,dispatch_no=?,specification=?,package_quantity=?,quantity_kg=?,customer_id=?,customer_name=?,recipient_name=?,recipient_phone=?,sample_address=?,courier_company=?,tracking_no=?,client_contact=?,client_contact_phone=?,sender_name=?,customer_requirement=?,customer_feedback=?,status=? WHERE organization_id=? AND id=?",businessDate,dispatchNo,specification,packageQuantity,quantityKg,customerId,customerName,recipientName,recipientPhone,sampleAddress,courierCompany,trackingNo,clientContact,clientContactPhone,senderName,customerRequirement,customerFeedback,status,org,id);}
    public int updateShipment(UUID org,UUID id,Shipment x){return jdbc.update("UPDATE mfg.inventory_shipment SET customer_id=?,customer_name=?,sender_name=?,batch_no=?,recipient_name=?,recipient_phone=?,shipping_address=?,courier_company=?,tracking_no=?,note=?,coa_no=? WHERE organization_id=? AND id=?",x.customerId(),x.customerName(),x.senderName(),x.batchNo(),x.recipientName(),x.recipientPhone(),x.shippingAddress(),x.courierCompany(),x.trackingNo(),x.note(),x.coaNo(),org,id);}

    public Optional<UUID> productIdByCode(UUID org,String code) { return jdbc.query("SELECT id FROM mfg.inventory_product WHERE organization_id=? AND code=? AND status='ACTIVE'",(rs,n)->rs.getObject(1,UUID.class),org,code).stream().findFirst(); }
    public void insertImport(UUID id,UUID org,String status,JsonNode rows,JsonNode errors,UUID actor){jdbc.update("INSERT INTO mfg.inventory_stock_import(id,organization_id,status,rows_jsonb,errors_jsonb,actor_id) VALUES(?,?,?,?::jsonb,?::jsonb,?)",id,org,status,rows.toString(),errors.toString(),actor);}
    public Optional<ImportData> findImport(UUID org,UUID id){return jdbc.query("SELECT id,status,rows_jsonb,errors_jsonb,created_at,committed_at FROM mfg.inventory_stock_import WHERE organization_id=? AND id=?",(rs,n)->new ImportData(rs.getObject("id",UUID.class),rs.getString("status"),rs.getString("rows_jsonb"),rs.getString("errors_jsonb"),instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("committed_at"))),org,id).stream().findFirst();}
    public int markImportCommitted(UUID org,UUID id){return jdbc.update("UPDATE mfg.inventory_stock_import SET status='COMMITTED',committed_at=now() WHERE organization_id=? AND id=? AND status='VALIDATED'",org,id);}

    private Sample mapSample(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new Sample(rs.getObject("id",UUID.class),rs.getString("dispatch_no"),rs.getObject("business_date",LocalDate.class),rs.getObject("product_id",UUID.class),rs.getString("product_name"),rs.getString("specification"),scope(rs.getString("inventory_scope")),rs.getBigDecimal("quantity_kg"),rs.getBigDecimal("package_quantity"),rs.getObject("customer_id",UUID.class),rs.getString("customer_name"),rs.getString("recipient_name"),rs.getString("recipient_phone"),rs.getString("sample_address"),rs.getString("courier_company"),rs.getString("tracking_no"),rs.getString("client_contact"),rs.getString("client_contact_phone"),rs.getString("sender_name"),rs.getString("customer_requirement"),rs.getString("customer_feedback"),rs.getString("status"),rs.getObject("inventory_transaction_id",UUID.class),rs.getString("operator"),instant(rs.getTimestamp("created_at")));}
    private Shipment mapShipment(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new Shipment(rs.getObject("id",UUID.class),rs.getString("shipment_no"),rs.getObject("header_business_date",LocalDate.class),rs.getObject("product_id",UUID.class),rs.getString("product_name"),scope(rs.getString("inventory_scope")),rs.getBigDecimal("quantity_kg"),rs.getObject("customer_id",UUID.class),rs.getString("customer_name"),rs.getString("sender_name"),rs.getString("batch_no"),rs.getString("specification"),rs.getBigDecimal("package_quantity"),rs.getBigDecimal("total_quantity_kg"),rs.getString("recipient_name"),rs.getString("recipient_phone"),rs.getString("shipping_address"),rs.getString("courier_company"),rs.getString("tracking_no"),rs.getString("note"),rs.getString("status"),rs.getObject("inventory_transaction_id",UUID.class),rs.getString("operator"),instant(rs.getTimestamp("created_at")),rs.getString("coa_no"));}
    private String txSelect(){return "SELECT t.*,p.code product_code,p.name product_name,p.category,coalesce(t.actor_name,u.display_name) actor_name,exists(SELECT 1 FROM mfg.inventory_transaction r WHERE r.reversal_of=t.id) reversed FROM mfg.inventory_transaction t JOIN mfg.inventory_product p ON p.organization_id=t.organization_id AND p.id=t.product_id JOIN iam.app_user u ON u.id=t.actor_id";}
    private Transaction mapTx(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new Transaction(rs.getObject("id",UUID.class),rs.getObject("product_id",UUID.class),rs.getString("product_code"),rs.getString("product_name"),rs.getString("category"),Scope.valueOf(rs.getString("inventory_scope")),Direction.valueOf(rs.getString("direction")),rs.getString("reason"),rs.getBigDecimal("quantity_kg"),rs.getBigDecimal("before_kg"),rs.getBigDecimal("after_kg"),rs.getObject("business_date",LocalDate.class),rs.getString("document_no"),rs.getString("business_type"),rs.getObject("business_id",UUID.class),rs.getObject("reversal_of",UUID.class),rs.getString("note"),rs.getObject("actor_id",UUID.class),rs.getString("actor_name"),instant(rs.getTimestamp("created_at")),rs.getBoolean("reversed"));}
    private static Instant instant(Timestamp t){return t==null?null:t.toInstant();}
    private static Scope scope(String value){return value==null||value.isBlank()?Scope.RND:Scope.valueOf(value);}
}
