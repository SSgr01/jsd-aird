import argparse
from contextlib import redirect_stderr
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import manage


class DeploymentBoundaries(unittest.TestCase):
    def test_database_credentials_are_not_command_arguments_or_public_errors(self):
        args = argparse.Namespace(pg_host="localhost", pg_port=5432, admin_user="owner",
                                  admin_password_env="PGADMIN_PASSWORD")
        result = subprocess.CompletedProcess([], 1, "", "CREATE ROLE app PASSWORD secret-value")
        with tempfile.TemporaryDirectory() as directory, patch.object(manage, "RUNTIME", Path(directory)), \
                patch.dict(os.environ, {"PGADMIN_PASSWORD": "secret-value"}), \
                patch.object(manage.subprocess, "run", return_value=result) as run:
            with self.assertRaises(RuntimeError) as raised:
                manage.psql(args, {}, "postgres", "CREATE ROLE app PASSWORD secret-value;")
            self.assertNotIn("secret-value", str(raised.exception))
            self.assertNotIn("secret-value", " ".join(run.call_args.args[0]))
            self.assertEqual(run.call_args.kwargs["env"]["PGPASSWORD"], "secret-value")
            self.assertIn("secret-value", run.call_args.kwargs["input"])

    def test_business_database_is_rejected_before_any_database_access(self):
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / "input.env"
            env.write_text("JSD_AIRD_DATASOURCE_URL=jdbc:postgresql://localhost/business\n", encoding="utf-8")
            args = argparse.Namespace(env_file=env, database="business", role="memory")
            with patch.dict(os.environ, {}, clear=True), patch.object(manage, "psql") as database:
                with self.assertRaisesRegex(ValueError, "separate database"):
                    manage.prepare(args)
                database.assert_not_called()

    def test_database_owned_by_another_role_is_not_modified(self):
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / "input.env"
            env.write_text("\n".join([
                "HINDSIGHT_API_LLM_PROVIDER=openai", "HINDSIGHT_API_LLM_MODEL=enterprise-model",
                "HINDSIGHT_API_LLM_BASE_URL=https://model.invalid/v1", "HINDSIGHT_API_LLM_API_KEY=private-key",
            ]), encoding="utf-8")
            args = argparse.Namespace(env_file=env, database="memory", role="memory_user", admin_user="owner")
            with patch.dict(os.environ, {}, clear=True), patch.object(manage, "RUNTIME", Path(directory) / "runtime"), \
                    patch.object(manage, "psql", side_effect=["180004", "0.8.6", "another_owner"]) as database:
                with self.assertRaisesRegex(ValueError, "another role"):
                    manage.prepare(args)
                statements = [call.args[-1] for call in database.call_args_list]
                self.assertTrue(all(statement.startswith("SELECT ") for statement in statements))
                self.assertFalse((Path(directory) / "runtime").exists())

    def test_docker_logs_redact_both_api_and_database_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "service.env").write_text("HINDSIGHT_API_LLM_API_KEY=model-secret\n", encoding="utf-8")
            (root / "credentials.json").write_text(json.dumps({"password": "db-secret"}), encoding="utf-8")
            result = subprocess.CompletedProcess([], 1, "model-secret", "db-secret")
            output = io.StringIO()
            with patch.object(manage, "RUNTIME", root), patch.object(manage.subprocess, "run", return_value=result), \
                    patch("sys.argv", ["manage.py", "logs"]), patch("sys.stdout", output), redirect_stderr(output):
                self.assertEqual(manage.main(), 1)
            self.assertNotIn("model-secret", output.getvalue())
            self.assertNotIn("db-secret", output.getvalue())
            self.assertIn("[redacted]", output.getvalue())


if __name__ == "__main__":
    unittest.main()
