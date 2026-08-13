# Domain and Use Cases

## 1. Product boundary

MentalBridge provides self-screening, emotional self-tracking, educational support, referral assistance, and follow-up. It does not diagnose, prescribe, provide psychotherapy, dispatch emergency responders, or guarantee continuous human monitoring.

The product has four actors:

| Actor | Goal | Important constraint |
| --- | --- | --- |
| Guest | Complete a PHQ-9/GAD-7 screening privately | No account history; collect only what is required |
| User | Track wellbeing and obtain appropriate support | Owns and controls access to sensitive data |
| Specialist | Manage availability and support consenting users | Sees only scopes granted by the user and required for care |
| Admin | Operate and govern the platform | Does not receive unrestricted journal access by default |

System actors include the AI provider, PhoBERT worker, notification provider, object storage, scheduler, and audit pipeline.

## 2. Ubiquitous language

- **Assessment**: one completed PHQ-9 or GAD-7 questionnaire and its immutable scored result.
- **Screening level**: severity derived only from the published questionnaire scoring band.
- **Risk classification**: platform support tier derived from current assessments plus recent, structured emotional indicators.
- **Journal entry**: user-authored private text. It is not a clinical record.
- **Analysis**: structured AI output tied to the exact journal revision, prompt version, provider, and model.
- **Intervention plan**: a versioned list of recommended platform actions, not a treatment plan.
- **Consent grant**: explicit, scoped, revocable permission from one user to one specialist.
- **Referral**: recommendation to seek human support and its operational status.
- **Appointment**: a booked consultation slot with an explicit state machine.
- **Follow-up plan**: reminders and check-ins after an intervention/referral/appointment.

Do not use "diagnosis", "patient", "treatment", or "clinical conclusion" in API/domain names unless future licensed clinical governance explicitly changes the product boundary.

## 3. Critical business rules

### Assessment scoring

1. Store the questionnaire definition/version used for every submission.
2. Each answer is an integer from 0 through 3. PHQ-9 has nine answers and GAD-7 has seven.
3. Compute scores on the server; never accept a client-computed score.
4. A submitted assessment is immutable. Correction means voiding it and creating a new attempt.
5. PHQ-9 item 9 must invoke an explicit safety rule regardless of total score. The exact reviewed policy belongs in configuration, not an LLM prompt.
6. Anonymous results expire and cannot be silently attached to a registered account. An explicit one-time claim flow may be designed later.

### Risk classification

The first implementation should use a documented deterministic policy owned by the Care Service. AI output is a supporting signal only.

Inputs:

- latest valid PHQ-9 and GAD-7 scores within a configured window;
- safety-item flags;
- recent journal analysis confidence and negative/emotion indicators;
- trend direction and data freshness.

Outputs:

- `MINIMAL`, `MILD`, `MODERATE`, or `SEVERE`;
- reason codes, input references, policy version, and calculation time;
- intervention template/version selected.

Rules:

- missing or stale inputs produce `INSUFFICIENT_DATA`, not fabricated certainty;
- severe/safety flags cannot be downgraded by positive AI sentiment;
- changing a policy does not rewrite prior results; reclassification creates a new record;
- user-facing wording always includes the non-diagnostic disclaimer.

### Consent and access

1. General privacy acceptance, AI-processing consent, research-data consent, and specialist access are separate decisions.
2. Specialist grants are scoped: `ASSESSMENTS`, `RISK_SUMMARY`, `EMOTION_TRENDS`, `JOURNAL_ENTRIES`.
3. Journal access should support selected-entry scope; "all past and future journals" must never be the quiet default.
4. Revocation blocks new reads immediately but does not falsify historical audit records.
5. Every specialist read of sensitive user data writes an audit event with actor, subject, scope, purpose, and correlation ID.
6. Admin operational access excludes raw journal content unless a separately authorized moderation/safety workflow requires it.

### Appointments and chat

Appointment transitions:

```text
REQUESTED -> CONFIRMED -> COMPLETED
     |           |
     +-> REJECTED+-> CANCELLED
     +-> CANCELLED
CONFIRMED -> RESCHEDULE_REQUESTED -> CONFIRMED or CANCELLED
```

- A slot can belong to at most one active appointment; enforce this transactionally.
- Store all timestamps in UTC and retain the participant timezone used for display.
- Chat is available only for an eligible confirmed consultation/relationship.
- Deleting a message is a tombstone operation; moderation/audit retention follows policy.
- Only a user from a completed appointment may create one review for that appointment.

