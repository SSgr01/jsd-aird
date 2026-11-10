package com.jsd.aird.shared.business;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Business facts only: never an editor workbook, permission result or model protocol. */
public record BusinessRecordView(ObjectNode ref, String title, ObjectNode facts,
                                 List<Relation> relations, String path) {
    public BusinessRecordView { relations = List.copyOf(relations); }
    public record Relation(String name, ObjectNode target) { }
}
