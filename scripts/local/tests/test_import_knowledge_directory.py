import importlib.util
from pathlib import Path
import unittest
import tempfile
from unittest.mock import Mock, call, patch

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


class PublicationReviewTest(unittest.TestCase):
    path = "/api/v1/workspace-reviews/KNOWLEDGE_VERSION/version"

    def test_manual_review_mode_does_not_submit_or_approve(self):
        api = Mock()
        api.json.return_value = {"status": "NOT_SUBMITTED"}
        with self.assertRaisesRegex(module.ImportErrorWithMessage, "--auto-approve"):
            module.ensure_publication_review(api, "version", auto_approve=False, retry_failed=False)
        api.json.assert_called_once_with("GET", self.path)

    def test_auto_review_uses_formal_submit_and_approve_endpoints(self):
        api = Mock()
        api.json.side_effect = [{"status": "NOT_SUBMITTED"}, {"status": "SUBMITTED"}, {"status": "APPROVED"}]
        module.ensure_publication_review(api, "version", auto_approve=True, retry_failed=False)
        self.assertEqual([call("GET", self.path), call("POST", self.path + "/submit", {}),
                          call("POST", self.path + "/approve", {})], api.json.call_args_list)

    def test_submitted_review_is_not_submitted_again(self):
        api = Mock()
        api.json.side_effect = [{"status": "SUBMITTED"}, {"status": "APPROVED"}]
        module.ensure_publication_review(api, "version", auto_approve=True, retry_failed=False)
        self.assertEqual([call("GET", self.path), call("POST", self.path + "/approve", {})], api.json.call_args_list)

    def test_existing_approval_is_reused_without_auto_approve(self):
        api = Mock()
        api.json.return_value = {"status": "APPROVED"}
        module.ensure_publication_review(api, "version", auto_approve=False, retry_failed=False)
        api.json.assert_called_once_with("GET", self.path)

    def test_rejected_review_requires_an_explicit_retry(self):
        api = Mock()
        api.json.return_value = {"status": "REJECTED"}
        with self.assertRaisesRegex(module.ImportErrorWithMessage, "--retry-failed"):
            module.ensure_publication_review(api, "version", auto_approve=True, retry_failed=False)
        api.json.assert_called_once_with("GET", self.path)

    def test_rejected_review_can_be_retried_through_formal_review(self):
        api = Mock()
        api.json.side_effect = [{"status": "REJECTED"}, {"status": "SUBMITTED"}, {"status": "APPROVED"}]
        module.ensure_publication_review(api, "version", auto_approve=True, retry_failed=True)
        self.assertEqual(call("POST", self.path + "/approve", {}), api.json.call_args_list[-1])

    def test_publication_waits_for_approval_and_keeps_the_current_revision(self):
        for auto_approve in (False, True):
            with self.subTest(auto_approve=auto_approve):
                api = Mock()
                state = {"approval": "NOT_SUBMITTED" if auto_approve else "APPROVED", "published": False}
                def request(method, path, value=None):
                    if method == "GET" and path.endswith("/documents/document"):
                        return {"id": "document", "currentVersionId": "version", "aiStatus": "APPROVED",
                                "currentPublicationId": "publication" if state["published"] else None,
                                "reviewStatus": "PUBLISHED" if state["published"] else "PENDING_REVIEW"}
                    if method == "GET" and path.endswith("/processing"):
                        return {"versionId": "version", "documentStatus": "READY", "parseRunStatus": "SUCCEEDED"}
                    if method == "GET" and path.endswith("/versions/version/review"):
                        return {"reviewRevision": {"id": "revision", "lockVersion": 7, "status": "DRAFT"}}
                    if method == "GET" and path == self.path:
                        return {"status": state["approval"]}
                    if method == "POST" and path == self.path + "/submit":
                        self.assertEqual("NOT_SUBMITTED", state["approval"])
                        state["approval"] = "SUBMITTED"
                        return {"status": "SUBMITTED"}
                    if method == "POST" and path == self.path + "/approve":
                        self.assertEqual("SUBMITTED", state["approval"])
                        state["approval"] = "APPROVED"
                        return {"status": "APPROVED"}
                    if method == "GET" and path.endswith("/upload-capabilities"):
                        return {"queue": {"availableSlots": 8, "organizationJobLimit": 8, "totalJobLimit": 16}}
                    if method == "POST" and path.endswith("/publish"):
                        self.assertEqual("APPROVED", state["approval"])
                        self.assertEqual({"reviewRevisionId": "revision", "lockVersion": 7,
                                          "basePublicationId": None, "authorizeAi": True}, value)
                        state["published"] = True
                        return {"status": "QUEUED"}
                    raise AssertionError(f"Unexpected request: {method} {path}")
                api.json.side_effect = request
                result = module.finish_document(api, "document", "version", 60, False, 60,
                                                auto_approve=auto_approve)
                self.assertEqual("PUBLISHED", result["reviewStatus"])

    def test_denied_or_unconfirmed_approval_never_publishes(self):
        for result in (module.ApiHttpError(403, "FORBIDDEN", "无审核权限"), {"status": "SUBMITTED"}):
            with self.subTest(result=result):
                api = Mock()
                api.json.side_effect = [{"status": "SUBMITTED"}, result]
                with self.assertRaises(module.ImportErrorWithMessage):
                    module.ensure_publication_review(api, "version", auto_approve=True, retry_failed=False)
                self.assertFalse(any(c.args[1].endswith("/publish") for c in api.json.call_args_list))

    def test_command_enables_auto_review_by_default_and_allows_opting_out(self):
        for options, expected in (([], True), (["--auto-approve"], True), (["--no-auto-approve"], False)):
            with self.subTest(options=options):
                with patch.object(module.sys, "argv", ["import_knowledge_directory.py", "/srv/kb/import", *options]), \
                        patch.object(module, "import_directory", return_value=0) as importer:
                    self.assertEqual(0, module.main())
                    self.assertEqual(expected, importer.call_args.args[0].auto_approve)


if __name__ == "__main__":
    unittest.main()
