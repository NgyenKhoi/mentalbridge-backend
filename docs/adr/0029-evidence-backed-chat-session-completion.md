# ADR 0029: Evidence-backed chat session completion

- Status: Accepted
- Date: 2026-10-01
- Decision ID: `MB-CHAT-SESSION-COMPLETION-001`
- Policy: [Chat session completion policy v1](../policies/chat-session-completion-policy-v1.md)
- Extends: [ADR 0014](0014-appointment-specialist-and-consultation-continuity.md) and [ADR 0017](0017-product-scope-v2.md)

## Context

Closing a 60-minute in-app chat channel is not evidence that both participants
attended or that the consultation completed. Completion affects a user's
consultation credit and is also the future input to specialist earning story
MB-516, so it must be server-authoritative, replay safe, and independent of
message content.

## Decision

Consultation owns appointment lifecycle, minimized session evidence, the final
session outcome, and credit settlement. Realtime owns chat/presence mechanics
and submits only explicit check-in, server-observed presence intervals, and
accepted-message identifiers through an authenticated service-to-service
boundary. Raw message content never crosses this boundary.

At the exclusive scheduled end, Consultation records `SESSION_ENDED` and the
channel becomes history-only. This transition does not complete the appointment
or settle its held credit. After a five-minute late-arrival grace period,
Consultation evaluates only evidence that occurred before the scheduled end.
Technical evidence uncertainty keeps the credit held for at most another 30
minutes before the deterministic `EVIDENCE_REVIEW`/release fallback.

The exact thresholds, precedence, no-show definitions, credit outcomes, and
examples are frozen in `chat-session-completion-v1`. Appointment lifecycle and
`SessionOutcome` remain separate. A completed session stores an opaque
`completion_fact_id` for MB-516; this decision creates no earning.

Settlement is an owner-local scheduled transaction using row locks and the
existing idempotent credit ledger. Kafka, a new service, and a distributed
transaction are not required for this owner-local state change.

## Consequences

- Joining or subscribing alone is never attendance evidence.
- Reconnects may create multiple presence intervals; overlapping time is merged
  before the two participants' common presence is measured.
- The UI can truthfully distinguish processing, completed, no-show,
  insufficient-evidence, and technical-review states.
- Video completion remains separately gated because provider evidence is not
  defined by this decision.
- Dispute handling is deferred until an approved dispute primitive exists.

## Alternatives rejected

- Completing every appointment at 60 minutes: cannot prove participation.
- Reading message text: unnecessary sensitive-data exposure.
- Treating socket join as attendance: vulnerable to idle or abandoned tabs.
- Introducing Kafka/outbox now: adds failure modes without an asynchronous or
  cross-owner business requirement for the settlement itself.
