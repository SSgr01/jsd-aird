package com.jsd.aird.mdm.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.security.Actor;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.jsd.aird.shared.business.BusinessViews.*;

@Service
public class BusinessRawMaterialReader implements BusinessReadPort,BusinessDataAccess {
    private final RawMaterialService materials;
    public BusinessRawMaterialReader(RawMaterialService materials){this.materials=materials;}
    public Set<String> resourceTypes(){return Set.of("RAW_MATERIAL","RAW_MATERIAL_SOURCE");}
    public Set<String> filters(String type){return Set.of("query");}
    public boolean allowed(Actor actor,String type,UUID id,String operation,JsonNode context) {
        if(!Set.of("SEARCH","READ","EXPORT").contains(operation))return false;
        try {if("RAW_MATERIAL_SOURCE".equals(type)&&id!=null)materials.source(id);else materials.require(id,false);return true;}
        catch(com.jsd.aird.shared.error.ApiException e){return false;}
    }
    public Page references(String type,ObjectNode filter,int page,int size) {
        if(!"RAW_MATERIAL".equals(type))return new Page(List.of(),false);
        var result=materials.list(filter.path("query").asText(""),(page-1)*size,size);var refs=new ArrayList<ObjectNode>();
        result.path("items").forEach(item->refs.add(ref(type,id(item)).put("version_id",item.path("versionId").asText())));
        return new Page(refs,result.has("nextOffset"));
    }
    public BusinessRecordView read(ObjectNode request) {
        var type=request.path("type").asText();var id=id(request);var links=new ArrayList<BusinessRecordView.Relation>();
        var body="RAW_MATERIAL".equals(type)?materials.profile(id,optionalId(request,"version_id")):materials.source(id);
        if("RAW_MATERIAL".equals(type))collectSources(body.path("facts"),links,new HashSet<>());
        var result=view(type,id,body.path("contentHash").asText(),"RAW_MATERIAL".equals(type)?body.path("code").asText()+" · "+body.path("facts").path("grade").asText():body.path("title").asText(),body,null,links);
        if("RAW_MATERIAL".equals(type))result.ref().set("version_id",body.path("versionId"));
        pinned(request,result);return result;
    }
    private void collectSources(JsonNode value,List<BusinessRecordView.Relation> links,Set<UUID> seen) {
        if(value.isObject()&&value.hasNonNull("documentId")){var id=uuid(value,"documentId");if(seen.add(id))links.add(link("原料资料原文","RAW_MATERIAL_SOURCE",id));}
        if(value.isContainerNode())value.forEach(item->collectSources(item,links,seen));
    }
}
