package com.jsd.aird.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.resource.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class MultipartDeadlineTest {
    @TempDir Path root;
    @Test void slowlyDrippingMultipartCannotExtendTheAbsoluteUploadDeadline()throws Exception{
        var limits=new Binder(new MapConfigurationPropertySource(Map.of("app.capacity.upload-millis",150))).bindOrCreate("app.capacity",Bindable.of(CapacityProperties.class));
        var space=new TemporarySpace(limits.temporaryBytes(),root.resolve("owned"));
        var admission=new ResourceAdmission(limits);
        var filter=new CapacityFilter(limits,space,new ObjectMapper().findAndRegisterModules(),new SimpleMeterRegistry(),admission);
        var tomcat=new Tomcat();tomcat.setBaseDir(root.resolve("tomcat").toString());tomcat.setPort(0);tomcat.getConnector().setProperty("address","127.0.0.1");
        var context=tomcat.addContext("",root.toString());
        var servlet=Tomcat.addServlet(context,"upload",new HttpServlet(){@Override protected void doPost(HttpServletRequest request,HttpServletResponse response)throws IOException,ServletException{request.getParts();response.getWriter().write("complete");}});
        servlet.setMultipartConfigElement(new MultipartConfigElement(root.toString(),512L*1024*1024,520L*1024*1024,0));context.addServletMappingDecoded("/api/upload","upload");
        var definition=new FilterDef();definition.setFilterName("capacity");definition.setFilter(filter);context.addFilterDef(definition);
        var mapping=new FilterMap();mapping.setFilterName("capacity");mapping.addURLPattern("/*");context.addFilterMapBefore(mapping);
        try{
            tomcat.start();
            try(var socket=new Socket("127.0.0.1",tomcat.getConnector().getLocalPort());var writer=Executors.newSingleThreadExecutor()){
                socket.setSoTimeout(3000);
                var sending=writer.submit(()->{
                    try{
                        var output=socket.getOutputStream();output.write(("POST /api/upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100000\r\nContent-Type: multipart/form-data; boundary=boundary\r\nConnection: close\r\n\r\n--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.flush();
                        for(int n=0;n<20;n++){output.write(new byte[32]);output.flush();Thread.sleep(30);}
                        socket.shutdownOutput();
                    }catch(IOException ignored){ }catch(InterruptedException error){Thread.currentThread().interrupt();}
                });
                long start=System.nanoTime();String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                assertThat(response).contains("504","OPERATION_TIMEOUT");assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(2500);
                sending.get(2,TimeUnit.SECONDS);
            }
            assertThat(admission.heavy.active()).isZero();assertThat(space.reserved()).isZero();
        }finally{tomcat.stop();tomcat.destroy();}
    }
}
