# MB-373 SupportPlan choice and activation evidence

## Scope delivered

MB-373 extends the MB-372 paid deterministic draft with bounded user choice and
explicit activation. Care remains the sole decision owner. The client can keep,
swap, or remove only the final selections represented by existing draft slots
and server-admitted exact resource versions; a core slot cannot be removed.

Implemented canonical operations:

- `PUT /api/v1/support-plans/{supportPlanId}/choices`
- `POST /api/v1/support-plans/{supportPlanId}/activate`
- `GET /api/v1/support-plans/current`

Choice replacement requires an exact `If-Match`, accepts only exact versions
already admitted into the draft, and is a no-op when the complete desired state
already exists. Activation additionally requires an owner-scoped
`Idempotency-Key`. Immediately before activation, Care revalidates the current
authoritative `PLUS`/`PREMIUM` entitlement, owned current-compatible
SupportEvaluation/template composition, and all selected exact Content
versions. `FREE`, stale evidence, unavailable dependencies, injected
candidates, duplicate intent, and version conflicts fail closed.

The transaction records the exact final selection set, request fingerprint,
expected/resulting versions, status, entitlement/evaluation/resource-policy
evidence, and resolution instants. Activation transitions `DRAFT` to `ACTIVE`
and writes one minimized `care.support-plan.activated` outbox fact atomically.
Owner locking and database partial uniqueness enforce exactly one official
`ACTIVE`/`PAUSED` current plan under concurrent commands.

## Persistence and contracts

- `care-014-support-plan-choice-activation` adds `activated_at`, complete
  nullable optional-slot tuples, the one-current-plan unique index, and
  append-only activation replay/evidence tables.
- The canonical Care OpenAPI owns the three operations and generalized
  `SupportPlan` response. Activation accepts no safety acknowledgement field.
- The remaining lifecycle proposal removes choice/activation operations and
  retains only unimplemented later lifecycle paths.
- The frontend snapshot/generated types are synchronized from the same Care
  OpenAPI.
- The canonical relational model, entity index, field dictionary, module
  boundary, policy register, runtime policy, and requirements traceability all
  reflect the executable state.

## Frontend behavior

- The BFF validates owner session, UUID, exact quoted ETag, unique slot intent,
  and exact version syntax before choice replacement. Activation additionally
  validates and forwards its printable idempotency key.
- The page queries the authoritative current plan first, then falls back to the
  current draft only when no current plan exists.
- Draft slots use labelled native radio groups. Only returned admitted
  candidates are selectable; optional slots expose an explicit remove choice.
- A dirty draft disables activation until choices are saved. Async controls are
  disabled while in flight and stable error copy explains entitlement, stale,
  invalid-choice, dependency, and optimistic-concurrency failures.
- Safety guidance precedes all plan controls. Activation uses one explicit
  button and no extra safety acknowledgement.
- After activation the browser ignores local derivation and reloads
  `/api/care/support-plans/current`; the active view has no draft controls.
- Focus styling, semantic fieldsets, text-plus-color status, 44px controls,
  reduced-motion loading behavior, responsive reflow, and no-horizontal-scroll
  checks cover accessibility and mobile use.

## Subtask evidence

| Subtask | Evidence |
| --- | --- |
| MB-445 decision and flow | Canonical OpenAPI operations, SupportPlan policy v2 MB-373 runtime rules, requirements traceability, and this evidence record. |
| MB-446 owner capability | Care service/policy/writer/controller, additive migration, natural PUT choice replacement, audited activation idempotency, current query, atomic activation outbox, and concurrency tests. |
| MB-447 frontend/BFF | Contract-generated types, strict validators, three BFF routes, admitted-choice UI, explicit activation, authoritative reload, unit/route/browser tests. |
| MB-448 verification | Full backend suite, repository paired-change verifier, full frontend quality gate, and focused Playwright fixture all pass. |

## Verification results

Backend from `care-service`:

```text
.\mvnw.cmd -q test "-Dtest=SupportPlanIntegrationTests,SupportPlanProposalContractTests,CareOpenApiContractTests,CareEventContractTests,CareLiquibaseChangelogTests,CareLiquibaseMigrationTests"
PASS: 38 affected tests, 0 failures, 0 errors, 0 skipped

Final production-code checkpoint:
.\mvnw.cmd -q test "-Dtest=SupportPlanIntegrationTests,SupportPlanProposalContractTests,CareOpenApiContractTests"
PASS: 20 directly affected tests, 0 failures, 0 errors, 0 skipped

SupportPlanIntegrationTests
PASS: 10 tests including admitted swap, natural PUT no-op, optional removal,
activation replay, Free/stale/version failure, authoritative current reload,
and concurrent activation producing exactly one current plan.
```

Repository root:

```text
powershell -ExecutionPolicy Bypass -File .\scripts\verify-repository.ps1 -BaseSha origin/dev
PASS: 635 tracked files checked; paired-change policy passed for 33 changed files.
```

Frontend from `mentalbridge`, with Care contract sources pointing to the MB-373
backend contracts:

```text
npx vitest run app/api/care/support-plans/[supportPlanId]/choices/route.test.ts features/support-plan/components/SupportPlanJourney.test.tsx
PASS: 2 affected files / 9 tests

npm run typecheck
PASS

npx eslint --max-warnings=0 <affected SupportPlan/BFF files>
PASS

node scripts/care-contract.mjs --check
PASS: Care OpenAPI snapshots and generated types
```

Full unrelated regression and production build coverage are delegated to the
pull-request quality gate. No live cross-stack or production-provider claim is
made.

## Privacy and safety review

- No raw assessment answer, journal content, bearer token, or AI reasoning is
  persisted in command audit, event payloads, UI state, or fixtures.
- Safety remains deterministic Care evidence, visible before ordinary plan
  controls, non-paywalled, and independent from activation eligibility.
- AI is absent from choice admission, revalidation, activation, and current-plan
  authority.
- SupportPlan copy remains general wellbeing support and does not claim
  diagnosis, treatment, recovery, or resolved safety risk.
