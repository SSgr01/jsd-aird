package com.jsd.aird.kb.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;

@Component
public class BibliographyJobHandler implements AsyncJobHandler {
    private final BibliographyService service;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BibliographyJobHandler.class);
    public BibliographyJobHandler(BibliographyService service) {this.service=service;}
    @Override public boolean supports(String type) {return BibliographyService.JOB_TYPE.equals(type);}
    @Override public JsonNode handle(JsonNode payload) {return service.process(payload,null);}
    @Override public JsonNode handle(JsonNode payload,ExecutionContext context) {return service.process(payload,context);}
    @Override public void handleTerminalFailure(JsonNode payload,Exception failure,ExecutionContext context) {service.terminalFailure(payload,context);}
    @EventListener public void parsed(BibliographyService.Parsed event) {
        try {service.parsed(event);} catch(RuntimeException failure) {
            // Metadata queue failure must not change successful parser/publication state. Operators can submit backfill.
            log.error("Bibliography scheduling failed for version {}; submit backfill after recovery ({})",event.versionId(),failure.getClass().getSimpleName());
        }
    }
}
