# MB-592 specialist client continuity evidence

MB-592 implements a bounded specialist client list and appointment snapshot. It
does not create a general user directory or a new consent mechanism.

## Authority and data boundary

- Consultation is authoritative for specialist approval, appointment
  assignment, status, modality, schedule, and appointment version.
- `GET /internal/v1/specialist/client-relationships` returns at most 200 rows
  for the authenticated currently approved specialist: eligible future/current
  appointments and appointments ending within the last 90 days.
- Care composes those rows with its own `user_profile.display_name` and the
  current ConsultationBrief snapshot/access-window provenance. The public
  endpoint is `GET /api/v1/specialist/client-continuity`.
- The response has no raw journal, screening answer, assessment score, AI
  analysis, SupportPlan state, chat content, diagnosis, or notes belonging to
  another specialist.

## Fail-closed behavior

Specialist suspension or loss of approval denies the relationship projection.
Consultation dependency failure or malformed data denies the composed list.
Brief revocation, deletion, appointment schedule/version drift, access before
the window, access after the window, and ineligible appointment state are
returned as explicit non-readable access states. The frontend displays those
states but cannot grant or extend access.

Both owners retain audit authority: Consultation records content-free list
attempts and Care records each returned appointment-bound continuity read using
the existing minimized ConsultationBrief audit.

## Frontend behavior

`/specialist/clients` now loads the authoritative continuity endpoint. It groups
only returned relationships by user, lets the specialist switch exact
appointments, opens the existing appointment-scoped brief/summary flows, and
shows source version and access-window provenance. Seed/demo clients are no
longer rendered.

## Verification

- Consultation and Care compile against their updated OpenAPI contracts.
- PostgreSQL integration coverage checks eligible relationship selection,
  suspension denial, content-free list audit, minimized Care composition,
  brief-access provenance, and absence of private content.
- Frontend strict runtime parsing rejects malformed or extra fields and the
  component test verifies API-owned identity/provenance while excluding former
  seed data.

### Executed checks (2026-10-04)

- `consultation-service`: `./mvnw.cmd -q -DskipTests compile` — PASS.
- `care-service`: `./mvnw.cmd -q -DskipTests compile` — PASS.
- `mentalbridge-frontend`: `npm run typecheck` and `npm run lint` — PASS.
- `mentalbridge-frontend`: targeted Vitest suite for continuity parsing,
  rendering, and existing ConsultationBrief panels — PASS, 14 tests.
- `mentalbridge-frontend`: `npm run build` — PASS, including the new specialist
  continuity BFF route.
- `consultation-service`: `ConsultationOpenApiContractTests` — PASS, 11 tests.
- `care-service`: `CareOpenApiContractTests` — PASS, 9 tests.
- Frontend Care and Consultation provider-snapshot/type generation checks —
  PASS. The repository-wide contract command additionally found a pre-existing
  Journal provider/snapshot difference outside MB-592; no Journal file was
  changed by this story.
- `consultation-service`: `AppointmentDecisionIntegrationTests` on PostgreSQL
  Testcontainers — PASS, 25 tests, including approved/suspended relationship
  selection and allowed/denied audit.
- `care-service`: `ConsultationBriefIntegrationTests` on PostgreSQL
  Testcontainers — PASS, 8 tests, including available/revoked continuity
  provenance and private-field exclusion.
