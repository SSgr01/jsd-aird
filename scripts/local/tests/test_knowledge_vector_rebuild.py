import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
import urllib.parse
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("knowledge_vector_import", Path(__file__).parents[1] / "import_knowledge_directory.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def vectors(model="qwen-new", dimension=1024, total=2):
    return {"status": "PRESENT", "total": total, "embedded": total,
            "modelCompatibilityChecked": False,
            "models": [{"model": model, "dimension": dimension, "count": total}]}


class RebuildApi:
    def __init__(self):
        self.requests = []
        self.jobs = {}
        self.vector = vectors("old-cloud-model")
        self.model, self.dimension = "qwen-new", 1024
        self.publication = "publication"
        self.ai_approved = True
        self.job_status = "SUCCEEDED"
        self.lose_post_response = False
        self.wrong_worker = False

    def json(self, method, path, value=None):
        self.requests.append((method, path, value))
        url = urllib.parse.urlsplit(path)
        if method == "GET" and url.path.endswith("/vector-status"):
            assert "/versions/published-version/" in path
            return self.vector
        if method == "GET" and url.path.endswith("/upload-capabilities"):
            return {"queue": {"availableSlots": 8, "organizationJobLimit": 8, "totalJobLimit": 16}}
        if not url.path.endswith("/documents/document/rebuild-index"):
            raise AssertionError(f"Unexpected request: {method} {path}")
        key = urllib.parse.parse_qs(url.query).get("requestKey", [None])[0]
        if method == "POST":
            self.jobs.setdefault(key, {"id": "job-" + str(len(self.jobs)), "status": "READY"})
            if self.lose_post_response:
                self.lose_post_response = False
                raise module.ImportErrorWithMessage("connection lost after enqueue")
            return {"jobId": self.jobs[key]["id"], "status": "QUEUED"}
        job = self.jobs.get(key) if key else None
        if job:
            job["status"] = self.job_status
            if self.job_status == "SUCCEEDED" and not self.wrong_worker:
                self.vector = vectors()
        return {"publicationId": self.publication, "versionId": "published-version", "reviewRevisionId": "review",
                "embeddingModel": self.model, "embeddingDimension": self.dimension,
                "aiApproved": self.ai_approved, "job": job}


class VectorRebuildTest(unittest.TestCase):
    def test_other_model_vectors_do_not_invalidate_complete_target(self):
        state = vectors("qwen-new", 2560)
        state["models"].append({"model": "text-embedding-v4", "dimension": 1024, "count": 2})
        self.assertTrue(module.matching_vectors(state, "qwen-new", 2560))
        self.assertFalse(module.matching_vectors(state, "qwen-new", 1024))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.manifest = Path(self.temp.name) / "progress.jsonl"
        self.context = {"server": "http://app", "model": "qwen-new", "dimension": 1024, "username": "operator"}
        self.args = SimpleNamespace(model="qwen-new", dimension=1024, dry_run=False, retry_failed=False,
                                    poll_seconds=0, timeout_minutes=1, queue_wait_timeout_minutes=1)
        self.document = {"id": "document", "title": "Existing paper", "currentPublicationId": "publication",
                         "currentVersionId": "new-unpublished-draft", "aiStatus": "APPROVED"}

    def run_document(self, api, previous=None):
        return module.rebuild_document(api, self.document, self.args, previous, self.context, self.manifest)

    def test_inventory_visits_every_page_beyond_one_thousand(self):
        api = Mock()
        documents = [{"id": str(i)} for i in range(1057)]
        def page(method, path):
            self.assertEqual("GET", method)
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)
            start = (int(query["page"][0]) - 1) * 100
            return {"items": documents[start:start + 100], "total": 1057, "totalPages": 11}
        api.json.side_effect = page
        self.assertEqual(documents, module.inventory(api))
        self.assertEqual(11, api.json.call_count)

    def test_inventory_refuses_a_changing_or_truncated_snapshot(self):
        for second in ({"items": [{"id": "2"}], "total": 3, "totalPages": 2},
                       {"items": [], "total": 2, "totalPages": 2}):
            with self.subTest(second=second):
                api = Mock()
                api.json.side_effect = [{"items": [{"id": "1"}], "total": 2, "totalPages": 2}, second]
                with self.assertRaises(module.ImportErrorWithMessage):
                    module.inventory(api, page_size=1)

    def test_same_dimension_from_the_old_model_is_not_accepted(self):
        self.assertFalse(module.matching_vectors(vectors("old-cloud-model"), "qwen-new", 1024))
        self.assertFalse(module.matching_vectors(vectors(dimension=512), "qwen-new", 1024))
        self.assertFalse(module.matching_vectors({"status": "PRESENT", "total": 2, "embedded": 2}, "qwen-new", 1024))
        self.assertFalse(module.matching_vectors(vectors(total=0), "qwen-new", 1024))
        mismatch = vectors()
        mismatch["models"][0]["count"] = 1
        self.assertFalse(module.matching_vectors(mismatch, "qwen-new", 1024))

    def test_rebuild_uses_published_snapshot_and_only_the_index_write_api(self):
        api = RebuildApi()
        self.assertEqual("REBUILT", self.run_document(api))
        writes = [(method, path) for method, path, _ in api.requests if method != "GET"]
        self.assertEqual(1, len(writes))
        self.assertIn("/documents/document/rebuild-index?requestKey=", writes[0][1])
        self.assertIn("vectorsOnly=true", writes[0][1])
        self.assertEqual("COMPLETED", module.read_progress(self.manifest, self.context)["document"]["status"])

    def test_dry_run_does_not_submit_or_write_a_manifest(self):
        api = RebuildApi()
        self.args.dry_run = True
        self.assertEqual("NEEDS_REBUILD", self.run_document(api))
        self.assertTrue(all(method == "GET" for method, _, _ in api.requests))
        self.assertFalse(self.manifest.exists())

    def test_current_vectors_are_verified_without_another_job(self):
        api = RebuildApi()
        api.vector = vectors()
        self.assertEqual("CURRENT", self.run_document(api))
        self.assertFalse(api.jobs)

    def test_lost_submission_response_resumes_without_a_duplicate_job(self):
        api = RebuildApi()
        api.lose_post_response = True
        self.assertEqual("SUBMISSION_UNKNOWN", self.run_document(api))
        previous = module.read_progress(self.manifest, self.context)["document"]
        self.assertEqual("REBUILT", self.run_document(api, previous))
        self.assertEqual(1, len(api.jobs))
        self.assertEqual(1, sum(method == "POST" for method, _, _ in api.requests))

    def test_failed_job_requires_retry_and_uses_a_new_key(self):
        api = RebuildApi()
        api.job_status = "FAILED"
        self.assertEqual("FAILED", self.run_document(api))
        previous = module.read_progress(self.manifest, self.context)["document"]
        self.assertEqual("FAILED", self.run_document(api, previous))
        self.assertEqual(1, len(api.jobs))
        self.args.retry_failed = True
        api.job_status = "SUCCEEDED"
        self.assertEqual("REBUILT", self.run_document(api, previous))
        self.assertEqual(2, len(api.jobs))

    def test_job_success_with_old_worker_vectors_is_not_complete(self):
        api = RebuildApi()
        api.wrong_worker = True
        self.assertEqual("FAILED", self.run_document(api))
        self.assertEqual("FAILED", module.read_progress(self.manifest, self.context)["document"]["status"])

    def test_poll_timeout_preserves_the_running_job_for_resume(self):
        api = RebuildApi()
        api.job_status = "RUNNING"
        with patch.object(module.time, "monotonic", side_effect=[0, 0, 61]):
            self.assertEqual("WAITING", self.run_document(api))
        previous = module.read_progress(self.manifest, self.context)["document"]
        api.job_status = "SUCCEEDED"
        self.assertEqual("REBUILT", self.run_document(api, previous))
        self.assertEqual(1, len(api.jobs))

    def test_wrong_api_target_stops_before_submission(self):
        api = RebuildApi()
        api.model = "old-cloud-model"
        with self.assertRaises(module.TargetChanged):
            self.run_document(api)
        self.assertFalse(api.jobs)

    def test_unapproved_ai_access_is_not_granted_by_rebuild(self):
        api = RebuildApi()
        api.ai_approved = False
        self.assertEqual("SKIPPED", self.run_document(api))
        self.assertTrue(all(method == "GET" for method, _, _ in api.requests))

    def test_rebuild_mode_uses_same_entrypoint_without_a_source_directory(self):
        with patch.object(module.sys, "argv", ["import_knowledge_directory.py", "--rebuild-vectors",
                                               "--embedding-model", "qwen-new"]), \
                patch.object(module, "rebuild_vectors", return_value=0) as rebuild, \
                patch.object(module, "import_directory") as upload:
            self.assertEqual(0, module.main())
            self.assertIsNone(rebuild.call_args.args[0].scope)
            upload.assert_not_called()

    def test_rebuild_rejects_source_filters_instead_of_ignoring_them(self):
        for options in (["/srv/kb/import"], ["--include", "one.pdf"]):
            with self.subTest(options=options):
                with patch.object(module.sys, "argv", ["import_knowledge_directory.py", "--rebuild-vectors",
                                                       "--embedding-model", "qwen-new", *options]), \
                        patch.object(module, "rebuild_vectors") as rebuild:
                    self.assertEqual(2, module.main())
                    rebuild.assert_not_called()


if __name__ == "__main__":
    unittest.main()
