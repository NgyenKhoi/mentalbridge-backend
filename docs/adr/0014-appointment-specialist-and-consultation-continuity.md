# ADR 0014: Appointment, specialist, and consultation continuity v1

- Status: Accepted
- Date: 2026-09-13
- Decision ID: `MB-CONSULTATION-FLOW-001`
- Amends: [ADR 0005](0005-consultation-billing-and-credit-settlement.md)
- Amended by: [ADR 0017](0017-product-scope-v2.md), which makes `IN_APP_CHAT` and `IN_APP_VIDEO` the only new v2 modes, ends channel access at 60 minutes without automatic completion, and requires user approval before summary reuse
- Policy: [Consultation and specialist policy v1](../policies/consultation-specialist-policy-v1.md)

## Context

ADR 0005 made Consultation authoritative for booking, credits, specialist
approval, and earnings, but its initial online-only appointment assumptions left
duration, request expiry, cancellation, completion evidence, in-person
locations, and bounded continuity unresolved. The approved product journey now
needs an appointment to bound time, credit, consultation mode, specialist data
access, and proof of session completion without creating continuous monitoring.

## Decision

### Ownership

- Consultation owns specialist profiles and approval, practice locations,
  discovery ranking, availability, appointments, check-in/session evidence,
  credit transitions, `SessionSummary`, and appointment status history.
- Care owns `SPECIALIST_SHARING` consent, appointment-scoped access grants, and
  the user-approved `ConsultationBrief` snapshot derived from Care-owned facts.
- Journal/AI may generate the draft brief under valid `AI_PROCESSING` consent
  and returns only the approved structured draft to Care. It never shares the
  draft with a specialist.
- Realtime owns chat messages and enforces Consultation's current appointment
  eligibility on entry and send.

No owner reads or mutates another owner's database.

### V1 consultation modes and duration

V1 supports `IN_APP_CHAT` and `IN_PERSON`; `IN_APP_VIDEO` remains deferred.
Every slot lasts 60 minutes. An in-person slot references one active
Consultation-owned `PracticeLocation` with an identifier, display name, address,
IANA timezone, and active state. V1 does not manage rooms.

### Booking and credit holds

Only an approved specialist can publish a slot. A user can request it no later
than four hours before `startsAt`. Creation atomically holds the slot and one
available credit and produces `REQUESTED`; it does not consume the credit.

The specialist must accept or reject by the earlier of `requestedAt + 24h` and
`startsAt - 2h`. An unanswered request becomes `EXPIRED` and releases both
holds. Acceptance creates `CONFIRMED`; rejection releases both holds.

### Cancellation and rescheduling

- User cancellation while `REQUESTED` releases the credit.
- User cancellation of `CONFIRMED` at least 24 hours before start releases the
  credit; later cancellation forfeits it.
- Specialist/system cancellation, specialist no-show, or specialist suspension
  before the session releases the user's credit.
- User no-show forfeits the credit.
- Rescheduling cancels the old appointment under the applicable policy and
  creates a new request; it never mutates the old slot snapshot.

### Chat and session completion

Participants may enter a waiting state ten minutes before start. Chat messages
are accepted only in `[startsAt, endsAt)` and history is read-only afterward.

An in-app chat appointment advances through `CONFIRMED`, `IN_PROGRESS`,
`SESSION_ENDED`, then `COMPLETED` only from server-observed evidence such as both
check-ins, entering the active window, activity evidence, and scheduled end.
Missing participation produces `USER_NO_SHOW` or `SPECIALIST_NO_SHOW`.

For in-person appointments, both participants check in. After the scheduled
session, the specialist records `SESSION_DELIVERED`; the user then confirms or
reports an issue. Confirmation completes the appointment, issue reporting makes
it `DISPUTED`, and no response completes it automatically after 24 hours when no
dispute exists. A specialist cannot unilaterally finalize and consume a credit.

Credit consumption and an earning snapshot occur only when the authoritative
appointment becomes `COMPLETED`.

### Bounded continuity

Specialists have no default continuing access to new assessments, journals, AI
analysis, or SupportPlans after an appointment. Continuity uses repeated paid
appointments plus user-approved `ConsultationBrief` snapshots and visible
`SessionSummary` records, not continuous monitoring.

An AI-generated brief is always a draft. The user reviews or edits it and
explicitly approves sharing. Journal-derived context requires `AI_PROCESSING`
consent; sharing requires separate `SPECIALIST_SHARING` consent. The specialist
receives only the approved snapshot, normally from `startsAt - 24h` through
`startsAt + 24h`, and never raw journal text, raw assessment answers, complete
AI history, or unrelated data. A later appointment requires a new brief/share.

A specialist may create a short user-visible `SessionSummary` containing topics
discussed, agreed next steps, a note for the user, and whether follow-up is
suggested. It is not a medical record or private psychotherapy note. Reuse in a
later brief requires user approval.

### Specialist approval and discovery

The public profile contains display name, bio, support areas, languages, years
of experience, and timezone. V1 collects no credential document and performs no
license verification. Internal state is `PENDING`, `APPROVED`, `REJECTED`, or
`SUSPENDED`; rejection and suspension require stable reason codes, and a
suspended specialist may return to approved.

Suspension hides the specialist from discovery and cancels future not-started
appointments with full credit release. Admin cannot read raw journals, raw
assessment answers, or private consultation chat.

Discovery is deterministic and non-clinical. It orders domain/support-area
match before availability, language, timezone, rating, and years of experience.
Instrument-specific screening levels may provide context but never create a
disease type, global disease level, or clinical matching claim. Controlled demo
specialists may be seeded as approved and must distinguish seeded rating data
from runtime-generated ratings.

## Consequences

- The former online-only/no-location and in-place reschedule rules in ADR 0005
  are superseded.
- Consultation needs additive contract and persistence work for locations,
  expanded appointment states, holds/deadlines, check-ins, completion evidence,
  disputes, and summaries before runtime is claimed.
- Care and Journal/AI need separately compatible brief-generation and explicit
  sharing contracts; appointment existence alone never grants sensitive access.
- Implementation order is specialist approval/admin, discovery, appointment,
  brief/sharing, session completion/summary, then Identity account-state admin.
- Admin appointment monitoring is read-only. Every mutation uses the owning
  domain command and business validation, never direct database mutation.
- Dataset/benchmark and financial administration remain deferred.

## Rejected alternatives

- Chat-only V1: rejected because approved in-person consultations are now part
  of the controlled flow and have a real location gate.
- In-place rescheduling: rejected because it rewrites the historical slot
  snapshot and obscures cancellation/credit consequences.
- Specialist-only completion: rejected because it could consume a credit without
  sufficient session or user evidence.
- Persistent specialist access between sessions: rejected because appointments
  are bounded paid consultations, not continuous monitoring.
