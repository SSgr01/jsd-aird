# pi Harness integration

This is the source-built pi worker, not an independent GPT Researcher or PaperQA agent loop.
The pinned upstream source is the `vendor/pi` git submodule; `pi-fork.lock.json` records the
commit and local fork branch. No upstream patches are currently needed. A remote fork has
not been created. Work is on `feature/pi-harness` in the isolated implementation worktree.

The delivery tracker is [实施记录](../docs/implementation.md); current evidence and remaining
acceptance gates are in [验收记录](../docs/validation.md).

`runtime_core.Coordinator` owns scheduling, leases, budgets, the call ledger, ordered events,
checkpoints and retention. Its explicit `RuntimeBindings`, capability registry and `HostGateway`
ports have no JSD imports, business database settings or default business registry. A second-host
PostgreSQL integration test runs an arithmetic capability without Java. JSD authorization,
research completion, citation policy and output projection live in `harness_v1.JsdBindings`;
`Service` remains a compatibility composition facade. Literature dispatch is a separate adapter.

Ordinary Q&A can now execute fixed generation through native pi. Java remains the canonical
conversation/message authority and retains its API, retrieval, history, edit/resend, rename/delete,
answer normalization and SSE contract. The trusted prepared-QA endpoint is not browser-proxied.
Research conversations retain their own IDs and execution policies.

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
cd ../../jsd-aird-agent/pi-worker
npm test
```

Hydration downloads public provider catalogs at build time; runtime provider discovery is
disabled. Use `npm --cache <workspace-cache>` if the default cache is not writable. Update
the upstream source deliberately, record the new SHA, rebuild and rerun tests before rollout.
Never use an unpinned remote `main` as a production build input.

## Configuration and startup

Python requires Python 3.11/3.12; Node requires Node 22.23.2+. The new control runtime needs a
**dedicated `jsd_aird_agents*` PostgreSQL database**. Business research objects live in the Java
database using `V94__research_topics_and_evidence.sql`; private sources additionally require `V95__agent_private_sources.sql`. For this local run,
the user authorized manual execution against `localhost:5433/jsd_aird`; it was applied in
a transaction without Flyway or changes to existing business rows. No production switch was enabled.

1. Install the existing agents project with its lock file. Its normal dependencies now include
   JSON Schema validation. Install `[research]` for PaperQA RCS; the optional dependency is pinned
   to a Git commit (retaining setuptools-scm version metadata), not a second search index. A normal installation is
   `python -m pip install -e './jsd-aird-agent/control[test,research]'`.
2. Merge `../jsd-aird-agent/control/.env.harness.example` into a private environment file without
   overwriting existing `.env` settings; use three
   different randomly generated service secrets. The Python settings read `.env` in their cwd.
3. Run `python -m aird_agent.server --migrate` with the dedicated agents DB configured,
   then `python -m aird_agent.server` (127.0.0.1:8093).
4. Supply `.env.example` to the Node process and run `npm start` here (127.0.0.1:8092).
5. Configure Java `APP_AI_HARNESS_SHARED_SECRET` to the same value as Python's
   `JSD_AIRD_HARNESS_SHARED_SECRET`, `APP_AI_HARNESS_CONTROL_URL=http://127.0.0.1:8093`,
   and explicitly enable `APP_AI_HARNESS_ENABLED=true` **only after deploying/migrating**.
   `APP_AI_HARNESS_QA_ENABLED` defaults to that flag; set it explicitly to `false` to retain
   legacy ordinary generation during a staged rollout. A failed pi request never silently
   falls back to another model path.
6. Open `/assistant`, then select **研究助手**, with `ai.use` permission. Direct link:
   `/assistant?mode=research`. The old `/assistant/research` redirects here.

The ordinary Q&A page, canonical API and stream store remain compatible; its optional final
generation now routes through Harness. A UI-only hub lazily mounts
two independent tabs and retains each visited tab's local state while switching. The research tab
reuses `AiConversationWorkspace` for history, messages, composer and mobile navigation, plus the
existing Markdown and original-document components. Research plans, external-query approval,
Agent self-check, evidence review/comparison and report downloads extend that shared UI; they do
not reuse ordinary conversation IDs, messages, retrieval settings or endpoints. Each tab has its
own render error boundary. With the ordinary Harness switch enabled, service failure is surfaced as the existing
ordinary-Q&A error event; Java history remains available.

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
`python -m aird_agent.sandbox.broker` (127.0.0.1:8094).
The broker and control service use independent roots. `/executions` transfers only bounded
base64 input/scratch files with hashes; GET polls durable status and DELETE requests cancellation.
Execution IDs and request hashes prevent replay. Broker restart marks pending work UNKNOWN.
Containers receive
only read-only `input`/scratch-seed mounts, no network, a read-only root, UID 10001, dropped
capabilities, PID/CPU/memory/time limits and bounded tmpfs. Outputs leave as checked bounded
data (50 files / 10 MiB), never a writable host mount. Office outputs use the existing file
validation path before registration. Use a dedicated host/rootless engine; for hostile tenants
deploy a stronger OCI runtime such as gVisor and test the actual kernel/runtime isolation.

Docker Desktop Linux image build, isolation and real literature calculations have been checked;
the local ignored configuration enables the broker. Checked-in defaults remain disabled.
`sandbox_execute` requires `input_paths`, which may be empty. Only concrete files under the
current workspace's input/ or scratch/ are accepted; directories, globs, links and traversal
are rejected. `sandbox_prepare(input_paths)` reports paths, sizes, hashes, container paths and
quota use. Execution validates again and transfers the exact bytes hashed, independently of the
workspace listing. Limits remain 50 files and 10 MiB; count and byte failures are distinct.
Use `/work` as the working directory and `output/` for exported artifacts. Script failure retains
its FAILED receipt; UNKNOWN is reconciled without replay. No host-execution fallback exists.

