-- Complex Agent Runs retain Graph execution, including active human approvals.
ALTER TABLE agent_runtime.runs ADD COLUMN execution_kind text NOT NULL DEFAULT 'graph'
    CHECK(execution_kind IN ('graph','task'));
ALTER TABLE agent_runtime.runs ADD COLUMN execution_version text;
-- Task progress reuses the existing Run/steps/calls/evidence/event ledgers.
-- The fixed-monitor Graph implementation is retired. Do not reinterpret its
-- checkpoint/step payloads as ordinary task progress; users create a new Run.
UPDATE agent_runtime.runs SET status='CANCELLED',cancel_requested=true,
    worker_id=NULL,lease_until=NULL,resume=NULL,interrupt=NULL,updated_at=now(),
    error='{"code":"MONITORING_EXECUTION_RETIRED","message":"固定巡检执行方式已升级，请重新创建巡检 Run","retryable":false}'::jsonb
WHERE pack_ids='["project"]'::jsonb AND monitoring<>'{}'::jsonb
    AND workflow_id IN ('project-progress-inspection','project-task-followup',
        'project-milestone-precheck','project-weekly-report','project-approval-reminder')
    AND status NOT IN ('SUCCEEDED','CANCELLED');
