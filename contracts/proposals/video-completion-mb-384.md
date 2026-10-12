# MB-384 final video completion contract proposal

Status: **Specialist command and evidence verification APPROVED on 2026-10-12;
implementation pending. Automatic 24-hour settlement/release NOT APPROVED**.
Provider/access/privacy/90-day participation retention are already approved by
the owner. This document records the approved additive command and the explicit
unapproved settlement boundary. It does not
alter historical chat evidence, settlement, payment, credit or completion facts.

## Command and authority

`POST /api/v1/specialist/appointments/{appointmentId}/video-completion`

Only the assigned currently approved SPECIALIST; Identity bearer authorization,
strong `If-Match` of Appointment version and participant-scoped
`Idempotency-Key` (16–128 characters) are required. Body:

```json
{ "confirmation": "CONSULTATION_FINISHED" }
```

No actor ID, score, duration, attendance, clinical conclusion or provider field
is accepted. The confirmation is an explicit business assertion, not media
evidence. It may be submitted when the actual session ends, including before
the scheduled end; it never requires waiting five minutes. This intent closes
further joins and asks the provider to revoke both participants/delete the room.
The scheduled 60-minute snapshot is unchanged. The actual end is recorded as an
additional server fact. Ordinary leave/disconnect never submits this command.

Persist confirmation identity, actor, Appointment version, policy version,
server acceptance time and idempotency key in an append-only audit. Under the
same Appointment lock, competing commands/replays converge on one intent;
same-key/different-body conflicts, stale versions and changed assignment fail.
Provider calls occur outside the database transaction.

## Response and states

Return 202 for an accepted pending confirmation, 200 for a replay or verified
completion, with current ETag and no provider credentials:

```json
{
  "appointmentId": "opaque-uuid",
  "confirmationId": "opaque-uuid",
  "verificationState": "PENDING_VERIFICATION",
  "appointmentStatus": "SESSION_ENDED",
  "creditState": "HELD",
  "completionFactId": null,
  "acceptedAt": "2026-10-12T13:00:00Z",
  "manualReviewAt": "2026-10-13T13:00:00Z"
}
```

Verification states: `PENDING_VERIFICATION`, `VERIFIED`, `EVIDENCE_REVIEW`.
Existing owner Appointment reads remain authoritative for final outcome. An
optional additive participant-only GET video state returns the same minimized
verification state. No arbitrary polling of another actor's appointment.

Approved transitions:

1. `CONFIRMED/IN_PROGRESS` + explicit assigned Specialist confirmation ->
   `SESSION_ENDED/PENDING_VERIFICATION`; no credit/earning side effect.
2. Scheduled end -> `SESSION_ENDED/PENDING_VERIFICATION` even without intent;
   never auto-confirm or auto-complete.
3. Explicit intent + verified provider closure + complete trusted evidence +
   at least 60 seconds of **common** valid-window attendance + no provider
   uncertainty/open dispute -> `COMPLETED/VERIFIED` in one local transaction.
4. Otherwise stay `SESSION_ENDED/PENDING_VERIFICATION` with credit HELD while
   awaiting reconciliation. Missing events never prove no-show.

An event pair matches room SID, participant identity and session SID. All known
attendance sessions must be pairable and internally consistent. Overlap alone,
room end alone, or Specialist confirmation alone cannot create a fact.
Verification may succeed immediately when all prerequisites exist; the grace
is a maximum normal wait for delayed evidence, not a forced delay or extension.

## Reconciliation and manual review boundary

Normal late event grace is five minutes from actual server session close (or
scheduled end when no earlier confirmed end exists). An uncertainty can be
reconciled using the same signature-verified original events, without re-opening
media or accepting client-authored attendance. After 24 hours a bounded retry job
may mark EVIDENCE_REVIEW as needing manual intervention; this is not a settlement
deadline. Credit remains HELD and no completion fact or earning is created while
verification is incomplete.
The owner scheduler performs bounded retries, records safe failure reasons and
exposes pending state. No raw provider body/token is retained.

**Not approved and must not be implemented:** automatic 24-hour EVIDENCE_REVIEW
settlement, credit release/refund, earning, no-show or completion. Pending review
does not set the existing terminal session outcome or settledAt. The current
dispute API applies to settled appointments, so a pending-video manual review /
dispute resolution workflow is a separately reviewed pre-production follow-up.
No new ADMIN settlement authority is implied by this command approval.

## HTTP errors

- 400 `VIDEO_CONFIRMATION_INVALID`: malformed/extra business fields.
- 401 `UNAUTHENTICATED`: no valid Identity actor.
- 403 `VIDEO_COMPLETION_FORBIDDEN`: wrong role or unapproved Specialist.
- 404 `APPOINTMENT_VIDEO_NOT_FOUND`: absent or not assigned, avoiding enumeration.
- 409 `VIDEO_COMPLETION_CONFLICT`: cancelled, replacement, wrong modality,
  terminal non-payable, conflicting replay or open dispute.
- 412 `APPOINTMENT_VERSION_CONFLICT`: version changed.
- 428 `APPOINTMENT_VERSION_REQUIRED`: missing/malformed strong If-Match.
- 503 `VIDEO_VERIFICATION_UNAVAILABLE`: intake itself unavailable. Once intent
  has committed, provider failure returns accepted pending, not a false failed
  save. Replaying the same key recovers authoritative state.

All errors use existing RFC 9457 Problem Details, no provider payload/token.

## Payment and compatibility impact

On verified completion only, reuse the existing owner-local credit transition
to CONSUMED, immutable completion_fact_id and MB-516 earning materialization.
Appointment lock, unique fact/ledger keys and existing earning uniqueness
protect exactly-once effects. Credit allocation/currency/earning/payout hold,
subscription, checkout, cancellation and historical facts are unchanged.
There is no cash refund or payout submission in video code. Open disputes and
release-credit resolutions continue to block earning eligibility.
The additional video provenance must be allowed by an additive Liquibase
constraint amendment; chat's stronger/different evidence criteria stay exact.
Brief and Summary authorization/consent remain unchanged.

## Required acceptance tests before implementation is declared complete

- Correct assigned Specialist, USER/other Specialist/ADMIN rejection, suspension,
  early actual session end, cancellation/reschedule/wrong modality.
- Accepted intent before grace expires; missing or delayed evidence remains
  pending and HELD; replay recovers a provider timeout.
- Threshold alone, both joins, clock, room finish and disconnect create no fact.
- Valid paired evidence + intent + closed room completes once; duplicate intent,
  reordered webhooks, reconnect/overlapping intervals and concurrent verification
  cannot double-consume or double-earn.
- Conflicting/unpaired/out-of-window/provider uncertainty never completes;
  no false no-show from missing webhook.
- After 24 hours unresolved evidence is flagged for manual review; credit stays
  HELD, no settlement ledger entry/fact/earning occurs, and late callbacks or
  retries cannot bypass the same approved verification predicates.
- Existing MB-516 financial tests, chat completion, credit cancellation,
  ConsultationBrief and SessionSummary compatibility remain green.

The command, actual-end transition and verification predicates are approved.
Automatic timeout settlement remains explicitly unapproved and unimplemented.
