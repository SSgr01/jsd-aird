"""Bind a native one-shot decision to the exact public literature tool call."""

import time
from fastapi import HTTPException
from jsonschema import ValidationError, validate
from aird_agent.core.contracts import digest

EXTERNAL_TOOLS = frozenset({'bibliography_lookup', 'literature_search', 'literature_expand', 'literature_acquire'})


async def native_literature_approval(service, principal, body):
    name, call_id, arguments = body.get('name'), body.get('call_id'), body.get('arguments')
    if name not in EXTERNAL_TOOLS or body.get('outcome') != 'allowed-once' or not isinstance(call_id, str) or not 1 <= len(call_id) <= 200:
        raise HTTPException(422, 'INVALID_LITERATURE_APPROVAL')
    run = await service.session_run(principal, body)
    if run.get('automation'):
        raise HTTPException(403, 'BACKGROUND_READ_ONLY')
    await service.authorized_topic(principal, run)
    spec = next((tool for tool in service.tools_for(run) if tool['name'] == name), None)
    if spec is None or name not in run.get('active_tools', []):
        raise HTTPException(403, 'CAPABILITY_NOT_AUTHORIZED')
    try:
        validate(arguments, spec['parameters'])
    except ValidationError:
        raise HTTPException(422, 'INVALID_LITERATURE_ARGUMENTS') from None
    await service.bindings.authorize_tool(principal, run['id'], name, arguments)
    key = digest({'tool': name, 'arguments': arguments, 'call_id': call_id})
    async with service.store.transaction(principal, run['id']) as connection:
        current = await service.active(principal, run['id'], body['epoch'], connection)
        current.setdefault('approvals', {})[key] = {'decision': 'APPROVED', 'call_id': call_id, 'expires_at': time.time() + 900}
        current['status'] = 'RUNNING'
        current.pop('pending_approval', None)
        await service.store.update(principal, 'run', current, connection)
        await service.store.event(principal, run['id'], 'approval.decided', {'id': key, 'tool': name, 'call_id': call_id, 'decision': 'APPROVED'}, connection)
    return {'approved': True, 'call_id': call_id}
