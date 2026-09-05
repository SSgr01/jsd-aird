package com.jsd.aird.tpl.application;

/** Customer-facing grouping without erasing the diagnostic issue code. */
public final class TemplateQualityIssueCategory {

    private TemplateQualityIssueCategory() {
    }

    public static String fromIssueType(String issueType) {
        return switch (issueType == null ? "" : issueType) {
            case "MIXED_CELL_ROLES", "FIELD_RELATION_UNCLEAR" -> "FIELD_RELATION_UNCLEAR";
            case "HIERARCHY_MISMATCH", "BUSINESS_BLOCK_UNCLEAR" -> "BUSINESS_BLOCK_UNCLEAR";
            case "RECORD_ORIENTATION_MISMATCH", "STRUCTURE_DIRECTION_UNCLEAR",
                    "TABLE_STRUCTURE_UNCLEAR" -> "TABLE_STRUCTURE_UNCLEAR";
            case "EDITABILITY_UNCLEAR" -> "EDITABILITY_UNCLEAR";
            case "VISUAL_PHYSICAL_MISMATCH", "LAYOUT_INCONSISTENT" -> "LAYOUT_INCONSISTENT";
            case "DUPLICATE_MEANING" -> "DUPLICATE_MEANING";
            default -> "OTHER";
        };
    }
}
