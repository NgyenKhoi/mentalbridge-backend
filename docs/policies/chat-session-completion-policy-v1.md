# Chat session completion policy v1

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-CHAT-SESSION-COMPLETION-001` |
| Runtime version | `chat-session-completion-v1` |
| Status | Product Owner approved; implemented by MB-383 |
| Effective decision date | 2026-10-01 |
| Decision | [ADR 0029](../adr/0029-evidence-backed-chat-session-completion.md) |

## Scheduled window and evidence

An `IN_APP_CHAT` appointment has the half-open 60-minute window
`[scheduledStartAt, scheduledEndAt)`. Sending stops at the exclusive end. An
explicit participant check-in is accepted from ten minutes before the start
until the end. Presence and accepted-message evidence count only when their
server-observed occurrence is inside the scheduled window.

The only evidence types are:

- explicit participant check-in;
- server-observed presence intervals;
- metadata for a message accepted by Realtime.

Subscribe/join alone is not evidence. No raw message content, journal content,
assessment response, diagnosis, or clinical inference is stored as completion
evidence.

Evidence may arrive during a five-minute grace period after the end, but its
`occurredAt` must still be before the end. Evidence identifiers are idempotent;
a conflicting reuse marks the evidence stream unreliable.

## Deterministic outcomes

Presence intervals are clamped to the scheduled window, merged per
participant, and intersected. Non-contiguous common intervals are summed.

Outcome precedence is: approved dispute primitive (none in the current
runtime), technical evidence failure, `COMPLETED`, `USER_NO_SHOW`,
`SPECIALIST_NO_SHOW`, `BOTH_NO_SHOW`, then `INSUFFICIENT_EVIDENCE`.

| Outcome | Exact requirement | Credit result |
| --- | --- | --- |
| `COMPLETED` | Both checked in, at least 30 cumulative minutes of common presence, and at least one accepted message from each participant | `CONSUMED` |
| `USER_NO_SHOW` | User has zero evidence; specialist checked in and has at least 15 minutes presence | `FORFEITED` |
| `SPECIALIST_NO_SHOW` | Specialist has zero evidence; user checked in and has at least 15 minutes presence | `RELEASED` |
| `BOTH_NO_SHOW` | Neither participant has any evidence | `RELEASED` |
| `INSUFFICIENT_EVIDENCE` | Every other non-technical partial-evidence combination | `RELEASED` |

Exactly 30 minutes qualifies for completion; 29:59 does not. Exactly 15
minutes qualifies for the no-show witness; 14:59 does not. "Zero evidence"
means no accepted check-in, counted presence, or accepted message for that
participant.

## End, grace, and reconciliation

At `scheduledEndAt`, the appointment moves once to `SESSION_ENDED`, a
history-only state. No completion, credit transition, or specialist earning is
created by that transition.

At end plus five minutes, reliable evidence is evaluated once. If evidence is
known to be unreliable, the outcome remains pending and the credit stays
`HELD` for an additional 30 minutes. Recovered evidence is evaluated normally.
At end plus 35 minutes, still-unreliable evidence settles as
`EVIDENCE_REVIEW`, reason `SYSTEM_EVIDENCE_UNAVAILABLE_TIMEOUT`, with credit
`RELEASED`.

`COMPLETED` advances the lifecycle to `COMPLETED` and stores an opaque
completion fact for future MB-516 earning work. Every other final outcome keeps
the lifecycle at `SESSION_ENDED`. MB-383 never creates or calculates an earning.

## Idempotency and concurrency

Consultation is authoritative. Settlement locks the appointment and credit in
one local transaction. Evidence identifiers, status-history keys, and credit
ledger keys prevent duplicate evidence, terminal outcomes, and credit events.
Late evidence received after a terminal outcome cannot rewrite it.

Disputes are not exposed in this version because the product has no approved
dispute command, authority, deadline, or resolution model.
