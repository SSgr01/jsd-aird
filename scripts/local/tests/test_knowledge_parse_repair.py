import importlib.util
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('repair_import', Path(__file__).parents[1] / 'import_knowledge_directory.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ParseRepairTest(unittest.TestCase):
    def test_lost_submit_response_resumes_keys_and_never_publishes(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = SimpleNamespace(username='admin', base_url='http://127.0.0.1:8080', category_id=None, scope=None,
                                   page_size=100, manifest=str(Path(temporary) / 'repair.jsonl'), request_key=None,
                                   max_in_flight=16, retry_failed=False, dry_run=False, queue_wait_timeout_minutes=2,
                                   parse_timeout_minutes=2, poll_seconds=0.5)
            docs = [{'id': f'doc-{i}', 'currentVersionId': f'version-{i}', 'title': 'paper'} for i in range(3)]
            api = Mock()
            submissions = []
            def request(method, path, body=None):
                self.assertNotIn('/publish', path)
                self.assertNotIn('/adopt', path)
                self.assertNotIn('/rebuild', path)
                if path.endswith('/preview'):
                    return [{'hasArchivedZip': True} for _ in body['files']]
                if path == '/api/v1/knowledge/parse-repairs':
                    submissions.append(body)
                    if len(submissions) == 1:
                        raise OSError('response lost after server accepted')
                    return [{'id': f'repair-{item["versionId"]}', 'versionId': item['versionId'], 'status': 'READY',
                             'result': {'providerTaskId': 'old-task', 'metadata': {'tier': 'standard',
                                        'repairSource': 'EXISTING_ZIP', 'completenessWarnings': ['REFERENCE_HEADERS_RECOVERED: 9']}}}
                            for item in body['files']]
                return {}
            api.json.side_effect = request
            with patch.dict(os.environ, {'JSD_AIRD_PASSWORD': 'test-only'}), \
                    patch.object(module.shutil, 'which', return_value='curl'), \
                    patch.object(module, 'RebuildClient', return_value=api), \
                    patch.object(module, 'inventory', return_value=docs), \
                    patch.object(module, 'queue_api_call', side_effect=lambda client, operation, *rest: operation()):
                with self.assertRaises(OSError):
                    module.repair_existing(args)
                self.assertTrue(Path(args.manifest).exists())
                self.assertEqual(0, module.repair_existing(args))
                self.assertEqual(submissions[0]['requestKey'], submissions[1]['requestKey'])
                self.assertEqual(submissions[0]['files'], submissions[1]['files'])
                records = module.read_progress(Path(args.manifest), {'mode': 'repair-existing', 'baseUrl': args.base_url,
                                                                     'categoryId': None, 'scope': None})
                self.assertEqual(3, len(records))
                self.assertTrue(all(r['status'] == 'READY' and r['parseTier'] == 'standard' for r in records.values()))
                self.assertEqual(0, module.repair_existing(args))
                self.assertEqual(2, len(submissions))  # Completed records never re-OCR because configuration changed.


if __name__ == '__main__':
    unittest.main()
