# pi Harness integration

This is the source-built pi worker, not an independent GPT Researcher or PaperQA agent loop.
The pinned upstream source is the `vendor/pi` git submodule; `pi-fork.lock.json` records the
commit and local fork branch. No upstream patches are currently needed. A remote fork has
not been created. Work is on `feature/pi-harness` in the isolated implementation worktree.

The current convergence work and remaining gaps are tracked in [IMPLEMENTATION.md](IMPLEMENTATION.md).
The control plane now supports topic-free `agent` runs and a fixed `fast_qa` profile.
Trusted packs are registered through `runtime_core.CapabilityRegistry`; another host supplies
`HostGateway` bindings instead of JSD database/identity objects. Legacy research requests remain
compatible. This is an incremental extraction: built-in literature dispatch still lives in the
transitional control service, and the ordinary Q&A UI has not switched execution paths yet.

## Boundaries

- Browser → Java `/api/v1/agents`: existing login, CSRF, `ai.use`, owned research resources.
- Java → Python `/internal/v1/harness`: short-lived HMAC identity, separate audience.
- Python → Java `/internal/v1/agent-tools`: current account/auth-version and per-document
  authorization are checked again. The model cannot supply a principal or arbitrary URL.
- Python → Node: independent runtime credential, execution epoch and renewable lease.
- Node runs the real pi session/agent loop. Only platform tools and a trusted compaction
  extension load; no user/home extensions, project AGENTS files or built-in filesystem tools.
- Python → separate sandbox broker → networkless OCI container. The broker has **no database,
  Java delegation or model credential**. Control and Node do not receive a Docker socket.

The existing legacy LangGraph services/runs are unchanged. This version has one main agent,
not independently prompted subagents. Tool calls are bounded per run; scheduling also limits
simultaneously running runs across control workers.

## Build the source

From the implementation repository root:

```powershell
git submodule update --init vendor/pi
cd vendor/pi
npm ci --ignore-scripts
npm run hydrate:model-data
npm run build:offline
cd ../../jsd-aird-agent-runtime
npm test
```

Hydration downloads public provider catalogs at build time; runtime provider discovery is
disabled. Use `npm --cache <workspace-cache>` if the default cache is not writable. Update
the upstream source deliberately, record the new SHA, rebuild and rerun tests before rollout.
Never use an unpinned remote `main` as a production build input.

## Configuration and startup

Python requires Python 3.11/3.12; Node requires Node 22.23.2+. The new control runtime needs a
**dedicated `jsd_aird_agents*` PostgreSQL database**. Business research objects live in the Java
database using the schema in `V94__research_topics_and_evidence.sql`. For this local run,
the user authorized manual execution against `localhost:5433/jsd_aird`; it was applied in
a transaction without Flyway or changes to existing business rows. No production switch was enabled.

1. Install the existing agents project with its lock file. Its normal dependencies now include
   JSON Schema validation. Install `[research]` for PaperQA RCS; the optional dependency is pinned
   to a Git commit (retaining setuptools-scm version metadata), not a second search index. A normal installation is
   `python -m pip install -e './jsd-aird-agents[test,research]'`.
2. Merge `../jsd-aird-agents/.env.harness.example` into a private environment file without
   overwriting existing `.env` settings; use three
   different randomly generated service secrets. The Python settings read `.env` in their cwd.
3. Run `python -m jsd_aird_agents.harness_v1 --migrate` with the dedicated agents DB configured,
   then `python -m jsd_aird_agents.harness_v1` (127.0.0.1:8093).
4. Supply `.env.example` to the Node process and run `npm start` here (127.0.0.1:8092).
5. Configure Java `APP_AI_HARNESS_SHARED_SECRET` to the same value as Python's
   `JSD_AIRD_HARNESS_SHARED_SECRET`, `APP_AI_HARNESS_CONTROL_URL=http://127.0.0.1:8093`,
   and explicitly enable `APP_AI_HARNESS_ENABLED=true` **only after deploying/migrating**.
6. Open `/assistant`, then select **研究助手**, with `ai.use` permission. Direct link:
   `/assistant?mode=research`. The old `/assistant/research` redirects here.

The original ordinary Q&A page, API and stream store are unchanged. A UI-only hub lazily mounts
two independent tabs and retains each visited tab's local state while switching. The research tab
reuses `AiConversationWorkspace` for history, messages, composer and mobile navigation, plus the
existing Markdown and original-document components. Research plans, external-query approval,
Agent self-check, evidence review/comparison and report downloads extend that shared UI; they do
not reuse ordinary conversation IDs, messages, retrieval settings or endpoints. Each tab has its
own render error boundary. Research service failure does not replace ordinary Q&A.

### Conversation controls

Running tasks keep both explicit delivery modes in a compact composer menu:
`follow_up` (default, queued for after the current run) and `steer` (delivered at the
next safe execution boundary, not a forced interruption of an executing tool).
Pending messages are visible above the composer and can be edited, reordered,
removed, or promoted to `steer`. These operations are owner-authorized, revision-
checked and idempotent; already dispatched messages cannot be edited or deleted.
Acceptance and native pi delivery are different states (`PENDING` / `DELIVERED`).

Stop is one composer control. It cancels the current execution and pauses pending
work, including a direct automatic handoff racing with the stop. Completed writes
are not rolled back. Continue through the same input: the explicit text `继续`
links the stopped task and resumes the queue; a different new instruction leaves
the queue paused. Checkpoints are reused only within the authorized source scope.
The latest interrupted goal survives repeated stop/continue cycles, even without
a checkpoint. Known transient failures have a retry action in the error card;
unknown side effects and budget/policy failures never get a blind replay button.

Queue changes use `/conversations/{id}/queue`; state stays in the existing control
records. This interaction update needs no additional business schema migration.

