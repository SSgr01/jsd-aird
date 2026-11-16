#!/usr/bin/env python3
"""Linux manual deployment entry point; run --help for commands. No dependencies."""
import argparse
import os
from pathlib import Path
import secrets
import shlex
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]


def initialize():
    path = ROOT / '.env'
    existing = load_config(path) if path.exists() else {}

    def paired_secret(*keys):
        values = {existing[key] for key in keys if existing.get(key)}
        if len(values) > 1:
            raise ValueError(f'Mismatched secrets: {", ".join(keys)}. Correct .env first.')
        value = next(iter(values)) if values else secrets.token_hex(32)
        if len(value) < 32:
            raise ValueError(f'{keys[0]} must contain at least 32 characters.')
        return value

    shared = paired_secret('APP_AI_HARNESS_SHARED_SECRET', 'JSD_AIRD_HARNESS_SHARED_SECRET')
    runtime = paired_secret('APP_AI_HARNESS_RUNTIME_SECRET', 'JSD_AIRD_HARNESS_RUNTIME_SECRET')
    sandbox = paired_secret('JSD_AIRD_HARNESS_SANDBOX_SECRET', 'JSD_AIRD_SANDBOX_TOKEN')
    agent = {
        'APP_AI_HARNESS_ENABLED': 'true',
        'APP_AI_HARNESS_QA_ENABLED': 'true',
        'APP_AI_HARNESS_CONTROL_URL': 'http://127.0.0.1:8093',
        'APP_AI_HARNESS_SHARED_SECRET': shared,
        'JSD_AIRD_HARNESS_SHARED_SECRET': shared,
        'APP_AI_HARNESS_RUNTIME_SECRET': runtime,
        'JSD_AIRD_HARNESS_RUNTIME_SECRET': runtime,
        'JSD_AIRD_HARNESS_JAVA_URL': 'http://127.0.0.1:8080',
        'JSD_AIRD_HARNESS_CONTROL_URL': 'http://127.0.0.1:8093',
        'JSD_AIRD_HARNESS_RUNTIME_URL': 'http://127.0.0.1:8092',
        'JSD_AIRD_PUBLIC_URL': 'CHANGE_ME_PUBLIC_URL',
        'JSD_AIRD_HARNESS_DATABASE_URL': 'CHANGE_ME_DEDICATED_AGENTS_POSTGRES_URL',
        'JSD_AIRD_HARNESS_STORAGE_ROOT': str(ROOT / '.runtime/agent/control'),
        'JSD_AIRD_DSH_ROOT': str(ROOT / '.runtime/agent/dsh'),
        'JSD_AIRD_DSH_EXECUTION_ROOT': str(ROOT / '.runtime/agent/execution'),
        'JSD_AIRD_DSH_PROVIDER': 'CHANGE_ME_TESTED_DIALOGUE_PROVIDER',
        'JSD_AIRD_DSH_MODEL': 'CHANGE_ME_DIALOGUE_MODEL',
        'JSD_AIRD_DSH_BASE_URL': 'CHANGE_ME_DIALOGUE_BASE_URL',
        'JSD_AIRD_DSH_API_KEY': 'CHANGE_ME_DIALOGUE_API_KEY',
        'JSD_AIRD_HARNESS_SANDBOX_ENABLED': 'true',
        'JSD_AIRD_HARNESS_SANDBOX_URL': 'http://127.0.0.1:8094',
        'JSD_AIRD_HARNESS_SANDBOX_SECRET': sandbox,
        'JSD_AIRD_HARNESS_MEMORY_ENABLED': 'false',
        'JSD_AIRD_HARNESS_MEMORY_URL': 'http://127.0.0.1:8888',
    }
    broker = {
        'JSD_AIRD_SANDBOX_TOKEN': sandbox,
        'JSD_AIRD_SANDBOX_WORKSPACE_ROOT': str(ROOT / '.runtime/sandbox'),
        'JSD_AIRD_SANDBOX_IMAGE': 'jsd-aird-analysis:1',
    }
    defaults = {**agent, **broker}
    secret_keys = {key for key in defaults if key.endswith('_SECRET') or key == 'JSD_AIRD_SANDBOX_TOKEN'}
    additions = {key: value for key, value in defaults.items()
                 if key not in existing or (key in secret_keys and not existing[key])}
    if additions:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, 'a', encoding='utf-8', newline='\n') as stream:
            stream.write('\n# Linux agent deployment: missing settings and paired secrets.\n')
            stream.writelines(f'{key}={shlex.quote(value)}\n' for key, value in additions.items())
        if os.name != 'nt':
            path.chmod(0o600)
    print(f'.env ready; added {len(additions)} settings. Existing nonempty values preserved.')
    print('Edit CHANGE_ME values and verify the Java/public URLs before starting services.')


def load_config(path):
    values = {}
    for number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        tokens = shlex.split(line, comments=True)
        if not tokens:
            continue
        if len(tokens) != 1 or '=' not in tokens[0]:
            raise ValueError(f'{path.name}:{number}: expected KEY=value (quote values containing spaces).')
        key, value = tokens[0].split('=', 1)
        if not key.isidentifier():
            raise ValueError(f'{path.name}:{number}: invalid variable name.')
        values[key] = value
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog=(
        'First run init and edit the root .env. Existing settings and secrets are preserved. '
        'Install/build dependencies separately. '
        'migrate initializes the dedicated Control database. Run sandbox, control and gateway '
        'as three processes. exec -- java -jar /path/app.jar loads the same agent environment '
        'for your existing Java command. For background operation use nohup and redirect logs.'))
    parser.add_argument('action', choices=['init', 'migrate', 'sandbox', 'control', 'gateway', 'exec'])
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.action == 'init':
        initialize()
        return
    broker = args.action == 'sandbox'
    config = load_config(ROOT / '.env')
    if broker:
        config = {key: value for key, value in config.items() if key.startswith('JSD_AIRD_SANDBOX_')}
    for key, value in config.items():
        if value.startswith('CHANGE_ME'):
            raise ValueError(f'Configure {key} in .env first.')
    # The sandbox receives no inherited application/provider credentials.
    env = ({key: value for key, value in os.environ.items()
            if key in ('PATH', 'HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'DOCKER_HOST',
                       'DOCKER_CONTEXT', 'DOCKER_CONFIG', 'XDG_RUNTIME_DIR')}
           if broker else dict(os.environ))
    env.update(config)
    env['PYTHONPATH'] = str(ROOT / 'jsd-aird-agent/control/src')
    env['PYTHONUTF8'] = '1'
    python = str(ROOT / 'jsd-aird-agent/control/.venv/bin/python')
    commands = {
        'sandbox': [python, '-m', 'aird_agent.sandbox.broker'],
        'control': [python, '-m', 'aird_agent.server'],
        'migrate': [python, '-m', 'aird_agent.server', '--migrate'],
        'gateway': [env.get('JSD_AIRD_DSH_NODE') or shutil.which('node') or 'node',
                    str(ROOT / 'jsd-aird-agent/deepseek/src/server.mjs')],
    }
    command = args.command
    if command[:1] == ['--']:
        command = command[1:]
    if args.action == 'exec' and not command:
        raise ValueError('Usage: python3 scripts/linux-agent.py exec -- java -jar /path/app.jar')
    if args.action != 'exec' and command:
        raise ValueError('Extra commands are supported only by the exec action.')
    command = command if args.action == 'exec' else commands[args.action]
    os.chdir(ROOT)
    os.execvpe(command[0], command, env)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError) as error:
        print(f'Error: {error}', file=sys.stderr)
        sys.exit(1)
