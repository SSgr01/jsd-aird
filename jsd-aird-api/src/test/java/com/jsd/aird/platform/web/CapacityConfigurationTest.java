package com.jsd.aird.platform.web;

import com.jsd.aird.bootstrap.configuration.CapacityConfiguration;
import com.jsd.aird.shared.resource.CapacityProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.assertj.core.api.Assertions.*;

class CapacityConfigurationTest {
    @Test void configurationBuildsOnePrimaryBudgetedDataSourceWithoutOpeningBusinessConnections(){
        new ApplicationContextRunner().withUserConfiguration(CapacityConfiguration.class,JacksonAutoConfiguration.class,org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration.class)
            .withBean("corsConfigurationSource",org.springframework.web.cors.CorsConfigurationSource.class,org.springframework.web.cors.UrlBasedCorsConfigurationSource::new)
            .withBean(MeterRegistry.class,SimpleMeterRegistry::new)
            .withPropertyValues("spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unused","spring.datasource.username=unused","spring.datasource.hikari.maximum-pool-size=10","spring.datasource.hikari.connection-timeout=1000")
            .run(context->{assertThat(context).hasNotFailed().hasSingleBean(CapacityFilter.class).hasSingleBean(CapacityProperties.class);var pool=context.getBean(HikariDataSource.class);assertThat(pool.getMaximumPoolSize()).isEqualTo(10);assertThat(pool.getConnectionTimeout()).isEqualTo(1000);assertThat(context.getBean(javax.sql.DataSource.class)).isInstanceOf(CapacityConfiguration.BudgetDataSource.class);});
    }
}
