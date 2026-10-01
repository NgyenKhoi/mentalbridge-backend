# MB-385 SessionSummary evidence

MB-385 implements the Consultation-owned post-session continuity boundary. An assigned specialist can publish only after an appointment carries the authoritative evidence-backed `COMPLETED` fact from MB-383/MB-384.

## Persisted contract

- `session_summary` is append-only. A correction requires the current summary ETag and creates a new version linked through `amends_summary_id`.
- `agreed_next_step` is immutable content attached to one summary version. A platform resource stores an exact resource ID and version but never writes a Care SupportPlan occurrence.
- `agreed_next_step_state` is user-owned mutable checklist state. Specialist responses omit state, hidden flag provenance, and state version.
- `session_summary_reuse_consent` is a separate user decision for one exact snapshot. Revocation immediately closes the reusable-summary handoff.

The publish request accepts only bounded user-visible topics, a short progress summary, a note for the user, follow-up suggestion, and structured next steps. Unknown or private fields are rejected; raw journal content, assessment answers, diagnosis, private notes, and chat transcript have no persistence field.

## API behavior

- Specialist publish/list: `/api/v1/specialist/appointments/{appointmentId}/session-summaries`
- User read: `/api/v1/appointments/{appointmentId}/session-summaries`
- User checklist state: `/api/v1/agreed-next-steps/{nextStepId}`
- User exact-snapshot consent: `/api/v1/session-summaries/{summaryId}/reuse-consent`
- MB-381 owner handoff: `/internal/v1/appointments/{appointmentId}/reusable-session-summaries/{summaryId}?version=...`

The handoff validates the actor against the target appointment, requires the same user owner, and requires current consent for the exact immutable version. It returns no user checklist tracking state.

## Verification

`SessionSummaryFlowIntegrationTests` exercises actor and completion gates, rejected private fields, idempotent publication, immutable amendments, specialist privacy, user checklist control, consent approval/revocation, and the exact-version reuse handoff on PostgreSQL Testcontainers. Contract and Liquibase tests cover the implemented surface and all four owner tables.
