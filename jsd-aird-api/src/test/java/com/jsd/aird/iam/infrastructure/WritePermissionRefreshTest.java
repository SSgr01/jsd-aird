package com.jsd.aird.iam.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.*;
import com.jsd.aird.shared.security.*;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

class WritePermissionRefreshTest {
    @Test void cachedClientPermissionsCannotAuthorizeARevokedWrite() throws Exception {
        var allowed=new AtomicBoolean(true);var checks=new AtomicInteger();var executions=new AtomicInteger();
        var filter=new PermissionRouteFilter(check->{checks.incrementAndGet();return new PermissionDecision(allowed.get(),check.permissionCode(),"ALLOW","ALL","TEST");},new ObjectMapper().findAndRegisterModules(),false);
        var org=UUID.randomUUID();var user=UUID.randomUUID();var id=UUID.randomUUID();
        ActorContext.set(new Actor(org,user,"reviewer"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reviewer",null,java.util.List.of()));
        try{
            var first=new MockHttpServletRequest("POST","/api/v1/experiments/"+id+"/approve");
            filter.doFilter(first,new MockHttpServletResponse(),(request,response)->executions.incrementAndGet());
            allowed.set(false);
            var second=new MockHttpServletRequest("POST","/api/v1/experiments/"+id+"/approve");
            second.addHeader("X-Cached-Permission","experiment.approve");
            var response=new MockHttpServletResponse();
            filter.doFilter(second,response,(request,reply)->executions.incrementAndGet());
            assertThat(checks).hasValue(2);assertThat(executions).hasValue(1);assertThat(response.getStatus()).isEqualTo(403);
        }finally{ActorContext.clear();SecurityContextHolder.clearContext();}
    }
}
