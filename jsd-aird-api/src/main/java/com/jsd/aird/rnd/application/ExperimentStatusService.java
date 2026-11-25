package com.jsd.aird.rnd.application;

import com.jsd.aird.rnd.api.ExperimentStatusFacade;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ExperimentStatusService implements ExperimentStatusFacade {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ExperimentStatusService.class);
    private final ExperimentRepository repository;
    private final AuthorizationService authorization;
    public ExperimentStatusService(ExperimentRepository repository, AuthorizationService authorization) {
        this.repository=repository;this.authorization=authorization;
    }
    @Override public List<Summary> current(Collection<UUID> ids) {
        if(ids==null||ids.isEmpty())return List.of();
        var actor=ActorContext.required();
        var scope=authorization.resolveScope(new PermissionCheck(actor.organizationId(),actor.userId(),"eln.view.view","EXPERIMENT",null,"READ"));
        if(!scope.allowed())return List.of();
        var filter=new ExperimentRepository.DataScopeFilter(scope.scopeType(),actor.userId(),scope.targetIds());
        var unique=ids.stream().filter(Objects::nonNull).distinct().toList();
        var result=new ArrayList<Summary>();
        try {
            for(int start=0;start<unique.size();start+=500)
                result.addAll(repository.statuses(actor.organizationId(),unique.subList(start,Math.min(start+500,unique.size())),filter));
        } catch (org.springframework.dao.DataAccessException exception) {
            log.warn("Linked experiment statuses unavailable",exception);
            return List.of();
        }
        return List.copyOf(result);
    }
}
