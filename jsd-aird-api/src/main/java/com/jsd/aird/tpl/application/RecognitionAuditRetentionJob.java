package com.jsd.aird.tpl.application;

import com.jsd.aird.tpl.application.port.TemplateImportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes model request/response bodies after 90 days while retaining audit metadata. */
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class RecognitionAuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RecognitionAuditRetentionJob.class);
    private final TemplateImportRepository repository;

    public RecognitionAuditRetentionJob(TemplateImportRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "${app.recognition.audit-cleanup-cron:0 30 2 * * *}")
    public void purgeExpiredPayloads() {
        var purged = repository.purgeExpiredRecognitionPayloads();
        if (purged > 0) log.info("Purged {} expired recognition audit payloads", purged);
    }
}
