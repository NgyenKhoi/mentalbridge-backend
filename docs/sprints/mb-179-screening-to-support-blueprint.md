# MB-179 Review 1 screening-to-support blueprint

## Decision record

| Field | Value |
| --- | --- |
| Blueprint ID | `MB-BLUEPRINT-SCREENING-SUPPORT-001` |
| Version | `1.0-capstone` |
| Status | `PRODUCT OWNER APPROVED — MENTOR CLOSURE REVIEW PENDING` |
| Product Owner decision date | 2026-09-02 |
| Authoritative feedback source | MB-179 and its imported CSV description, confirmed by the MentalBridge Project Lead on 2026-09-02 |
| Applies to | Controlled local/demo Capstone use with synthetic or test data |
| Production use | Not approved |
| Primary owner | Care Service |
| Related decisions | [ADR 0009](../adr/0009-care-screening-safety-and-support-boundaries.md), [ADR 0010](../adr/0010-capstone-questionnaire-publication-gates.md) |

This blueprint closes the product-definition questions raised in Review 1. It does not claim that every stage has runtime code. Product Owner approval permits engineering to implement the bounded Capstone design. Mentor or supervisor review validates Review 1 closure after implementation and does not block preparation of the blueprint.

## Status vocabulary

| Status | Meaning |
| --- | --- |
| `RUNTIME COMPLETE` | Contract, implementation, persistence where applicable, and automated evidence exist. |
| `DEFINITION COMPLETE` | Product behavior and boundaries are approved, but runtime may not exist. |
| `RUNTIME UNAVAILABLE` | The client must present an explicit unavailable result and must not simulate success. |
| `UNPUBLISHED` | The instrument cannot be returned or submitted through a public questionnaire endpoint. |
| `PRODUCTION BLOCKED` | Controlled synthetic demo use may continue, but real-user production use requires the recorded review. |

## Product and safety boundaries

MentalBridge provides standardized symptom screening, educational support and an optional path to human support. It does not diagnose a condition, prescribe treatment, infer suicide intent or urgency, dispatch emergency responders, guarantee human contact, or provide continuous monitoring.

Care is authoritative for questionnaire versions, score calculation, `screeningLevel`, PHQ-9 `safetyStatus`, future `supportTier` decisions and assessment ownership. The browser and AI receive authoritative results and cannot calculate, modify or override them. AI personalization and model selection are outside Sprint 2.

The following dimensions remain independent:

| Dimension | Authority | Meaning | Prohibited interpretation |
| --- | --- | --- | --- |
| `screeningLevel` | Care questionnaire scoring version | Symptom-frequency band for one instrument | Diagnosis, disease severity or treatment mandate |
| `safetyStatus` | Care PHQ-9 item-9 rule | Positive or negative deterministic safety screen | Intent, plan, imminence, urgency or suicide-risk tier |
| `supportTier` | Future versioned Care support policy | Product support pathway | Clinical treatment protocol or diagnosis |
| `supportActions` | Future reviewed catalogue | Eligible versioned platform actions | AI-generated treatment or proof an action occurred |
| `entitlementPlan` | Consultation/Billing | Commercial feature access | Permission to hide score, disclaimer or safety output |

Approved UI/API vocabulary includes “screening result”, “screening level”, “symptom score”, “safety screen”, “support option” and “professional support recommendation”. Prohibited claims include “diagnosed”, “has depression/anxiety”, “disease severity”, “recovered”, “clinically improved/worsened”, “treatment required”, “high suicide risk”, “a specialist was contacted” or “emergency help is on the way” unless a future governed capability can prove that exact fact.

## Target cohort and eligibility

The primary Capstone cohort is people aged 18 through 30 who are in Vietnam. This is a product scope selected for a bounded academic demonstration and carried from the approved Capstone registration. Age 18 keeps minor/guardian governance outside the bounded demo. Age 30 narrows research, design and delivery scope for the five-person team; it is not a medical-validity threshold.

| Case | Eligibility source | Blueprint result |
| --- | --- | --- |
| Registered user with complete profile | Owned Care profile | Eligible when age is `18..30` and country is Vietnam. |
| Registered user with incomplete profile | Explicit self-declaration or profile completion | Require a declaration before the questionnaire; do not infer age or country. |
| Anonymous user | Session-scoped self-declaration | Require age-range and country confirmation; do not create longitudinal history. |
| Under 18, over 30 or outside Vietnam | Profile or self-declaration | Return `OUTSIDE_CAPSTONE_SCOPE`; make no statement about medical validity or health status. |
| Missing, conflicting or unverifiable declaration | Current request/profile state | Return `ELIGIBILITY_UNCONFIRMED`; require correction or explicit reconfirmation rather than silently choosing a value. |

