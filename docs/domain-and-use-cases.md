# Domain and Use Cases

## 1. Product boundary

MentalBridge provides self-screening, emotional self-tracking, educational support, referral assistance, and follow-up. It does not diagnose, prescribe, provide psychotherapy, dispatch emergency responders, or guarantee continuous human monitoring.

V1 supports exactly two screening and post-screening support domains for the primary Capstone cohort of adults aged 18 through 30 residing in Vietnam: `DEPRESSIVE_SYMPTOMS` through PHQ-9 and `ANXIETY_SYMPTOMS` through GAD-7, focused on generalized anxiety symptoms. It does not claim specialized screening for OCD, trauma/PTSD, panic disorder, social anxiety, bipolar/mania, psychosis, eating disorders, substance use, ADHD, insomnia as an independent domain, personality disorders, or another unapproved concern. An additional domain requires a separately governed product vertical.

The product has four actors:

| Actor | Goal | Important constraint |
| --- | --- | --- |
| Guest | Complete a PHQ-9/GAD-7 screening privately | No account history; collect only what is required |
| User | Track wellbeing and obtain appropriate support | Owns and controls access to sensitive data |
| Specialist | Manage availability and support consenting users | Sees only scopes granted by the user and required for care |
| Admin | Operate and govern the platform | Does not receive unrestricted journal access by default |

System actors include configured AI providers, the notification provider, object storage, scheduler, and audit pipeline. A PhoBERT worker is an optional future benchmark actor under ADR 0011, not a current runtime dependency.

## 2. Ubiquitous language

- **Assessment**: one completed PHQ-9 or GAD-7 questionnaire and its immutable scored result.
- **Screening domain**: the bounded symptom concern measured by an approved instrument; V1 has `DEPRESSIVE_SYMPTOMS` and `ANXIETY_SYMPTOMS` only.
- **Screening level**: the band derived only from one published questionnaire's scoring policy; it is not a global mental-health severity.
- **Safety status**: deterministic PHQ-9 item-9 screen stored independently from the questionnaire band.
- **Support tier**: approved platform support pathway derived from eligible inputs; it is not a suicide-risk label.
- **Support evaluation**: an immutable, versioned Care decision that retains instrument-specific evidence, contributing domains, independent safety evidence, pathway and reason codes.
- **SupportPlan**: a versioned, system-proposed set of approved platform actions which the user may activate after reviewing allowed choices; it is not a treatment plan.
- **Journal entry**: user-authored private text. It is not a clinical record.
- **Analysis**: structured AI output tied to the exact journal revision, prompt version, provider, and model.
- **Consent grant**: explicit, scoped, revocable permission from one user to one specialist.
- **Referral**: recommendation to seek human support and its operational status.
- **Subscription period**: one paid, time-bounded activation of an immutable plan version.
- **Consultation credit**: one indivisible right to one standard specialist appointment; booking reserves it and completion consumes it.
- **Appointment**: the authoritative scheduled consultation unit linking one user, specialist, slot, credit, channel-eligibility window, completion, review, and earning.
- **Follow-up plan**: reminders and check-ins after an intervention/referral/appointment.

Do not use "diagnosis", "patient", "treatment", or "clinical conclusion" in API/domain names unless future licensed clinical governance explicitly changes the product boundary.

## 3. Critical business rules

### Assessment scoring

1. Store the questionnaire definition/version used for every submission.
2. Each answer is an integer from 0 through 3. PHQ-9 has nine answers and GAD-7 has seven.
3. Compute scores on the server; never accept a client-computed score.
4. A submitted assessment is immutable. Correction means voiding it and creating a new attempt.
5. PHQ-9 item 9 uses the versioned deterministic rule in `MB-SAFETY-PHQ9-001`; a positive item remains independent of total-score severity and never relies on an LLM.
6. Anonymous results expire and cannot be silently attached to a registered account. An explicit one-time claim flow may be designed later.
7. Every result retains questionnaire, scoring, and safety-policy versions. Exact approved policy text lives in `docs/policies/`.

### Safety status and support tier

