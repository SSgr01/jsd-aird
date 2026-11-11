import pytest
from fastapi import HTTPException

from aird_agent.sandbox.broker import Execution
from aird_agent.sandbox.jobs import BrokerJobs


async def test_cancel_before_upload_prevents_dispatch_and_survives_restart(tmp_path, principal):
    class Runner:
        async def execute(self, *args):
            pytest.fail('A cancelled command must not start')

    job = Execution(organization_id=principal.organization_id, run_id=principal.actor_id,
        execution_id='d' * 32, script='never run', language='shell')
    jobs = BrokerJobs(tmp_path, Runner())
    assert (await jobs.cancel(job.execution_id))['status'] == 'CANCELLED'
    restored = BrokerJobs(tmp_path, Runner())
    receipt = await restored.submit(job, [])
    assert receipt['status'] == 'CANCELLED' and receipt['request_hash']
    assert not restored.tasks
    assert await restored.submit(job, []) == receipt
    with pytest.raises(HTTPException, match='EXECUTION_ID_CONFLICT'):
        await restored.submit(job.model_copy(update={'script': 'different command'}), [])