Literature calculations should select a small extraction JSON/CSV with exact evidence/quote
handles. Registration binds output SHA-256 to its successful execution receipt and freezes valid
original spans/source versions from those handles. Owned artifact metadata provides a provenance
download at `/api/v1/agents/research/artifact/{id}/provenance`, under the normal access checks.

Both private and published KB search accept `match_mode=auto|phrase|all|any` (query ≤256 characters,
≤8 effective terms). Search offset pages ranked hits; node_offset locates original reading.
`document_outline`/`private_document_outline` expose bounded navigation candidates, not verified
semantic TOCs. `evidence_context` adds up to two neighbors on either side within the same immutable
source version; exact-span `evidence_read` pages long nodes. Task-local indexes reuse canonical
evidence only after authorization checks; AUTO-library search always refreshes discovery.
Search/context are not fulltext coverage. COMPLETE with evidence requires valid source citations.
See VALIDATION.md for the two-source acceptance and the remaining default-Flash KB limitation.

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
scores/summaries. Optional provider initialization runs outside the ASGI event loop.
`PAPERQA_ENABLE_THINKING` is unset by default; configure it only for a compatible provider/model.
`REPORT_REVIEW_TIMEOUT_SECONDS` defaults to 120. It does not parse, re-chunk or vectorize the library. Crossref is metadata only;
web/DOI queries require an explicit approval bound to exact tool arguments and expiration.
Rejection is final for that query. Fulltext is not inferred from metadata/search snippets.

The workspace has rebuilt authorized context/evidence inputs, scratch and candidate outputs.
Raw private uploads live in the existing file service, scoped to owner and conversation in
`ai.agent_private_source`. Existing safety scanning, parser policy and structured-source codec
are reused, without publishing, embedding or indexing. Generic file download/preview/presigned
paths also apply an owner policy for these originals and registered research artifacts. Parsing is bounded; interrupted pending
work expires to FAILED after 30 minutes instead of silently rerunning an external parser.
The user can detach failed sources and submit a fresh upload.

`RUN_UPLOAD` and `EXTERNAL_FULLTEXT` are enabled in the separately versioned `private-literature`
pack. Fulltext completion, exact quotes, matrix/version validation and report coverage recognize
these authorities. The source drawer supports upload, readiness, original download and removal.
Private reading separates `private_document_outline` (page starts and candidate headings),
`private_document_search` (whole-document short-phrase search) and `private_document_read`
(sequential nodes without query). Search returns node offsets for targeted reading and never
counts as fulltext coverage. Durable no-progress guards require a changed strategy or position
after repetitive empty/unchanged results. The development pack version remains 1.

Explicit user-requested PMC acquisition uses the current official PMC Open Data Cloud (the old
OA API/FTP retired in August 2026). It accepts PMCID identifiers, verifies published/nonretracted
open-access metadata, reusable CC licences, version ambiguity, bucket/prefix, MD5 and PDF/XML
identity, and keeps provenance. It refuses arbitrary URLs and redirects. It is not an unrestricted
model URL tool, and does not bypass paywalls.

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

Durable mutation receipts and an audited owner-only operations view support UNKNOWN result
reconciliation against an authoritative host receipt, a committed control-transaction receipt,
or a sandbox result. A missing receipt stays UNKNOWN; reconciliation never replays the operation.
Confirmed saved outputs are projected back into the run. Owner monitoring exposes run states and
unknown counts and rechecks current access. Unprovable outcomes remain blocked for operator review.

Remaining release gates include default-model KB reliability, scientific content acceptance and
expert review. The assisted two-paper engineering loop passed, but source
review rejected its scientific content; the initial autonomous run was PARTIAL. Current source
review v3 rejects unsupported absence/causal-certainty claims and invalidates older checks.
Malformed reviewer output gets at most one budgeted format repair; no content verdict is invented.
The isolated live-stack fixture is not production migration acceptance: baseline duplicate versions
and missing legacy MDM/RND tables require explicitly documented test fixtures.

## Verification

```powershell
# Real isolated PostgreSQL, not the business database
./jsd-aird-agent/control/tools/setup-local-postgres.ps1 -Port 55449
$env:HARNESS_TEST_DATABASE_URL='postgresql://agents_test@127.0.0.1:55449/jsd_aird_agents_test'
cd jsd-aird-agent/control
python -m pytest harness_tests --confcutdir=harness_tests --basetemp=.tmp/harness-check-UNIQUE
```

The current `harness_tests/` suite uses synthetic Java responses and a real isolated
PostgreSQL ledger for adapter/authorization/replay tests; pure Q&A and evidence-policy tests
need no database. Without `HARNESS_TEST_DATABASE_URL`, database tests explicitly skip.
These fixtures never load application `.env` files and reject database names outside
`jsd_aird_agents*_test`. The tests are also included in the Python project's default discovery.

Run `npm test` and `npm run check` here. Policy tests run without pi; the native tests load the
built pinned pi source and exercise discovery, activation, tool execution, checkpoint restoration
and fixed QA with a local synthetic model. An opt-in real model test is available:

```powershell
$env:HARNESS_LIVE_MODEL='1'
node --env-file=../.env --test --test-name-pattern='configured real model' test/native.test.mjs
```
