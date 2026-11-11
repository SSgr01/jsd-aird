package com.jsd.aird.ai.application;

import com.jsd.aird.shared.business.BusinessReadPort;
import com.jsd.aird.shared.security.Actor;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Explicit local administrator access while domain record policies are being integrated. */
@Component
@Profile("local & !prod")
@ConditionalOnProperty(prefix = "app.ai.harness", name = "business-local-access", havingValue = "true")
public class LocalBusinessAccess {
    private static final Set<String> COMPUTE_TYPES = Set.of("AI_PREDICTION", "AI_FORMULA_DESIGN", "AI_EXPERIMENT_OPTIMIZATION");
    private final Set<String> readTypes;

    public LocalBusinessAccess(List<BusinessReadPort> readers) {
        readTypes = readers.stream().flatMap(reader -> reader.resourceTypes().stream()).collect(Collectors.toUnmodifiableSet());
    }

    boolean allowed(Actor actor, String type, String operation) {
        if (actor == null || actor.organizationId() == null || actor.userId() == null || !"SYSTEM_ADMIN".equals(actor.role())) return false;
        if (COMPUTE_TYPES.contains(type)) return Set.of("READ", "EXECUTE").contains(operation);
        return readTypes.contains(type) && Set.of("SEARCH", "READ", "EXPORT").contains(operation);
    }
}