No identity-document or clinical-age verification is introduced by this blueprint. Anonymous eligibility data is limited to the active session and is not silently attached to an account created later.

## Instrument register

| Instrument | Identifier and locale | Exact source artifact | Research/validation population boundary | Use/publication basis | Accountable review and gate |
| --- | --- | --- | --- | --- | --- |
| PHQ-9 | `phq9-vi-vn-capstone-v1`; locale `vi-VN`; scoring `phq9-standard-bands-v1`; safety `MB-SAFETY-PHQ9-001` | Original study DOI `10.1046/j.1525-1497.2001.016009606.x`; exact *PHQ-9 Vietnamese* from SBIRT Oregon, archive timestamp 2024-07-20, SHA-256 `E2775444E5AB4A05C3FF097F1CAB356C2DA9ECC73BAC63E91827BAA77E965FF7` | Original adult primary-care evidence plus cited Vietnamese primary-healthcare/community evidence supports screening adoption; it does not establish diagnosis or universal performance for every Vietnamese adult aged 18–30 | Artifact states no permission is required to copy, translate, display or distribute; exact wording and labels are executable reference data | Product Owner: `CAPSTONE PUBLISHED`; Care runtime authoritative; mentor/domain review recommended and production domain/privacy/legal review open |
| GAD-7 | Reserved `gad7-vi-vn-adult-v1`; locale `vi-VN`; proposed scoring `gad7-standard-bands-v1` | Original study DOI `10.1001/archinte.166.10.1092`; exact candidate *GAD-7 — Vietnamese for Vietnam — Translated by UNC Vietnam, 2024* from NDA | Original adult primary-care validation and a cited Vietnamese methadone-maintenance study are applicability evidence; neither permits diagnosis or universal generalization. The localized artifact is interviewer-oriented | Source is identified; exact use terms, retained wording, `0..3` labels, non-score-code handling and self-administered mapping remain publication evidence | Product Owner: `RESEARCH VERIFIED / IMPLEMENTATION AUTHORIZED FOR CAPSTONE`; mentor/domain review recommended; `UNPUBLISHED` until reference data and automated tests pass |

The accountable Capstone publication reviewer is the Product Owner. Mentor/domain review is recommended academic validation and remains a production follow-up, not a blocker to beginning GAD-7 engineering. The complete publication evidence is maintained in the [Capstone questionnaire policy](../policies/capstone-questionnaire-publication-policy.md), [PHQ-9 policy](../policies/phq9-screening-and-safety-policy.md) and [GAD-7 policy](../policies/gad7-screening-policy.md).

## End-to-end user journey

```text
Profile or session self-declaration
  -> Capstone cohort eligibility and versioned disclosure
  -> request a published questionnaire version
  -> anonymous session or authenticated owner boundary
  -> submit raw answers and an idempotency key to Care
  -> Care validates completeness and computes the standardized result
  -> PHQ-9 only: Care computes item-9 safetyStatus independently
  -> persist immutable result and governing versions
  -> render score, screeningLevel, disclaimer and safety output
  -> evaluate the definition-only support blueprint
  -> show reviewed generic resources or an explicit unavailable state
  -> registered user may voluntarily request the future specialist path
  -> explicit scoped consent before any sensitive data is shared
  -> user-initiated reassessment
  -> registered same-instrument descriptive comparison when evidence is compatible
```

### Stage entry and exit criteria

