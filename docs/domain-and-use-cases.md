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

### UC-01 Register and authenticate

**Actors:** User, Specialist, Admin

**Main flow:** validate email/password, verify ownership, create account, issue short-lived access token and rotated refresh token. Specialist accounts enter `PENDING_VERIFICATION`.

**Exceptions:** duplicate identity, expired verification/reset token, disabled account, excessive attempts.

**Audit:** registration, verification, login failures, password/security changes, admin status changes.

### UC-02 Complete an anonymous screening

**Actor:** Guest

**Main flow:** choose instrument, receive current questionnaire version, submit complete answers, server scores, return band/explanation/disclaimer and support resources.

**Privacy:** use opaque session ID, short TTL, no journal/AI analysis, no fingerprint-based account linkage.

**Safety:** relevant answer/score returns crisis guidance in the same response without waiting for async processing.

### UC-03 Manage profile and privacy

**Actor:** User

**Main flow:** view/update profile, record consent decisions, grant/revoke specialist scopes, request export/deletion.

**Exceptions:** specialist not approved, expired grant, scope not supported, deletion blocked by an active workflow that must first be resolved.

### UC-04 Create and analyze a journal

**Actor:** User

**Main flow:** save encrypted private entry, create analysis request for its revision, worker redacts unnecessary identifiers and calls configured model, validate structured output, store result, notify user.

**Exceptions:** no AI consent means save without analysis; provider failure means `RETRYABLE` or `FAILED`, while journal remains usable; edit invalidates prior result and creates a new revision/request.

**Output:** polarity, emotion scores, dominant emotions, confidence/quality flags, model and prompt versions. Do not display hidden chain-of-thought.

### UC-05 Submit an authenticated assessment

**Actor:** User

**Main flow:** start attempt, submit all responses, server validates and scores, persist immutable result, run risk policy, synchronously return safety guidance, asynchronously build recommendations/notifications.

**Exceptions:** duplicate submit is idempotent; expired questionnaire version requires restart; invalid answer prevents persistence.

### UC-06 Classify risk and generate intervention

**Actor:** System

**Trigger:** assessment submission, successful journal analysis, follow-up check-in, or reviewed policy replay.

**Main flow:** load eligible inputs, evaluate versioned policy, persist reasons and provenance, select intervention template.

**Paths:** minimal/mild gets self-help and reassessment; moderate gets referral/booking prompt and follow-up; severe gets immediate crisis guidance and an optional user-approved human contact path.

**Guardrail:** never present background notification delivery as guaranteed emergency response.

### UC-07 Discover and approve a specialist

**Actors:** Specialist, Admin, User

**Main flow:** specialist submits profile/document metadata; admin reviews and approves/rejects with reason; approved profiles become searchable/filterable; recommendation uses transparent matching criteria.

**Privacy:** verification documents live in private object storage, not PostgreSQL/MongoDB blobs; access is time-limited and audited.

### UC-08 Book and conduct a consultation

**Actors:** User, Specialist

**Main flow:** specialist publishes slot, user requests booking with idempotency key, specialist accepts, system sends reminders, enables chat, specialist completes appointment, user may review.

**Exceptions:** concurrent booking, cancellation deadline, reschedule negotiation, no-show, disabled specialist.

### UC-09 Grant specialist access

**Actors:** User, Specialist

**Main flow:** user selects specialist, scopes, data range/entries, purpose, and expiry; system records grant; specialist queries go through authorization with current consent; user revokes at any time.

**Exception:** an appointment does not itself imply access to journals.

### UC-10 Follow up and monitor progress

**Actors:** User, Specialist, System

**Main flow:** create milestones, schedule reminders, user submits check-in/reassessment, compare scores and emotion aggregates, re-run risk policy where appropriate.

**Constraint:** charts distinguish missing data from zero and avoid causal claims.

### UC-11 Moderate content

**Actors:** User/Specialist reporter, Admin

**Main flow:** report a review or message, snapshot minimal moderation context, admin assigns/reviews, applies action, records reason, optionally accepts appeal.

**Constraint:** reports do not grant broad access to the rest of a conversation.

### UC-12 Evaluate AI models

**Actor:** Research Admin

**Main flow:** register licensed/de-identified dataset, validate label schema, create benchmark configuration, run the same split through LLM and PhoBERT, record per-class metrics, latency, failures, cost, versions, and reproducibility metadata.

**Constraint:** benchmark data is isolated from production journals; production user data is not included by default.

### UC-13 Administer and audit

**Actor:** Admin

**Main flow:** manage account states, specialist verification, resources/hotlines, retention policies; inspect operational aggregates and append-only audit records.

**Constraint:** dashboard aggregates must enforce minimum cohort sizes to reduce re-identification risk.

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
- availability and race-safe appointment booking;
- scoped consent grants and specialist view;
- consultation chat, reminders, reviews, follow-up.

### Research/governance release (iteration 4)

- moderation and account deletion orchestration;
- aggregate reporting and retention configuration;
- isolated benchmark dataset pipeline and PhoBERT comparison.

Defer payments, video calls, social/community feeds, organization tenancy, automatic emergency dispatch, custom model training, and Kubernetes unless formally added to scope.

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
