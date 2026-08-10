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
        MATRIX,
        UNKNOWN
    }

    public Topology classify(Evidence evidence) {
        return analyze(evidence).topology();
    }

    /**
     * Returns the physical conclusion and the evidence used to reach it.  A
     * blank identity band is deliberately treated as a runtime-member surface,
     * not as proof that a second member axis is absent.  Such a shape remains
     * UNKNOWN unless the independent axes are explicit and complete.
     */
    public Classification analyze(Evidence evidence) {
        if (evidence == null || evidence.dataColumnCount() < 2 || evidence.bodyRowCount() < 2) {
            return new Classification(Topology.UNKNOWN, List.of(), evidence);
        }
        var candidates = new java.util.ArrayList<Topology>();
        if (evidence.explicitColumnMemberCount() >= 2
                && evidence.leftLabelRowCount() >= 2
                && evidence.crossSurfacePresent()) {
            candidates.add(Topology.MATRIX);
        }
        // A column table needs a real left attribute band and repeated record
        // columns, with no runtime/explicit competing member surface.
        if (!evidence.runtimeColumnMemberSurface()
                && evidence.explicitColumnMemberCount() == 0
                && "COLUMN".equals(evidence.recordAxis())
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
            int explicitColumnMemberCount,
            int leftLabelRowCount,
            int dataColumnCount,
            int bodyRowCount,
            boolean crossSurfacePresent,
            boolean runtimeColumnMemberSurface,
            boolean runtimeRowMemberSurface,
            String runtimeColumnMemberRange,
            String runtimeRowMemberRange,
            String crossDataRange,
            int rowLabelDepth,
            boolean formulaTopologyPresent
    ) {
        public Evidence(
                String recordAxis,
                boolean blankIdentityBand,
                int explicitColumnMemberCount,
                int leftLabelRowCount,
                int dataColumnCount,
                int bodyRowCount,
                boolean crossSurfacePresent
        ) {
            this(recordAxis, blankIdentityBand, explicitColumnMemberCount, leftLabelRowCount,
                    dataColumnCount, bodyRowCount, crossSurfacePresent, blankIdentityBand, false,
                    "", "", "", leftLabelRowCount, false);
        }
    }
}
