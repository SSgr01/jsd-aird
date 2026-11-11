import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("knowledge_quality", Path(__file__).parents[1] / "evaluate_knowledge_quality.py")
quality = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quality)


class EvaluationTest(unittest.TestCase):
    def test_parent_context_is_retained_without_inflating_ranked_child_recall(self):
        source = {"document_id": "doc", "version_id": "v", "source_node_key": "child"}
        parent = {"text": "Necessary method condition", "source": {**source, "source_node_key": "parent"}}

        class Client:
            def json(self, method, path, body):
                return {"candidates": [{"text": "Title", "source": source}], "context_candidates": [parent],
                        "document_metadata": [{"title": "Actual title"}], "trace": {"parent_links": {"p": ["c"]}}}

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            result = quality.run_retrieval(Client(), {"id": "query", "query": "Actual query", "evidence_groups": [
                {"alternatives": [{"document_id": "doc", "version_id": "v", "text": "Necessary method"}]}]}, output)
            record = json.loads((output / "query-retrieval.json").read_text(encoding="utf-8"))
            self.assertEqual(result["metrics"]["evidence_recall_at_20"], 0)
            self.assertEqual(record["context_pages"][0]["context_candidates"], [parent])
            self.assertEqual(record["context_pages"][0]["document_metadata"][0]["title"], "Actual title")

    def test_gold_and_review_never_cross_the_model_boundary(self):
        case = {"question": "Actual request", "expected": ["secret answer"], "evidence_groups": [{"secret": "gold"}],
                "grading": "do not send", "documents": [{"document_id": "doc", "publication_id": "pub", "version_id": "v"}]}
        for entry in ("qa", "research"):
            body = quality.model_request(case, entry)
            self.assertNotIn("secret", json.dumps(body))
            self.assertNotIn("grading", body)
        self.assertEqual(quality.model_request(case, "research")["context_refs"][0]["publication_id"], "pub")

    def test_alternative_passages_and_repeated_hits_do_not_inflate_recall_or_document_rank(self):
        def hit(doc, node, text, version="v"):
            return {"source": {"document_id": doc, "version_id": version, "source_node_key": node}, "text": text}
        first = hit("doc", "n1", "Measured 12 kPa at 25 C.")
        hits = [first] * 25 + [hit("next", "n2", "Other paper")]
        groups = [{"alternatives": [{"document_id": "doc", "version_id": "v", "text": "12 kPa"},
                                     {"document_id": "doc", "version_id": "v", "text": "25 C"}]},
                  {"alternatives": [{"document_id": "doc", "version_id": "other", "text": "12 kPa"}]}]
        result = quality.evidence_metrics(hits, groups, ["next"], ["doc"])
        self.assertEqual(result["evidence_recall_at_20"], 0.5)
        self.assertTrue(result["key_documents_at_5"])
        self.assertEqual(result["returned_at_10"], 2)

    def test_recovering_unknown_run_collects_it_without_another_creation_request(self):
        class Client:
            timeout = 60
            def request(self, method, path, body=None, **headers):
                if method != "GET":
                    raise AssertionError("A write was replayed")
                return io.BytesIO(b'event: run.succeeded\ndata: {"type":"run.succeeded","payload":{"text":"Answer"}}\n\n')
            def json(self, method, path):
                return {"status": "SUCCEEDED", "result": "Answer", "assistant_messages": [{"text": "Old intermediate"}]}
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            quality.write_json(output / "Q01-research.json", {"case": "Q01", "entrypoint": "research", "status": "RESULT_UNKNOWN", "run_id": "existing", "started_at": 0, "events": []})
            result = quality.run_case(Client(), {"id": "Q01", "question": "Actual request"}, "research", output)
            self.assertEqual(result["status"], "SUCCEEDED")
            record = json.loads((output / "Q01-research.json").read_text(encoding="utf-8"))
            self.assertFalse(record["client_timing_valid"])
            self.assertEqual(record["answer"], "Answer")

    def test_qa_usage_is_measured_and_missing_cache_usage_is_unknown(self):
        self.assertEqual(quality.measured_usage({"entrypoint": "qa", "response": {"usage": {"inputTokens": 20, "outputTokens": 3}}}),
                         {"input": 20, "output": 3, "cacheRead": None, "cacheWrite": None})

    def test_replay_timing_uses_server_events_and_does_not_call_resume_time_queue_time(self):
        record = {"events": [
            {"data": {"type": "run.queued", "timestamp": "2026-11-04T10:00:00+00:00"}},
            {"data": {"type": "message.delta", "timestamp": "2026-11-04T10:00:02+00:00", "payload": {"delta": "Text"}}},
            {"data": {"type": "run.succeeded", "timestamp": "2026-11-04T10:01:00+00:00"}},
        ], "response": {"started_at": 1793786430}, "first_text_ms": 999999}
        result = quality.durable_timing(record)
        self.assertEqual(result["first_text_ms"], 2000)
        self.assertEqual(result["total_ms"], 60000)
        self.assertIsNone(result["initial_queue_ms"])

    def test_channel_ranks_use_chunk_identity_and_do_not_guess_from_evidence_nodes(self):
        hits = [{"source": {"document_id": "doc", "version_id": "v", "source_node_key": "node",
                            "anchor": {"chunkId": "chunk"}}, "text": "Actual evidence"}]
        trace = {"queries": [{"channel": "DOCUMENT_TAG", "status": "SUCCEEDED", "resultCount": 1,
                               "topChunkIds": ["chunk"]},
                              {"channel": "BM25", "status": "SUCCEEDED", "resultCount": 1,
                               "topChunkIds": ["node"]}]}
        tagged, lexical = quality.channel_metrics(hits, trace, ["doc"])
        self.assertEqual(tagged["metrics"]["precision_at_10"], 1)
        self.assertEqual(tagged["ranked_hits_resolved"], 1)
        self.assertEqual(lexical["ranked_hits_resolved"], 0)

    def test_running_eof_reconnects_without_repeating_execution_or_usage(self):
        class Client:
            timeout = 10
            reads = 0

            def request(self, method, path, body=None, *, last_event_id=None):
                self.assert_get(method)
                self.reads += 1
                event = {"seq": 7, "type": "message.completed", "payload": {"usage": {"input": 20, "output": 3}}}
                if self.reads == 2:
                    assert last_event_id == 7
                return io.BytesIO(("event: message.completed\ndata: " + json.dumps(event) + "\n\n").encode())

            def json(self, method, path):
                self.assert_get(method)
                return {"status": "RUNNING" if self.reads == 1 else "SUCCEEDED", "result": "Answer"}

            @staticmethod
            def assert_get(method):
                assert method == "GET", "Execution must not be repeated"

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            quality.write_json(output / "Q01-research.json", {"case": "Q01", "entrypoint": "research", "status": "RUNNING", "run_id": "existing", "events": []})
            result = quality.run_case(Client(), {"id": "Q01", "question": "Actual request"}, "research", output)
            self.assertEqual(result["status"], "SUCCEEDED")
            record = json.loads((output / "Q01-research.json").read_text(encoding="utf-8"))
            self.assertEqual(record["model_usage"]["input"], 20)
            self.assertEqual(len(record["events"]), 1)


if __name__ == "__main__":
    unittest.main()
