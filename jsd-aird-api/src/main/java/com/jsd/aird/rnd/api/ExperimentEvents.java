package com.jsd.aird.rnd.api;

// Public event names consumed by project/data/AI adapters. Only completed versions are publishable facts.
public final class ExperimentEvents {
    public static final String CREATED = "experiment.created.v1";
    public static final String SUBMITTED = "experiment.submitted.v1";
    public static final String PUBLISHED = "experiment.published.v1";
    public static final String VERSION_PUBLISHED = "experiment.version.published.v1";
    public static final String VOIDED = "experiment.voided.v1";
    public static final String IMPORTED = "experiment.imported.v1";
    private ExperimentEvents() {}
}