### Deletion and retention

- Account deletion is an asynchronous, idempotent workflow across services.
- Legal/security audit entries may be retained but must minimize or pseudonymize subject data.
- Backups expire according to retention policy; deletion cannot promise immediate removal from immutable backups.
- Research exports must be separately consented, de-identified, and unlinkable to operational account identifiers.

## 4. Use-case catalogue

The project-tracking workbook currently groups the 162 functions into seven delivery use cases. The detailed scenarios below refine those groups; they do not restore the previous thirteen-UC numbering.

### UC-01 Authentication & User Management

**Actors:** Guest, User, Specialist, Admin

**Scope:** registration for users/specialists, login/logout, password recovery, role authorization, profile/privacy, consent, specialist grants, personal-data deletion request, and anonymous assessment entry.

**Main flow:** Identity validates credentials and owns account/session state; Care owns health profile and consent. Specialist registration enters `PENDING_VERIFICATION`. A guest may start a short-lived anonymous assessment without creating an account.

**Exceptions and acceptance:** duplicate identity, expired/reused challenge, disabled account, excessive attempts, unsupported/expired grant, and deletion restrictions produce stable errors. Consent choices are independent and versioned; revocation blocks new reads. Anonymous data is never silently attached to a later account. Security and sensitive-access actions emit minimized audit facts.

### UC-02 Mental Health Assessment & AI Analysis

**Actors:** Guest, User, Research Admin, System

**Scope:** journal CRUD, PHQ-9/GAD-7 submission/result/history/deletion, LLM emotion analysis and re-run, benchmark execution/results, risk-result display, and personal emotional analytics.

**Main flow:** Care serves an immutable questionnaire version, validates complete answers, scores deterministically and returns screening guidance synchronously. Journal/AI stores encrypted revisions and runs consent-gated asynchronous analysis. Governed benchmark runs compare the same licensed/de-identified split through versioned LLM and PhoBERT configurations.

**Exceptions and acceptance:** incomplete/invalid answers do not persist a final score; duplicate submission/analysis is idempotent; stale questionnaire requires restart; AI/provider failure never makes the journal or assessment unavailable. Raw journal content and chain-of-thought do not enter events or logs. AI cannot calculate PHQ/GAD scores or downgrade a safety path. Analytics distinguish missing data from zero and expose source freshness.

### UC-03 Intervention & Support

**Actors:** User, Admin, System

**Scope:** deterministic risk classification, personalized intervention, crisis hotline/emergency guidance, self-help resources, and risk-appropriate notification/follow-up triggers.

**Main flow:** Care evaluates a versioned local policy from eligible assessment and approved structured journal indicators, persists reason/source provenance and selects a reviewed intervention template. Content/Notification serves reviewed localized resources and handles non-critical delivery.

**Exceptions and acceptance:** missing/stale input yields `INSUFFICIENT_DATA`; severe/safety flags cannot be downgraded by positive AI sentiment. Immediate guidance is returned without waiting for Kafka, Redis, WebSocket, email or push. Provider failure affects delivery status only and never claims guaranteed emergency response.

### UC-04 Specialist Discovery & Appointment

**Actors:** User, Specialist, Admin

**Scope:** specialist discovery/recommendation, subscription plan/payment/consultation credits, specialist profile/verification, availability, booking/transitions, reviews, and related administration.

**Main flow:** an approved specialist publishes non-overlapping availability. A user with an authoritative available consultation credit requests a slot; Consultation enforces one active appointment per slot and records every transition. Completion may make one review eligible and causes the approved financial settlement action.

**Exceptions and acceptance:** concurrent booking yields one winner; mutations are idempotent; rejection/cancellation/reschedule/completion applies the approved credit rule exactly once. Search/matching is transparent and versioned. Verification files remain private and access-audited. A completed appointment does not itself grant health/journal access.

Payment, subscription, credit-ledger, earnings, and payout ownership require an accepted architecture decision before implementation. Appointment booking may consume a confirmed credit only through the authoritative owner contract and must not maintain an independent balance.

### UC-05 Communication & Follow-up

**Actors:** User, Specialist, Admin, System

**Scope:** eligible consultation conversations, realtime messages/receipts/tombstones/reports, follow-up plans/check-ins/reassessment, notifications, and progress comparison.

