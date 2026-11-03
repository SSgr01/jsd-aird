package com.jsd.aird.kb.api;

import com.fasterxml.jackson.databind.JsonNode;

/** JSON clients preserve coordinate values but not integer versus floating-point tokens. */
public final class SourceAnchorComparison {
    private SourceAnchorComparison() { }
    public static boolean same(JsonNode left, JsonNode right) {
        if (left == null || right == null) return left == right;
        return left.equals((a, b) -> a.isNumber() && b.isNumber()
            ? a.decimalValue().compareTo(b.decimalValue()) : a.equals(b) ? 0 : 1, right);
    }
}