| Stage | Entry criteria | Successful exit | Non-success exit | Sprint 2 runtime state |
| --- | --- | --- | --- | --- |
| Eligibility | Profile facts or explicit self-declaration are available | Cohort eligibility and disclosure are acknowledged | `OUTSIDE_CAPSTONE_SCOPE` or `ELIGIBILITY_UNCONFIRMED` without medical interpretation | `DEFINITION COMPLETE`; enforcement integration is separate runtime work |
| Questionnaire selection | Eligible actor requests an instrument | Exact published locale/version returned | `QUESTIONNAIRE_UNAVAILABLE`; GAD-7 remains unpublished | PHQ-9 `RUNTIME COMPLETE`; GAD-7 `UNPUBLISHED` |
| Submission and scoring | Complete raw answers, owner/session proof and idempotency key | Immutable Care-owned result stored and returned | Stable validation, authorization, expiry or version error; no partial final result | PHQ-9 `RUNTIME COMPLETE` |
| Safety evaluation | A PHQ-9 result includes item 9 | Independent positive/negative safety status returned synchronously | No AI, broker, cache, notification or billing dependency may suppress the result | PHQ-9 `RUNTIME COMPLETE`; not applicable to GAD-7 |
| Support routing | At least one eligible published assessment result is explicitly selected | Versioned product support tier can be explained | `SUPPORT_ROUTING_UNAVAILABLE` or `INSUFFICIENT_DATA`; no invented action | `DEFINITION COMPLETE / RUNTIME UNAVAILABLE` |
| Reviewed resources | A result page requests published generic content | Only active reviewed content is shown | Explicit empty/unavailable content state | Owned by MB-180 runtime work; not evidence that personalized routing exists |
| Specialist handoff | Registered user voluntarily selects professional support | Entitlement, availability and scoped consent all pass before sharing | Explicit feature, entitlement, availability or consent failure | `DEFINITION COMPLETE / RUNTIME UNAVAILABLE` |
| Reassessment | Registered owner starts a new attempt | New immutable result is created; earlier result remains unchanged | Same submission failure behavior as an initial assessment | User-initiated behavior belongs to MB-178 runtime work |
| Progress comparison | Registered owner has two compatible results for one instrument | Descriptive score, band and interval comparison returned | `INSUFFICIENT_COMPARABLE_DATA` | `DEFINITION COMPLETE / RUNTIME UNAVAILABLE` |

## Versioned severity-to-support decision table

The approved blueprint identifier is `mb-support-routing-capstone-v1`. It is product-support routing, not a treatment protocol. It is definition-complete but not executable in Sprint 2.

An eligible input is a complete, immutable result from a published questionnaire explicitly included in the same user-initiated support evaluation. Sprint 2 does not search an implicit clinical recency window and does not treat unpublished GAD-7 as a missing result. A later automatic “latest assessment” feature requires a new policy version with a freshness window.

| Available evidence | PHQ-9 safety | Blueprint `supportTier` | Required boundary |
| --- | --- | --- | --- |
| No eligible published result | Not available | `INSUFFICIENT_DATA` | Do not infer a tier. |
| Every available PHQ-9/GAD-7 level is `MINIMAL` or `MILD` | Negative or not applicable | `SELF_GUIDED_SUPPORT` | May expose only reviewed generic resources; no diagnosis or treatment claim. |
| Any available PHQ-9/GAD-7 level is `MODERATE` or higher | Negative or not applicable | `PROFESSIONAL_SUPPORT_RECOMMENDED` | Recommendation is optional; no automatic contact, booking or sharing. |
| Any available PHQ-9 result has item 9 `>= 1` | Positive | `SAFETY_FOLLOW_UP_RECOMMENDED` plus an optional professional-support recommendation | Preserve the questionnaire band; do not infer intent, plan, imminence or urgency. |

PHQ-9 item 9 has priority only for `supportTier` selection; it never changes `screeningLevel`. Values `1`, `2` and `3` use the same Capstone safety pathway while the raw response remains protected. GAD-7 has no item-9-equivalent rule. AI is not an input to `mb-support-routing-capstone-v1`.

No personalized `supportActions` are approved by this blueprint. The intervention catalogue, eligibility and exact localized wording remain unavailable until their separate gate passes.

## Specialist recommendation and handoff

The professional-support recommendation is optional and user-initiated. Anonymous users receive only the current screening result, disclaimer, applicable safety guidance and generic availability information; they cannot enter specialist sharing, booking or consultation.

For a registered user, the future handoff sequence is:

1. Show an optional professional-support action without claiming that a specialist is available.
2. Check the authoritative runtime feature state.
3. Check the current immutable entitlement and consultation-credit decision in Consultation/Billing.
4. Check specialist/slot availability in Consultation; a recommendation never reserves or books a slot.
5. Let the user select the specialist and the exact assessment evidence they intend to share.
6. Obtain a separate revocable Care grant before any sensitive read.
7. Re-check the current grant at every owner read; revocation fails closed and is audited.
8. Create a booking only from an explicit user command after every booking rule passes.

The minimum proposed assessment-sharing scope contains the subject, selected specialist, purpose, `ASSESSMENTS` scope, selected assessment identifiers or bounded time range, expiry, grant version, instrument/version, completion time, score, screening level and PHQ-9 safety status where applicable. Raw answers, journal entries, emotion trends and all past/future assessments are excluded by default. Sharing journal data requires a separate selected-entry grant and is not part of MB-179.

