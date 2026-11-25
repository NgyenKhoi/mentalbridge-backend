# Story 5102 SupportEvaluation v2 evidence

## Delivered boundary

Issue #48 / MB-335 adds the Care-owned `SupportEvaluation` v2 provider without
changing the v1 resource, rows, or `care.support-tier.resolved` event. The
controlled implementation is frozen in:

- `contracts/openapi/care-support-evaluation-v2.yaml`;
- `contracts/events/care/support-evaluation-created-v2.schema.json`;
- policy `mb-support-routing-capstone-v2`;
- Liquibase changeset `care-011-domain-aware-support-evaluation-v2`.

Human known-consumer and production domain approval remain release gates. This
evidence does not claim those external approvals.

## Verification evidence

Executed from the `care-service` module on 2026-09-16:

| Check | Result |
| --- | --- |
| Maven compile and test compile | Passed |
| REST v2, event v2, v1 REST/event, and proposal contract tests | Passed |
| Domain policy unit tests | Passed |
| PostgreSQL Liquibase migration tests | 12 passed |
| SupportEvaluation v2 PostgreSQL/service integration tests | 5 passed |
| Complete Care test suite | 132 passed, 0 failed, 0 errors, 0 skipped |
| Repository policy verifier against `origin/dev` | Passed |

The integration suite exercises authentication and role rejection, opaque
cross-owner reads, exact evidence snapshots, independent positive item-9
safety, same-key replay, conflicting-key rejection, same-evidence aliases,
invalid/foreign evidence, concurrent creates, atomic outbox rollback, minimized
event payloads, and simultaneous v1/v2 readability.

## Compatibility matrix

| Boundary | v1 | v2 | Compatibility result |
| --- | --- | --- | --- |
| Create REST | `POST /api/v1/support-evaluations` | `POST /api/v2/support-evaluations` | Separate resources and idempotency namespaces |
| Owner read REST | `GET /api/v1/support-evaluations/{id}` | `GET /api/v2/support-evaluations/{id}` | Both remain readable; identifiers are not reinterpreted |
| Persistence | `support_evaluation*` | `support_evaluation_v2*` | Additive tables; no v1 update or backfill |
| Policy | `mb-support-routing-capstone-v1` | `mb-support-routing-capstone-v2` | Exact version retained on every aggregate |
| Event | `care.support-tier.resolved` schema `1.0` | `care.support-evaluation.created` schema `2.0` | Independent message contracts and aggregate types |
| Classification | Coarse `supportTier` | Two domain-local pathways and stable reasons | v2 has no global severity/tier |
| Safety | v1 safety-follow-up routing | Separate PHQ-9 item-9 evidence object | v2 safety never overwrites a domain level/pathway |
| Sensitive answers | Excluded | Excluded | No raw answer, item-9 value, or total score in the v2 event |

## Forward correction

Any policy or schema correction must use a new immutable version and a new
append-only changeset. Existing v1 and v2 history, evidence snapshots, request
aliases, and emitted event contracts must remain unchanged.
