package com.jsd.aird.ai.rnd.optimization;

import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;

import java.util.Map;
import java.util.Set;

/** Immutable material identities used while projecting one history dataset. */
public record MaterialCatalogSnapshot(Map<String, String> idsByIdentity,
                                      Map<String, String> codesById,
                                      Set<String> validIds) {
    public MaterialCatalogSnapshot {
        idsByIdentity = Map.copyOf(idsByIdentity);
        codesById = Map.copyOf(codesById);
        validIds = Set.copyOf(validIds);
    }

    public String resolve(String identity) {
        var normalized = ConfigurationHashing.normalizeAlias(identity);
        if (normalized.isBlank()) return null;
        var id = idsByIdentity.get(normalized);
        return id == null || id.isBlank() || !validIds.contains(id) ? null : id;
    }

    public String code(String id) {
        return codesById.get(id);
    }

    public static MaterialCatalogSnapshot empty() {
        return new MaterialCatalogSnapshot(Map.of(), Map.of(), Set.of());
    }
}
