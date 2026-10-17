# Online consultation state and settlement map v1

Map ID: `consultation-journey-v1`. Delivery: MB-623. Owner: Consultation.
This consolidates existing contracts; it approves no new transition, provider,
financial fallback, retention rule, or aggregate.

Authorities: [Consultation OpenAPI](../../contracts/openapi/consultation-service-v1.yaml),
[Consultation policy v2](consultation-specialist-policy-v2.md),
[ADR 0031 disputes](../adr/0031-appointment-dispute-settlement-gate.md),
[ADR 0039 video](../adr/0039-livekit-cloud-video-consultation.md), and owner
migrations 005, 007–017, 023–025. The canonical logical schema is documentation
only; tests and runtime apply owner Liquibase migrations, never the logical SQL.

## State dimensions must not be conflated

`appointment.status`, `sessionOutcome`, credit state, video verification and
dispute status are distinct fields. A no-show is a settled `sessionOutcome`
while the appointment remains `SESSION_ENDED`; `DISPUTED` is not an appointment
status. An open dispute does not erase prior completion or financial facts.

```text
REQUESTED -> CONFIRMED | REJECTED | EXPIRED | CANCELLED
CONFIRMED -> IN_PROGRESS | SESSION_ENDED | CANCELLED
IN_PROGRESS -> SESSION_ENDED
SESSION_ENDED -> COMPLETED only after the applicable evidence policy passes
```

Cancellation and linked reschedule operate only before the existing start
boundary. Rejected/expired/cancelled snapshots are retained, not revived.

## Credit settlement matrix

| Transition / authoritative outcome | Appointment status | Credit / ledger effect | Completion / earning |
| --- | --- | --- | --- |
| Eligible request | REQUESTED | AVAILABLE → HELD once | None |
| Assigned Specialist accepts | CONFIRMED | Keep exact HELD credit | None |
| Specialist rejects / decision deadline expires | REJECTED / EXPIRED | HELD → AVAILABLE, RELEASED once | None |
| Owner cancels REQUESTED or CONFIRMED at least 24h before start | CANCELLED | HELD → AVAILABLE, RELEASED once | None |
| Owner cancels CONFIRMED inside 24h | CANCELLED | HELD → FORFEITED once | None |
| Reschedule-as-new | Old CANCELLED, new REQUESTED | One transaction applies cancellation policy, acquires replacement and preserves exact links; failure rolls back all effects | None |
| Scheduled chat end / explicit video close | SESSION_ENDED | Keep HELD during verification | No timer-only fact or earning |
| Verified COMPLETED | COMPLETED | HELD → CONSUMED once | One immutable completion fact and earning from exact consumed-credit snapshot |
| Chat USER_NO_SHOW | SESSION_ENDED | HELD → FORFEITED once | None |
| Chat SPECIALIST_NO_SHOW / BOTH_NO_SHOW / INSUFFICIENT_EVIDENCE | SESSION_ENDED | HELD → AVAILABLE, RELEASED once | None |
| Chat evidence uncertainty before end+35m | SESSION_ENDED, outcome null | Keep HELD | None; EVIDENCE_REVIEW presentation |
| Chat evidence uncertainty at end+35m | SESSION_ENDED, EVIDENCE_REVIEW | RELEASED once under existing chat policy | None |
| Video pending verification / EVIDENCE_REVIEW | SESSION_ENDED | Keep HELD; 24h retry/manual flag is NOT release approval | None |
| Participant opens eligible dispute | Existing status retained; dispute OPEN | No implicit credit adjustment; blocks future earning eligibility | Prior facts remain historical; no payout while gated |
| ADMIN upholds outcome | Existing status retained; dispute RESOLVED | No new settlement | Existing financial gates apply |
| ADMIN releases eligible consumed/forfeited credit | Existing status retained; dispute RESOLVED | One ADJUSTED_RELEASED, or ALREADY_AVAILABLE with no duplicate release | Prior completion/consumption remain; no payable earning from released credit |

