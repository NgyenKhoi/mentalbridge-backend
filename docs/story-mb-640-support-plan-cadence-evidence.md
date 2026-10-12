# MB-640 verification evidence

## Delivered boundary

Care persists a reviewable schedule for every schedulable resource action in a
new SupportPlan draft. The owner can replace or remove that schedule before
activation and can version future workload on an active plan. The bounded rule
covers cadence, ISO weekdays, preferred daypart/resolved local time, estimated
duration, IANA timezone, effective dates, and reminder choice.

The write command uses a strong plan ETag and an idempotency ledger. Active
changes cut over no earlier than the next local day, end the prior revision,
cancel only superseded future open occurrences with `SCHEDULE_CHANGED`, and
regenerate the deterministic rolling horizon. Completed, skipped, and elapsed
history remains intact. Removing or lowering workload is neutral and does not
change eligibility, safety, completion evidence, or plan interpretation.

Professional-support and self-report-review actions are not forced into a
recurrence. Reassessment supports a one-time reminder only. Existing v2
resource plans remain readable and editable through the additive v3 projection.
The web and React Native clients expose equivalent review, edit, removal, stale
write recovery, and activation guards. Native Android device execution remains
owned by MB-666; MB-640 includes the shared mobile contract, UI, and unit tests.

The implementation follows [ADR 0034](adr/0034-support-plan-v3-cross-domain-actions.md)
and [SupportPlan policy v3](policies/support-plan-policy-v3.md).

## Automated evidence

- `SupportPlanIntegrationTests` covers persisted draft defaults, review/edit,
  idempotent replay, activation, active next-day cutover, stale writes, schedule
  revision history, occurrence cancellation, and neutral workload semantics.
- Liquibase and OpenAPI contract tests cover the additive schema, constraints,
  endpoint, request, and response surface.
- Web editor tests cover persisted defaults, bounded changes, neutral removal,
  and preserving input after a stale response. BFF tests cover auth/header
  forwarding, validation, upstream errors, and the current-draft projection.
- React Native editor and API tests cover the equivalent contract, rendering,
  request/version propagation, dirty-state reporting, stale recovery, and
  removal behavior; the complete screen regression suite passes with the new
  projection and mutation surface.
- The Stitch artifacts under `mentalbridge/.stitch/mb-640/` record the approved
  desktop/mobile baseline, generated direction, prototype, and visual QA.

## Verified commands

- Backend: full `mvnw -q test`, plus the focused SupportPlan, OpenAPI, and
  Liquibase suites.
- Web: full quality gate, including contract checks, 1,045 Vitest assertions,
  and the Next.js production build; the SupportPlan Playwright fixture also
  passed on Chromium.
- Mobile: lint, typecheck, all 506 Jest assertions, and Android/iOS Expo bundle
  exports. All changed files pass the scoped Prettier check.

These commands passed locally on 2026-10-12. The repository-wide mobile
`format:check` remains blocked by the pre-existing Prettier baseline on 276
unrelated files; MB-640 does not mechanically rewrite those files.
