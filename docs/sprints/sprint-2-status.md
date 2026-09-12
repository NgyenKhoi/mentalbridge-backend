# Sprint 2 status

## Snapshot

| Field | Value |
| --- | --- |
| Status date | 2026-09-12 |
| Lifecycle | `CLOSED` |
| Delivery progress | `100%` of committed Sprint 2 work completed |
| Nominal capacity | 250 person-hours: 5 members × 5 hours × 10 working days |
| Committed capacity | 180 hours |
| Integration/review/risk buffer | 70 hours |
| Target branch | `dev` |

## Dev baselines at closure

| Repository | `dev` baseline | Integrated evidence | Required checks at baseline |
| --- | --- | --- | --- |
| Backend | `536f2a736a25730107a565b3ab5e095202ecb554` | PR #35 merged and all planned Sprint 2 backend outcomes present | `8/8` successful |
| Frontend | `414db1d70b488058ab076a18a4e90a8e5b19381f` | PR #16 merged and all planned Sprint 2 frontend outcomes present | `3/3` successful |

These immutable SHAs are the verified code baselines before this closure-only
documentation update. Later documentation or promotion commits do not change the
Sprint 2 product-scope conclusion.

## Sprint goal result

Sprint 2 delivered the safe, backend-authoritative PHQ-9 vertical slice for
anonymous and authenticated users and completed the Review 1 business-definition
work needed to enter Sprint 3.

The closed sprint preserves the distinction between implemented runtime,
approved product definitions, and future capabilities. Completion does not make
specialist workflows, automatic follow-up, personalized SupportPlans, production
clinical use, or AI-owned screening decisions available.

## Completed outcomes

| Outcome | Final status | Closure result |
| --- | --- | --- |
| Anonymous PHQ-9 | `COMPLETE` | Care owns the questionnaire, isolated anonymous session, scoring, result, safety fields, reopening, expiry, and fallback behavior. |
| Authenticated PHQ-9 | `COMPLETE` | Authenticated ownership, idempotent submission, exact result reopening, and explicit failure states are integrated. |
| Browser scoring and obsolete hotline removal | `COMPLETE` | Production browser paths do not calculate PHQ-9 results or invent hotline, diagnosis, treatment, or emergency-response claims. |
| Care profile and consent | `COMPLETE` | Own-profile concurrency, versioned privacy disclosure, and append-only consent decisions are integrated. |
| Assessment history, reassessment, and progress | `COMPLETE` | Owned history, immutable result reopening, distinct reassessment, and bounded descriptive progress are integrated. |
| Reviewed resources and fallback | `COMPLETE` | Only eligible reviewed content is rendered; empty and unavailable states remain explicit and neutral. |
| Realtime Sprint 1 carry-over | `COMPLETE` | Contract, authentication, presence, durable message/history, idempotency, and failure-path foundations are closed. |
| Review 1 business definitions | `COMPLETE` | Cohort, instrument provenance, terminology, safety, support, consent, handoff, reassessment, and progress boundaries are recorded in canonical policies and ADRs. |
| Integrated release evidence | `COMPLETE` | Backend and frontend delivery PRs are merged into `dev` with their required quality gates. |

## Delivery evidence

- Backend integrated evidence: [PR #35](https://github.com/NgyenKhoi/mentalbridge-backend/pull/35), merged into `dev`.
- Frontend integrated evidence: [PR #16](https://github.com/NgyenKhoi/mentalbridge-frontend/pull/16), merged into `dev`.
- Reproducible Sprint 2 verification record: [integrated release evidence](../sprint-2-integrated-release-evidence.md).
- Product and safety decisions: [ADR 0009](../adr/0009-care-screening-safety-and-support-boundaries.md) and [ADR 0012](../adr/0012-two-domain-screening-and-system-proposed-support-plans.md).
- Current requirement mapping: [requirements traceability](../requirements-traceability.md).
- Jira remains the source of truth for individual Sprint 2 work-item completion and approval history.

## Definition of Done

- [x] All committed Sprint 2 work items are complete.
- [x] Backend-authoritative PHQ-9 works for anonymous and authenticated users.
- [x] Profile, consent, history, reassessment, progress, reviewed-resource, and Realtime carry-over work is complete.
- [x] Review 1 questions and approved dispositions are represented in canonical policies, ADRs, contracts, and traceability.
- [x] Runtime-complete, definition-complete, deferred, unavailable, and out-of-scope states remain distinguishable.
- [x] Required backend and frontend pull requests are integrated into `dev`.
- [x] Sprint 2 task progress and closure are recorded in Jira.

## Scope transferred beyond Sprint 2

The two-domain, system-proposed SupportPlan direction approved on 2026-09-12 is
forward work for Sprint 3. It does not reopen Sprint 2. Sprint 3 owns the
compatible domain-aware evaluation, draft-plan proposal, resource eligibility,
user-choice, revalidation, activation, and frontend-consumer work.

Specialist discovery/booking, automatic follow-up, production deployment,
production clinical approval, and AI clinical personalization remain outside the
closed Sprint 2 commitment unless separately approved and planned.

## Sprint 3 handoff goal

Establish the implementation-ready provider foundation for domain-aware,
system-proposed SupportPlans: synchronize the approved policy, add compatible
SupportEvaluation v2, add exact-version Resource Eligibility v1, and publish the
initial reviewed eligibility matrix. This foundation must unblock proposal work
without prematurely committing lifecycle, frontend workspace, or end-to-end
activation scope before their provider gates pass.

## Directory policy

`docs/sprints/` contains only one status document per sprint. Task-specific
blueprints, closure matrices, runbooks, readiness reports, impact analyses, and
delivery evidence are not tracked in this directory; Jira, pull requests, and
the appropriate canonical policy, ADR, contract, or module documentation retain
their durable decisions and evidence.