Specialist runtime is currently unavailable. The UI must distinguish at least feature unavailable, no entitlement/credit, no available specialist/slot and consent required. It must not fake a specialist, appointment, message, AI response or successful sharing operation.

## Specialist journey

```text
Approved specialist signs in
  -> manages future Consultation-owned availability
  -> receives a user-initiated appointment request
  -> accepts or rejects through Consultation rules
  -> requests only the assessment scope needed for the stated purpose
  -> Care authorizes the current exact grant or fails closed
  -> specialist sees only the minimized granted projection
  -> every sensitive read records minimized audit evidence
  -> appointment-scoped communication becomes available only when its runtime contract permits
```

An appointment never implies access to assessments or journals. Specialist approval, entitlement, booking, scoped consent and data-owner authorization are separate gates.

| Specialist stage | Entry criteria | Successful exit | Non-success exit |
| --- | --- | --- | --- |
| Availability management | Approved specialist and enabled Consultation runtime | Versioned, channel-specific slots are published | Feature unavailable or invalid/conflicting slot; no availability claim |
| Appointment request | Registered entitled user explicitly chooses an available slot | Consultation records a request without granting data access | Entitlement, credit, availability or runtime unavailable |
| Scoped data request | Appointment relationship exists and the user has selected evidence to share | Care records an explicit bounded grant | Consent declined/missing; specialist receives no sensitive projection |
| Sensitive read | Specialist identifies purpose and exact current grant | Data owner returns only the minimized authorized projection and audit evidence | Timeout, expiry, revocation or scope mismatch fails closed |
| Consultation communication | Appointment is confirmed and within the snapshotted channel window | Appointment-scoped interaction follows Realtime/Consultation contracts | No direct, out-of-window or implied 24/7 access |
| Journey completion | Consultation reaches its authoritative terminal state | Follow-up may be offered only through a separately available feature | No automatic monitoring, reminder or continued data access |

## Follow-up and reassessment

Sprint 2 defines only user-initiated reassessment. Care owns assessment and future follow-up policy; Content/Notification may deliver a reminder only after a later approved cadence/opt-out contract. `Asia/Ho_Chi_Minh` is a display and scheduling timezone, not evidence of operating-hour monitoring.

| State | Entry | Allowed behavior | Exit |
| --- | --- | --- | --- |
| No previous owned result | Registered owner has not completed the selected instrument | Start a new assessment; no progress claim | First immutable result stored |
| Previous result available | Registered owner chooses to reassess | Start a new attempt using the current published instrument | New immutable result stored or attempt abandoned/expired |
| Comparable results available | Two valid compatible results exist | Present descriptive comparison | User leaves comparison; no monitoring promise remains active |
| Missing, stale, voided or incompatible evidence | Comparison requirements fail | Return `INSUFFICIENT_COMPARABLE_DATA` and offer a user-initiated new assessment where eligible | New compatible result later becomes available |

No clinical cadence, automatic reminder or continuous monitoring is hard-coded. Reminder opt-out is therefore not applicable in Sprint 2; any future reminder contract must define opt-in/opt-out and delivery fallback before enablement. “Continue” may refer only to continuing an available non-clinical platform activity. “Adapt” may offer another reviewed platform resource. Internal “escalate” may mean only offering the optional professional-support path; user-facing copy uses “seek professional support” and never asserts escalation of disease. These terms do not assert clinical improvement, deterioration or a treatment decision.

## Descriptive progress rules

Progress is available only to the authenticated owner. Anonymous sessions have no longitudinal history or comparison. The comparison uses the selected current result and the immediately preceding valid owned result for the same instrument and an identical scoring version, unless a future policy explicitly records version compatibility.

```text
rawDelta = current.totalScore - previous.totalScore
interval = current.completedAt - previous.completedAt
```

The output may contain instrument, questionnaire/scoring versions, previous and current score, raw signed delta, previous and current screening level, band transition and elapsed interval. It may say only that the score increased, decreased or did not change. It must not say “recovered”, “clinically improved”, “clinically worsened”, “clinically significant”, identify a cause, combine PHQ-9 and GAD-7 into one Mental Health Score, or interpret a safety-status change as resolved risk.

Return `INSUFFICIENT_COMPARABLE_DATA` when there are fewer than two valid results, instruments differ, scoring versions are not explicitly compatible, a result is voided/missing, ownership fails, or required timestamps are unavailable.

