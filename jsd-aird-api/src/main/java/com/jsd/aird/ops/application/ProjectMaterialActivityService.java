package com.jsd.aird.ops.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jsd.aird.ops.api.ProjectMaterialActivity;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.shared.security.ActorContext;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ProjectMaterialActivityService implements ProjectMaterialActivity {
    private final AuditLogFacade audit;
    public ProjectMaterialActivityService(AuditLogFacade audit){this.audit=audit;}
    public void record(String operation,String type,UUID id,UUID projectId,String name){
        if(projectId==null)return;
        var actor=ActorContext.required();var detail=JsonNodeFactory.instance.objectNode().put("projectId",projectId.toString()).put("name",name);
        audit.append(actor.organizationId(),actor.userId(),operation,type,id,detail);
    }
}
