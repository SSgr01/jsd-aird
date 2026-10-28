package com.jsd.aird.mfg.inventory.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.inventory.domain.InventoryModels.*;
import com.jsd.aird.mfg.inventory.application.port.InventoryRepository;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.quality.application.port.QualityDataStore;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.shared.security.Actor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class InventoryService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(InventoryService.class);
    private static final java.util.regex.Pattern CONSTRAINT_PATTERN=java.util.regex.Pattern.compile("constraint\\s+\"([^\"]+)\"");
    private final InventoryRepository repository; private final AuditLogFacade audit; private final ObjectMapper mapper; private final QualityDataStore qualityData;
    public InventoryService(InventoryRepository repository, AuditLogFacade audit, ObjectMapper mapper, QualityDataStore qualityData){this.repository=repository;this.audit=audit;this.mapper=mapper;this.qualityData=qualityData;}
    public BalancePage balances(String keyword,Scope scope,AlertStatus alert,int page,int size,String sort){return repository.balances(actor().organizationId(),keyword,scope,alert,Math.max(page,1),Math.min(Math.max(size,1),100),sort);}
    public java.util.List<ProductOption> productOptions(Scope scope,String keyword){return repository.productOptions(actor().organizationId(),scope,keyword);}
    @Transactional public ProductOption createProduct(ProductInput input){if(input==null||input.scope()==null)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"库存域不能为空");var a=actor();var name=required(input.name(),"产品名称");var product=repository.createProduct(a.organizationId(),name,input.scope(),a.userId());audit.append(a.organizationId(),a.userId(),"INVENTORY_PRODUCT_CREATED","INVENTORY_PRODUCT",product.id(),mapper.valueToTree(input));return product;}
    public Balance balance(UUID product,Scope scope){return repository.balance(actor().organizationId(),product,scope).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"库存产品不存在"));}
    @Transactional public Balance updatePolicy(UUID product,Scope scope,PolicyInput input){var a=actor();if(!repository.productExists(a.organizationId(),product,scope))throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"该库存域中产品不存在或已停用");if(input.lowStockKg()==null||input.lowStockKg().signum()<0||input.packageKg()!=null&&input.packageKg().signum()<=0)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"包装规格必须大于 0，预警阈值不能为负数");repository.lockBalance(a.organizationId(),product,scope,a.userId());com.jsd.aird.shared.json.RevisionToken.require(input.revision(),balance(product,scope).getPolicyRevision());repository.upsertPolicy(a.organizationId(),product,scope,input.packageKg(),input.lowStockKg(),a.userId());audit.append(a.organizationId(),a.userId(),"INVENTORY_POLICY_UPDATED","INVENTORY_POLICY",product,mapper.valueToTree(input));return balance(product,scope);}
    public Page<Transaction> transactions(Scope scope,String keyword,String direction,String reason,int page,int size){return repository.transactions(actor().organizationId(),scope,keyword,direction,reason,Math.max(page,1),Math.min(Math.max(size,1),100));}
    public java.util.List<String> transactionReasons(Scope scope){return repository.transactionReasons(actor().organizationId(),scope);}
    public Transaction transaction(UUID id){return repository.findTransaction(actor().organizationId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"库存流水不存在"));}

    @Transactional
    public Transaction move(Movement command,String idempotencyKey){return move(command,idempotencyKey,false);}

    @Transactional
    public Transaction move(Movement command,String idempotencyKey,boolean allowNegative){
        var a=actor(); var existing=repository.findByIdempotency(a.organizationId(),idempotencyKey); if(existing.isPresent())return existing.get();
        // 幂等预检：同组织下已存在相同业务单号+业务类型的流水时直接返回，避免重复保存（必须在写入前查询，写入失败后事务已中止不可再查）
        var dup=repository.findByDocumentNo(a.organizationId(),command.documentNo(),command.businessType()==null?"MANUAL":command.businessType()); if(dup.isPresent())return dup.get();
        validate(command); var before=repository.lockBalance(a.organizationId(),command.productId(),command.scope(),a.userId());
        var after=command.direction()==Direction.INBOUND?before.add(command.quantityKg()):before.subtract(command.quantityKg());
        if(!allowNegative&&after.signum()<0)throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                "库存不足，单号 "+command.documentNo()+" 需 "+command.quantityKg().stripTrailingZeros().toPlainString()+
                " KG，当前可用 "+before.stripTrailingZeros().toPlainString()+" KG",
                java.util.Map.of("requiredKg",command.quantityKg(),"availableKg",before));
        var id=UUID.randomUUID();var tx=new Transaction(id,command.productId(),null,null,null,command.scope(),command.direction(),required(command.reason(),"业务原因"),command.quantityKg(),before,after,command.businessDate()==null?LocalDate.now():command.businessDate(),required(command.documentNo(),"业务单号"),command.businessType()==null?"MANUAL":command.businessType(),command.businessId(),null,command.note(),a.userId(),command.actorName(),null,false);
        try{repository.insertTransaction(a.organizationId(),tx,idempotencyKey);repository.updateBalance(a.organizationId(),command.productId(),command.scope(),after);}catch(DataIntegrityViolationException e){throw integrityFailure(e,"业务单号或幂等键已存在");}
        audit.append(a.organizationId(),a.userId(),"INVENTORY_MOVED","INVENTORY_TRANSACTION",id,mapper.valueToTree(command)); return repository.findTransaction(a.organizationId(),id).orElseThrow();
    }

    @Transactional
    public Transaction reverse(UUID id,String note,String idempotencyKey,String revision){var a=actor();repository.lockTransaction(a.organizationId(),id);var prior=repository.findByIdempotency(a.organizationId(),idempotencyKey);if(prior.isPresent())return prior.get();var original=transaction(id);com.jsd.aird.shared.json.RevisionToken.require(revision,original.getRevision());if(original.reversed()||original.reversalOf()!=null)throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"该流水不可重复冲销");var reverseDirection=original.direction()==Direction.INBOUND?Direction.OUTBOUND:Direction.INBOUND;var movement=new Movement(original.productId(),original.scope(),reverseDirection,"REVERSAL",original.quantityKg(),LocalDate.now(),"REV-"+original.documentNo(),"REVERSAL",original.id(),note,null);var before=repository.lockBalance(a.organizationId(),original.productId(),original.scope(),a.userId());var after=reverseDirection==Direction.INBOUND?before.add(original.quantityKg()):before.subtract(original.quantityKg());if(after.signum()<0)throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"当前库存不足，无法冲销该入库流水");var rid=UUID.randomUUID();var tx=new Transaction(rid,original.productId(),null,null,null,original.scope(),reverseDirection,"REVERSAL",original.quantityKg(),before,after,LocalDate.now(),movement.documentNo(),"REVERSAL",original.id(),original.id(),note,a.userId(),null,null,false);try{repository.insertTransaction(a.organizationId(),tx,idempotencyKey);repository.updateBalance(a.organizationId(),original.productId(),original.scope(),after);}catch(DataIntegrityViolationException e){throw integrityFailure(e,"该流水已冲销");}audit.append(a.organizationId(),a.userId(),"INVENTORY_REVERSED","INVENTORY_TRANSACTION",rid,mapper.valueToTree(java.util.Map.of("reversalOf",id)));return repository.findTransaction(a.organizationId(),rid).orElseThrow();}

    @Transactional public Transaction updateTransaction(UUID id,TransactionUpdateInput input){var a=actor();repository.lockTransaction(a.organizationId(),id);var original=transaction(id);com.jsd.aird.shared.json.RevisionToken.require(input.revision(),original.getRevision());if(original.reversed()||original.reversalOf()!=null)throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"已冲销或冲销流水不可编辑");if(input==null||input.businessDate()==null)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"业务日期不能为空");var documentNo=required(input.documentNo(),"业务单号");var reason=required(input.reason(),"业务原因");try{if(repository.updateTransaction(a.organizationId(),id,input.businessDate(),documentNo,reason,input.note(),input.actorName())==0)throw new ApiException(ApiErrorCode.NOT_FOUND,"库存流水不存在");}catch(DataIntegrityViolationException e){throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"业务单号或业务原因已存在",e);}audit.append(a.organizationId(),a.userId(),"INVENTORY_TRANSACTION_UPDATED","INVENTORY_TRANSACTION",id,mapper.valueToTree(input));return transaction(id);}

    @Transactional public Sample createSample(SampleInput c,String key){
        var a=actor();var dispatchNo=required(c.dispatchNo(),"发样单号");
        var idemKey=key==null||key.isBlank()?"SAMPLE:"+dispatchNo:key;
        var prior=repository.findByIdempotency(a.organizationId(),idemKey);
        if(prior.isPresent())return repository.sampleByTransaction(a.organizationId(),prior.get().id()).orElseThrow(()->new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"幂等请求尚未完成"));
        if(repository.findSampleByDispatchNo(a.organizationId(),dispatchNo).isPresent())
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"发样单号已存在");
        var scope=c.scope()==null?Scope.RND:c.scope();
        if(c.productId()==null||!repository.productExists(a.organizationId(),c.productId(),scope))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"该库存域中产品不存在或已停用");
        var customerName=customerName(c.customerId());var recipientName=required(c.recipientName(),"收件人");
        var recipientPhone=required(c.recipientPhone(),"收件人电话");var address=required(c.sampleAddress(),"发样地址");
        var totalKg=shipmentTotal(c.specification(),c.packageQuantity());
        if(c.quantityKg()!=null&&c.quantityKg().compareTo(totalKg)!=0)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"总数量与规格乘数量不一致");
        var specification=new BigDecimal(c.specification().trim()).stripTrailingZeros().toPlainString();
        var status=sampleStatus(c.status());var id=UUID.randomUUID();
        Transaction tx=null;
        if("COMPLETED".equals(status)){
            tx=move(new Movement(c.productId(),scope,Direction.OUTBOUND,"SAMPLE_DISPATCH",totalKg,c.businessDate(),dispatchNo,"SAMPLE",id,c.customerRequirement(),null),idemKey,false);
            var existing=repository.sampleByTransaction(a.organizationId(),tx.id());
            if(existing.isPresent())return existing.get();
        }
        var x=new Sample(id,dispatchNo,c.businessDate(),c.productId(),tx==null?null:tx.productName(),specification,scope,totalKg,c.packageQuantity(),c.customerId(),customerName,recipientName,recipientPhone,address,c.courierCompany(),c.trackingNo(),c.clientContact(),c.clientContactPhone(),c.senderName(),c.customerRequirement(),c.customerFeedback(),status,tx==null?null:tx.id(),null,null);
        try{repository.insertSample(a.organizationId(),x,a.userId());}
        catch(DataIntegrityViolationException e){throw integrityFailure(e,"发样单号已存在");}
        repository.upsertRecipientPreference(c.customerId(),recipientName,recipientPhone,address);
        return repository.findSample(a.organizationId(),id).orElseThrow();
    }
    @Transactional public Shipment createShipment(ShipmentInput c,String key){return createShipment(c,key,false);}
    private Shipment createShipment(ShipmentInput c,String key,boolean imported){
        var a=actor();var shipmentNo=required(c.shipmentNo(),"出货单号");
        if(c.businessDate()==null)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"业务日期不能为空");
        var idemKey=key==null||key.isBlank()?"SHIPMENT:"+UUID.randomUUID():key;
        var prior=repository.findByIdempotency(a.organizationId(),idemKey);
        if(prior.isPresent())return repository.shipmentByTransaction(a.organizationId(),prior.get().id())
                .orElseThrow(()->new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"幂等请求尚未完成"));
        var header=repository.shipmentHeader(a.organizationId(),shipmentNo)
                .orElseGet(()->repository.insertShipmentHeader(a.organizationId(),shipmentNo,c.businessDate(),null,a.userId()));
        if(header.detailCount()>0&&header.completedCount()==header.detailCount())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"已完成的出货单不可新增明细");
        if(!header.businessDate().equals(c.businessDate()))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"同一出货单号的日期必须一致");
        var totalKg=shipmentTotal(c.specification(),c.packageQuantity());
        var scope=c.scope()==null?Scope.PRODUCTION:c.scope();
        if(c.productId()==null||!repository.productExists(a.organizationId(),c.productId(),scope))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"该库存域中产品不存在或已停用");
        var recipientName=imported?c.recipientName():required(c.recipientName(),"收件人");
        var recipientPhone=imported?c.recipientPhone():required(c.recipientPhone(),"收件人电话");
        var address=imported?c.shippingAddress():required(c.shippingAddress(),"收货地址");
        var customerName=customerName(c.customerId());var status=shipmentStatus(c.status());var id=UUID.randomUUID();
        Transaction tx=null;
        if("COMPLETED".equals(status)){
            validateReleasedCoa(a,c.coaNo());
            tx=move(new Movement(c.productId(),scope,Direction.OUTBOUND,"SHIPMENT",totalKg,c.businessDate(),shipmentDocumentNo(id),"SHIPMENT",id,shipmentNote(shipmentNo,c.note()),null),idemKey,false);
            var existing=repository.shipmentByTransaction(a.organizationId(),tx.id());
            if(existing.isPresent())return existing.get();
        }
        var specification=new BigDecimal(c.specification().trim()).stripTrailingZeros().toPlainString();
        var x=new Shipment(id,shipmentNo,c.businessDate(),c.productId(),tx==null?null:tx.productName(),scope,totalKg,
                c.customerId(),customerName,c.senderName(),c.batchNo(),specification,c.packageQuantity(),totalKg,
                recipientName,recipientPhone,address,c.courierCompany(),c.trackingNo(),c.note(),status,
                tx==null?null:tx.id(),null,null,c.coaNo());
        try{repository.insertShipment(a.organizationId(),x,a.userId());}
        catch(DataIntegrityViolationException e){throw integrityFailure(e,"出货明细保存失败");}
        if(recipientName!=null&&!recipientName.isBlank()&&recipientPhone!=null&&!recipientPhone.isBlank()&&address!=null&&!address.isBlank())
            repository.upsertRecipientPreference(c.customerId(),recipientName,recipientPhone,address);
        return repository.findShipment(a.organizationId(),id).orElseThrow();
    }
    @Transactional public java.util.List<Shipment> importShipment(String shipmentNo,java.util.List<ShipmentInput> details){
        var number=required(shipmentNo,"出货单号");
        if(details==null||details.isEmpty()||details.size()>100)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"请选择 1 至 100 条出货明细");
        var date=details.get(0)==null?null:details.get(0).businessDate();
        if(date==null||details.stream().anyMatch(item->item==null||!number.equals(item.shipmentNo())||!date.equals(item.businessDate())))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"同一出货单的单号和日期必须一致");
        var existingHeader=repository.shipmentHeader(actor().organizationId(),number);
        if(existingHeader.isPresent()&&!date.equals(existingHeader.get().businessDate()))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"导入日期与已有出货单日期不一致");
        if(details.stream().anyMatch(item->item.status()!=null&&!"PENDING".equals(item.status())))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"导入的明细状态必须为未完成");
        return details.stream().map(item->createShipment(item,null,true)).toList();
    }
    @Transactional public java.util.List<Shipment> createShipmentBatch(String shipmentNo,java.util.List<ShipmentInput> details){
        var number=required(shipmentNo,"出货单号");
        if(details==null||details.isEmpty()||details.size()>100)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"请选择 1 至 100 条出货明细");
        if(details.get(0)==null)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"出货明细不能为空");
        var date=details.get(0).businessDate();
        if(details.stream().anyMatch(item->item==null||!number.equals(item.shipmentNo())||!java.util.Objects.equals(date,item.businessDate())))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"同一出货单的单号和日期必须一致");
        return details.stream().map(item->createShipment(item,null)).toList();
    }
    @Transactional public Sample updateSample(UUID id,SampleUpdateInput input){
        var a=actor();if(input==null||input.businessDate()==null)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"业务日期不能为空");
        var existing=repository.lockSample(a.organizationId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"发样记录不存在"));
        com.jsd.aird.shared.json.RevisionToken.require(input.revision(),existing.getRevision());
        var dispatchNo=required(input.dispatchNo(),"发样单号");
        var recipientName=required(input.recipientName(),"收件人");var recipientPhone=required(input.recipientPhone(),"收件人电话");
        var address=required(input.sampleAddress(),"发样地址");var totalKg=shipmentTotal(input.specification(),input.packageQuantity());
        if("COMPLETED".equals(existing.status())&&totalKg.compareTo(existing.quantityKg())!=0)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"已扣库存的总数量不能在编辑时修改");
        var specification=new BigDecimal(input.specification().trim()).stripTrailingZeros().toPlainString();
        var customerName=customerName(input.customerId());
        try{
            if(repository.updateSample(a.organizationId(),id,input.businessDate(),dispatchNo,specification,input.packageQuantity(),input.customerId(),customerName,recipientName,recipientPhone,address,input.courierCompany(),input.trackingNo(),input.clientContact(),input.clientContactPhone(),input.senderName(),input.customerRequirement(),input.customerFeedback(),existing.status(),totalKg)==0)
                throw new ApiException(ApiErrorCode.NOT_FOUND,"发样记录不存在");
        }catch(DataIntegrityViolationException e){throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"发样单号已存在",e);}
        repository.upsertRecipientPreference(input.customerId(),recipientName,recipientPhone,address);
        audit.append(a.organizationId(),a.userId(),"INVENTORY_SAMPLE_UPDATED","INVENTORY_SAMPLE_DISPATCH",id,mapper.valueToTree(input));
        return repository.findSample(a.organizationId(),id).orElseThrow();
    }
    @Transactional public int updateSampleStatuses(java.util.List<UUID> ids,String requestedStatus){
        var a=actor();
        if(!"COMPLETED".equals(requestedStatus))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"发样状态只能修改为已完成");
        if(ids==null||ids.isEmpty()||ids.size()>100||ids.stream().anyMatch(java.util.Objects::isNull)||
                ids.stream().distinct().count()!=ids.size())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"请选择 1 至 100 条不重复的发样记录");
        var sorted=ids.stream().sorted().toList();
        int changed=0;
        for(var id:sorted){
            var sample=repository.lockSample(a.organizationId(),id)
                    .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"发样记录不存在"));
            if("COMPLETED".equals(sample.status()))continue;
            // A previous completion may have been reversed; its document number remains in the ledger.
            var documentNo=repository.findByDocumentNo(a.organizationId(),sample.dispatchNo(),"SAMPLE").isPresent()
                    ? "FY-"+id+"-"+UUID.randomUUID().toString().substring(0,8)
                    : sample.dispatchNo();
            var tx=move(new Movement(sample.productId(),sample.scope(),Direction.OUTBOUND,"SAMPLE_DISPATCH",
                    sample.quantityKg(),sample.businessDate(),documentNo,"SAMPLE",id,sample.customerRequirement(),null),
                    "SAMPLE:"+id+":"+UUID.randomUUID(),false);
            if(repository.completeSample(a.organizationId(),id,tx.id())!=1)
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"发样记录状态已变化，请刷新后重试");
            changed++;
        }
        audit.append(a.organizationId(),a.userId(),"INVENTORY_SAMPLE_STATUS_UPDATED","INVENTORY_SAMPLE_DISPATCH",
                sorted.get(0),mapper.valueToTree(java.util.Map.of("ids",sorted,"status","COMPLETED")));
        return changed;
    }
    public java.util.Optional<InventoryRepository.RecipientPreference> recipientPreference(UUID customerId){customerName(customerId);return repository.recipientPreference(customerId).or(()->repository.latestSampleRecipient(customerId));}
    @Transactional public int updateShipmentStatuses(java.util.List<UUID> ids,String requestedStatus){
        var a=actor();
        if(!"COMPLETED".equals(requestedStatus))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"出货状态只能修改为已完成");
        if(ids==null||ids.isEmpty()||ids.size()>100||ids.stream().anyMatch(java.util.Objects::isNull)||
                ids.stream().distinct().count()!=ids.size())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"请选择 1 至 100 条不重复的出货记录");
        var sorted=ids.stream().sorted().toList();
        int changed=0;
        for(var id:sorted){
            var shipment=repository.lockShipment(a.organizationId(),id)
                    .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"出货记录不存在"));
            if("COMPLETED".equals(shipment.status()))continue;
            validateReleasedCoa(a,shipment.coaNo());
            var tx=move(new Movement(shipment.productId(),shipment.scope(),Direction.OUTBOUND,"SHIPMENT",
                    shipment.quantityKg(),shipment.businessDate(),shipmentDocumentNo(id),"SHIPMENT",id,
                    shipmentNote(shipment.shipmentNo(),shipment.note()),null),"SHIPMENT:"+id,false);
            if(repository.completeShipment(a.organizationId(),id,tx.id())!=1)
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"出货记录状态已变化，请刷新后重试");
            changed++;
        }
        audit.append(a.organizationId(),a.userId(),"INVENTORY_SHIPMENT_STATUS_UPDATED","INVENTORY_SHIPMENT",
                sorted.get(0),mapper.valueToTree(java.util.Map.of("ids",sorted,"status","COMPLETED")));
        return changed;
    }
    @Transactional public int deleteShipmentDetails(String shipmentNo,java.util.List<UUID> ids){
        var a=actor();var number=required(shipmentNo,"出货单号");
        if(ids==null||ids.isEmpty()||ids.size()>100||ids.stream().anyMatch(java.util.Objects::isNull)||
                ids.stream().distinct().count()!=ids.size())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"请选择 1 至 100 条不重复的出货明细");
        var sorted=ids.stream().sorted().toList();
        for(var id:sorted){
            var shipment=repository.lockShipment(a.organizationId(),id)
                    .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"出货明细不存在"));
            if(!number.equals(shipment.shipmentNo()))
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"选中的明细不属于当前出货单");
            if(!"PENDING".equals(shipment.status())||shipment.transactionId()!=null)
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"仅未完成且未扣库存的出货明细可以删除");
        }
        for(var id:sorted)
            if(repository.deletePendingShipment(a.organizationId(),id)!=1)
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"出货明细状态已变化，请刷新后重试");
        audit.append(a.organizationId(),a.userId(),"INVENTORY_SHIPMENT_DETAILS_DELETED","INVENTORY_SHIPMENT",
                sorted.get(0),mapper.valueToTree(java.util.Map.of("shipmentNo",number,"ids",sorted)));
        return sorted.size();
    }
    @Transactional public int deleteShipmentHeader(String shipmentNo){
        var a=actor();var number=required(shipmentNo,"出货单号");
        var header=shipmentHeader(number);
        if(header.completedCount()>0)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"出货单存在已完成明细，不可删除");
        var deleted=repository.deletePendingShipmentDetailsByHeader(a.organizationId(),number);
        if(deleted!=header.detailCount())
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"出货单明细状态已变化，请刷新后重试");
        if(repository.deleteShipmentHeader(a.organizationId(),number)!=1)
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"出货单已变化，请刷新后重试");
        audit.append(a.organizationId(),a.userId(),"INVENTORY_SHIPMENT_HEADER_DELETED","INVENTORY_SHIPMENT_HEADER",
                header.id(),mapper.valueToTree(java.util.Map.of("shipmentNo",number,"detailCount",deleted)));
        return deleted;
    }
    @Transactional public int deleteShipmentHeaders(java.util.List<String> shipmentNos){
        if(shipmentNos==null||shipmentNos.isEmpty()||shipmentNos.size()>100||
                shipmentNos.stream().anyMatch(number->number==null||number.isBlank()))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"请选择 1 至 100 张出货单");
        var numbers=shipmentNos.stream().map(String::trim).sorted().toList();
        if(numbers.stream().distinct().count()!=numbers.size())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"出货单号不能重复");
        for(var number:numbers) deleteShipmentHeader(number);
        return numbers.size();
    }
    @Transactional public int deleteSamples(java.util.List<UUID> ids){
        var a=actor();
        if(ids==null||ids.isEmpty()||ids.size()>100||ids.stream().anyMatch(java.util.Objects::isNull)||
                ids.stream().distinct().count()!=ids.size())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"请选择 1 至 100 条不重复的发样记录");
        var sorted=ids.stream().sorted().toList();
        for(var id:sorted){
            var sample=repository.lockSample(a.organizationId(),id)
                    .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"发样记录不存在"));
            if(!"PENDING".equals(sample.status())||sample.transactionId()!=null)
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"仅未完成且未扣库存的发样记录可以删除");
        }
        for(var id:sorted)
            if(repository.deletePendingSample(a.organizationId(),id)!=1)
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"发样记录状态已变化，请刷新后重试");
        audit.append(a.organizationId(),a.userId(),"INVENTORY_SAMPLES_DELETED","INVENTORY_SAMPLE",
                sorted.get(0),mapper.valueToTree(java.util.Map.of("ids",sorted)));
        return sorted.size();
    }
    @Transactional public int completeShipmentHeader(String shipmentNo){
        shipmentHeader(shipmentNo);
        var ids=repository.pendingShipmentIds(actor().organizationId(),shipmentNo);
        int changed=0;
        for(int start=0;start<ids.size();start+=100)
            changed+=updateShipmentStatuses(ids.subList(start,Math.min(start+100,ids.size())),"COMPLETED");
        return changed;
    }
    @Transactional public ShipmentHeaderCompletion completeShipmentHeaders(java.util.List<String> shipmentNos){
        if(shipmentNos==null||shipmentNos.isEmpty()||shipmentNos.size()>100||
                shipmentNos.stream().anyMatch(number->number==null||number.isBlank()))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"请选择 1 至 100 张出货单");
        var numbers=shipmentNos.stream().map(String::trim).sorted().toList();
        if(numbers.stream().distinct().count()!=numbers.size())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"出货单号不能重复");
        int headers=0,details=0;
        for(var number:numbers){
            var changed=completeShipmentHeader(number);
            if(changed>0)headers++;
            details+=changed;
        }
        return new ShipmentHeaderCompletion(headers,details);
    }
    public record ShipmentHeaderCompletion(int headerCount,int detailCount){}
    @Transactional public Shipment updateShipment(UUID id,ShipmentUpdateInput input){
        var a=actor();if(input==null||input.businessDate()==null)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"业务日期不能为空");
        var existing=repository.lockShipment(a.organizationId(),id)
                .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"出货记录不存在"));
        if("COMPLETED".equals(existing.status()))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"已完成的出货记录不可修改");
        com.jsd.aird.shared.json.RevisionToken.require(input.revision(),existing.getRevision());
        var shipmentNo=required(input.shipmentNo(),"出货单号");
        if(!shipmentNo.equals(existing.shipmentNo()))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"明细出货单号不可修改，请在主表中管理出货单");
        var header=shipmentHeader(shipmentNo);
        if(!header.businessDate().equals(input.businessDate())&&header.completedCount()>0)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"已有完成明细的出货单不可修改日期");
        var status=input.status()==null?existing.status():shipmentStatus(input.status());
        if("COMPLETED".equals(existing.status())&&"PENDING".equals(status))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"已完成的出货记录不能改为未完成");
        var customerName=customerName(input.customerId());
        var recipientName=required(input.recipientName(),"收件人");
        var recipientPhone=required(input.recipientPhone(),"收件人电话");
        var address=required(input.shippingAddress(),"收货地址");
        var updated=new Shipment(id,shipmentNo,input.businessDate(),existing.productId(),existing.productName(),
                existing.scope(),existing.quantityKg(),input.customerId(),customerName,input.senderName(),
                input.batchNo(),existing.specification(),existing.packageQuantity(),existing.totalQuantityKg(),
                recipientName,recipientPhone,address,input.courierCompany(),input.trackingNo(),input.note(),
                existing.status(),existing.transactionId(),existing.operator(),existing.createdAt(),input.coaNo());
        repository.updateShipmentHeaderDate(a.organizationId(),shipmentNo,input.businessDate());
        try{if(repository.updateShipment(a.organizationId(),id,updated)==0)
            throw new ApiException(ApiErrorCode.NOT_FOUND,"出货记录不存在");}
        catch(DataIntegrityViolationException e){throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"出货单号已存在",e);}
        if("COMPLETED".equals(status)&&"PENDING".equals(existing.status())){
            validateReleasedCoa(a,input.coaNo());
            var tx=move(new Movement(existing.productId(),existing.scope(),Direction.OUTBOUND,"SHIPMENT",
                    existing.quantityKg(),input.businessDate(),shipmentDocumentNo(id),"SHIPMENT",id,shipmentNote(shipmentNo,input.note()),null),
                    "SHIPMENT:"+id,false);
            if(repository.completeShipment(a.organizationId(),id,tx.id())!=1)
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"出货记录已完成，请刷新后重试");
        }
        repository.upsertRecipientPreference(input.customerId(),recipientName,recipientPhone,address);
        audit.append(a.organizationId(),a.userId(),"INVENTORY_SHIPMENT_UPDATED","INVENTORY_SHIPMENT",id,mapper.valueToTree(input));
        return repository.findShipment(a.organizationId(),id).orElseThrow();
    }
    public Page<Sample> samples(String keyword,String status,LocalDate startDate,LocalDate endDate,int page,int size){return repository.samples(actor().organizationId(),keyword,optionalStatus(status),startDate,endDate,Math.max(page,1),Math.min(Math.max(size,1),100));} public Page<Shipment> shipments(String keyword,int page,int size){return repository.shipments(actor().organizationId(),keyword,Math.max(page,1),Math.min(Math.max(size,1),100));}
    public Page<ShipmentHeader> shipmentHeaders(String keyword,String status,LocalDate startDate,LocalDate endDate,int page,int size){return repository.shipmentHeaders(actor().organizationId(),keyword,optionalStatus(status),startDate,endDate,Math.max(page,1),Math.min(Math.max(size,1),100));}
    public Page<Shipment> shipmentDetails(String shipmentNo,int page,int size){shipmentHeader(shipmentNo);return repository.shipmentDetails(actor().organizationId(),shipmentNo,Math.max(page,1),Math.min(Math.max(size,1),100));}
    public ShipmentHeader shipmentHeader(String shipmentNo){return repository.shipmentHeader(actor().organizationId(),shipmentNo).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"出货单不存在"));}
    @Transactional public ShipmentHeader createShipmentHeader(String shipmentNo,LocalDate businessDate,String note){
        var a=actor();var number=required(shipmentNo,"出货单号");
        if(businessDate==null)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"业务日期不能为空");
        if(repository.shipmentHeader(a.organizationId(),number).isPresent())throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"出货单号已存在");
        return repository.insertShipmentHeader(a.organizationId(),number,businessDate,optionalNote(note),a.userId());
    }
    @Transactional public ShipmentHeader updateShipmentHeader(String shipmentNo,LocalDate businessDate,String note){
        if(businessDate==null)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"业务日期不能为空");
        var header=shipmentHeader(shipmentNo);
        if(header.completedCount()>0&&!header.businessDate().equals(businessDate))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"已有完成明细的出货单不可修改日期");
        repository.updateShipmentHeader(actor().organizationId(),shipmentNo,businessDate,optionalNote(note));
        return shipmentHeader(shipmentNo);
    }
    private static String optionalNote(String note){return note==null||note.isBlank()?null:note.trim();}
    private static String shipmentDocumentNo(UUID detailId){return "CH-"+detailId;}
    private static String shipmentNote(String shipmentNo,String note){return "出货单号="+shipmentNo+(note==null||note.isBlank()?"":"；"+note);}
    private void validate(Movement c){if(c==null||c.productId()==null||c.scope()==null||c.direction()==null)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"产品、库存域和方向不能为空");if(c.quantityKg()==null||c.quantityKg().signum()<=0)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"数量必须大于 0");if(!repository.productExists(actor().organizationId(),c.productId(),c.scope()))throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"该库存域中产品不存在或已停用");required(c.reason(),"业务原因");required(c.documentNo(),"业务单号");}
    private static String required(String x,String label){if(x==null||x.isBlank())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,label+"不能为空");return x.trim();}
    private static String shipmentStatus(String status){if(status==null)return "PENDING";if(!status.equals("PENDING")&&!status.equals("COMPLETED"))throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"出货状态无效");return status;}
    private static String optionalStatus(String status){if(status==null||status.isBlank())return null;if(!status.equals("PENDING")&&!status.equals("COMPLETED"))throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"状态无效");return status;}
    static BigDecimal shipmentTotal(String specification,BigDecimal packageQuantity){
        BigDecimal spec;
        try{spec=new BigDecimal(required(specification,"规格"));}catch(NumberFormatException e){throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"规格必须是有效数字");}
        if(spec.signum()<=0||packageQuantity==null||packageQuantity.signum()<=0)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"规格和数量必须大于 0");
        var quantity=packageQuantity.stripTrailingZeros();
        if(quantity.scale()>6||quantity.precision()-quantity.scale()>12)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"数量超出允许范围");
        var total=spec.multiply(packageQuantity).setScale(6,java.math.RoundingMode.HALF_UP);
        if(total.signum()<=0||total.precision()-total.scale()>12)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"总数量超出允许范围");
        return total;
    }
    private static Actor actor(){return ActorContext.required();}
    /**
     * 把数据库完整性异常翻译成可读的业务错误。
     * 之前无论哪种约束冲突都统一报“业务单号或幂等键已存在”，负库存检查约束、字段超长、外键缺失等问题
     * 都会被误导成单号重复；这里按 SQLSTATE 区分、并把具体约束名写进日志和 detail，便于定位。
     */
    private static ApiException integrityFailure(DataIntegrityViolationException e,String duplicateMessage){
        var cause=e.getMostSpecificCause();
        var sqlState=cause instanceof java.sql.SQLException sql?sql.getSQLState():null;
        var code=sqlState==null?"":sqlState;
        var constraint=constraintName(cause);
        log.warn("库存流水写入被数据库拒绝 sqlState={} constraint={} message={}",code,constraint,cause.getMessage());
        var detail=java.util.Map.of("sqlState",code,"constraint",constraint==null?"":constraint);
        if(code.startsWith("23505"))return new ApiException(ApiErrorCode.RESOURCE_CONFLICT,duplicateMessage,detail);
        if(code.startsWith("23514"))return new ApiException(ApiErrorCode.RESOURCE_CONFLICT,constraint!=null&&(constraint.contains("after_kg")||constraint.contains("quantity_kg"))
                ?"库存扣减后为负数，被数据库检查约束限制；请先执行放开负库存的数据库脚本"
                :"数量超出数据库允许范围，写入被检查约束拒绝",detail);
        if(code.startsWith("23503"))return new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"关联的产品或用户不存在，无法写入库存流水",detail);
        if(code.startsWith("23502"))return new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"库存流水必填字段为空，写入被拒绝",detail);
        if(code.startsWith("22"))return new ApiException(ApiErrorCode.VALIDATION_ERROR,"填写的单号或备注超出数据库字段长度，或数量超出可存储范围",detail);
        return new ApiException(ApiErrorCode.RESOURCE_CONFLICT,duplicateMessage,detail);
    }
    private static String constraintName(Throwable cause){
        var message=cause.getMessage()==null?"":cause.getMessage();
        var matcher=CONSTRAINT_PATTERN.matcher(message);
        return matcher.find()?matcher.group(1):null;
    }
    private String customerName(UUID customerId){if(customerId==null)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"客户不能为空");return repository.activeCustomerName(customerId).orElseThrow(()->new ApiException(ApiErrorCode.VALIDATION_ERROR,"客户不存在或已停用"));}
    private void validateReleasedCoa(Actor actor,String coaNo){if(coaNo==null||coaNo.isBlank())return;var coa=qualityData.recordByBusinessNo(actor.organizationId(),actor.role(),"coa",coaNo.trim()).orElseThrow(()->new ApiException(ApiErrorCode.VALIDATION_ERROR,"COA 编号未找到，或当前账号无权查看；请核对编号"));if(!"已签发".equals(coa.data().path("status").asText()))throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"COA 尚未签发，不能放行出货");}
    public record SampleInput(String dispatchNo,LocalDate businessDate,UUID productId,Scope scope,BigDecimal quantityKg,String specification,BigDecimal packageQuantity,UUID customerId,String recipientName,String recipientPhone,String sampleAddress,String courierCompany,String trackingNo,String clientContact,String clientContactPhone,String senderName,String customerRequirement,String customerFeedback,String status){}
    public record ShipmentInput(String shipmentNo,LocalDate businessDate,UUID productId,Scope scope,BigDecimal packageQuantity,UUID customerId,String senderName,String note,String coaNo,String batchNo,String specification,String recipientName,String recipientPhone,String shippingAddress,String courierCompany,String trackingNo,String status){}
    public record TransactionUpdateInput(@jakarta.validation.constraints.NotBlank String revision,LocalDate businessDate,String documentNo,String reason,String note,String actorName){}
    public record SampleUpdateInput(@jakarta.validation.constraints.NotBlank String revision,String dispatchNo,LocalDate businessDate,String specification,BigDecimal packageQuantity,UUID customerId,String recipientName,String recipientPhone,String sampleAddress,String courierCompany,String trackingNo,String clientContact,String clientContactPhone,String senderName,String customerRequirement,String customerFeedback,String status){}
    private static String sampleStatus(String status){if(status==null)return "PENDING";if(!status.equals("PENDING")&&!status.equals("COMPLETED"))throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"发样状态无效");return status;}
    public record ShipmentUpdateInput(@jakarta.validation.constraints.NotBlank String revision,String shipmentNo,LocalDate businessDate,UUID customerId,String senderName,String note,String batchNo,String recipientName,String recipientPhone,String shippingAddress,String courierCompany,String trackingNo,String coaNo,String status){}
    public record PolicyInput(@jakarta.validation.constraints.NotBlank String revision,BigDecimal packageKg,BigDecimal lowStockKg){}
    public record ProductInput(String name,Scope scope){}
}
