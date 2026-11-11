"""Deploy Hindsight against an existing PostgreSQL instance, without business migrations.

Secrets stay in .runtime/hindsight. psql gets passwords through its environment and
SQL through stdin; neither credentials nor a rendered Compose config are printed.
"""

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
from urllib.parse import quote, urlparse


ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / ".runtime" / "hindsight"
COMPOSE = Path(__file__).with_name("compose.yaml")


def load_env(path):
    values = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        match = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$", line.strip())
        if match:
            value = match[2].strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            values[match[1]] = value
    return values


def identifier(value):
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", value):
        raise ValueError("Use a lowercase PostgreSQL identifier of at most 63 characters")
    return '"' + value + '"'


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def write_private(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with open(temporary, "w", encoding="utf-8", newline="\n") as output:
        if os.name != "nt":
            os.chmod(temporary, 0o600)
        output.write(text)
    temporary.replace(path)


def psql(args, cfg, database, sql):
    env = {**os.environ, "PGCONNECT_TIMEOUT": "5", "PGCLIENTENCODING": "UTF8",
           "PGPASSWORD": os.environ.get(args.admin_password_env, "")}
    result = subprocess.run([
        cfg.get("JSD_AIRD_PSQL_PATH") or "psql", "-X", "-w", "-At",
        "-v", "ON_ERROR_STOP=1", "-h", args.pg_host, "-p", str(args.pg_port),
        "-U", args.admin_user, "-d", database,
    ], input=sql, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
    if result.returncode:
        # A PostgreSQL error may echo the statement, including a CREATE ROLE password.
        # Keep the raw diagnostic local and never include it in a raised exception.
        write_private(RUNTIME / "database-error.log", result.stderr)
        raise RuntimeError("PostgreSQL operation failed; inspect .runtime/hindsight/database-error.log locally")
    return result.stdout.strip()


def prepare(args):
    cfg = {**load_env(args.env_file), **os.environ}
    database, role = identifier(args.database), identifier(args.role)
    business = urlparse(cfg.get("JSD_AIRD_DATASOURCE_URL", "").removeprefix("jdbc:"))
    if business.path.lstrip("/") == args.database:
        raise ValueError("Hindsight must use a separate database from the business service")
    if args.database in {"postgres", "template0", "template1", "jsd_aird_agents_dev"}:
        raise ValueError("Choose a dedicated memory database")
    # Require an explicit model route. The Hindsight service does not inherit dsh providers.
    required = ["HINDSIGHT_API_LLM_PROVIDER", "HINDSIGHT_API_LLM_MODEL",
                "HINDSIGHT_API_LLM_BASE_URL", "HINDSIGHT_API_LLM_API_KEY"]
    missing = [key for key in required if not cfg.get(key)]
    if missing:
        raise ValueError("Configure " + ", ".join(missing) + " in the local environment file")
    if not args.admin_user:
        raise ValueError("Specify --admin-user; its password can be supplied in PGADMIN_PASSWORD")
    status = psql(args, cfg, "postgres", "SELECT current_setting('server_version_num')::int;\n")
    if int(status) < 150000:
        raise ValueError("Hindsight requires PostgreSQL 15 or later")
    available = psql(args, cfg, "postgres", "SELECT default_version FROM pg_available_extensions WHERE name='vector';\n")
    if not available or tuple(int(v) for v in available.split(".")[:3]) < (0, 5, 0):
        raise ValueError("Install pgvector 0.5.0 or later in the existing PostgreSQL instance")
    owner = psql(args, cfg, "postgres", "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=" + literal(args.database) + ";\n")
    if owner and owner != args.role:
        raise ValueError("Existing memory database is owned by another role; it will not be changed")
    secret_path = RUNTIME / "credentials.json"
    if secret_path.exists():
        credentials = json.loads(secret_path.read_text(encoding="utf-8"))
        if credentials["database"] != args.database or credentials["role"] != args.role:
            raise ValueError("Existing memory credentials belong to another database or role")
    else:
        credentials = {"database": args.database, "role": args.role,
                       "password": secrets.token_urlsafe(40)}
        existing = psql(args, cfg, "postgres", "SELECT 1 FROM pg_roles WHERE rolname=" + literal(args.role) + ";\n")
        if existing:
            raise ValueError("The memory role already exists without managed credentials; supply a different dedicated role")
        write_private(secret_path, json.dumps(credentials, indent=2) + "\n")
    # Retry reconciles the actual catalog; it never resets an existing role password.
    if not psql(args, cfg, "postgres", "SELECT 1 FROM pg_roles WHERE rolname=" + literal(args.role) + ";\n"):
        psql(args, cfg, "postgres", f"SET log_statement = 'none';\nCREATE ROLE {role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD {literal(credentials['password'])};\n")
    if not owner:
        psql(args, cfg, "postgres", f"CREATE DATABASE {database} OWNER {role};\n")
    psql(args, cfg, args.database, f"REVOKE CONNECT ON DATABASE {database} FROM PUBLIC;\nGRANT CONNECT ON DATABASE {database} TO {role};\nCREATE EXTENSION IF NOT EXISTS vector;\nCREATE EXTENSION IF NOT EXISTS pg_trgm;\n")
    address = args.container_pg_host or args.pg_host
    url = f"postgresql://{quote(args.role, safe='')}:{quote(credentials['password'], safe='')}@{address}:{args.pg_port}/{args.database}"
    service = {key: value for key, value in cfg.items() if key.startswith("HINDSIGHT_API_") and value}
    service.update(HINDSIGHT_API_DATABASE_URL=url, HINDSIGHT_API_DATABASE_SCHEMA="public",
                   HINDSIGHT_API_DATABASE_BACKEND="postgresql",
                   HINDSIGHT_API_MIGRATION_DATABASE_URL=url)
    if service.get("HINDSIGHT_API_READ_DATABASE_URL"):
        service["HINDSIGHT_API_READ_DATABASE_URL"] = url
    service.setdefault("HINDSIGHT_API_EMBEDDINGS_PROVIDER", "local")
    service.setdefault("HINDSIGHT_API_EMBEDDINGS_LOCAL_MODEL", "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
    service.setdefault("HINDSIGHT_API_LLM_MAX_CONCURRENT", "2")
    service.setdefault("HINDSIGHT_API_LLM_MAX_RETRIES", "1")
    service.setdefault("HINDSIGHT_API_DB_POOL_MIN_SIZE", "2")
    service.setdefault("HINDSIGHT_API_DB_POOL_MAX_SIZE", "16")
    service.setdefault("HINDSIGHT_API_WORKER_ID", "jsd-aird-memory")
    service.setdefault("HINDSIGHT_API_WORKER_MAX_SLOTS", "4")
    if any("\n" in value or "\r" in value for value in service.values()):
        raise ValueError("Memory environment values must be single-line")
    # Compose raw format preserves keys containing $, #, quotes or backslashes.
    write_private(RUNTIME / "service.env", "".join(key + "=" + value + "\n" for key, value in sorted(service.items())))
    print("Hindsight configuration ready; dedicated database and role reconciled. Secrets remain in .runtime/hindsight.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "up", "status", "logs", "stop"])
    parser.add_argument("--env-file", default=str(ROOT / ".env"))
    parser.add_argument("--pg-host", default="127.0.0.1")
    parser.add_argument("--container-pg-host", default="host.docker.internal")
    parser.add_argument("--pg-port", type=int, default=5432)
    parser.add_argument("--admin-user", default=os.environ.get("PGADMIN_USER"))
    parser.add_argument("--admin-password-env", default="PGADMIN_PASSWORD")
    parser.add_argument("--database", default="jsd_aird_hindsight")
    parser.add_argument("--role", default="jsd_hindsight")
    args = parser.parse_args()
    try:
        if args.action == "prepare":
            prepare(args)
            return 0
        if not (RUNTIME / "service.env").exists():
            raise ValueError("Run prepare first")
        command = {"up": ["up", "-d"], "status": ["ps"], "logs": ["logs", "--tail", "60"], "stop": ["stop"]}[args.action]
        command = ["docker", "compose", "-f", str(COMPOSE), *command]
        if args.action == "logs":
            result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True,
                                    encoding="utf-8", errors="replace")
            output = (result.stdout or "") + (result.stderr or "")
            values = load_env(RUNTIME / "service.env")
            for key, value in values.items():
                if value and any(part in key for part in ("KEY", "TOKEN", "PASSWORD", "DATABASE_URL")):
                    output = output.replace(value, "[redacted]")
            credentials = RUNTIME / "credentials.json"
            if credentials.exists():
                output = output.replace(json.loads(credentials.read_text(encoding="utf-8"))["password"], "[redacted]")
            print(output, end="")
            return result.returncode
        return subprocess.run(command, cwd=ROOT).returncode
    except (ValueError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        return 1
    except (OSError, subprocess.SubprocessError) as error:
        print("Memory deployment could not complete (" + type(error).__name__ + ")", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())
