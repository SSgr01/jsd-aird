package com.jsd.aird.bootstrap.configuration;

import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class UploadConnectorConfiguration {
    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> uploadConnector(
            @Value("${spring.servlet.multipart.location}") String temporary){
        try{Files.createDirectories(Path.of(temporary));}catch(java.io.IOException error){throw new IllegalStateException("Cannot create multipart spool",error);}
        return factory->factory.addConnectorCustomizers(connector->{
            connector.setProperty("disableUploadTimeout","false");connector.setProperty("connectionUploadTimeout","30000");
            connector.setProperty("maxThreads","48");connector.setProperty("acceptCount","32");
        });
    }
}
