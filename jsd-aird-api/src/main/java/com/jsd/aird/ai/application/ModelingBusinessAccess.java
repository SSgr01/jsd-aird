package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.shared.business.BusinessDataAccess;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.iam.api.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ModelingBusinessAccess implements BusinessDataAccess {
    private final AuthorizationService authorization;
    public ModelingBusinessAccess(AuthorizationService authorization){this.authorization=authorization;}
    public Set<String> resourceTypes(){return Set.of("AI_MODEL_TRAINING");}
    public boolean allowed(Actor actor,String type,UUID id,String operation,JsonNode context) {
        if(!Set.of("READ","EXECUTE").contains(operation))return false;
        return authorization.check(new PermissionCheck(actor.organizationId(),actor.userId(),"EXECUTE".equals(operation)?"ai.modeling.manage":"ai.modeling.read","AI_MODELING",id,operation)).allowed();
    }
}
