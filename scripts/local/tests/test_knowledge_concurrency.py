import importlib.util
import json
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('concurrent_import', Path(__file__).parents[1] / 'import_knowledge_directory.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class KnowledgeConcurrencyTest(unittest.TestCase):
    def test_create_conflict_only_reuses_verified_content_duplicate(self):
        api = Mock()
        record = {'fileId': 'file', 'targetCategoryId': 'category'}
        conflict = module.ApiHttpError(409, 'RESOURCE_CONFLICT', 'conflict')
        api.json.return_value = {'decision': 'EXACT_DUPLICATE', 'exactMatches': [{'documentId': 'doc'}]}
        document = {'id': 'doc', 'currentVersionId': 'version'}
        with patch.object(module, 'duplicate_document', return_value=document), patch.object(module, 'require_document_target') as check:
            self.assertEqual(document, module.create_or_resolve_duplicate(api, record, Mock(side_effect=conflict)))
            check.assert_called_once_with(api, document, record)
        self.assertTrue(record['duplicate'])
        api.json.return_value = {'decision': 'POSSIBLE_VERSION'}
        with self.assertRaises(module.ApiHttpError):
            module.create_or_resolve_duplicate(api, record, Mock(side_effect=conflict))
        self.assertEqual(2, api.json.call_count)

    def test_publication_can_finish_after_ten_minutes_without_resubmission(self):
        clock = [0.0]
        queued = [False]
        api = Mock()
        def request(method, path, value=None):
            if path.endswith('/documents/doc'):
                published = clock[0] >= 900
                return {'currentVersionId': 'version', 'aiStatus': 'APPROVED',
                        'currentPublicationId': 'publication' if published else None,
                        'reviewStatus': 'PUBLISHED' if published else 'PENDING_REVIEW'}
            if path.endswith('/processing'):
                return {'documentStatus': 'READY', 'parseRunStatus': 'SUCCEEDED'}
            if path.endswith('/review'):
                return {'reviewRevision': {'id': 'revision', 'lockVersion': 1,
                                           'status': 'BUILDING' if queued[0] else 'DRAFT'}}
            if path.endswith('/publish'):
                queued[0] = True
                return {}
            raise AssertionError(path)
        api.json.side_effect = request
        def pause(*args): clock[0] += 301
        with patch.object(module.time, 'monotonic', side_effect=lambda: clock[0]), \
                patch.object(module, 'pause', side_effect=pause), \
                patch.object(module, 'wait_for_parse'), patch.object(module, 'ensure_publication_review'), \
                patch.object(module, 'queue_api_call', side_effect=lambda client, operation, *args: operation()):
            result = module.finish_document(api, 'doc', 'version', 60, False, 60,
                                            publish_timeout_seconds=1200)
        self.assertEqual('publication', result['currentPublicationId'])
        self.assertEqual(1, sum(c.args[0] == 'POST' and c.args[1].endswith('/publish')
                                for c in api.json.call_args_list))

    def test_slow_first_document_does_not_block_completed_document_and_work_is_bounded(self):
        release = threading.Event()
        started = []
        class Client:
            cancelled = threading.Event()
            def fork(self): return self
        def operation(client, number):
            started.append(number)
            if number == 0:
                if not release.wait(3): raise AssertionError('head-of-line blocking')
            return number
        results = module.parallel_items(Client(), range(6), 2, operation)
        try:
            self.assertEqual((1, 1), next(results))
            self.assertEqual({0, 1}, set(started))
        finally:
            release.set()
        remaining = list(results)
        self.assertEqual({0, 2, 3, 4, 5}, {value for _, value in remaining})

    def test_fork_reuses_login_snapshot_without_shared_cookie_writes(self):
        with tempfile.TemporaryDirectory() as folder:
            api = module.ApiClient('http://localhost', Path(folder) / 'cookie', 'curl')
            api.session_cookies = b'authenticated-cookie'
            api.csrf_token = 'csrf'
            api.cookie_file.write_bytes(b'partially-rewritten-parent-cookie')
            first, second = api.fork(), api.fork()
            self.assertNotEqual(first.cookie_file, second.cookie_file)
            self.assertEqual(b'authenticated-cookie', first.cookie_file.read_bytes())
            self.assertEqual('csrf', first.csrf_token)
            self.assertIs(first.cancelled, second.cancelled)

    def test_first_401_stops_all_further_requests(self):
        with tempfile.TemporaryDirectory() as folder:
            api = module.ApiClient('http://localhost', Path(folder) / 'cookie', 'curl')
            response = SimpleNamespace(returncode=0, stdout=b'{"message":"replaced"}\n401', stderr=b'')
            with patch.object(module.subprocess, 'run', return_value=response) as run:
                for _ in range(2):
                    with self.assertRaises(module.SessionExpired):
                        api.json('GET', '/test')
                self.assertEqual(1, run.call_count)

    def test_concurrent_manifest_appends_remain_valid_jsonl(self):
        with tempfile.TemporaryDirectory() as folder:
            manifest = Path(folder) / 'manifest.jsonl'
            with module.ThreadPoolExecutor(max_workers=8) as executor:
                list(executor.map(lambda i: module.append_manifest(manifest, {'number': i}), range(200)))
            rows = [json.loads(line) for line in manifest.read_text().splitlines()]
            self.assertEqual(set(range(200)), {row['number'] for row in rows})

    def test_auth_loss_is_not_converted_into_an_enrichment_failure(self):
        class Client:
            def json(self, *args): raise module.SessionExpired()
        with self.assertRaises(module.SessionExpired):
            module.sync_enrichment(Client(), {'documentId': 'doc', 'versionId': 'version'})

    def test_import_finishes_two_documents_concurrently_with_one_login(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder)
            for name in ('a.pdf', 'b.pdf'):
                (source / name).write_bytes(b'pdf')
            prior = {(name, 'hash'): {'documentId': name, 'versionId': 'version'}
                     for name in ('a.pdf', 'b.pdf')}
            api = Mock(base_url='http://localhost')
            api.cancelled = threading.Event()
            api.fork.return_value = api
            barrier = threading.Barrier(2)
            args = SimpleNamespace(source_dir=folder, manifest=str(source / 'progress.jsonl'),
                                   include=None, category_mode='fixed', category_id='category', dry_run=False,
                                   username='admin', base_url='http://localhost', scope='INTERNAL',
                                   max_in_flight=2, queue_wait_timeout_minutes=1, parse_timeout_minutes=1,
                                   retry_failed=False, auto_approve=True, metadata_wait_seconds=0)
            def finish(client, document_id, *args, **kwargs):
                barrier.wait(3)
                return {'id': document_id, 'title': document_id, 'status': 'READY'}
            def sync(client, record, **kwargs):
                record.update(metadataStatus='ACCEPTED', vectorIndexStatus='PRESENT', importStatus='COMPLETED')
            capabilities = {'maxFileBytes': 100, 'supportedExtensions': ['.pdf'],
                            'queue': {'organizationJobLimit': 8}}
            with patch.dict(module.os.environ, {'JSD_AIRD_PASSWORD': 'secret'}), \
                    patch.object(module, 'ApiClient', return_value=api), \
                    patch.object(module, 'read_manifest', return_value=prior), \
                    patch.object(module, 'sha256_file', return_value='hash'), \
                    patch.object(module, 'upload_capabilities', return_value=capabilities), \
                    patch.object(module, 'CategoryResolver') as resolver, \
                    patch.object(module, 'refresh_document_state', return_value={'currentVersionId': 'version'}), \
                    patch.object(module, 'require_document_target'), \
                    patch.object(module, 'sync_document_metadata'), \
                    patch.object(module, 'sync_enrichment', side_effect=sync), \
                    patch.object(module, 'finish_document', side_effect=finish):
                resolver.return_value.resolve.return_value = {'id': 'category', 'name': 'category'}
                self.assertEqual(0, module.import_directory(args))
            api.login.assert_called_once()
            api.stage_file.assert_not_called()


if __name__ == '__main__':
    unittest.main()
