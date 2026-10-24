package com.jsd.aird.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.resource.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class CapacityFilterTest {
    @Test void earlyCapacityErrorsKeepCorsAndTheRequestId()throws Exception{
        var filter=filter(Map.of("app.capacity.json-bytes",16));
        var configuration=new org.springframework.web.cors.CorsConfiguration();configuration.setAllowedOrigins(List.of("https://client.example"));configuration.setAllowedMethods(List.of("POST"));configuration.setExposedHeaders(List.of("Retry-After","X-Request-Id"));configuration.setAllowCredentials(true);
        var source=new org.springframework.web.cors.UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",configuration);filter.setCorsSource(source);
        var request=new MockHttpServletRequest("POST","/api/v1/projects");request.setContent(new byte[17]);request.addHeader("Origin","https://client.example");request.addHeader("X-Request-Id","capacity-correlation-123");var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->{throw new AssertionError("Oversize request entered business handling");});
        assertThat(response.getStatus()).isEqualTo(413);assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://client.example");assertThat(response.getHeader("Access-Control-Expose-Headers")).contains("Retry-After");assertThat(response.getHeader("X-Request-Id")).isEqualTo("capacity-correlation-123");
    }
    @TempDir Path directory;
    private CapacityFilter filter(Map<String,Object> values){
        var limits=new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("app.capacity",Bindable.of(CapacityProperties.class));
        return new CapacityFilter(limits,new TemporarySpace(limits.temporaryBytes(),directory),new ObjectMapper().findAndRegisterModules(),new SimpleMeterRegistry());
    }
    @Test void chunkedJsonCountsRealBytesAndReleasesAdmissionAfterFailure()throws Exception{
        var filter=filter(Map.of("app.capacity.json-bytes",16,"app.capacity.requests",1));
        var request=new MockHttpServletRequest("POST","/api/v1/projects"){@Override public long getContentLengthLong(){return -1;}};
        request.setContent(new byte[17]);var denied=new MockHttpServletResponse();
        filter.doFilter(request,denied,(req,res)->req.getInputStream().readAllBytes());
        assertThat(denied.getStatus()).isEqualTo(413);assertThat(denied.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
        var next=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("GET","/api/v1/projects"),next,(req,res)->res.getWriter().write("ok"));
        assertThat(next.getContentAsString()).isEqualTo("ok");
    }
    @Test void saturatedAdmissionRejectsMultipartBeforeParsingAndHealthRemainsAvailable()throws Exception{
        var filter=filter(Map.of("app.capacity.requests",1,"app.capacity.request-queue",0,"app.capacity.admission-millis",1));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            var active=pool.submit(()->{try{filter.doFilter(new MockHttpServletRequest("GET","/api/v1/projects"),new MockHttpServletResponse(),(req,res)->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});}catch(Exception error){throw new RuntimeException(error);}});
            try{
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                var multipart=new MockHttpServletRequest("POST","/api/v1/files");multipart.setContentType("multipart/form-data; boundary=x");
                var response=new MockHttpServletResponse();
                filter.doFilter(multipart,response,(req,res)->{throw new AssertionError("Rejected upload reached multipart/business processing");});
                assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getHeader("Retry-After")).isEqualTo("2");assertThat(response.getContentAsString()).contains("SERVER_BUSY");
                var health=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("GET","/actuator/health"),health,(req,res)->res.getWriter().write("UP"));assertThat(health.getContentAsString()).isEqualTo("UP");
            }finally{release.countDown();active.get(3,TimeUnit.SECONDS);}
        }
    }
    @Test void asyncLeaseStaysOwnedUntilActualCompletion()throws Exception{
        var filter=filter(Map.of("app.capacity.requests",1,"app.capacity.request-queue",0));
        var request=new MockHttpServletRequest("GET","/api/v1/projects");request.setAsyncSupported(true);
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->req.startAsync());
        var denied=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("GET","/api/v1/projects"),denied,(req,res)->{});assertThat(denied.getStatus()).isEqualTo(503);
        request.getAsyncContext().complete();
        var recovered=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("GET","/api/v1/projects"),recovered,(req,res)->{});assertThat(recovered.getStatus()).isEqualTo(200);
    }
    @Test void deadlineInterruptsWaitingWorkAndDoesNotPoisonTheNextRequest()throws Exception{
        var filter=filter(Map.of("app.capacity.read-millis",40));var response=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/api/v1/projects"),response,(req,res)->{try{Thread.sleep(5000);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
        assertThat(response.getStatus()).isEqualTo(504);assertThat(Thread.currentThread().isInterrupted()).isFalse();
        var next=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("GET","/api/v1/projects"),next,(req,res)->{});assertThat(next.getStatus()).isEqualTo(200);
    }
}