Care calculates safety status synchronously from the approved questionnaire-specific rule. Story 1103 publishes `mb-support-routing-capstone-v1` for bounded authenticated controlled-Capstone routing from one explicit compatible PHQ-9/GAD-7 pair. AI is not an input to this routing version and can never define or override standardized scoring or safety.

Controlled-Capstone routing inputs:

- complete immutable PHQ-9 or GAD-7 results from published versions, explicitly selected in the same user-initiated evaluation;
- the independent PHQ-9 item-9 safety status where applicable.

Each input keeps its own instrument, domain and screening level. Equal PHQ-9 and GAD-7 bands do not mean the same support need, and the product never creates a combined score or global mental-health severity. Safety is a cross-cutting layer: it can prioritize reviewed guidance and professional/immediate-help paths, but it never changes either instrument's score or band.

Unpublished GAD-7 is not an eligible input. A future policy may add automatic latest-result windows, journal indicators or trends only after it versions freshness, consent, confidence, missing-data and conflict behavior.

Independent outputs:

- `NEGATIVE_SAFETY_SCREEN` or `POSITIVE_SAFETY_SCREEN` for the PHQ-9 item-9 rule;
- a support tier of `SELF_GUIDED_SUPPORT`, `PROFESSIONAL_SUPPORT_RECOMMENDED`, or `SAFETY_FOLLOW_UP_RECOMMENDED`;
- reason codes, input references, policy version, and calculation time;
- contributing screening domains and exact instrument-specific evidence.

Rules:

- no explicitly selected eligible result produces `INSUFFICIENT_DATA`, not fabricated certainty; a future time-window policy must treat stale inputs the same way;
- safety status cannot be downgraded by positive AI sentiment;
- changing a policy does not rewrite prior results; reclassification creates a new record;
- the current `mb-support-routing-capstone-v1` tier is historical coarse routing and is not sufficient by itself to select resources or a SupportPlan;
- future plan creation starts from a domain-aware SupportEvaluation and a system proposal; the user cannot submit an arbitrary initial resource list;
- Care revalidates exact versioned eligibility before explicit activation, and a new evaluation never silently changes an existing plan;
- user-facing wording always includes the non-diagnostic disclaimer.

### Consent and access

1. General privacy acceptance, AI-processing consent, research-data consent, and specialist access are separate decisions.
2. Specialist grants are scoped: `ASSESSMENTS`, `RISK_SUMMARY`, `EMOTION_TRENDS`, `JOURNAL_ENTRIES`.
3. Journal access should support selected-entry scope; "all past and future journals" must never be the quiet default.
4. Revocation blocks new reads immediately but does not falsify historical audit records.
5. Every specialist read of sensitive user data writes an audit event with actor, subject, scope, purpose, and correlation ID.
6. Admin operational access excludes raw journal content unless a separately authorized moderation/safety workflow requires it.

### Subscription, upgrade, and credits

| Plan | Monthly price | Consultation credits | Main access |
| --- | ---: | ---: | --- |
| Free | USD 0.00 | 0 | Assessment/history, journal/AI analysis with separate consent, basic dashboard/resources, specialist discovery and basic approved-catalogue support; no specialist consultation |
| Premium Care | USD 9.99 | 1 | Free features plus appointment, appointment-scoped specialist chat, deeper longitudinal non-safety personalization, advanced analytics/follow-up and priority recommendation |
| Premium Plus | USD 19.99 | 3 | Care features plus priority booking/matching and enhanced follow-up |

