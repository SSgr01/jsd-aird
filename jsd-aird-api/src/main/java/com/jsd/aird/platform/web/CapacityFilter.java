package com.jsd.aird.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.resource.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import io.micrometer.core.instrument.MeterRegistry;

/** Runs before security/multipart parsing, so rejected work consumes no upload spool. */
@Order(Ordered.HIGHEST_PRECEDENCE+20)
public class CapacityFilter extends OncePerRequestFilter {
    public static final String OWNED_DOWNLOAD=CapacityFilter.class.getName()+".ownedDownload";
    private final CapacityProperties limits;private final TemporarySpace temporary;private final ObjectMapper json;
    private final BoundedGate requests,writes,heavy,streams;
    private org.springframework.web.cors.CorsConfigurationSource cors;
    public void setCorsSource(org.springframework.web.cors.CorsConfigurationSource source){cors=source;}
    public CapacityFilter(CapacityProperties limits,TemporarySpace temporary,ObjectMapper json,MeterRegistry metrics){
        this(limits,temporary,json,metrics,new ResourceAdmission(limits));
    }
    public CapacityFilter(CapacityProperties limits,TemporarySpace temporary,ObjectMapper json,MeterRegistry metrics,ResourceAdmission admission){
        this.limits=limits;this.temporary=temporary;this.json=json;
        requests=admission.requests;writes=admission.writes;heavy=admission.heavy;streams=admission.streams;
        metrics.gauge("aird.capacity.requests.active",requests,BoundedGate::active);metrics.gauge("aird.capacity.writes.active",writes,BoundedGate::active);metrics.gauge("aird.capacity.heavy.active",heavy,BoundedGate::active);metrics.gauge("aird.capacity.streams.active",streams,BoundedGate::active);metrics.gauge("aird.capacity.temporary.reserved",temporary,TemporarySpace::reserved);
        metrics.gauge("aird.capacity.requests.rejected",requests,BoundedGate::rejections);
        metrics.gauge("aird.capacity.writes.rejected",writes,BoundedGate::rejections);metrics.gauge("aird.capacity.heavy.rejected",heavy,BoundedGate::rejections);metrics.gauge("aird.capacity.streams.rejected",streams,BoundedGate::rejections);
        metrics.gauge("aird.capacity.requests.queued",requests,BoundedGate::queued);metrics.gauge("aird.capacity.heavy.queued",heavy,BoundedGate::queued);
        metrics.gauge("aird.capacity.memory.estimated.reserved",limits,ignored->WorkingMemory.reserved());
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !request.getRequestURI().startsWith("/api/") || request.getMethod().equals("OPTIONS");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        var leases=new ArrayList<AutoCloseable>();
        try{
            String path=request.getRequestURI();boolean stream=Objects.toString(request.getHeader("Accept"),"").contains("text/event-stream") || path.endsWith("/events") || path.matches("/api/v1/files/[0-9a-fA-F-]+/content");
            boolean upload=Objects.toString(request.getContentType(),"").toLowerCase(Locale.ROOT).startsWith("multipart/");
            boolean read=request.getMethod().equals("GET") || request.getMethod().equals("HEAD");
            boolean workspaceCommand=path.contains("workspace") || path.endsWith("/workbook-draft") || path.endsWith("/workbook-snapshot") || path.endsWith("/draft") || path.endsWith("/edit-model") || path.endsWith("/word-preview") || path.equals("/api/v1/quality/records/batch");
            boolean expensive=upload || workspaceCommand || path.endsWith("/re-extract") || path.endsWith("/publish") || path.endsWith("/export") || path.endsWith("/rollback");
            leases.add((stream?streams:requests).enter(limits.admissionMillis()));
            if(!read || upload || expensive)leases.add((expensive?heavy:writes).enter(limits.admissionMillis()));
            if(upload)leases.add(temporary.reserve(545259520L));
            long maximum=workspaceCommand?limits.workspaceJsonBytes():limits.jsonBytes();
            if(!upload && !read && request.getContentLengthLong()>maximum)throw new ApiException(ApiErrorCode.PAYLOAD_TOO_LARGE);
            var kind=upload?OperationBudget.Kind.UPLOAD:expensive?OperationBudget.Kind.HEAVY:read?OperationBudget.Kind.READ:OperationBudget.Kind.WRITE;
            long duration=upload?limits.uploadMillis():expensive?limits.heavyMillis():read?limits.readMillis():limits.writeMillis();
            var uploadInput=upload?request.getInputStream():null;
            try(var budget=stream?null:OperationBudget.open(kind,duration);var deadline=stream?null:new DeadlineInterrupt(duration,()->{if(uploadInput!=null)try{uploadInput.close();}catch(IOException ignored){ }})){
                if(!stream && !upload && !read){long bytes=request.getContentLengthLong()<0?maximum:request.getContentLengthLong();OperationBudget.hold(WorkingMemory.reserve(Math.min(maximum,bytes)*4+65536));}
                try{chain.doFilter(upload||read?request:bounded(request,maximum),response);}
                catch(IOException|ServletException|RuntimeException failure){if(deadline!=null && deadline.expired())throw new ApiException(ApiErrorCode.OPERATION_TIMEOUT);throw failure;}
                if(!stream)OperationBudget.check();
            }
            if(request.isAsyncStarted()){
                var owned=List.copyOf(leases);try { request.getAsyncContext().addListener(new AsyncListener(){
                    public void onComplete(AsyncEvent e){close(owned);}public void onTimeout(AsyncEvent e){close(owned);}public void onError(AsyncEvent e){close(owned);}public void onStartAsync(AsyncEvent e){e.getAsyncContext().addListener(this);}
                });leases.clear(); } catch (IllegalStateException completed) { /* Completed between inspection and listener registration. */ }
            }
        }catch(ApiException error){writeError(request,response,error.errorCode());}
        finally{if(request.getAttribute(OWNED_DOWNLOAD) instanceof AutoCloseable download)try{download.close();}catch(Exception ignored){ }close(leases);}
    }
    private void writeError(HttpServletRequest request,HttpServletResponse response,ApiErrorCode code)throws IOException{
        if(cors!=null && !new org.springframework.web.cors.DefaultCorsProcessor().processRequest(cors.getCorsConfiguration(request),request,response))return;
        if(response.isCommitted())return;response.setStatus(code.httpStatus());response.setContentType("application/json;charset=UTF-8");
        if(code==ApiErrorCode.SERVER_BUSY || code==ApiErrorCode.DATABASE_BUSY)response.setHeader("Retry-After","2");
        String incoming=Objects.toString(request.getHeader("X-Request-Id"),"");
        String id=Objects.toString(response.getHeader("X-Request-Id"),incoming.matches("[a-zA-Z0-9_-]{8,128}")?incoming:UUID.randomUUID().toString());response.setHeader("X-Request-Id",id);
        Object body=request.getRequestURI().startsWith("/api/v1/ai/rnd/")?Map.of("code",code.code(),"message",code.defaultMessage(),"requestId",id):ResponseFactory.error(code,id);
        json.writeValue(response.getOutputStream(),body);
    }
    private static void close(List<AutoCloseable> leases){for(int i=leases.size()-1;i>=0;i--)try{leases.get(i).close();}catch(Exception ignored){ }}
    private static HttpServletRequest bounded(HttpServletRequest request,long maximum){return new HttpServletRequestWrapper(request){
        private ServletInputStream wrapped;
        @Override public ServletInputStream getInputStream()throws IOException{
            if(wrapped!=null)return wrapped;var source=super.getInputStream();wrapped=new ServletInputStream(){
                long total;
                private void count(int count){OperationBudget.check();if(count>0 && (total+=count)>maximum)throw new ApiException(ApiErrorCode.PAYLOAD_TOO_LARGE);}
                public int read()throws IOException{int value=source.read();count(value<0?0:1);return value;}
                public int read(byte[] b,int o,int n)throws IOException{int count=source.read(b,o,n);count(count);return count;}
                public boolean isFinished(){return source.isFinished();}public boolean isReady(){return source.isReady();}public void setReadListener(ReadListener listener){source.setReadListener(listener);}
                public void close()throws IOException{source.close();}
            };return wrapped;
        }
        @Override public BufferedReader getReader()throws IOException{return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
    };}
}
