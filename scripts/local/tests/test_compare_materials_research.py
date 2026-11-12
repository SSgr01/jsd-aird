import argparse
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "materials_comparison", Path(__file__).parents[1] / "compare_materials_research.py"
)
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)


class ComparisonTest(unittest.TestCase):
    def test_model_budget_and_case_changes_cannot_be_reported_as_a_paired_comparison(self):
        fixed = {"cases_sha256": "suite", "repetitions": 2, "workers": 2, "timeout": 900,
            "base_url": "local", "model_and_budget_config": {"model": "fixed", "steps": 20},
            "memory_during_evaluation": {"read_enabled": False}}
        comparison.assert_comparable(fixed, dict(fixed))
        for field, value in (("cases_sha256", "different"), ("repetitions", 1),
                ("model_and_budget_config", {"model": "other"}), ("memory_during_evaluation", {})):
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, field):
                comparison.assert_comparable(fixed, {**fixed, field: value})

    def test_native_usage_is_measured_and_missing_receipts_stay_unknown(self):
        record = {"response": {"dsh_usage": [
            {"inputTokens": 10, "outputTokens": 5, "cacheReadTokens": 20, "totalTokens": 35},
            {"inputTokens": 3, "outputTokens": 2, "cacheReadTokens": 30, "totalTokens": 35}]}}
        actual = comparison.native_measurements(record)
        self.assertEqual((actual["input"], actual["output"], actual["total"], actual["cache_read"]), (13, 7, 70, 50))
        self.assertIsNone(comparison.native_measurements({})["total"])

    def test_actual_models_use_conversation_receipts_instead_of_configuration_or_auxiliary_calls(self):
        record = {"response": {"dsh_model_attempts": [
            {"purpose": "conversation", "provider": "provider", "model": "actual"},
            {"purpose": "conversation", "provider": "provider", "model": "actual"},
            {"purpose": "report-review", "provider": "other", "model": "reviewer"}]}}
        self.assertEqual(comparison.actual_models(record), [("provider", "actual")])
        self.assertEqual(comparison.actual_models({}), [])

    def run_arm(self, folder, fail):
        cases = folder / "cases.json"
        cases.write_text(json.dumps({"cases": [{"id": "M1", "question": "Actual question"},
            {"id": "M2", "question": "Another question"}]}), encoding="utf-8")
        args = argparse.Namespace(cases=cases, output=folder, variant="baseline", workers=2,
            repetitions=2, env_file=folder / "absent.env", base_url="local", origin="local", timeout=20)
        settings = {"revision": 1, "read_enabled": True, "auto_enabled": True}
        edits = []

        def memory(_client, _origin, body):
            if body["action"] == "set-settings":
                self.assertEqual(body["revision"], settings["revision"])
                settings.update(body["patch"])
                settings["revision"] += 1
                edits.append(dict(body["patch"]))
            return dict(settings)

        def run_case(client, case, _entrypoint, arm):
            self.assertIs(client, shared)
            self.assertFalse(settings["read_enabled"])
            self.assertFalse(settings["auto_enabled"])
            if fail:
                raise RuntimeError("Transport failure")
            comparison.quality.write_json(arm / (case["id"] + "-research.json"), {"status": "SUCCEEDED"})
            return {"status": "SUCCEEDED"}

        shared = object()
        with patch.object(comparison, "environment", return_value={"JSD_AIRD_EVAL_PASSWORD": "test-only"}), \
                patch.object(comparison, "manifest", return_value={}), \
                patch.object(comparison, "memory", side_effect=memory), \
                patch.object(comparison.quality, "Client", return_value=shared) as client, \
                patch.object(comparison.quality, "run_case", side_effect=run_case), \
                patch("builtins.print"):
            if fail:
                with self.assertRaisesRegex(RuntimeError, "Transport failure"):
                    comparison.run(args)
            else:
                comparison.run(args)
            self.assertEqual(client.call_count, 1, "Concurrent workers must not replace one another's login")
        self.assertEqual(edits, [{"read_enabled": False, "auto_enabled": False},
            {"read_enabled": True, "auto_enabled": True}])

    def test_parallel_repetitions_share_one_login_and_restore_memory(self):
        with tempfile.TemporaryDirectory() as directory:
            self.run_arm(Path(directory), fail=False)

    def test_transport_failure_still_restores_memory(self):
        with tempfile.TemporaryDirectory() as directory:
            self.run_arm(Path(directory), fail=True)


if __name__ == "__main__":
    unittest.main()
