import importlib.util
from pathlib import Path
import unittest
import tempfile
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location("knowledge_import", Path(__file__).parents[1] / "import_knowledge_directory.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class EnrichmentResumeTest(unittest.TestCase):
    def test_explicit_selection_excludes_unselected_files_and_rejects_parent_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary).resolve()
            selected=root / "article.pdf"; selected.write_bytes(b"selected")
            (root / "cover.pdf").write_bytes(b"unselected")
            self.assertEqual([selected], module.collect_files(root, root / "manifest.jsonl", ["article.pdf", "article.pdf"]))
            with self.assertRaises(module.ImportErrorWithMessage):
                module.collect_files(root, root / "manifest.jsonl", ["../outside.pdf"])

    def record(self):
        return {"documentId": "document", "versionId": "version", "bodyImportStatus": "COMPLETED"}

    def api(self, status="PENDING", vector="MISSING"):
        api = Mock()
        def call(method, path, value=None):
            if path.endswith("/bibliography"):
                return {"status": status, "revision": 2, "message": "等待核对"}
            if path.endswith("/vector-status"):
                return {"status": vector, "total": 3, "embedded": 0, "modelCompatibilityChecked": False}
            if method == "POST" and path.endswith("/bibliography-tasks"):
                return {"id": "task"}
            if method == "GET" and path.endswith("/task"):
                return {"id": "task", "items": [{"id": "item", "versionId": "version", "status": "WAITING_PROVIDER", "retryAt": "later"}]}
            raise AssertionError(f"Unexpected side effect: {method} {path}")
        api.json.side_effect = call
        return api

    def test_resume_reuses_task_and_never_uploads_or_reparses(self):
        api = self.api()
        record = self.record()
        module.sync_enrichment(api, record)
        module.sync_enrichment(api, record)
        self.assertEqual("WAITING_PROVIDER", record["metadataStatus"])
        self.assertEqual("PARTIAL", record["importStatus"])
        self.assertEqual("COMPLETED", record["bodyImportStatus"])
        self.assertEqual(1, sum(call.args[0] == "POST" for call in api.json.call_args_list))
        api.stage_file.assert_not_called()

    def test_accepted_metadata_is_not_resubmitted_but_missing_vectors_stay_visible(self):
        api = self.api("ACCEPTED")
        record = self.record()
        module.sync_enrichment(api, record)
        self.assertEqual("MISSING", record["vectorIndexStatus"])
        self.assertEqual("PARTIAL", record["importStatus"])
        self.assertTrue(all(call.args[0] == "GET" for call in api.json.call_args_list))
        self.assertEqual(1, module.summarize([record])["vectorIncomplete"])

    def test_manual_review_is_not_reported_as_complete(self):
        record = self.record()
        module.sync_enrichment(self.api("NEEDS_REVIEW", "PRESENT"), record)
        self.assertEqual("PARTIAL", record["importStatus"])
        self.assertEqual(1, module.summarize([record])["metadataNeedsReview"])

    def test_query_failure_does_not_reuse_stale_success(self):
        record = {**self.record(), "metadataStatus": "ACCEPTED", "vectorIndexStatus": "PRESENT"}
        api = Mock()
        api.json.side_effect = RuntimeError("offline")
        module.sync_enrichment(api, record)
        self.assertEqual("CHECK_FAILED", record["metadataStatus"])
        self.assertEqual("CHECK_FAILED", record["vectorIndexStatus"])
        self.assertEqual("PARTIAL", record["importStatus"])

    def test_already_published_resume_does_not_republish_or_reauthorize(self):
        api = Mock()
        document = {"id": "document", "currentVersionId": "version", "currentPublicationId": "publication",
                    "reviewStatus": "PUBLISHED", "aiStatus": "APPROVED", "status": "READY"}
        api.json.return_value = document
        self.assertEqual(document, module.finish_document(api, "document", "version", 60, False, 60))
        self.assertTrue(all(call.args[0] == "GET" for call in api.json.call_args_list))


if __name__ == "__main__":
    unittest.main()
