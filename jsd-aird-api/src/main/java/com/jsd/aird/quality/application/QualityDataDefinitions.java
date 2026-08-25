package com.jsd.aird.quality.application;

import java.util.List;
import java.util.Map;

public final class QualityDataDefinitions {
    private QualityDataDefinitions() {}
    public record Field(String key, String label, String kind, boolean required, List<String> options) {
        public Field(String key, String label, String kind, boolean required) { this(key,label,kind,required,List.of()); }
    }
    public record Type(String id, String name, String prefix, List<Field> fields) {}
    public static final List<Type> TYPES = List.of(
        new Type("standard","测试标准","STD",List.of(new Field("fileNo","文件编号","text",true),new Field("fileName","文件名称","text",true),new Field("fileType","文件类型","text",true),new Field("author","制定人","text",true),new Field("effectiveDate","生效日期","date",true),new Field("version","版本","text",true))),
        new Type("record","测试记录表","TR",List.of(new Field("recordNo","记录编号","text",true),new Field("testDate","检测日期","date",true),new Field("productName","产品名称","text",true),new Field("batchNumber","批号","text",true),new Field("testItem","检测项目","text",true),new Field("testResult","检测结果","long",true),new Field("judgement","判定","select",true,List.of("合格","不合格","待判定")),new Field("tester","检测人","text",true),new Field("standardNo","采用标准编号","text",false))),
        new Type("coa","COA报告","COA",List.of(new Field("reportNo","报告编号","text",true),new Field("customer","客户","text",true),new Field("productName","产品名称","text",true),new Field("batchNumber","批号","text",true),new Field("productionDate","生产日期","date",true),new Field("conclusion","检测结论","long",true),new Field("issueDate","签发日期","date",true),new Field("issuer","签发人","text",true),new Field("status","状态","select",true,List.of("草稿","待审核","已签发","已归档")),new Field("attachment","附件名称","text",false),new Field("internalRecordNo","内部测试记录编号","text",false))),
        new Type("defect","不良报告单","NG",List.of(new Field("defectNo","不良单号","text",true),new Field("occurDate","发生日期","date",true),new Field("source","来源","text",true),new Field("sourceRecordNo","来源记录编号","text",false),new Field("productName","产品名称","text",true),new Field("batchNumber","批号","text",true),new Field("phenomenon","不良现象","long",true),new Field("quantity","不良数量","number",true),new Field("cause","原因分析","long",false),new Field("correctiveAction","纠正措施","long",false),new Field("owner","责任人","text",true),new Field("status","状态","select",true,List.of("待处理","处理中","已关闭")))),
        new Type("manual","产品说明书","MAN",List.of(new Field("manualNo","说明书编号","text",true),new Field("productName","产品名称","text",true),new Field("productModel","产品型号","text",true),new Field("version","版本","text",true),new Field("usage","主要用途","long",true),new Field("keyIndicators","关键指标","long",true),new Field("publishDate","发布日期","date",true),new Field("owner","负责人","text",true),new Field("status","状态","select",true,List.of("草稿","有效","修订中","已废止")))),
        new Type("msds","安全数据表（MSDS）","MSDS",List.of(new Field("msdsNo","MSDS编号","text",true),new Field("chemicalName","产品/化学品名称","text",true),new Field("casNo","CAS号","text",false),new Field("hazardClass","危险性分类","text",true),new Field("ingredients","主要成分","long",true),new Field("emergency","应急措施","long",true),new Field("version","版本","text",true),new Field("effectiveDate","生效日期","date",true),new Field("status","状态","select",true,List.of("草稿","有效","修订中","已废止")),new Field("relatedManualNo","关联产品说明书编号","text",false))),
        new Type("label","安全标签","LBL",List.of(new Field("labelNo","标签编号","text",true),new Field("productName","产品名称","text",true),new Field("modelBatch","型号/批号","text",true),new Field("signalWord","警示词","text",true),new Field("hazardStatement","危险说明","long",true),new Field("precaution","防范说明","long",true),new Field("packageSpec","包装规格","text",false),new Field("version","版本","text",true),new Field("effectiveDate","生效日期","date",true),new Field("status","状态","select",true,List.of("草稿","有效","修订中","已废止")),new Field("relatedMsdsNo","关联MSDS编号","text",false)))
    );
    public static final Map<String,Type> BY_ID = TYPES.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Type::id, x->x));
    public static String idKey(String type) { return Map.of("standard","fileNo","record","recordNo","coa","reportNo","defect","defectNo","manual","manualNo","msds","msdsNo","label","labelNo").get(type); }
}
