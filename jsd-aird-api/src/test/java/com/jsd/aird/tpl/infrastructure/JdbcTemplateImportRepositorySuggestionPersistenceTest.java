package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class JdbcTemplateImportRepositorySuggestionPersistenceTest {

    @Test
    void resolvesLogicalParentBeforeUntrustedModelUuid() {
        var parentId = UUID.randomUUID();
        var relationIds = new LinkedHashMap<String, UUID>();
        relationIds.put("region-1", parentId);

        assertThat(JdbcTemplateImportRepository.resolveParentSuggestionId(
                UUID.randomUUID().toString(), "region-1",
                new LinkedHashSet<>(List.of(UUID.randomUUID())), relationIds))
                .isEqualTo(parentId);
    }

    @Test
    void rejectsAnExplicitUuidThatIsNotGeneratedInThisBatch() {
        var parentId = UUID.randomUUID();
        var untrustedId = UUID.randomUUID();

        assertThat(JdbcTemplateImportRepository.resolveParentSuggestionId(
                untrustedId.toString(), "", new LinkedHashSet<>(List.of(parentId)),
                new LinkedHashMap<>())).isNull();
    }

    @Test
    void insertsParentBeforeChildEvenWhenInputArrivesChildFirst() {
        var parent = UUID.randomUUID();
        var child = UUID.randomUUID();

        assertThat(JdbcTemplateImportRepository.parentFirstOrder(List.of(
                new JdbcTemplateImportRepository.ParentLink(child, parent),
                new JdbcTemplateImportRepository.ParentLink(parent, null))))
                .containsExactly(parent, child);
    }
}
