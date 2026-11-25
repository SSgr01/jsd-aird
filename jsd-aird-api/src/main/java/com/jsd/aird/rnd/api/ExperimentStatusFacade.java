package com.jsd.aird.rnd.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Current, permission-filtered summaries; never loads document snapshots. */
public interface ExperimentStatusFacade {
    List<Summary> current(Collection<UUID> experimentIds);
    record Summary(UUID experimentId, UUID versionId, String experimentNo, String title, String status) {}
}
