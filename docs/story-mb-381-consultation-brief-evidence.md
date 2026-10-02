# MB-381 ConsultationBrief evidence

MB-381 implements an appointment-scoped, non-diagnostic ConsultationBrief for controlled local/test/demo use.

## Ownership and contract

- Consultation remains authoritative for appointment owner, assigned specialist, status, exact times, and appointment version. Its internal endpoint returns only those fields and hides wrong actors as not found.
- Care owns the draft, immutable approved snapshot, sharing grant, revocation, deletion tombstone, and minimized audit. The browser never supplies actor, specialist, appointment status, access window, or screening content.
- The user contract accepts only current situation, an exact owned SupportEvaluation v2 identifier, and one to five goals. Care resolves the two domain screening facts and exact version provenance itself.
- The specialist contract returns only the immutable approved snapshot for the matching assigned appointment. Raw journals, assessment answers, chat, private notes, diagnosis, scores, and unapproved summaries are absent by contract and runtime parsing.

## Concurrency and failure behavior

Care verifies Consultation before entering a local write transaction. Specialist read and owner revoke serialize on the same grant row. The transaction that locks first defines the outcome: an allowed read is audited before commit, while a revocation that wins first causes the read to be denied and audited. Dependency failure, wrong actor/appointment, changed assignment or schedule, non-active appointment, early/expired window, revocation, deletion, or missing source fails closed.

The default access window is `[scheduledStartAt - 24h, scheduledStartAt + 24h]`. Draft creation/edit and approval close when the appointment starts. Deleting clears both editable and approved-snapshot content and revokes active access. Audit rows never store brief content.

## Later SessionSummary reuse

MB-385 now owns SessionSummary creation and its exact immutable source contract. MB-381 does not accept client-authored prior-session text and does not treat brief approval as summary-reuse consent. Consultation exposes only an exact summary snapshot/version after a separate current user approval; Care must use that owner handoff rather than accepting copied text. The existing appointment-preparation grant never implies summary-reuse consent.

## Verification

- Consultation OpenAPI contract minimality and owner/assigned-specialist tests.
- Care OpenAPI privacy/minimality tests and Liquibase changelog/migration tests.
- PostgreSQL integration coverage for draft, approval, exact snapshot read, concurrent revoke/read ordering, early/expired-window denial, reschedule invalidation, deleted-source denial, content-clearing deletion, wrong actor, missing/stale version, and allowed/denied audit facts.
- Frontend generated-contract synchronization, strict runtime parsers, user draft and explicit approval UI, specialist read-only UI, and role-enforcing BFF routes.

### Executed checks (2026-09-29)

- `care-service`: `.\mvnw.cmd -q "-Dtest=ConsultationBriefIntegrationTests" test` — PASS, 7 tests.
- `care-service`: `.\mvnw.cmd -q "-Dtest=CareOpenApiContractTests,CareLiquibaseChangelogTests,CareLiquibaseMigrationTests" test` — PASS, 21 tests.
- `consultation-service`: `.\mvnw.cmd -q "-Dtest=ConsultationOpenApiContractTests,AppointmentDecisionIntegrationTests" test` — PASS, 16 tests.
- `mentalbridge-frontend`: `npm test -- --run lib/care/consultation-brief-validation.test.ts features/appointments/components/ConsultationBriefPanels.test.tsx features/appointments/components/AppointmentRequestPanel.test.tsx features/appointments/components/SpecialistAppointmentDecisionPanel.test.tsx` — PASS, 20 tests.
- `mentalbridge-frontend`: `npm run contracts:check`, `npm run typecheck`, and `npm run lint` — PASS.
- Both repositories: `git diff --check` — PASS (Git only reported existing LF-to-CRLF conversion warnings).

All automated and manual fixtures used synthetic/minimized data; no real journal, chat, assessment-answer, diagnosis, or private-note content was introduced.
