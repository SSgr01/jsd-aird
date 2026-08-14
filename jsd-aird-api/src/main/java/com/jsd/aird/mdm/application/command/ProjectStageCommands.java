package com.jsd.aird.mdm.application.command;

import com.jsd.aird.mdm.domain.model.StageStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ProjectStageCommands {
    private ProjectStageCommands() {
    }

    public record Create(String name, String stageCode, StageStatus status, String owner, String description,
                         LocalDate plannedStart, LocalDate plannedEnd) {
    }

    public record Update(String name, StageStatus status, String owner, String description,
                         LocalDate plannedStart, LocalDate plannedEnd, String transitionReason, long version) {
    }

    public record ReorderItem(UUID id, long version) {
    }

    public record Reorder(List<ReorderItem> items) {
        public Reorder {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
