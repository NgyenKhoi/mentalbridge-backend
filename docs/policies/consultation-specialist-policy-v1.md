# Consultation and specialist policy v1

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-CONSULTATION-FLOW-001` |
| Status | `PRODUCT POLICY APPROVED; RUNTIME NOT IMPLEMENTED` |
| Effective decision date | 2026-09-13 |
| Appointment and specialist owner | Consultation |
| Sharing consent and approved brief owner | Care |
| AI draft generator | Journal/AI |
| Chat owner | Realtime |
| Decision | [ADR 0014](../adr/0014-appointment-specialist-and-consultation-continuity.md) |

This policy defines a bounded platform consultation. It does not define a
medical record, treatment relationship, clinical matching engine, 24/7 chat, or
continuous specialist monitoring.

## Specialist profile and approval

Public V1 fields are `displayName`, `bio`, `supportAreas[]`, `languages[]`,
`yearsOfExperience`, and `timezone`. Internal operational fields may include
`approvalStatus`, `stableReasonCode`, `isSeeded`, `ratingAggregate`, and
`ratingCount`. Seeded rating facts must remain distinguishable from ratings
created by completed runtime appointments.

```text
PENDING -> APPROVED | REJECTED
APPROVED -> SUSPENDED
SUSPENDED -> APPROVED
```

`REJECTED` and `SUSPENDED` require a stable reason. V1 has no credential upload
or license-verification workflow. Suspension immediately removes discovery and
new-booking eligibility, cancels future not-started appointments, and releases
affected user credits.

Admin receives only operational profile, decision, appointment, and aggregate
facts. Admin never receives raw journal text, raw assessment answers, or private
consultation chat through this workflow.

## Discovery and recommendation

Only approved specialists are candidates. Deterministic ranking applies:

1. screened-domain to `supportAreas` match;
2. current availability;
3. language compatibility;
4. timezone compatibility;
5. rating;
6. years of experience.

The response records ranking-policy version and a transparent explanation.
Instrument-specific screening levels and support tier may be context, but
`diseaseType`, a global disease level, or clinical-suitability claims are
prohibited. Domain match always outranks rating.

## Practice locations and availability

V1 modes are `IN_APP_CHAT` and `IN_PERSON`; video remains unavailable. Every
slot is exactly 60 minutes and stores UTC instants plus the user's IANA timezone
snapshot. An in-person slot references one active `PracticeLocation`:

| Field | Meaning |
| --- | --- |
| `practiceLocationId` | Immutable Consultation-owned location reference |
| `displayName` | Reviewed user-visible practice name |
| `address` | User-visible meeting address; not a room-management identifier |
| `timezone` | IANA timezone used to display the location schedule |
| `active` | Whether new in-person slots may reference the location |

Controlled demo locations may be seeded. Deactivation blocks new slots but does
not rewrite an appointment's snapshotted location. V1 has no room inventory.

## Appointment request

The user selects one exact available slot and an eligible credit. Requests are
allowed only at least four hours before start. Consultation atomically holds the
slot and credit and creates `REQUESTED`.

```text
responseDeadline = min(requestedAt + 24h, startsAt - 2h)
```

Specialist acceptance creates `CONFIRMED`. Rejection or deadline expiry creates
`REJECTED` or `EXPIRED` and releases both holds. Retrying the same logical
request cannot create another appointment or consume another credit.

## Cancellation, reschedule, and credit outcome

| Outcome | Credit result |
| --- | --- |
| User cancels `REQUESTED` | Release |
| User cancels `CONFIRMED` at least 24 hours before start | Release in full |
| User cancels `CONFIRMED` less than 24 hours before start | Forfeit |
| Specialist or system cancels | Release in full |
| Specialist suspended before an unstarted session | Cancel and release in full |
| `USER_NO_SHOW` | Forfeit |
| `SPECIALIST_NO_SHOW` | Release in full |

Reschedule means cancel the old appointment and create a new request. The old
slot, time, mode, location, status, and credit outcome stay auditable.

## Session enforcement and completion

Chat participants may enter a waiting state from ten minutes before start, may
send only in `[startsAt, endsAt)`, and receive read-only history afterward.
Backend authorization enforces these times independently of UI state.

For chat:

```text
CONFIRMED -> IN_PROGRESS -> SESSION_ENDED -> COMPLETED
```

Completion requires server-observed evidence for both participants, the active
window, activity, and scheduled end. Missing participation produces the
appropriate user or specialist no-show outcome.

For in-person:

```text
CONFIRMED -> IN_PROGRESS -> SESSION_ENDED -> SESSION_DELIVERED
SESSION_DELIVERED -> COMPLETED | DISPUTED
```

Both participants check in. The specialist records delivery after the session;
the user confirms or reports an issue. No response and no dispute auto-completes
after 24 hours. Only `COMPLETED` consumes the credit and creates the immutable
earning snapshot.

## ConsultationBrief and sharing

The Care-owned brief is titled `ConsultationBrief` in APIs and “Tóm tắt trước
buổi tư vấn” in Vietnamese UI. Suggested fields are:

- `briefId`, `generatedAt`;
- `screeningSummary`, `currentSupportPathway`, `currentSupportPlanSummary`;
- `recentContextSummary`, `screeningTrendSummary` when available;
- `userGoalsForConsultation[]`, `preferences[]`, `barriers[]`;
- optional `userAdditionalNote`;
- `aiGenerated`, `reviewedByUserAt`, `approvedForSharingAt`;
- exact `sourceVersionReferences[]`.

```text
allowed facts
-> AI-generated DRAFT
-> user review/edit
-> explicit sharing approval
-> specialist receives immutable shared snapshot
```

Journal-derived facts require current `AI_PROCESSING` consent. Specialist
delivery requires separate current `SPECIALIST_SHARING` consent and a bounded
grant. Default appointment access is `[startsAt - 24h, startsAt + 24h]`. The
specialist cannot access raw journals, raw assessment answers, full AI-analysis
history, or unrelated user facts. A later appointment requires a newly approved
brief and grant.

## SessionSummary and continuity

A Consultation-owned `SessionSummary` may contain `topicsDiscussed`,
`agreedNextSteps`, `specialistNoteForUser`, and `followUpSuggested`. The user can
read it. It is neither a medical record nor private psychotherapy notes. A later
brief includes it only after user approval.

Continuity is repeated appointments plus approved briefs and summaries. Between
sessions, ongoing support belongs to SupportPlan and AI Companion. Appointment
1, 2, and 3 each require their own credit; no appointment grants continuous
specialist access.

## Runtime gates

This policy is approved design input. Runtime remains unavailable until
compatible contracts, append-only owner migrations, frontend flows, Realtime
eligibility enforcement, Care sharing grants, Journal/AI brief generation, and
session/credit concurrency tests pass their own delivery tasks.