## Entitlement and explicit fallback

Score, screening level, disclaimer, PHQ-9 safety status and approved safety guidance are available regardless of plan and are never paywalled. Anonymous users receive only the current result. Registered ownership is required for history, reassessment history, progress and specialist flows. Paid capabilities follow the current immutable plan version and never alter standardized results.

The following identifiers document required semantics and are not yet API contract fields:

| Blueprint status | Required user-visible meaning |
| --- | --- |
| `QUESTIONNAIRE_UNAVAILABLE` | The selected questionnaire/version cannot currently be used; do not substitute another instrument silently. |
| `SUPPORT_ROUTING_UNAVAILABLE` | The screening result remains valid, but personalized support routing is not available. |
| `RESOURCE_CATALOGUE_EMPTY_OR_UNAVAILABLE` | No reviewed resources can currently be displayed; do not leave an unexplained blank region. |
| `SPECIALIST_FEATURE_UNAVAILABLE` | Specialist connection is not currently available; no specialist was contacted. |
| `ENTITLEMENT_OR_SLOT_UNAVAILABLE` | The requested paid/booking capability cannot proceed; do not imply a reservation or charge. |
| `INSUFFICIENT_COMPARABLE_DATA` | There is not enough compatible owned evidence for a progress comparison. |

Every optional dependency failure leaves the authoritative assessment result accessible and reports the unavailable capability explicitly.

## Approval and deferral register

| Decision | Owner | State | Effective scope | Follow-up gate |
| --- | --- | --- | --- | --- |
| MB-179 feedback summary is authoritative | Product Owner | Approved 2026-09-02 | Sprint 2 closure | Mentor validates the completed closure matrix |
| Cohort, terminology and non-diagnostic boundary | Product Owner | Approved 2026-09-02 | Controlled Capstone | Revisit before expanding age/country scope |
| PHQ-9 publication and item-9 rule | Product Owner/Care | Capstone published | Controlled Capstone | Production domain/privacy/legal/operational review |
| GAD-7 implementation start | Product Owner | Approved 2026-09-02 | Engineering may complete mapping/tests | Publication remains blocked until its exact checklist passes |
| Support routing blueprint | Product Owner | Definition approved 2026-09-02 | Documentation and future contract design | Runtime requires contracts, persistence/tests and the applicable reviewed content/domain gate |
| Specialist handoff | Product Owner | Definition approved; runtime unavailable | Future registered-user flow | Consultation runtime plus scoped consent and security/privacy review |
| User-initiated reassessment | Product Owner | Definition approved | Sprint 2 | MB-178 runtime evidence |
| Automatic follow-up/reminders | Product Owner | Deferred | None | New cadence, opt-out, delivery and ownership decision |
| Descriptive progress | Product Owner | Definition approved; runtime unavailable | Future registered-user flow | Contract, implementation and compatibility tests |
| Real-user study/production data | Privacy/security/legal owners | Production blocked | Synthetic/test data only | Separate privacy, security, retention, legal and operational approval |
| AI personalization/model selection | Product Owner/Journal-AI | Deferred beyond Sprint 2 | None | Separate AI consent, model, prompt, data-use and failure policy |

## Acceptance examples

| Scenario | Blueprint outcome |
| --- | --- |
| Anonymous eligible user completes published PHQ-9 | Current score, screening level, disclaimer and independent safety status; no history or specialist flow |
| PHQ-9 score is mild and item 9 is 1 | `MILD` remains unchanged; positive safety screen; definition-only safety follow-up tier; no automatic contact |
| Eligible user requests GAD-7 before publication | Explicit questionnaire unavailable response; no substitute score |
| PHQ-9 moderate with negative item 9 | Definition-only professional-support recommendation; no treatment or booking mandate |
| Specialist runtime is absent | Result stays accessible; UI states that specialist connection is unavailable and that no one was contacted |
| Registered user completes a compatible reassessment | New immutable result plus previous/current score, raw delta, band transition and interval |
| Anonymous user requests progress | Denied as unavailable because anonymous results have no longitudinal ownership |
| AI, Kafka, Redis, notification or Content service fails | Care result and synchronous safety output remain authoritative and available |

## Definition versus runtime completion

MB-179 completes the business definition when this blueprint, its policies and the Review 1 closure matrix agree. It does not complete GAD-7 publication, intervention runtime, specialist runtime, reminders, progress APIs/UI, production consent/retention or AI personalization. Those capabilities remain unpublished, unavailable or production-blocked until their separate gates and Jira work are complete.