- Free is the default when no paid subscription is active. Scoring, disclaimer, safety status, reviewed safety guidance, and access to owned assessment data are never paywalled.
- A successful payment or renewal grants credits exactly once. Available credits expire at the billing-period end and do not roll over.
- Care to Plus is the only in-period upgrade. Free to paid is a purchase; Plus to Care is unsupported. A user may separately cancel Plus, lose paid access immediately without refund, and later buy Care as a new purchase; this is not a downgrade.
- Upgrade starts a new full Plus period. The non-withdrawable offset is the sum of available-credit allocation plus the old plan's remaining non-consultation value, prorated by actual remaining seconds and rounded down to a minor unit. Consumed/expired/forfeited/revoked credits have no value; reserved credits remain attached to their appointment and are not offset.
- Upgrade checkout moves included available credits to `UPGRADE_HELD`; verified payment revokes them and grants three Plus credits. Failure or quote expiry releases them. Booking and upgrade cannot use the same credit concurrently.
- User cancellation stops paid entitlements immediately and does not refund money. Future appointments are cancelled and their credits revoked. One confirmed session already inside its scheduled window may finish at `scheduledEndAt`; the subscription then completes cancellation. Returning a credit for an appointment-level eligible cancellation is not a payment refund.
- Current plan versions allocate USD 5.00 to each credit and snapshot a 70% specialist share, USD 3.50, only when a consultation completes. A price/allocation/share change requires a new immutable plan version.
- “Longer consultation” is not a current Plus benefit. One credit purchases one standard slot; any duration-specific tier requires a new plan version and compensation rule.

### Appointments and in-app consultation chat

Appointment transitions:

```text
REQUESTED -> CONFIRMED -> COMPLETED
     |           |
     +-> REJECTED+-> CANCELLED
     +-> CANCELLED
CONFIRMED -> RESCHEDULE_REQUESTED -> CONFIRMED or CANCELLED
```

- The first enabled channel is `IN_APP_CHAT`; `IN_APP_VIDEO` is a planned channel that remains disabled until its call contract/provider/safety policy is defined. Neither uses a physical location, phone number, or external meeting link.
- A slot can belong to at most one active appointment and a credit to at most one active appointment; enforce both transactionally.
- A specialist publishes discrete bookable slots from their working schedule in an IANA timezone; the server stores UTC and, once approved, validates the standard duration. The user chooses one slot and booking snapshots its start, end, timezone, and channel on the appointment.
- Booking reserves one available credit; it does not consume the credit. Rejection, specialist cancellation/no-show, platform failure, or eligible user cancellation releases it. Completion consumes it and creates one earning. User late cancellation/no-show forfeits it without creating an earning.
- Chat send/join is available only during the confirmed appointment's scheduled window through its one conversation. History may remain readable afterward, but direct or 24/7 friend-style specialist messaging is not a consultation path.
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

**Main flow:** Identity validates credentials and owns account/session state; Care owns health profile and consent. After specialist registration, Consultation records the profile approval state as pending; this is not an Identity account state and requires no document upload. A guest may start a short-lived anonymous assessment without creating an account.

**Exceptions and acceptance:** duplicate identity, expired/reused challenge, disabled account, excessive attempts, unsupported/expired grant, and deletion restrictions produce stable errors. Consent choices are independent and versioned; revocation blocks new reads. Anonymous data is never silently attached to a later account. Security and sensitive-access actions emit minimized audit facts.

### UC-02 Mental Health Assessment & AI Analysis

**Actors:** Guest, User, Admin, System

**Scope:** journal CRUD, PHQ-9/GAD-7 submission/result/history/deletion, LLM emotion analysis and re-run, benchmark execution/results, screening/safety/support display, and personal emotional analytics.

**Main flow:** Care serves an immutable questionnaire version, validates complete answers, scores deterministically and returns screening guidance synchronously. Journal/AI stores encrypted revisions and runs consent-gated asynchronous analysis. Governed benchmark runs compare the same licensed/de-identified split through versioned provider configurations, initially OpenAI and Gemini; PhoBERT is an optional future third baseline.

**Exceptions and acceptance:** incomplete/invalid answers do not persist a final score; duplicate submission/analysis is idempotent; stale questionnaire requires restart; AI/provider failure never makes the journal or assessment unavailable. Raw journal content and chain-of-thought do not enter events or logs. AI cannot calculate PHQ/GAD scores or downgrade a safety path. Analytics distinguish missing data from zero and expose source freshness.

### UC-03 Intervention & Support

**Actors:** User, Admin, System

**Scope:** deterministic safety status, domain-aware SupportEvaluation, system-proposed SupportPlans, entitlement-aware personalized support, reviewed safety guidance, self-help resources, and support-appropriate notification/follow-up triggers.

