package com.jsd.aird.mfg.inventory.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jsd.aird.mfg.inventory.domain.InventoryModels.*;
import com.jsd.aird.mfg.inventory.application.port.InventoryRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.UUID;

@Service
public class InitialStockImportService {
    private final InventoryRepository repository; private final InventoryService inventory; private final ObjectMapper mapper;
    public InitialStockImportService(InventoryRepository repository,InventoryService inventory,ObjectMapper mapper){this.repository=repository;this.inventory=inventory;this.mapper=mapper;}
    public ImportView upload(MultipartFile file){if(file==null||file.isEmpty())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"请选择 XLSX 文件");var a=ActorContext.required();var rows=mapper.createArrayNode();var errors=mapper.createArrayNode();var seen=new HashSet<String>();try(var workbook=new XSSFWorkbook(file.getInputStream())){var sheet=workbook.getSheetAt(0);var fmt=new DataFormatter();for(int i=1;i<=sheet.getLastRowNum();i++){var row=sheet.getRow(i);if(row==null)continue;var code=fmt.formatCellValue(row.getCell(0)).trim();var scope=fmt.formatCellValue(row.getCell(1)).trim().toUpperCase();var qty=fmt.formatCellValue(row.getCell(2)).trim();var date=fmt.formatCellValue(row.getCell(3)).trim();if(code.isBlank()&&scope.isBlank()&&qty.isBlank())continue;var item=rows.addObject().put("rowNumber",i+1).put("productCode",code).put("scope",scope).put("quantityKg",qty).put("businessDate",date);var id=repository.productIdByCode(a.organizationId(),code);if(id.isEmpty())error(errors,i+1,"productCode","库存产品编码不存在");else item.put("productId",id.get().toString());try{Scope.valueOf(scope);}catch(Exception e){error(errors,i+1,"scope","库存域必须为 RND 或 PRODUCTION");}try{if(new BigDecimal(qty).signum()<0)throw new Exception();}catch(Exception e){error(errors,i+1,"quantityKg","数量必须为非负数字");}if(!date.isBlank())try{LocalDate.parse(date);}catch(Exception e){error(errors,i+1,"businessDate","日期格式必须为 yyyy-MM-dd");}if(!seen.add(code+"|"+scope))error(errors,i+1,"productCode","同一产品和库存域重复");}}catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(ApiErrorCode.BAD_REQUEST,"无法解析 XLSX 文件",e);}var id=UUID.randomUUID();var status=errors.isEmpty()?"VALIDATED":"INVALID";repository.insertImport(id,a.organizationId(),status,rows,errors,a.userId());return new ImportView(id,status,rows,errors,null);}
    public ImportView get(UUID id){var a=ActorContext.required();var x=repository.findImport(a.organizationId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"期初库存导入不存在"));try{return new ImportView(x.id(),x.status(),mapper.readTree(x.rowsJson()),mapper.readTree(x.errorsJson()),x.committedAt());}catch(Exception e){throw new ApiException(ApiErrorCode.INTERNAL_ERROR,"导入数据损坏",e);}}
    @Transactional public ImportView commit(UUID id){var view=get(id);if(!"VALIDATED".equals(view.status()))throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"只有校验通过且未提交的导入可以确认");for(JsonNode row:view.rows()){var qty=new BigDecimal(row.path("quantityKg").asText());if(qty.signum()==0)continue;var product=UUID.fromString(row.path("productId").asText());var scope=Scope.valueOf(row.path("scope").asText());var date=row.path("businessDate").asText().isBlank()?LocalDate.now():LocalDate.parse(row.path("businessDate").asText());inventory.move(new Movement(product,scope,Direction.INBOUND,"OPENING_BALANCE",qty,date,"OPEN-"+id+"-"+row.path("rowNumber").asInt(),"INITIAL_STOCK",id,"期初盘点导入"),"initial-stock:"+id+":"+row.path("rowNumber").asInt());}if(repository.markImportCommitted(ActorContext.required().organizationId(),id)==0)throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"导入已提交或状态已变化");return get(id);}
    private static void error(ArrayNode errors,int row,String field,String message){errors.addObject().put("rowNumber",row).put("field",field).put("message",message);}
    public record ImportView(UUID id,String status,JsonNode rows,JsonNode errors,java.time.Instant committedAt){}
}
