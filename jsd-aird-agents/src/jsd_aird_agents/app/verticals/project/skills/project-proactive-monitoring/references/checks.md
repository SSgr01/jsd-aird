# Check semantics

Dates use the configured IANA timezone. An active task is overdue only after its
due date, due soon within the inclusive configured window, and stalled after the
configured number of calendar days without a progress record. Missing progress
timestamps are unknown. Completed and cancelled tasks are excluded.

Progress deviation is planned percentage minus actual percentage, warning above
10 percentage points. Missing percentages are unknown. Milestone checks require
explicit prerequisite tasks and published documents. Undefined requirements are
unknown; unresolved or missing prerequisites are reported as gaps.

Weekly drafts count completions from completion timestamps in the local week.
Comparison requires a successful, authorized baseline at or before Monday local
midnight and no more than 14 days older. Approval reminders check pending status,
wait threshold and expiry. They never approve or execute the pending action.
