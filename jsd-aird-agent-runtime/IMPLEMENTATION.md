# Unified Harness delivery tracker

This is an implementation checklist, not a claim of acceptance. Existing code is in the
isolated `feature/pi-harness` worktree. No business migrations are run automatically.

## P0 — converge before adding platforms

- [x] One capability registry with version/schema pinning and explicit host bindings.
- [x] Topic-free conversations and runs; literature remains a business pack.
- [x] Shared authorized retrieval contract; legacy Q&A and the new transport use the same retrieval/compression pipeline.
- [x] Fixed Q&A execution profile/API, no model tools or nested answer-generating assistant tool.
- [x] Real project read-only tools with Java object authorization (real search verified; no accessible records returned).
- [x] Conversation-first research UI with existing routes/history compatibility; generic artifacts no longer require topics.
- [x] Regression: scope isolation, citations, queue/steer, retry and restoration.
- [ ] Route the ordinary Q&A tab through Harness, including canonical message IDs, edit/resend, rename/delete, streaming and historical compatibility. **The ordinary tab still uses its original execution path.**
- [ ] Separate remaining built-in business dispatch and pure Q&A evidence policy from the transitional service classes; do not claim the runtime is completely host-independent yet.

### Verified in the current development slice

- Python: 84 passed, 1 opt-in model test skipped. Node: 7 passed. Java: 83 related tests passed (81 regression + 2 shared-retrieval tests).
- Frontend: 34 targeted tests, typecheck, targeted ESLint, production build and bundle gate passed.
- Real authenticated fixed QA: run `3d2a0faf-acbe-4814-8b68-b4aff3ee956a`, shared retrieval returned 12 evidence fragments; one fixed retrieval and two model-call reservations (query understanding + final answer), succeeded with canonical citations.
- Real literature reading: run `d60ac14d-58e4-4cad-9592-49def677c30e`, 60/269 PDMS publication nodes read; answer generated, **not full-text acceptance** and no Agent delivery review recorded.
- Real project read-only search: run `79ff14b1-738f-4ee3-bcee-7b85f9bb0089`, no authorized candidates; no project or experiment write was made.
- Developer services updated on 5184 / 8180 / 8093 / 8092. No new business SQL/Flyway was run.

## P1 — production reliability

- [ ] Remote Linux sandbox transfer/status/cancel protocol and actual isolation acceptance.
- [ ] Durable receipts, unknown-result reconciliation and audited operations UI.
- [ ] Runtime monitoring, restart/disconnect/revocation fault drills.

## P2 — research acceptance

- [ ] Owner/conversation-private uploads using existing parsing components.
- [ ] Controlled OA acquisition; no paywall bypass or unrestricted URL tool.
- [ ] Both target PDFs, real authenticated pipeline and expert-reviewed gold set.

External acceptance prerequisites: Linux sandbox host access, a real test account,
and a research reviewer. Synthetic tests never replace these acceptance gates.

The provided account has now been used successfully. The user confirmed no Linux sandbox host is currently available, so script execution stays disabled with no host fallback. The user subsequently explicitly authorized the hydrogel PDF import into a dedicated KB acceptance category. Both original file hashes are checked and publications are pinned; this is not delivery of owner/conversation-private uploads. Full-text execution and scientific-content acceptance are tracked separately in VALIDATION.md and the ignored `.runtime/literature-quality/` receipts. Failed or operator-assisted runs must not be presented as blind, first-attempt scientific acceptance.
