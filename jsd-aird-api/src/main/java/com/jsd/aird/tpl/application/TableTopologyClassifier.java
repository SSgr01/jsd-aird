package com.jsd.aird.tpl.application;

import java.util.List;

/**
 * Classifies only the physical repetition topology of a rectangular table surface.
 * Business words, file names, sheet names and fixed coordinates are deliberately
 * absent from the input so the result is reusable across customer templates.
 */
public final class TableTopologyClassifier {

    public enum Topology {
        COLUMN_TABLE,
        UNKNOWN
    }

    public Topology classify(Evidence evidence) {
        return analyze(evidence).topology();
    }

    /**
     * Returns the physical conclusion and the evidence used to reach it. Both
     * populated and blank identity bands are valid column evidence; genuinely
     * missing or competing axes remain UNKNOWN.
     */
    public Classification analyze(Evidence evidence) {
        if (evidence == null || evidence.dataColumnCount() < 2 || evidence.bodyRowCount() < 2) {
            return new Classification(Topology.UNKNOWN, List.of(), evidence);
        }
        var candidates = new java.util.ArrayList<Topology>();
        // A column table needs a real left attribute band and repeated record
        // columns.  The identity row may be populated (completed report) or
        // blank (new input template); both are valid column-table evidence.
        if ("COLUMN".equals(evidence.recordAxis())
                && evidence.leftLabelRowCount() >= 3
                && evidence.dataColumnCount() >= 3
                && evidence.bodyRowCount() >= 4) {
            candidates.add(Topology.COLUMN_TABLE);
        }
        if (candidates.size() == 1) {
            return new Classification(candidates.get(0), List.copyOf(candidates), evidence);
        }
        return new Classification(Topology.UNKNOWN, List.copyOf(candidates), evidence);
    }

    public record Classification(Topology topology, List<Topology> candidates, Evidence evidence) {
    }

    public record Evidence(
            String recordAxis,
            boolean blankIdentityBand,
            int topHeaderValueCount,
            int leftLabelRowCount,
            int dataColumnCount,
            int bodyRowCount,
            boolean rectangularDataSurface,
            boolean blankColumnHeaderBand,
            boolean blankRowHeaderBand,
            String columnHeaderRange,
            String rowHeaderRange,
            String dataRange,
            int rowLabelDepth,
            boolean formulaTopologyPresent
    ) {
        public Evidence(
                String recordAxis,
                boolean blankIdentityBand,
                int topHeaderValueCount,
                int leftLabelRowCount,
                int dataColumnCount,
                int bodyRowCount,
                boolean rectangularDataSurface
        ) {
            this(recordAxis, blankIdentityBand, topHeaderValueCount, leftLabelRowCount,
                    dataColumnCount, bodyRowCount, rectangularDataSurface, blankIdentityBand, false,
                    "", "", "", leftLabelRowCount, false);
        }
    }
}
