package com.jsd.aird.bootstrap.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI jsdAirdOpenApi() {
        return new OpenAPI().info(new Info()
                .title("JSD AIRD API")
                .description("杰事达研发数字化与AI平台接口")
                .version("v1"));
    }
}

