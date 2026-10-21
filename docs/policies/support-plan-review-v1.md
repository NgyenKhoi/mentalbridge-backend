# Adaptive SupportPlan review policy v1

| Field | Value |
| --- | --- |
| Policy version | `support-plan-review-v1` |
| Review schema | `adaptive-support-plan-review-v1` |
| Status | Accepted by the owner for MB-645 on 2026-10-21 |
| Owner | Care |
| Amends | [SupportPlan policy v3](support-plan-policy-v3.md) and [proposal policy v1](support-plan-proposal-v1.md) prospectively |
| Applies to | Owned current native `ACTIVE` or `PAUSED` plans; existing plan and summary history remains under its saved policy |

This policy governs a factual review, practical alternatives and the user's
explicit keep or change decision. It preserves the one-current-plan and
one-draft invariants, paid mutation capability, source ownership, professional
precedence, and safety rules. An alternative means a defensible practical fit;
it is never a claim of clinical superiority, recovery or treatment success.
Care admits options, the user decides, and optional AI may explain only the
approved facts and choices. Neither the browser nor AI ranks or admits actions.

## Review timing and participation

The existing [14-local-day cycle](support-plan-proposal-v1.md#fourteen-day-review-cycle)
and its pause arithmetic remain unchanged. The activation date is day one;
`reviewDueOn = startedOn + 13 days + pausedDays`. Only applicable pause days
shift the checkpoint. The canonical reassessment comparison continues to use
two adjacent, half-open 14-day UTC periods anchored at midnight. A program
participation period and a canonical comparison period are distinct facts.

| Situation | Required behavior |
| --- | --- |
| Early or shortened participation | Allow a factual manual review with actual dates, timezone, elapsed and paused days, and available scheduled/completed/skipped/open counts. Do not claim that a full cycle or comparison elapsed. |
| Active and review due | Invite review and optional eligible paired screening/self-report. Keep the plan active until an explicit user decision. There is no hard review-expiry deadline, automatic completion, renewal, replacement or plan termination. |
| Paused | Show the pause and shifted checkpoint. Keep may leave the plan paused; schedule changes and continuation require a separate explicit resume. Admitted explicit replacement remains possible. |
| Missed or delayed check-ins | Preserve actual coverage and missing outcomes. Missed work neither moves the checkpoint nor means failure, non-adherence or poorer wellbeing. |
| Screening missing, incomplete or not ready | Explain the actual missing source or readiness state without inventing an eligibility date. Allow available factual review and freshly admitted nonreplacement choices; replacement requires the latest canonical matching v2 summary. Never fabricate or compose a summary during review. |
| Screening refused or review dismissed | Allow return to Today without submission or mutation. Refusal preserves history. Dismissal is not keep, continuation or acceptance and sends no command. |
| No current native plan | Preserve existing draft, history, Guide, free screening and safety paths. A sample or draft is not a personalized adaptive review, and reading a review never creates a draft. |

Read authorization is separate from paid mutation authorization. An owner may
read their current plan and review on `FREE` or expired access; mutation options
are withheld with the applicable entitlement reason. Required entitlement
uncertainty blocks an affected mutation rather than hiding owned history.
This review adds no blanket paid-read or schedule lock to existing APIs.

## Separate evidence and provenance

The canonical dimensions remain screening change, Journal context, plan
engagement, and Care-owned explicit user-authored self-reported experience. Activity
helpfulness/reflection is supporting evidence and never substitutes for the
explicit self-report. Preserve each source independently, including sparse,
absent, deleted, denied or unavailable states and the actual period, coverage,
timezone and as-of context. An outage is not absence or unchanged evidence.

Every fact retains an evidence reference, owner, source identity and available
revision/version, source policy/schema and availability reason. In particular:

- screening retains both instruments' exact assessment IDs, saved bands,
  questionnaire/scoring versions and independent comparison directions;
- Journal retains only its authorized bounded aggregate context, coverage,
  analysis/job identity and exact source revisions;
- explicit self-report retains the exact Care report ID/revision, period and
  withdrawal/deletion state; and
- reusable engagement retains the native action and occurrence identities,
  available plan/action versions, approved closed codes and counts.

When no canonical summary exists, the exact-period owned Care self-report may
still be shown independently. Other dimensions remain explicitly missing.
Review does not silently repair a missing report or re-create deleted facts.
Optional source failure degrades only its evidence and affected options;
required admission uncertainty prevents commitment.

Only deterministic structured disagreements may be annotated, such as opposite
PHQ-9/GAD-7 directions. Preserve supporting and conflicting references without
resolving them into an overall improvement verdict. Journal or self-report
prose is not classified into a direction to manufacture a contradiction.
Task completion and helpfulness do not override screening or explicit experience.
No wellbeing, recovery, adherence or treatment score or ratio is produced.

Private Journal text, private emotion values/notes/factors/trends and optional
occurrence reflection text are excluded from option payloads and logs. A
separate same-user Emotion progress view may show its real rolling dates and
coverage; it is not Care comparison evidence, an AI/admission input or plan
storage. Its failure does not block the canonical four dimensions.

## Repeated practical patterns

A repeated pattern requires at least two distinct explicitly reusable
occurrences of the same approved code for the same native action within the
actual engagement period. Expose the count, contributing occurrence IDs and
available coverage. Two occurrences of different actions or codes do not form
one repeat; unapproved feedback contributes no inferred signal. `OTHER` and
free text are not classified into a supported pattern.

The admitted barrier codes are `NOT_ENOUGH_TIME`, `LOW_ENERGY` and
`NOT_A_GOOD_FIT`. At least two reusable `HELPFUL` reports may support retaining
that action. These are practical reports, not clinical or adherence evidence,
and never erase conflicting facts.

## Bounded admitted choices

Every returned option has a deterministic opaque `optionId`, a closed kind and
execution kind, affected action/focus IDs, rationale codes, supporting and
conflicting evidence references, and the exact before/after consequence. Care
resolves the required plan, draft and schedule versions. An unavailable
category has a closed reason; the system never invents a candidate.

| Kind | Admission and consequence |
| --- | --- |
| `KEEP_CURRENT` | Available when fresh current admission passes, even when alternatives exist. Before due or without a cycle it validates an unchanged plan. At due it uses explicit cycle continuation. It never silently resumes a pause. |
| `LOWER_WORKLOAD` | Repeated `NOT_ENOUGH_TIME` or `LOW_ENERGY` may support a single-action shorter admitted duration or lower weekly cadence. Preserve core minimums, aggregate time budget, source eligibility and professional-primary roles. Use one atomic existing schedule update. |
| `DIFFERENT_TIME` | Use a saved explicit `preferredDaypart` and schedule-v2 constraints. A barrier alone cannot choose a time. Omit the option when no concrete permitted change exists. |
| `ALTERNATIVE_ACTION` | Repeated `NOT_A_GOOD_FIT` may support an exact currently eligible same-slot alternative already admitted by `SupportPlanPolicy`. Preserve required core/optional slots and roles; introduce no action kind without its owner integration. Prepare a draft for explicit comparison. |
| `DIFFERENT_FOCUS` | Change only deterministic emphasis/order among already admitted focuses, consistent with explicit goals/preferences. Do not create a clinical domain, change support tier, remove a required action or overwrite personalization. Persist focus ordinals independently of canonical family admission. Prepare a draft for explicit comparison. |
| `PROFESSIONAL_SUPPORT_NEXT_STEP` | Navigate to existing Care professional routing. Navigation creates no appointment, consent, sharing or plan write and is not posted to confirm. Adding or changing a plan action still requires an admitted variant. Safety/free help remains accessible. |

Keep uses the existing outcomes
`CURRENT_PLAN_VALID_NO_BETTER_ALTERNATIVE` and
`CURRENT_PLAN_VALID_ALTERNATIVES_AVAILABLE`. Definite inadmissibility uses
`CURRENT_PLAN_NOT_ADMISSIBLE` and omits ordinary keep; history remains intact
and an admitted replacement or professional path is presented. Unknown
provider admission is `UNAVAILABLE`, with no outcome verdict, rather than
proven inadmissibility. New screening alone does not invalidate a compatible
current plan or its original evaluation.

An identical practical action/focus intent cannot be replaced merely because
generated IDs differ. Meaningful admitted focus emphasis/order does count as
a practical change and must survive activation/revalidation. Workload/time
changes use scheduling rather than an unchanged-plan replacement. Existing
drafts remain editable and discardable under their existing rules.

## Explicit confirmation and consistency

The owner-only no-store GET exposes the current context and fingerprint. The
unified confirm command requires the strong current-plan `If-Match` and an
owner-scoped `Idempotency-Key`. The client submits only `reviewFingerprint`,
`optionId`, optional `reassessmentSummaryId`, and, for final replacement, the
paired `proposedPlanId` and `proposedVersion`. Arbitrary client action/source
overrides are not accepted.

For `PREPARE_REPLACEMENT`, confirmation without draft fields prepares exactly
the selected admitted variant through the existing native writer and returns
`DRAFT_PREPARED`. The one-draft invariant still applies. The user compares the
draft through existing replacement review and explicitly confirms again with
its ID/version to return `REPLACED`. Preparation and replacement are separate
commands and use separate keys. Preparing a draft does not invalidate its own
review fingerprint; final confirmation separately binds its version and exact
semantic variant.

Fingerprint identity includes policy, current plan/version/cycle, canonical
anchored periods, relevant source IDs/revisions and exact admitted options.
Exclude generated observation timestamps, prepared-draft existence/IDs and
display-only Emotion reads. Confirmation recomputes this same context. Local
report/engagement changes, a new canonical summary, owner-reported
availability/consent change, changed admission or period rollover invalidates
the choice. Immutable Journal analysis revisions remain historical facts;
existing owner reads do not promise live revalidation of every underlying
entry. Historical snapshots and current source availability remain distinct.

Replay an identical recorded mutation before recomputing mutable evidence,
searching the existing applicable mutation ledgers. Bind fingerprint, option,
operation and submitted fields in the existing request hash while retaining
legacy direct-command hashes. A lost reply retried with the same key/payload
returns its recorded result even after evidence changes. Reusing a mutation
key with a different payload conflicts. Unchanged keep and navigation need no
new durable record; a retry of unchanged keep revalidates current admission.

Perform required remote evidence/entitlement checks outside database locks,
then lock and recheck local plan/draft/schedule versions and apply exactly one
existing owner operation with its ledger. Replacement, continuation and
schedule mutation cannot partly apply under concurrent commands. Due keep
requires fresh admission and applicable paid capability. A schedule-only edit
leaves `REVIEW_DUE` and the reminder unchanged; a separate explicit continuation
decides the next cycle. No browser sequence may silently combine resume,
schedule changes and renewal.

Stale current/draft/schedule versions return 412. A changed fingerprint,
withdrawn option or stale/withdrawn summary returns 409. Preserve the current
plan and require reload plus a new explicit choice/confirmation. Required
provider uncertainty returns 503 for the affected mutation. Return the
authoritative plan and ETag with `KEPT`, `CYCLE_CONTINUED`, `SCHEDULE_UPDATED`,
`DRAFT_PREPARED` or `REPLACED`; never display optimistic success or automatically
retry a different choice against a new review.

## Persistence and compatibility

Reviews are computed using existing plan/action/focus ordinals, schedule
revisions, immutable summary JSON and proposal/replacement/continuation/
schedule ledgers. This policy adds no review database, migration or audit of
every displayed or declined option. Existing history does not claim to archive
the exact displayed proposal. A requirement for that archive would be a
separate snapshot/persistence decision with migrations and canonical docs.

Earlier plan and summary versions, native direct-command APIs and the labelled
v2 adapter remain compatible. They are not backfilled or rewritten to claim
this policy or a native cycle. Mobile authoring and live/provider verification
retain their separate delivery gates.