**Main flow:** Realtime authorizes an eligible relationship, persists a message before acknowledgement and uses Redis only for ephemeral fan-out. Care records milestones/check-ins and repeated assessments; Content/Notification persists reminders and delivery attempts.

**Exceptions and acceptance:** reconnect restores missed state through cursor-based REST history; duplicate `clientMessageId` returns the original message; Redis/Kafka/provider failure cannot lose durable chat/domain state. Reports expose only minimal moderation context. Follow-up charts avoid diagnostic or causal claims.

### UC-06 Specialist Portal

**Actor:** Specialist

**Scope:** workload/dashboard, appointments and unread chats, consenting-user list/details, scoped assessment/emotion/journal views, earnings, payout history, and pending payout.

**Main flow:** Consultation composes its own workload and requests the minimum authorized projection from Care or Journal/AI. Financial views read a bounded projection from the future authoritative ledger owner.

**Exceptions and acceptance:** each sensitive read checks the current exact grant and fails closed on timeout/revocation. Journal access is selected-entry/range scoped, not all past/future by default. Dashboard projections expose freshness and never become authorization truth. Earnings/payout figures cannot be calculated independently by Consultation.

### UC-07 Administration

**Actor:** Admin

**Scope:** bounded dashboard; user/specialist administration; subscription/payment/payout monitoring; resource/hotline CRUD; review/chat moderation; appointment monitoring; dataset/evaluation; reporting; audit; and retention.

**Main flow:** each data owner exposes an authorized admin command/query or publishes a minimized projection. Moderation snapshots only necessary evidence; reporting uses versioned projections instead of runtime distributed joins.

**Exceptions and acceptance:** admin role does not grant unrestricted raw journal/chat/assessment, verification-document, or payment-provider payload access. Changes record stable reasons and append-only audit facts. Aggregates enforce cohort/privacy thresholds and projection freshness. Retention changes remain owner-enforced and do not rewrite historical audit evidence.

## 5. Suggested MVP and deferrals

### MVP (iterations 1-2)

- user registration/login/reset and basic RBAC;
- versioned PHQ-9/GAD-7, authenticated and anonymous scoring;
- user consent and journal CRUD;
- one asynchronous LLM provider integration with schema validation;
- deterministic risk policy, intervention resources, severe-risk fallback;
- basic admin management of resources/hotlines;
- audit for security and sensitive-data access.

### Human-support release (iteration 3)

- specialist verification/profile/search;
- premium subscription/payment and consultation-credit workflow after the financial ADR is accepted;
- availability and race-safe appointment booking;
- scoped consent grants and specialist view;
- consultation chat, reminders, reviews, follow-up.

### Research/governance release (iteration 4)

- moderation and account deletion orchestration;
- aggregate reporting and retention configuration;
- isolated benchmark dataset pipeline and PhoBERT comparison.

Video calls, social/community feeds, organization tenancy, automatic emergency dispatch, custom model training, and Kubernetes remain deferred unless formally added to scope. Subscription/payment and specialist payout are now present in the project-tracking workbook, but implementation remains blocked until ownership, provider, ledger, refund/chargeback, settlement, security, and reconciliation decisions are accepted in an ADR.

## 6. Open product decisions

These require supervisor/domain-expert approval before implementation:

1. Exact risk-policy matrix, recency windows, confidence thresholds, and PHQ-9 item 9 response.
2. Who qualifies as a specialist/mentor and what evidence administrators must verify.
3. Crisis resources for each supported location, owner, review cadence, and after-hours wording.
4. Whether specialists can author notes; if yes, ownership, visibility, amendment, and retention rules.
5. Minimum user age and guardian/consent behavior if expansion includes users under 18.
6. Consent text/versioning, retention periods, deletion SLA, export scope, and applicable Vietnamese regulation review.
7. Consultation channel and whether external meeting links/phone numbers may be shared.
8. Dataset licenses, label mapping, train/test leakage controls, and research ethics approval.
9. Subscription plan lifecycle, renewal/cancellation semantics, supported payment provider/methods, payment webhook verification, consultation-credit reservation/consume/return/expiry rules, refunds/chargebacks, specialist earning calculation, payout settlement, reconciliation, and financial retention.
10. Whether WBS 28-29 are end-user/research benchmark views distinct from admin WBS 155-156, or duplicate functions that should share one admin-only workflow.
