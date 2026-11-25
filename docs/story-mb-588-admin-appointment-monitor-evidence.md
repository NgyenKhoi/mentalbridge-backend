# MB-588 admin appointment monitor evidence

MB-588 implements read-only operational visibility into Consultation-owned
appointment lifecycle state. It does not grant ADMIN clinical or session
authority.

## Authority and data boundary

- Consultation owns and serves appointment status, schedule, modality,
  lifecycle timestamps, operational reason codes, version, and the associated
  service-credit settlement state.
- `GET /api/v1/admin/appointments` requires an explicit range of at most 180
  days, accepts bounded status/modality/user/specialist filters, and returns at
  most 100 rows per cursor-paged request.
- The response contains identifiers and operational facts only. It excludes
  ConsultationBrief data, SessionSummary content, journals, assessment answers,
  chat bodies, SupportPlan data, diagnosis, and private notes.
- The endpoint is protected by the `ADMIN` role and introduces no write path,
  evidence bypass, credit bypass, clinical completion, SupportPlan mutation, or
  SessionSummary authoring capability.

## Freshness and frontend behavior

Every response declares Consultation as its source, carries a server generation
time, and exposes an explicit `CURRENT`, `STALE`, or `UNAVAILABLE` data state.
The admin workspace fetches through a server-side BFF, strictly validates the
owner contract, displays unavailable state explicitly, clears stale rows after
failure, and renders only the minimized operational projection. Production UI
does not fall back to mock appointments.

## Verification

- OpenAPI contract coverage verifies the ADMIN query operation and rejects
  sensitive field names from the response schema.
- PostgreSQL integration coverage exercises bounded filtering, pagination-safe
  result shape, non-admin denial, invalid ranges, and absence of private
  content.
- Frontend BFF and component tests exercise filter forwarding, strict response
  validation, pagination, minimized rendering, and unavailable-state behavior.

### Executed checks (2026-10-05)

- Consultation compilation and `ConsultationOpenApiContractTests`: PASS (14
  contract tests).
- Frontend targeted BFF/component suite: PASS (7 tests).
- Frontend typecheck, lint, Consultation contract snapshot check, and production
  build: PASS.
- Consultation PostgreSQL integration suite: PASS (2 tests) with Docker-backed
  Testcontainers, including ADMIN filtering, minimized output, role denial, and
  invalid/unbounded query rejection.
- Full Consultation test suite: PASS (129 tests), including shared-database
  execution with the pagination fixture isolated by its user filter.
