package com.jsd.aird.shared.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkbookFieldDefinitionReviewsTest {
    @Test void skipsRegistryAndHistoricalReadsWhenNoDefinitionsNeedConfirmation() {
        var json = new ObjectMapper(); var jdbc = mock(JdbcTemplate.class);
        var reviews = new WorkbookFieldDefinitionReviews(jdbc, json, new JsonCanonicalizer(json));
        var instance = json.createObjectNode();
        var field = instance.withArray("/model/fields").addObject().put("fieldOrigin", "INSTANCE_LOCAL")
                .put("name", "硬度：750g").put("reviewStatus", "CONFIRMED");
        assertThat(reviews.apply(UUID.randomUUID(), UUID.randomUUID(), instance)).isFalse();
        field.put("fieldOrigin", "TEMPLATE").put("reviewStatus", "NEEDS_CONFIRMATION");
        assertThat(reviews.apply(UUID.randomUUID(), UUID.randomUUID(), instance)).isFalse();
        verifyNoInteractions(jdbc);
    }
    @Test void reusesAnExplicitConfirmationStoredBeforeTheRegistryWasIntroduced() {
        var json = new ObjectMapper(); var jdbc = mock(JdbcTemplate.class);
        var reviews = new WorkbookFieldDefinitionReviews(jdbc, json, new JsonCanonicalizer(json));
        var org = UUID.randomUUID(); var version = UUID.randomUUID();
        var instance = json.createObjectNode();
        var field = instance.withArray("/model/fields").addObject().put("fieldOrigin", "INSTANCE_LOCAL")
                .put("name", "硬度：750g").put("groupId", "film").put("valueType", "string").put("unit", "")
                .put("reviewStatus", "CONFIRMED");
        when(jdbc.queryForList(anyString(), eq(String.class), eq(org), eq(version), eq(org), eq(version)))
                .thenReturn(List.of(field.toString()));
        field.put("reviewStatus", "NEEDS_CONFIRMATION");
        assertThat(reviews.apply(org, version, instance)).isTrue();
        field.put("reviewStatus", "NEEDS_CONFIRMATION");
        assertThat(reviews.apply(UUID.randomUUID(), version, instance)).isFalse();
    }
    @Test void reusesOnlyAnExactDefinitionWithinTheSameOrganizationAndTemplateVersion() {
        var json = new ObjectMapper();
        var jdbc = mock(JdbcTemplate.class);
        var canonicalizer = new JsonCanonicalizer(json);
        var reviews = new WorkbookFieldDefinitionReviews(jdbc, json, canonicalizer);
        var org = UUID.randomUUID(); var version = UUID.randomUUID();
        var instance = json.createObjectNode();
        var field = instance.withArray("/model/fields").addObject().put("id", "hardness")
                .put("fieldOrigin", "INSTANCE_LOCAL").put("name", "硬度：750g").put("groupId", "film")
                .put("valueType", "string").put("unit", "").put("reviewStatus", "NEEDS_CONFIRMATION");
        when(jdbc.queryForList(contains("SELECT definition_hash"), eq(org), eq(version)))
                .thenReturn(List.of(java.util.Map.<String,Object>of("definition_hash",canonicalizer.hash(reviews.definition(field)),"definition_text",reviews.definition(field).toString())));
        assertThat(reviews.apply(org, version, instance)).isTrue();
        assertThat(field.path("reviewStatus").asText()).isEqualTo("CONFIRMED");
        field.put("reviewStatus", "NEEDS_CONFIRMATION").put("unit", "kg");
        assertThat(reviews.apply(org, version, instance)).isFalse();
        field.put("unit", "");
        when(jdbc.queryForList(contains("SELECT definition_hash"), eq(org), eq(version))).thenReturn(List.of());
        assertThat(reviews.apply(org, version, instance)).isFalse();
    }
    @Test void reusesAcrossPhysicalSheetsWithoutMergingDifferentConditions() {
        var json=new ObjectMapper();var jdbc=mock(JdbcTemplate.class);var org=UUID.randomUUID();var version=UUID.randomUUID();
        var reviews=new WorkbookFieldDefinitionReviews(jdbc,json,new JsonCanonicalizer(json));
        var instance=json.createObjectNode();var field=instance.withArray("/model/fields").addObject()
                .put("fieldOrigin","INSTANCE_LOCAL").put("name","硬度：750g").put("groupId","s1234567890abcdef.film")
                .put("valueType","string").put("unit","").put("reviewStatus","CONFIRMED");
        var stored=field.deepCopy();field.put("name","硬度: 750g").put("groupId","sfedcba0987654321.film").put("reviewStatus","NEEDS_CONFIRMATION");
        when(jdbc.queryForList(contains("definition_jsonb::text"),eq(org),eq(version))).thenReturn(List.of(java.util.Map.<String,Object>of("definition_hash","legacy","definition_text",stored.toString())));
        assertThat(reviews.apply(org,version,instance)).isTrue();
        field.put("reviewStatus","NEEDS_CONFIRMATION").put("name","硬度：500g");
        assertThat(reviews.apply(org,version,instance)).isFalse();
        field.put("name","硬度：750g");
        assertThat(reviews.apply(org,UUID.randomUUID(),instance)).isFalse();
    }
}
