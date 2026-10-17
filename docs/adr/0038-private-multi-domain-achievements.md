# ADR 0038: Private multi-domain achievements

- Status: Accepted for MB-661 implementation
- Date: 2026-10-13
- Decision ID: `MB-PRIVATE-ACHIEVEMENT-001`
- Owner: Content/Notification, with Journal/AI source ownership
- Policy: [Private achievement policy v1](../policies/private-achievement-policy-v1.md)

## Context

MentalBridge needs an optional private collection that recognizes factual first
steps and bounded participation without converting sensitive activity into a
clinical score, public comparison, or pressure to maintain a streak. Journal,
emotion check-in, and reviewed Resource facts have different owners and deletion
rules. SupportPlan v3 journey events are not yet available for this consumer.

## Decision

Content/Notification owns the collection contract and the frozen
`achievement-rules-v1` taxonomy. The read model is deterministic: reviewed
Resource completion and verified practice come from Content tables; Journal/AI
returns only owner-bound counts, longest streaks, and the exact instants when
the supported three- and seven-day streak thresholds were first reached. No raw
Journal text, emotion/note value, influence factor, PHQ/GAD answer or score,
consultation content, or AI inference crosses the boundary.

Every item is `EARNED`, `UNEARNED`, `SOURCE_UNAVAILABLE`, or `DEFERRED`.
Dependency failure and owner/timezone mismatch fail the affected Journal and
emotion domains closed without erasing available Resource truth. Earned facts
retain their source unlock instant while valid. Authorized source deletion or
correction is reflected on the next deterministic read; there is no duplicate
award ledger to drift from the owner record.

The collection is private to the authenticated owner and visible by default.
Only its reversible `VISIBLE` or `HIDDEN` preference is persisted with optimistic
concurrency. Hiding does not alter or gate any source activity, SupportPlan,
safety, clinical, or professional capability.

`journey.new-chapter` remains `DEFERRED`. It can become eligible only after a
future contract consumes an explicit owner confirmation from appropriate
SupportPlan v3 events. Absence, inactivity, resumed use, or inferred recovery
can never unlock it.

## Consequences

- Same-day Journal edits and emotion revisions remain one owner day.
- Resource variety counts distinct reviewed resources; practice windows count
  distinct verified days and are never described as a streak.
- Web and future mobile consumers render server status/progress directly and do
  not recompute eligibility.
- Native implementation and evidence remain owned by MB-665 and MB-666.