**Main flow:** Care evaluates versioned local scoring, domain and safety rules. Once the domain-aware policy and contracts are approved, Care resolves a support pathway, selects a bounded proposal from exact eligible content versions, and creates a system-proposed `DRAFT` SupportPlan. The user reviews only the choices allowed by that proposal; Care revalidates eligibility before explicit activation. Content/Notification owns reviewed localized resources and their versioned eligibility metadata, while Care owns final plan eligibility and lifecycle.

**Exceptions and acceptance:** missing/stale support inputs yield `INSUFFICIENT_DATA`; safety status cannot be downgraded by positive AI sentiment. Review/publication alone never makes a resource universally plan-eligible. Immediate guidance is returned before ordinary plan controls and without waiting for Kafka, Redis, WebSocket, email or push. Provider failure affects delivery status only and never claims guaranteed emergency response or that a human was notified.

### UC-04 Specialist Discovery & Appointment

**Actors:** User, Specialist, Admin

**Scope:** specialist discovery/recommendation, plan/payment/subscription/upgrade/consultation credits, specialist profile approval, availability, booking/transitions, earnings, reviews, and related administration.

**Main flow:** an approved specialist publishes non-overlapping availability for an enabled in-app channel. A user with an authoritative available consultation credit requests a slot; Consultation atomically snapshots its scheduled interval/channel, reserves the slot and credit, and records every transition. Confirmation enables the appointment-scoped channel only within that interval. Completion consumes the credit, creates one earning snapshot, and may make one review eligible.

**Exceptions and acceptance:** concurrent booking or upgrade against the last credit yields one winner; mutations/webhooks are idempotent; rejection/cancellation/reschedule/no-show/completion applies the approved credit rule exactly once. Care-to-Plus upgrade uses the ADR 0005 time-and-unused-credit offset; downgrade and refund are unsupported. Search/matching is transparent and versioned. A completed appointment does not itself grant health/journal access.

### UC-05 Communication & Follow-up

**Actors:** User, Specialist, Admin, System

**Scope:** eligible consultation conversations, realtime messages/receipts/tombstones/reports, follow-up plans/check-ins/reassessment, notifications, and progress comparison.

**Main flow:** Realtime authorizes an eligible relationship, persists a message before acknowledgement and uses Redis only for ephemeral fan-out. Care records milestones/check-ins and repeated assessments; Content/Notification persists reminders and delivery attempts.

**Exceptions and acceptance:** reconnect restores missed state through cursor-based REST history; duplicate `clientMessageId` returns the original message; Redis/Kafka/provider failure cannot lose durable chat/domain state. Reports expose only minimal moderation context. Follow-up charts avoid diagnostic or causal claims.

### UC-06 Specialist Portal

**Actor:** Specialist

**Scope:** workload/dashboard, appointments and unread chats, consenting-user list/details, scoped assessment/emotion/journal views, earnings, payout history, and pending payout.

**Main flow:** Consultation composes its own workload and authoritative earnings/provider-payout views, and requests the minimum authorized health projection from Care or Journal/AI.

**Exceptions and acceptance:** each sensitive read checks the current exact grant and fails closed on timeout/revocation. Journal access is selected-entry/range scoped, not all past/future by default. Dashboard projections expose freshness and never become authorization truth. Other services cannot calculate financial balances independently from Consultation/Billing.

### UC-07 Administration

**Actor:** Admin

**Scope:** bounded dashboard; user/specialist administration; subscription/payment/payout monitoring; reviewed resource CRUD; review/chat moderation; appointment monitoring; dataset/evaluation; reporting; audit; and retention.

**Main flow:** each data owner exposes an authorized admin command/query or publishes a minimized projection. Moderation snapshots only necessary evidence; reporting uses versioned projections instead of runtime distributed joins.

**Exceptions and acceptance:** admin role does not grant unrestricted raw journal/chat/assessment, payout-destination data, or provider payload access. Changes record stable reasons and append-only audit facts. Aggregates enforce cohort/privacy thresholds and projection freshness. Retention changes remain owner-enforced and do not rewrite historical audit evidence. A payout becomes successful only from a verified provider result/status query.