Released means a credit-state correction, not a cash refund. A historical
COMPLETED appointment can legitimately carry AVAILABLE credit after an explicit
dispute adjustment; readers must preserve its completion fact and provenance.

## Timing, evidence and modality

- Every selectable slot is exactly 60 minutes, UTC plus an IANA display
  timezone. The owner amendment of 2026-10-18 removes advance booking lead time:
  an unexpired active slot is selectable even after its start. Specialist
  confirmation remains required, and interactive access closes at scheduled end.
- Chat waiting begins ten minutes before start. Waiting may permit check-in,
  never sending. Active send permission comes from current owner eligibility.
- `chat-session-completion-v1`: both check-ins, an accepted message for each
  participant and at least 30 minutes common presence from trusted non-content
  evidence. Presence intervals are bounded to 60 seconds and unioned; duplicate
  delivery does not increase attendance. Settlement starts after five-minute
  grace. One-sided no-show requires a checked-in witness with 15 minutes of
  presence; no evidence from either participant yields BOTH_NO_SHOW.
- Video uses MB-384's distinct rules: local preview from start−5m; new provider
  connections only during the authorized session; explicit assigned Specialist
  confirmation plus verified signed participation/closure evidence. Its
  supporting 60-second overlap is not a clinical completion judgment. Neither
  disconnect nor room closure is itself a completion command.
- MB-623's automated closure environment explicitly disables video publication
  and connection issuance. Discovery reports `videoEnabled=false`, video slots
  cannot be published/booked, and no external link or mock meeting is offered.
  MB-384 owns enabled-provider verification. This is not blanket video approval.

## Cross-owner continuation and privacy

Care alone owns the appointment-scoped ConsultationBrief and its separate
sharing grant. Only the assigned eligible Specialist in the current window may
read the explicitly approved snapshot. Revocation, stale assignment/schedule,
deletion, missing consent or dependency uncertainty fails closed. An appointment
is not authorization for raw Journal, assessment answers, AI history or notes.

After evidence-backed completion, the assigned Specialist may publish immutable
SessionSummary versions and bounded AgreedNextSteps. The USER owns personal
next-step state and exact-summary reuse consent; Specialist reads omit both.
One eligible owner rating can be edited with its version; it does not become
multiple ratings. A resource proposal retains exact resource/version and latest
summary provenance; only Care/MB-560 may revalidate PlanChangeRequest and the
USER confirms any SupportPlan mutation. Next-step completion is not plan
adherence or clinical improvement.

## Executable coverage and evidence classes

- `ConsultationJourneyIntegrationTests`: real HTTP/JWT validation, real owner
  migrations/PostgreSQL, synthetic entitlement/approved-profile prerequisites,
  synthetic trusted-producer evidence, production settlement services. No
  direct SQL creates a completed appointment, rating, summary or settlement.
- Existing `AppointmentRequestIntegrationTests`,
  `AppointmentDecisionIntegrationTests`, `AppointmentDisputeFlowIntegrationTests`,
  `SessionSummaryFlowIntegrationTests`, `AppointmentRatingFlowIntegrationTests`
  and `VideoAccessIntegrationTests` retain focused races, conflicts, provider
  failure, consent/provenance and enabled-video coverage.
- `ConsultationBriefIntegrationTests` verifies the real Care owner against its
  disposable database and controlled outbound contract fixtures. This is not
  evidence that Care and Consultation ran together in the browser test.
- Opt-in `productionBrowserBffAndRealConsultationPreserveTheWholeJourney`
  starts the production Web/BFF and real Consultation HTTP. Identity is a
  synthetic JWT/current-account fixture, Care is an explicit dependency-outage
  fixture, and chat evidence is generated by a trusted test producer through
  the actual internal HTTP endpoint. There is no live WebSocket, LiveKit,
  Android, Identity-login, MoMo, patient or full deployed-stack claim.

See [MB-623 verification runbook](../runbooks/mb-623-consultation-journey.md)
for commands, final results and remaining operational gates.
