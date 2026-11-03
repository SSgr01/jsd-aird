package com.jsd.aird.ai.api;
/** Compatibility facade for the shared evidence identity implementation. */
public final class EvidenceProtocol {
    private EvidenceProtocol() { }
    public static String evidenceId(com.fasterxml.jackson.databind.JsonNode source) {return com.jsd.aird.shared.evidence.EvidenceProtocol.evidenceId(source);}
    public static String sha(String text) {return com.jsd.aird.shared.evidence.EvidenceProtocol.sha(text);}
    public static com.fasterxml.jackson.databind.node.ObjectNode candidate(String label,String text,com.fasterxml.jackson.databind.JsonNode source) {return com.jsd.aird.shared.evidence.EvidenceProtocol.candidate(label,text,source);}
}