## 5. Suggested MVP and deferrals

### MVP (iterations 1-2)

- user registration/login/reset and basic RBAC;
- versioned PHQ-9/GAD-7, authenticated and anonymous scoring;
- user consent and journal CRUD;
- one asynchronous LLM provider integration with schema validation;
- deterministic instrument-specific screening, cross-cutting safety behavior, and domain-aware system-proposed support;
- basic admin management of reviewed self-help resources;
- audit for security and sensitive-data access.

### Human-support release (iteration 3)

- specialist approval/profile/search without verification-document upload;
- premium subscription/payment/upgrade and consultation-credit workflow defined by ADR 0005;
- availability and race-safe appointment booking;
- scoped consent grants and specialist view;
- consultation chat, reminders, reviews, follow-up.

### Research/governance release (iteration 4)

- moderation and account deletion orchestration;
- aggregate reporting and retention configuration;
- isolated provider-neutral benchmark dataset pipeline, with PhoBERT comparison optional after its activation gate passes.

In-app video is intended but its call/signaling/provider/security contract is deferred; phone/in-person consultation, social/community feeds, organization tenancy, automatic emergency dispatch, custom model training, automated refunds, and Kubernetes remain out of scope unless formally added. Subscription/payment ownership, credit accounting, upgrade, earnings, and payout workflow are fixed by ADR 0005; real provider credentials/signatures, VND plan pricing or explicit FX policy, settlement delay, retention, and chargeback reconciliation still require approval.

## 6. Open product decisions

These remain open for the affected production or optional feature. Under ADR 0010 they do not block a base questionnaire that has passed the controlled Capstone publication gate:

1. The current `mb-support-routing-capstone-v1` runtime remains immutable historical coarse routing. Compatible domain-bearing SupportEvaluation evolution, any automatic latest-result freshness window, and downstream plan use are tracked by issue #48. PHQ-9 item-9 core behavior is already executable through `MB-SAFETY-PHQ9-001`.
2. Who qualifies as a specialist/mentor and which profile facts administrators review without collecting credential documents.
3. Exact Vietnamese production safety/disclaimer wording and whether a specific emergency number may appear as versioned safety content. A Product Owner may select bounded non-diagnostic and capability wording for controlled Capstone use; no hotline/facility catalogue is planned.
4. Whether specialists can author notes; if yes, ownership, visibility, amendment, and retention rules.
5. Minimum user age and guardian/consent behavior if expansion includes users under 18.
6. Consent text/versioning, retention periods, deletion SLA, export scope, and applicable Vietnamese regulation review before public real-user data collection. Synthetic controlled demos do not require these values to publish a questionnaire.
7. Exact standard appointment duration, join grace, late-cancellation cutoff, and later in-app-video signaling/provider/recording/fallback policy.
8. Dataset licenses, label mapping, train/test leakage controls, and research ethics approval. PhoBERT additionally requires an approved narrow classification task, deterministic preprocessing, and a compatible versioned fine-tuned checkpoint before implementation.
9. Exact MoMo request type/payment methods, credential/key rotation, settlement delay, payout onboarding, VND plan prices or versioned FX policy, chargeback reconciliation, and financial retention. Downgrade and refund remain unsupported; no second production payment provider is planned.
10. Whether WBS 28-29 are end-user/research benchmark views distinct from admin WBS 155-156, or duplicate functions that should share one admin-only workflow.
11. Before SupportPlan contract work resumes, issue #49 must decide whether templates are persisted, required versus optional proposed resources, minimum/maximum choices, rule-versus-mapping ownership, and whether safety-positive activation needs an additional reviewed acknowledgement.
12. Issue #50 must approve versioned resource-eligibility semantics, including primary-domain versus cross-domain adjunct content; review/publication alone is not eligibility.

The governing correction and repository impact are recorded in [ADR 0012](adr/0012-two-domain-screening-and-system-proposed-support-plans.md) and the [MB-SCOPE-DOMAIN-001 impact analysis](sprints/mb-scope-domain-001-impact-analysis.md).
