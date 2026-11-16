"""Administrator-configured filesystem batches. No experiment/import/artifact writes."""
import asyncio
import base64
import hashlib
import json
import os
import shutil
import tempfile
from pathlib import Path
from uuid import UUID

from fastapi import HTTPException

from aird_agent.core.contracts import Principal, digest
from aird_agent.core.workspace import Workspace
from aird_agent.server.security import issue

MAX_BYTES = 8 * 1024 * 1024
EXTENSIONS = {'.xlsx', '.xls', '.doc', '.docx'}
DEFAULT_CONFIG_PATH = Path('/etc/jsd-aird/experiment-conversion-roots.json')


def sha(content):
    return hashlib.sha256(content).hexdigest()


def contained(root, path):
    """Reject symlinks/junctions in every component, including configured roots."""
    root, path = Path(root).absolute(), Path(path).absolute()
    try:
        path.relative_to(root)
    except ValueError:
        raise HTTPException(403, 'FOLDER_PATH_DENIED') from None
    for part in [path, *path.parents]:
        if part.is_symlink() or getattr(part, 'is_junction', lambda: False)():
            raise HTTPException(403, 'FOLDER_LINK_DENIED')
    if not path.resolve().is_relative_to(root.resolve()):
        raise HTTPException(403, 'FOLDER_PATH_DENIED')
    return path


def roots(user, config_path=None):
    config = config_path or os.environ.get('JSD_AIRD_HARNESS_CONVERSION_ROOTS_FILE') or os.environ.get('JSD_AIRD_CONVERSION_ROOTS_FILE')
    path = Path(config) if config else DEFAULT_CONFIG_PATH
    if not path.is_file():
        if config:
            raise HTTPException(503, 'FOLDER_CONFIGURATION_UNAVAILABLE')
        # A deployment without an installed allowlist has no directory access.
        return {}
    values = json.loads(path.read_text(encoding='utf-8-sig'))
    result = {}
    for alias, value in values.items():
        if str(user.organization_id) != value['organization_id'] or str(user.actor_id) not in value['administrator_ids']:
            continue
        paths = {key: contained(Path(value[key]).anchor, Path(value[key])) for key in ('source', 'templates', 'output')}
        if any(not Path(value[key]).is_absolute() for key in paths):
            raise HTTPException(503, 'FOLDER_ABSOLUTE_PATH_REQUIRED')
        if any(a.is_relative_to(b) or b.is_relative_to(a) for a, b in ((paths['source'], paths['output']), (paths['templates'], paths['output']))):
            raise HTTPException(503, 'FOLDER_ROOTS_OVERLAP')
        if not paths['source'].is_dir() or not paths['templates'].is_dir():
            raise HTTPException(503, 'FOLDER_INPUT_DIRECTORY_MISSING')
        result[alias] = {**value, **paths}
    return result


def bounded(path):
    if not path.is_file() or path.stat().st_size > MAX_BYTES:
        raise HTTPException(413, 'FOLDER_FILE_TOO_LARGE_OR_UNAVAILABLE')
    with path.open('rb') as stream:
        data = stream.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise HTTPException(413, 'FOLDER_FILE_TOO_LARGE')
    return data


