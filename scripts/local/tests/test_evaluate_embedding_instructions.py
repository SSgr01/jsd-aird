import copy
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import evaluate_embedding_instructions as evaluator


class EmbeddingInstructionEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.presets = json.loads(evaluator.PRESETS.read_text(encoding="utf-8"))
        self.suite = {"corpus": [{"id": "a", "text": "doc A"}, {"id": "b", "text": "doc B"}],
                      "queries": [{"id": "q1", "query": "材料问题", "relevance": {"a": 3, "b": 1}}]}

    def test_metrics_use_all_positive_gold_and_graded_discount(self):
        scores = evaluator.metrics(["b", "a"], {"a": 3, "b": 1}, 1)
        self.assertEqual(scores["recall@1"], .5)
        self.assertAlmostEqual(scores["ndcg@1"], 1 / 7)
        self.assertEqual(evaluator.metrics(["a", "b"], {"a": 3, "b": 1}, 10)["ndcg@10"], 1)

    def test_documents_are_embedded_once_and_only_queries_change(self):
        calls = []
        class FakeClient:
            model, dimension = "frozen-qwen3", 2
            def embed(self, inputs):
                calls.append(inputs[:])
                return [[1, 0], [0, 1]] if len(calls) == 1 else [[1, 0]]
        original = copy.deepcopy(self.suite)
        report = evaluator.evaluate(self.suite, FakeClient(), self.presets, [1, 10])
        self.assertEqual(self.suite, original)
        self.assertEqual(calls[0], ["doc A", "doc B"])
        self.assertEqual(calls[1], ["材料问题"])
        for index, profile in enumerate(("generic", "materials"), 2):
            self.assertEqual(calls[index], ["Instruct: " + self.presets[profile] + "\nQuery:材料问题"])
        self.assertEqual(len(calls), 4)
        self.assertEqual(report["profiles"]["materials"]["mean"]["recall@1"], .5)
        self.assertEqual(report["delta_vs_none"]["generic"]["ndcg@1"], 0)
        self.assertEqual(report["corpus_sha256"], evaluator.sha(evaluator.canonical(self.suite["corpus"])))

    def test_rejects_unknown_duplicate_or_missing_judgments_before_api_calls(self):
        invalid = []
        for relevance in ({"missing": 1}, {"a": 0}, {"a": True}, {"a": float("nan")}, {"a": 4}):
            suite = copy.deepcopy(self.suite)
            suite["queries"][0]["relevance"] = relevance
            invalid.append(suite)
        suite = copy.deepcopy(self.suite)
        suite["corpus"].append(suite["corpus"][0])
        invalid.append(suite)
        for suite in invalid:
            with self.subTest(suite=suite), self.assertRaises(ValueError):
                evaluator.validate_suite(suite)

    def response(self, data):
        return io.BytesIO(json.dumps({"data": data}).encode())

    def test_http_batch_order_optional_auth_and_dimensions(self):
        bodies, headers = [], []
        def respond(request, timeout):
            body = json.loads(request.data)
            bodies.append(body)
            headers.append(request.get_header("Authorization"))
            return self.response([{"index": i, "embedding": [int(text) + 1, 1]}
                                  for i, text in reversed(list(enumerate(body["input"])))])
        for key in ("", "cloud-key"):
            client = evaluator.EmbeddingClient("http://localhost:8000/v1/", "qwen3", 2, key)
            with patch.object(evaluator.urllib.request, "urlopen", respond):
                vectors = client.embed([str(i) for i in range(11)])
            self.assertEqual(len(vectors), 11)
            for i, vector in enumerate(vectors):
                self.assertAlmostEqual(vector[0] / vector[1], i + 1)
        self.assertEqual([len(body["input"]) for body in bodies], [10, 1, 10, 1])
        self.assertEqual(headers, [None, None, "Bearer cloud-key", "Bearer cloud-key"])
        self.assertTrue(all(body["dimensions"] == 2 for body in bodies))

    def test_malformed_vectors_indices_and_counts_fail_closed(self):
        client = evaluator.EmbeddingClient("http://localhost/v1", "qwen3", 2)
        for data in ([], [{"index": 1, "embedding": [1, 0]}], [{"embedding": [1, 2, 3]}],
                     [{"embedding": [0, 0]}], [{"embedding": [True, 0]}], [{"embedding": [float("nan"), 0]}]):
            with self.subTest(data=data), patch.object(evaluator.urllib.request, "urlopen", return_value=self.response(data)):
                with self.assertRaises(ValueError):
                    client.embed(["test"])
        duplicate = [{"index": 0, "embedding": [1, 0]}, {"index": 0, "embedding": [0, 1]}]
        with patch.object(evaluator.urllib.request, "urlopen", return_value=self.response(duplicate)), self.assertRaises(ValueError):
            client.embed(["a", "b"])

    def test_deterministic_ties_and_preset_identity(self):
        class TiedClient:
            model, dimension = "qwen3", 2
            def embed(self, inputs):
                return [[1, 0] for _ in inputs]
        self.suite["corpus"].reverse()
        first = evaluator.evaluate(self.suite, TiedClient(), self.presets, [1])
        changed = dict(self.presets, materials="A new task instruction")
        second = evaluator.evaluate(self.suite, TiedClient(), changed, [1])
        self.assertEqual(first["profiles"]["none"]["queries"][0]["hits"][0]["id"], "a")
        self.assertNotEqual(first["preset_sha256"], second["preset_sha256"])
        self.assertEqual(first["corpus_sha256"], second["corpus_sha256"])


if __name__ == "__main__":
    unittest.main()
