package com.jsd.aird.ai.rnd.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "JSD_AIRD_CATALOG_DB_TEST", matches = "true")
class PredictionCatalogPostgresTest {
    @Test
    void readsTargetUnitsAndMethodsFromTheCurrentVersionInPostgres() {
        var dataSource = new DriverManagerDataSource(
                System.getenv("JSD_AIRD_DATASOURCE_URL"),
                System.getenv("JSD_AIRD_DATASOURCE_USERNAME"),
                System.getenv("JSD_AIRD_DATASOURCE_PASSWORD"));
        var jdbc = new JdbcTemplate(dataSource);
        var organizationId = jdbc.queryForObject("""
                SELECT organization_id FROM ai.prediction_target
                WHERE current_version_id IS NOT NULL ORDER BY created_at LIMIT 1
                """, UUID.class);
        var catalog = new PredictionRepository(jdbc, new ObjectMapper()).targetCatalog(organizationId);
        assertThat(catalog).isNotEmpty();
        var configured = catalog.stream().filter(item -> item.unit() != null).findFirst().orElseThrow();
        var expected = jdbc.queryForMap("""
                SELECT unit, coalesce(definition_jsonb->>'testMethod',observation_semantics_jsonb->>'testMethod') method,
                       coalesce(definition_jsonb->>'testStage',observation_semantics_jsonb->>'testStage') stage
                FROM ai.target_version WHERE id=(
                    SELECT current_version_id FROM ai.prediction_target WHERE id=? AND organization_id=?)
                """, configured.id(), organizationId);
        assertThat(configured.unit()).isEqualTo(expected.get("unit"));
        assertThat(configured.testMethod()).isEqualTo(expected.get("method"));
        assertThat(configured.testStage()).isEqualTo(expected.get("stage"));
    }
}