The feature defaults off. The checked-in files contain placeholders only; no `.env` is copied
into a Workspace, and no model key is accepted from a browser or tool call.

### Sandbox (separately deployed)

Build `docker build -t jsd-aird-analysis:1 sandbox` from this directory. Deploy the broker with
the variables in `.env.sandbox.example` and
`python -m jsd_aird_agents.harness_v1.sandbox_broker` (127.0.0.1:8094).
The broker and control service must see the same authorized workspace root. Containers receive
only read-only `input`/scratch-seed mounts, no network, a read-only root, UID 10001, dropped
capabilities, PID/CPU/memory/time limits and bounded tmpfs. Outputs leave as checked bounded
data (50 files / 10 MiB), never a writable host mount. Office outputs use the existing file
validation path before registration. Use a dedicated host/rootless engine; for hostile tenants
deploy a stronger OCI runtime such as gVisor and test the actual kernel/runtime isolation.

Docker is **not available on this development machine**. Container execution and image build
remain unverified here; the service returns `SANDBOX_UNAVAILABLE`, with no host fallback.
Protocol/command-policy tests do not constitute a successful container isolation test.

## Research path and limitations

The first authority is **authorized, published, AI-approved knowledge documents**. A plain task
creates an owned topic server-side in AUTOMATIC mode; `knowledge_search` discovers eligible
current publications and checks document permissions before retrieval. Explicit SELECTED topics
remain limited to the selected publication versions. MinerU/OCR,
corrected structured nodes, file versions, publication snapshots, existing chunks/embeddings,
BM25 and vector repositories are reused. The scoped research adapter applies document filters
before ranking, fuses BM25/vector/metadata with RRF, then uses the existing reranker. It does
not silently search the whole organization. The existing QA's global rewrite/coverage pipeline
is not copied wholesale: research planning/subquestions are driven by the main agent. No claim
is made that the two orchestrations have identical retrieval/coverage behavior.

Pinned historical publications are read through `document_read`; they never inherit current
index hits from another correction of the same file. Table windows remain authoritative table
reads; evidence fields should use the corresponding published-node source for a stable citation.

PaperQA RCS only receives authorized candidates via `aadd_texts(defer_embedding=true)` and
`aget_evidence(evidence_retrieval=false, NoEmbedding())`. It returns original evidence IDs with
scores/summaries. It does not parse, re-chunk or vectorize the library. Crossref is metadata only;
web/DOI queries require an explicit approval bound to exact tool arguments and expiration.
Rejection is final for that query. Fulltext is not inferred from metadata/search snippets.

The workspace has rebuilt `input/topic.json`, `input/evidence/<id>.json`, scratch and candidate
outputs. Raw authoritative PDFs remain in the existing file service and reader; there is **no
new private raw-upload parser or automatic external-fulltext acquisition in this implementation**.
The `RUN_UPLOAD`/`EXTERNAL_FULLTEXT` source enum values are reserved, not enabled authorities.
Use the existing knowledge upload → parse → review → publish flow to add first-version materials.

Research topics, notes, field-level evidence cards, explicit extraction verification/history,
comparison export, online reports and Word export are Java-owned. Reports are labeled candidate,
not experimentally validated. Analysis files must call `artifact_save`; registered files live in
the existing file service and remain available independently of the temporary workspace.

Run state, call ledger and ordered events are durable in PostgreSQL. Full pi checkpoints are
owner-scoped immutable blobs separate from model context and never returned in list/UI payloads.
The model input cap is min(64k, model-window − output − 4096), compaction triggers at 80%,
recent content is bounded to 16k, and research summaries target 4k. Main and compaction/RCS
calls share the budget. Pricing remains `UNKNOWN` unless an actual tariff is supplied.
Expired epochs cannot commit callbacks. Unknown side effects are not blindly replayed.
This applies across linked runs of the same conversation, not only the original call ID.
Oversized tool outputs are projected to read-only input files with explicit preview flags,
instead of being silently stuffed into the next model request. Canonical evidence remains
separate from these previews and is bounded independently.
Terminal-run workspaces expire after 7 days by default; only the exact checked UUID/UUID
working directory is removed. Research records, checkpoints and registered authority files
are not deleted. Resuming recreates authorized input projections; unregistered outputs are
temporary and may need regeneration. Configure `JSD_AIRD_HARNESS_WORKSPACE_RETENTION_DAYS`.

Remaining release gates: real Java/control/model/reader end-to-end acceptance with the two target
PDFs, expert-scored retrieval/citation/summary coverage evaluation, actual Docker isolation tests,
private-upload/fulltext acquisition, side-effect reconciliation UI and broader operations telemetry.
These are not represented as passed by the synthetic tests or the UI fixture.

## Verification

```powershell
# Real isolated PostgreSQL, not the business database
./jsd-aird-agents/tools/setup-local-postgres.ps1 -Port 55449
$env:HARNESS_TEST_DATABASE_URL='postgresql://agents_test@127.0.0.1:55449/jsd_aird_agents_test'
cd jsd-aird-agents
python -m pytest harness_tests --confcutdir=harness_tests --basetemp=.tmp/harness-check-UNIQUE
```

`test_pi_e2e.py` starts the real pi HTTP worker and control API with a synthetic local model.
`test_rcs_contract.py` uses real PaperQA with a synthetic local model and asserts no embedding
requests. Install `[research]` to run it. These tests prove integration contracts, not scientific
accuracy. Do not count unit test totals as a reviewed research gold set.

Frontend: run typecheck, targeted ESLint and `vitest run src/services/agents/agent-api.test.ts`.
The development-only `harness-preview.html` is explicitly a **mock UI fixture**, not the production
route or an enabled backend. It is not an entry in the production Vite build.
