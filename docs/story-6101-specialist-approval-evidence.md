# Story 6101 specialist profile submission and approval evidence

## Actor outcome

A `SPECIALIST` can create, reload, update, and explicitly submit the six
approved public profile fields. An `ADMIN` can list only submitted pending
profiles, inspect one profile, and approve it. Approval is persisted with the
administrator identity and timestamp. Repeating approval against the current
version is idempotent and creates no duplicate history entry.

Editing a submitted `PENDING` profile clears `submittedAt`, withdrawing it from
the admin queue until the specialist submits again. An unsubmitted draft is not
visible through the admin detail API. An `APPROVED` profile is immutable in
this slice.

## Delivered boundary

- `GET|PUT /api/v1/specialist-profile`
- `POST /api/v1/specialist-profile/submit`
- `GET /api/v1/admin/specialist-profiles`
- `GET /api/v1/admin/specialist-profiles/{specialistAccountId}`
- `POST /api/v1/admin/specialist-profiles/{specialistAccountId}/approve`

The contract is `contracts/openapi/consultation-service-v1.yaml`. Updates and
commands use the current quoted `ETag` in `If-Match`. The frontend snapshot and
generated types are checked against that provider contract.

## Evidence classification

Backend evidence is service integration with synthetic UUIDs and profile text
against a disposable PostgreSQL Testcontainer. It covers create, persisted
reload, submit, pending queue, admin detail visibility, approve, approval
replay, audit history, edit/withdraw, role denial, stale/missing version, and
invalid timezone. No live environment or real specialist data was used.

Frontend evidence is fixture-based component/BFF/contract verification. It
covers load, save, submit, pending queue, detail, approve, empty/error display,
contract validation, server-only configuration, and production compilation.
It is not represented as live cross-stack evidence.

## Verification

| Command | Environment | Result |
| --- | --- | --- |
| `.\mvnw.cmd -q test` | Java 22; PostgreSQL Testcontainers | 9 tests passed |
| `npm run lint` | Node.js 22 | passed |
| `npm run typecheck` | Next.js 16 / TypeScript | passed |
| `npm run contracts:check` with `CONSULTATION_OPENAPI_SOURCE` set to the provider contract | local paired worktrees | passed |
| `npm test` | Vitest/jsdom fixtures | 52 files, 274 tests passed |
| `npm run build` | Next.js production build | passed |
| `npm run test:e2e` | Playwright fixture mode | passed; no failed tests |

## Intentional deferrals

Story 6101 exposes no discovery, slot-publication, credential, license,
certificate, document-upload, rejection, suspension, restoration, notification,
Kafka, or OpenTelemetry behavior. Therefore a profile cannot proceed through a
discovery or slot API in this slice. Stories 6103–6105 must enforce
`approvalStatus=APPROVED` when those capabilities are introduced. Story 6102
owns the remaining approval lifecycle.
