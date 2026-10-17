# ADR 0031: Appointment dispute and settlement gate

- Status: Accepted
- Decision ID: `MB-APPOINTMENT-DISPUTE-001`
- Delivery: MB-619

## Decision

Consultation owns one dispute aggregate per appointment. The assigned user or
specialist may open it during the 24 hours following an eligible authoritative
session settlement. Opening records the exact appointment version, participant
role, one stable reason, and optional bounded operational evidence type/time.
It never stores raw chat, ConsultationBrief, Journal, assessment, AI, recording,
private-note, or clinical content.

An open dispute makes the appointment ineligible for specialist earning or
payout materialization. An administrator may only uphold the recorded outcome
or release user credit with a stable operational reason. Resolution snapshots
the prior and resulting appointment/session outcome plus the exact credit
action. It does not diagnose, summarize the session, or rewrite a finalized
appointment fact.

When a terminal consumed/forfeited credit is released, Consultation appends an
`ADJUSTED_RELEASED` ledger fact before returning the credit to `AVAILABLE`.
The original terminal ledger event remains immutable. Open and resolve commands
are idempotent and transactionally local; unavailable external services cannot
change the result.