class FolderConversions:
    def __init__(self, service):
        self.service, self.store = service, service.store
        self.scan_lock = asyncio.Lock()
        self.scans = {}

    def roots(self, user):
        return roots(user, getattr(getattr(self.service, 'settings', None), 'conversion_roots_file', None))

    async def batch(self, user, batch_id):
        try:
            batch_id = str(UUID(batch_id))
        except (ValueError, TypeError, AttributeError):
            raise HTTPException(422, 'FOLDER_BATCH_ID_REQUIRED') from None
        batch = await self.store.get(user, 'folder-batch', batch_id)
        config = self.roots(user).get(batch['alias'])
        if not config or digest(config) != batch['root_hash']:
            raise HTTPException(403, 'FOLDER_CONFIGURATION_CHANGED')
        if user.automation_grant_id and user.automation_grant_id != batch['id']:
            raise HTTPException(403, 'FOLDER_BATCH_DENIED')
        return batch, config

    async def check(self, user, session_id=None):
        batch, _ = await self.batch(user, user.automation_grant_id)
        if user.scope != 'folder-conversion' or batch['status'] not in {'SCANNING', 'RUNNING', 'PAUSED'} or batch['auth_version'] != user.auth_version:
            raise HTTPException(403, 'FOLDER_GRANT_REVOKED')
        if session_id:
            item = await self.item_for_session(user, batch['id'], session_id)
            if item['status'] not in {'RUNNING', 'SUCCEEDED', 'FAILED'}:
                raise HTTPException(403, 'FOLDER_SESSION_DENIED')
        return batch

    async def item_for_session(self, user, batch_id, session_id):
        if not session_id.startswith('folder-'):
            raise HTTPException(403, 'FOLDER_SESSION_DENIED')
        item = await self.store.get(user, 'folder-item', str(UUID(session_id[7:])))
        if item['batch_id'] != batch_id:
            raise HTTPException(403, 'FOLDER_SESSION_DENIED')
        return item

    async def rows(self, user, batch_id):
        async with self.store.pool.connection() as conn:
            rows = await (await conn.execute("SELECT id,revision,created_at,updated_at,payload FROM agent_runtime.harness_records WHERE kind='folder-item' AND organization_id=%s AND actor_id=%s AND payload->>'batch_id'=%s ORDER BY created_at,id", (*user.key, batch_id))).fetchall()
        return [{**row['payload'], 'id':str(row['id']), 'revision':row['revision'], 'created_at':str(row['created_at']), 'updated_at':str(row['updated_at'])} for row in rows]

    async def handle(self, user, run, args, call_id):
        action = args['action']
        if action == 'list':
            return {'aliases': list(self.roots(user))}
        if action == 'start':
            if user.automation_grant_id:
                raise HTTPException(403, 'INTERACTIVE_AUTHORIZATION_REQUIRED')
            config = self.roots(user).get(args.get('alias'))
            if not config:
                raise HTTPException(403, 'FOLDER_ALIAS_DENIED')
            # Stable across lost responses to the original tool call.
            from uuid import NAMESPACE_URL, uuid5
            bid = str(uuid5(NAMESPACE_URL, digest([*user.key, run['id'], call_id])))
            async with self.scan_lock:
                try:
                    batch, _ = await self.batch(user, bid)
                    return {'batch_id': bid, 'folder_dispatch': True, 'status': batch['status']}
                except HTTPException as error:
                    if error.status_code != 404:
                        raise
                templates = {kind: sha(bounded(contained(config['templates'], config['templates'] / (kind + '.xlsx')))) for kind in ('test', 'synthesis')}
                # Publish only the technical batch here; hashing thousands of
                # Office files must not hold an interactive tool request open.
                async with self.store.transaction(user, 'folder-start:' + bid) as conn:
                    await self.store.create(user, 'folder-batch', {'alias': args['alias'], 'root_hash': digest(config), 'templates': templates,
                        'principal': user.model_dump(mode='json', exclude_none=True), 'auth_version': user.auth_version,
                        'status': 'SCANNING', 'count': 0}, conn, rid=bid)
            return {'batch_id': bid, 'folder_dispatch': True, 'status': 'SCANNING'}
        batch, config = await self.batch(user, args.get('batch_id') or user.automation_grant_id)
        if action in {'pause', 'continue'}:
            if user.automation_grant_id:
                raise HTTPException(403, 'INTERACTIVE_AUTHORIZATION_REQUIRED')
            async with self.store.transaction(user, batch['id']) as conn:
                batch = await self.store.get(user, 'folder-batch', batch['id'], conn)
                if batch['status'] == 'SCANNING':
                    batch['pause_after_scan'] = action == 'pause'
                else:
                    batch['status'] = 'PAUSED' if action == 'pause' else 'RUNNING'
                await self.store.update(user, 'folder-batch', batch, conn)
            return {'batch_id': batch['id'], 'status': batch['status'], 'folder_dispatch': action == 'continue'}
        if action == 'status':
            rows = await self.rows(user, batch['id'])
            counts = {status: sum(row['status'] == status for row in rows) for status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')}
            offset = args.get('offset', 0)
            return {'batch_id': batch['id'], 'status': batch['status'], 'counts': counts, 'scan_error': batch.get('scan_error'),
                    'files': [{k: row[k] for k in ('id', 'relative_path', 'status', 'error') if k in row} for row in rows[offset:offset+30]],
                    **({'next_offset': offset+30} if offset+30 < len(rows) else {})}
        item = await self.item_for_session(user, batch['id'], run['dsh_session_id'])
        if action == 'fail':
            item.update(status='FAILED', error=args.get('reason', 'Conversion blocked')[:2000])
            async with self.store.transaction(user, batch['id']) as conn:
                await self.store.update(user, 'folder-item', item, conn)
            self.write_error(config, batch, item)
            return {'saved': True}
        if action != 'source':
            raise HTTPException(422, 'FOLDER_ACTION_DENIED')
        original = bounded(contained(config['source'], config['source'] / item['relative_path']))
        if sha(original) != item['source_sha256']:
            raise HTTPException(409, 'FOLDER_SOURCE_CHANGED')
        content, name = original, Path(item['relative_path']).name
        normalization = {'status': 'PASSTHROUGH'}
        if Path(name).suffix.lower() in {'.xls', '.doc'}:
            cache = contained(config['output'], config['output']/batch['id']/'.dsh-state'/item['id'])
            if cache.exists():
                metadata = json.loads(bounded(contained(config['output'], cache/'normalization.json')))
                content = bounded(contained(config['output'], cache/'working.bin'))
                if metadata['original_sha256'] != item['source_sha256'] or sha(content) != metadata['working_sha256'] or item.get('working_sha256', sha(content)) != sha(content):
                    raise HTTPException(409, 'FOLDER_NORMALIZED_COPY_CHANGED')
                normalization, name = metadata['normalization'], metadata['name']
            else:
                normalization = await self.service.java(user, 'normalize_conversion_office', {'name': name, 'base64': base64.b64encode(original).decode()})
                content = base64.b64decode(normalization.pop('base64'), validate=True)
                name = normalization['name']
                if len(content) > MAX_BYTES:
                    raise HTTPException(413, 'NORMALIZED_FILE_TOO_LARGE')
                metadata = {'original_sha256':item['source_sha256'], 'working_sha256':sha(content), 'normalization':normalization, 'name':name}
                cache.parent.mkdir(parents=True, exist_ok=True)
                temporary = Path(tempfile.mkdtemp(prefix='.normalizing-', dir=cache.parent))
                try:
                    (temporary/'working.bin').write_bytes(content)
                    (temporary/'normalization.json').write_text(json.dumps(metadata, ensure_ascii=False), encoding='utf-8')
                    temporary.rename(cache)
                finally:
                    if temporary.exists():
                        shutil.rmtree(temporary)
        if len(content) > MAX_BYTES:
            raise HTTPException(413, 'NORMALIZED_FILE_TOO_LARGE')
        templates = {}
        for kind, expected in batch['templates'].items():
            data = bounded(contained(config['templates'], config['templates'] / (kind + '.xlsx')))
            if sha(data) != expected:
                raise HTTPException(409, 'FOLDER_TEMPLATE_CHANGED')
            templates[kind] = base64.b64encode(data).decode()
        item.update(working_sha256=sha(content), normalization=normalization)
        async with self.store.transaction(user, batch['id']) as conn:
            await self.store.update(user, 'folder-item', item, conn)
        return {'folder_source': True, 'batch_id': batch['id'], 'item_id': item['id'], 'name': name,
                'relative_path': item['relative_path'], 'base64': base64.b64encode(content).decode(),
                'original_sha256': item['source_sha256'], 'working_sha256': sha(content), 'templates': templates}

    @staticmethod
    def inventory(config):
        entries = []
        for parent, directories, names in os.walk(config['source'], followlinks=False):
            directories.sort()
            for name in list(directories):
                contained(config['source'], Path(parent) / name)
            for name in sorted(names):
                path = contained(config['source'], Path(parent) / name)
                if path.suffix.lower() not in EXTENSIONS or name.startswith('~$'):
                    continue
                entry = {'relative_path': path.relative_to(config['source']).as_posix(), 'status': 'QUEUED'}
                try:
                    entry['source_sha256'] = sha(bounded(path))
                except HTTPException as error:
                    entry.update(status='FAILED', error=str(error.detail))
                entries.append(entry)
        return entries

    @staticmethod
    def directory(config, batch, item):
        relative = Path(item['relative_path'])
        return contained(config['output'], config['output'] / batch['id'] / relative.parent / (relative.name + '.converted'))

    def write_error(self, config, batch, item):
        target = contained(config['output'], config['output']/batch['id']/'.errors')
        target.mkdir(parents=True, exist_ok=True)
        path = contained(config['output'], target / (item['id'] + '.json'))
        if not path.exists():
            with path.open('x', encoding='utf-8') as stream:
                json.dump({'source': item['relative_path'], 'error': item['error']}, stream, ensure_ascii=False, indent=2)

    async def owners(self, offset=0):
        async with self.store.pool.connection() as conn:
            rows = await (await conn.execute("SELECT id FROM agent_runtime.harness_records WHERE kind='folder-batch' AND payload->>'status' IN ('SCANNING','RUNNING','PAUSED') ORDER BY id LIMIT 101 OFFSET %s", (offset,))).fetchall()
        return {'owners': [{'id': str(row['id'])} for row in rows[:100]], **({'next_offset': offset+100} if len(rows)>100 else {})}

    async def ticket_for(self, body):
        batch_id = body.get('owner_id')
        if not batch_id:
            session_id = body.get('session_id', '')
            if not session_id.startswith('folder-'):
                raise HTTPException(403, 'FOLDER_SESSION_DENIED')
            async with self.store.pool.connection() as conn:
                row = await (await conn.execute("SELECT payload FROM agent_runtime.harness_records WHERE kind='folder-item' AND id=%s", (str(UUID(session_id[7:])),))).fetchone()
            if not row:
                raise HTTPException(403, 'FOLDER_SESSION_DENIED')
            batch_id = row['payload']['batch_id']
        return await self.ticket(batch_id)

    async def ended(self, user, session_id):
        batch, config = await self.batch(user, user.automation_grant_id)
        async with self.store.transaction(user, batch['id']) as conn:
            item = await self.item_for_session(user, batch['id'], session_id)
            if item['status'] == 'RUNNING':
                item.update(status='FAILED', error='本文件执行已结束，未取得有效的文件夹保存回执；检查原会话诊断后重试。')
                await self.store.update(user, 'folder-item', item, conn)
                self.write_error(config, batch, item)
        return {'saved': True}

    async def ticket(self, batch_id):
        async with self.store.pool.connection() as conn:
            row = await (await conn.execute("SELECT payload FROM agent_runtime.harness_records WHERE kind='folder-batch' AND id=%s", (str(UUID(batch_id)),))).fetchone()
        if not row:
            raise HTTPException(403, 'FOLDER_GRANT_REVOKED')
        user = Principal.model_validate({**row['payload']['principal'], 'automation_grant_id': batch_id, 'scope': 'folder-conversion'})
        await self.check(user)
        proof = issue(self.service.settings.shared_secret.get_secret_value(), user, 'automation-exchange', ttl=60)
        response = await self.service.http.post(self.service.settings.java_url + '/internal/v1/agent-identity/automation',
            headers={'Authorization': 'Bearer '+self.service.settings.runtime_secret.get_secret_value()}, json={'proof': proof})
        if response.status_code != 200:
            raise HTTPException(response.status_code, 'FOLDER_AUTHORIZATION_REQUIRED')
        await self.check(user)
        return response.json()

    async def scan(self, user, batch_id):
        try:
            batch, config = await self.batch(user, batch_id)
            entries = await asyncio.to_thread(self.inventory, config)
            async with self.store.transaction(user, batch_id) as conn:
                batch = await self.store.get(user, 'folder-batch', batch_id, conn)
                if batch['status'] != 'SCANNING':
                    return
                for entry in entries:
                    await self.store.create(user, 'folder-item', {**entry, 'batch_id':batch_id}, conn)
                batch.update(status='PAUSED' if batch.get('pause_after_scan') else 'RUNNING', count=len(entries))
                batch.pop('scan_error', None)
                await self.store.update(user, 'folder-batch', batch, conn)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            # Retain SCANNING for a transient retry and a concise diagnosis.
            import logging
            logging.getLogger(__name__).warning('Folder inventory pending: %s', type(error).__name__)
            try:
                async with self.store.transaction(user, batch_id) as conn:
                    batch = await self.store.get(user, 'folder-batch', batch_id, conn)
                    batch['scan_error'] = str(error.detail) if isinstance(error, HTTPException) else type(error).__name__
                    await self.store.update(user, 'folder-batch', batch, conn)
            except Exception:
                pass  # Storage outages are retried from the durable SCANNING batch.
        finally:
            self.scans.pop(batch_id, None)

    async def close(self):
        tasks = list(self.scans.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

    async def dispatch(self, user, body=None):
        batch = await self.check(user)
        _, config = await self.batch(user, batch['id'])
        if batch['status'] == 'SCANNING':
            if batch['id'] not in self.scans:
                self.scans[batch['id']] = asyncio.create_task(self.scan(user, batch['id']))
            return {'batch_id':batch['id'], 'status':'SCANNING', 'items':[]}
        async with self.store.transaction(user, batch['id']) as conn:
            batch = await self.store.get(user, 'folder-batch', batch['id'], conn)
            if body and body.get('engine_hash'):
                if batch.get('engine_hash') and batch['engine_hash'] != body['engine_hash']:
                    raise HTTPException(409, 'FOLDER_ENGINE_CHANGED')
                if not batch.get('engine_hash'):
                    batch['engine_hash'] = body['engine_hash']
                    batch = await self.store.update(user, 'folder-batch', batch, conn)
            rows = await self.rows(user, batch['id'])
            active = [row for row in rows if row['status'] == 'RUNNING']
            if batch['status'] == 'RUNNING':
                for item in [row for row in rows if row['status'] == 'QUEUED'][:max(0, 2-len(active))]:
                    item['status'] = 'RUNNING'
                    await self.store.update(user, 'folder-item', item, conn)
                    active.append(item)
            if not active and not any(row['status'] == 'QUEUED' for row in rows):
                batch['status'] = 'COMPLETED'
                await self.store.update(user, 'folder-batch', batch, conn)
        _, config = await self.batch(user, batch['id'])
        for item in rows:
            if item['status'] == 'FAILED':
                self.write_error(config, batch, item)
        return {'batch_id': batch['id'], 'status': batch['status'], 'items': [{'id': item['id'], 'session_id': 'folder-'+item['id']} for item in active]}

    async def export(self, user, body):
        run = await self.service.session_run(user, body)
        await self.service.authorized_topic(user, run)
        batch, config = await self.batch(user, user.automation_grant_id)
        item = await self.item_for_session(user, batch['id'], body['session_id'])
        if sha(bounded(contained(config['source'], config['source']/item['relative_path']))) != item['source_sha256']:
            raise HTTPException(409, 'FOLDER_SOURCE_CHANGED')
        from aird_agent.capabilities.files.quality import validate_format
        from aird_agent.hosts.jsd.native_files import decode_files, storage_path
        files = decode_files(body['files'])
        if set(files) != set(body['paths'].values()) or set(body['paths']) != {'output', 'plan', 'report'}:
            raise HTTPException(422, 'FOLDER_RESULT_FILES_REQUIRED')
        workspace = Workspace(self.service.settings.storage_root/'workspaces', str(user.organization_id), run['id'])
        receipts = []
        for key, path in body['paths'].items():
            data = files[path]
            receipt, _, _ = await self.service.bindings.files.lineage(user, run, workspace, storage_path(path), data)
            if not receipt:
                raise HTTPException(422, 'FOLDER_EXECUTION_RECEIPT_REQUIRED')
            receipts.append(receipt)
            validate_format(path, data)
        report = json.loads(files[body['paths']['report']])
        plan = json.loads(files[body['paths']['plan']])
        template_hash = report.get('template_sha256')
        if template_hash not in batch['templates'].values() or sha(bounded(config['templates']/('test.xlsx' if template_hash == batch['templates']['test'] else 'synthesis.xlsx'))) != template_hash:
            raise HTTPException(409, 'FOLDER_TEMPLATE_CHANGED')
        expected = {'source_sha256': item.get('working_sha256'), 'template_sha256': template_hash,
                    'plan_sha256': sha(files[body['paths']['plan']]), 'output_sha256': sha(files[body['paths']['output']])}
        if report.get('status') not in {'validated', 'review_required'} or any(report.get(key) != value for key, value in expected.items()) or plan.get('source_sha256') != item.get('working_sha256') or plan.get('template_sha256') != template_hash:
            raise HTTPException(422, 'FOLDER_VERIFICATION_MISMATCH')
        if not body['paths']['output'].lower().endswith('.xlsx'):
            raise HTTPException(422, 'FOLDER_XLSX_REQUIRED')
        report.pop('human_review', None)
        report['provenance'] = {'original_path': item['relative_path'], 'original_sha256': item['source_sha256'],
            'working_sha256': item['working_sha256'], 'normalization': item['normalization'], 'executions': receipts}
        payload = {'result.xlsx': files[body['paths']['output']], 'mapping.json': files[body['paths']['plan']],
                   'verification.json': json.dumps(report, ensure_ascii=False, indent=2).encode()}
        target = self.directory(config, batch, item)
        target.parent.mkdir(parents=True, exist_ok=True)
        async with self.store.transaction(user, batch['id']) as conn:
            if target.exists():
                if any(not contained(config['output'], target/name).is_file() or (target/name).read_bytes() != data for name, data in payload.items()):
                    raise HTTPException(409, 'FOLDER_RESULT_EXISTS_OR_CHANGED')
            else:
                temporary = Path(tempfile.mkdtemp(prefix='.conversion-', dir=target.parent))
                try:
                    for name, data in payload.items():
                        (temporary/name).write_bytes(data)
                    temporary.rename(target)
                finally:
                    if temporary.exists():
                        shutil.rmtree(temporary)
            item.update(status='SUCCEEDED', result_hashes={name: sha(data) for name, data in payload.items()})
            await self.store.update(user, 'folder-item', item, conn)
        return {'saved': True, 'batch_id': batch['id'], 'source': item['relative_path'], 'result_directory': str(target)}
