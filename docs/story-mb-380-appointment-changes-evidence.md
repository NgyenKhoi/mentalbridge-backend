# MB-380 appointment cancellation and reschedule evidence

## Delivered boundary

Consultation owns the complete appointment-change transaction. An authenticated
appointment owner can cancel a future `REQUESTED` or `CONFIRMED` appointment
with `If-Match` and `Idempotency-Key`. The response and subsequent list read
include the immutable actor, reason, occurrence time, credit outcome, ordered
status history, and any old/new replacement relationship.

Reschedule remains `POST /api/v1/appointments` with
`replacesAppointmentId`, now guarded by the old appointment's `If-Match`.
The original interval, mode, specialist, and slot snapshot never change. The
old cancellation, credit transition, replacement insert, new hold, and both
history facts commit in one Consultation-owned PostgreSQL transaction.

## Policy outcomes

| Old appointment | Command time | Old credit outcome | Replacement behavior |
| --- | --- | --- | --- |
| `REQUESTED` | before start | `RELEASED` / `TRANSFERRED_TO_REPLACEMENT` | same credit is released and re-held atomically |
| `CONFIRMED` | at least 24h before start | `RELEASED` / `TRANSFERRED_TO_REPLACEMENT` | same credit is released and re-held atomically |
| `CONFIRMED` | less than 24h before start | `FORFEITED` | replacement must atomically hold another eligible credit |

Other states and commands at/after the scheduled start fail closed. A failed
slot, credit, version, or concurrency check leaves the original appointment and
credit truthful.

## Verification map

- provider integration: release, forfeit, replay, stale version, wrong owner,
  terminal repeat, replacement at capacity, replacement rollback, and command
  races;
- migration/constraint: cancellation metadata and history credit outcome are
  complete or rejected;
- OpenAPI: cancel route, conditional replacement version, relations, audit
  fields, and bounded ordered history;
- frontend: authenticated BFF forwarding, strict provider validation,
  confirmation actions, old/new relation, actor/reason/state/outcome rendering,
  and reload-safe tests.
