import asyncio
import json

from aird_agent.core.stage_timing import StageTimingMiddleware, fields, measure


async def test_timing_is_request_local_and_records_no_request_content(caplog):
    caplog.set_level('INFO', logger='uvicorn.error')
    async def app(scope, receive, send):
        fields(session_id=scope['session_id'])
        with measure(scope['session_id']):
            await asyncio.sleep(0)
        await send({'type':'http.response.start', 'status':200, 'headers':[]})
        await send({'type':'http.response.body', 'body':b'ok'})
    middleware = StageTimingMiddleware(app, enabled=True)
    async def invoke(sid):
        messages = []
        async def send(message):
            messages.append(message)
        await middleware({'type':'http', 'path':'/internal/v1/harness/dsh/register',
            'session_id':sid, 'headers':[(b'authorization',b'secret-do-not-log')], 'body':'private-evidence'}, None, send)
        assert b'x-jsd-trace-id' in dict(messages[0]['headers'])
    await asyncio.gather(invoke('first'), invoke('second'))
    records = [json.loads(row.message.split('HARNESS_STAGE ')[1]) for row in caplog.records if 'HARNESS_STAGE ' in row.message]
    assert len(records) == 2
    for row in records:
        assert set(row['durations_ms']) == {row['session_id']}
        assert row['status'] == 200 and row['response_bytes'] == 2
    assert 'secret-do-not-log' not in caplog.text and 'private-evidence' not in caplog.text
