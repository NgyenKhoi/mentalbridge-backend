# Domain and Use Cases

## 1. Product boundary

MentalBridge provides self-screening, emotional self-tracking, educational support, referral assistance, and follow-up. It does not diagnose, prescribe, provide psychotherapy, dispatch emergency responders, or guarantee continuous human monitoring.

V1 supports exactly two screening and post-screening support domains for the primary Capstone cohort of adults aged 18 through 30 residing in Vietnam: `DEPRESSIVE_SYMPTOMS` through PHQ-9 and `ANXIETY_SYMPTOMS` through GAD-7, focused on generalized anxiety symptoms. It does not claim specialized screening for OCD, trauma/PTSD, panic disorder, social anxiety, bipolar/mania, psychosis, eating disorders, substance use, ADHD, insomnia as an independent domain, personality disorders, or another unapproved concern. An additional domain requires a separately governed product vertical.

ADR 0017 defines the product-wide scope v2 package, Support Guide,
SupportPlan, reminder, appointment, financial, AI, and safety amendments. The
two-domain screening boundary remains unchanged.

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
- **Safety signal**: either a positive deterministic PHQ-9 item-9 result or the user's explicit “Tôi cần hỗ trợ ngay” action, stored independently from questionnaire bands.
- **Support tier**: approved platform support pathway derived from eligible inputs; it is not a suicide-risk label.
- **Support evaluation**: an immutable, versioned Care decision that retains instrument-specific evidence, contributing domains, independent safety evidence, pathway and reason codes.
- **Support Guide**: a one-time Care-approved post-screening guidance result available to every package; it has no plan lifecycle or activity tracking.
- **SupportPlan**: durable Care-owned support data for `PLUS`/`PREMIUM`, with one official current plan, explicit lifecycle and activity tracking; it is not a treatment plan.
- **PlanChangeRequest**: a pending specialist proposal that Care revalidates and the user must confirm; it is not a second SupportPlan.
- **Journal entry**: user-authored private text. It is not a clinical record.
- **Analysis**: structured AI output tied to exact journal source revision(s), bounded period where applicable, prompt version, provider, and model.
- **Reassessment Summary**: Care-owned presentation of four separate dimensions—standardized screening trend, non-standardized journal/context trend, SupportPlan engagement, and user reflection—without a combined improvement score.
- **Consent grant**: explicit, scoped, revocable permission from one user to one specialist.
- **Referral**: recommendation to seek human support and its operational status.
- **Subscription period**: one paid, time-bounded activation of an immutable plan version.
- **Consultation credit**: one indivisible right to one standard specialist appointment; booking reserves it and completion consumes it.
- **Appointment**: the authoritative scheduled consultation unit linking one user, specialist, slot, credit, channel-eligibility window, completion, review, and earning.
- **AgreedNextSteps**: specialist-authored post-session actions visible to the user; they do not mutate the SupportPlan.
- **Wellbeing digest**: the default at-most-once-daily reminder combining eligible unfinished resources, Journal, and emotion check-in prompts.
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

Care calculates questionnaire safety status synchronously from the approved
questionnaire-specific rule and also owns the explicit “Tôi cần hỗ trợ ngay”
action. Either positive PHQ-9 item 9 or that explicit action activates the
safety flow; a `High` or `Severe` band alone does not. Story 1103 publishes
`mb-support-routing-capstone-v1` for bounded authenticated controlled-Capstone
routing from one explicit compatible PHQ-9/GAD-7 pair. AI is not an input to
this routing version and can never define or override standardized scoring or
safety.

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

When safety guidance includes a facility directory, the user manually enters
or selects province, district, or another supported area. Every presented entry
has source/provenance, `reviewedAt`, `verifiedAt`, address, phone, coverage, and
active status. Without coordinates and a distance calculation, copy says “cơ
sở trong khu vực đã chọn”, never “gần nhất”. MentalBridge does not
automatically call, share location, email a safety alert, notify a third party,
or claim guaranteed response.

### Reassessment and longitudinal context

Reassessment never asks one signal to decide whether the user “improved.” Care
presents four separate dimensions:

1. deterministic, versioned PHQ-9/GAD-7 score and band trend;
2. AI-derived contextual/emotional trend limited to the consented journal
   entries available in each bounded period;
3. SupportPlan engagement, including activity completion and barriers;
4. user-rated helpfulness, self-reported change, and notes.

The first dimension is the standardized symptom-measure comparison. The second
is non-standardized, model-derived, context-dependent evidence and must expose
data coverage. Sparse or imbalanced journal periods produce
`INSUFFICIENT_DATA`; absence of a journal mention never proves that a difficulty
resolved. The dimensions cannot be collapsed into a recovery percentage,
clinical improvement verdict, or global score.

AI may surface contextual signals, recurring themes, preferences, barriers, and
helpful patterns for a SupportPlan review. Care remains responsible for finding
allowed alternatives, and the user reviews and confirms any change.

### Consent and access

1. General privacy acceptance, AI-processing consent, research-data consent, and specialist access are separate decisions.
2. Specialist grants are scoped: `ASSESSMENTS`, `RISK_SUMMARY`, `EMOTION_TRENDS`, `JOURNAL_ENTRIES`.
3. Journal access should support selected-entry scope; "all past and future journals" must never be the quiet default.
4. Revocation blocks new reads immediately but does not falsify historical audit records.
5. Every specialist read of sensitive user data writes an audit event with actor, subject, scope, purpose, and correlation ID.
6. Admin operational access excludes raw journal content unless a separately authorized moderation/safety workflow requires it.

### Subscription, upgrade, and credits

| Package | V2 price/paid period | Consultation credits | Main access |
| --- | ---: | ---: | --- |
| `FREE` | VND 0 | 0 | Standard Support Guide, Journal, emotion check-in, reviewed resources, and default five delivered AI responses/day |
| `PLUS` | VND amount pending approval | 1 | Higher AI quota, persistent SupportPlan/lifecycle tracking, and one credit per paid period |
| `PREMIUM` | VND amount pending approval | 3 | No displayed daily AI-response limit, server fair-use/token/rate limits, optional stronger model, advanced recommendations, and three credits per paid period |

- `FREE` is the default. Scoring, disclaimer, safety flow, Support Guide,
  reviewed safety guidance, reviewed-resource count, and access to owned data are
  never paywalled.
- Successful paid-package purchase or upgrade grants credits exactly once.
  Available credits expire at period end and do not roll over.
- `FREE` to a paid package is a purchase. `PLUS` to `PREMIUM` is the only
  upgrade. Downgrade and user-initiated refund APIs are unsupported.
- Upgrade starts a new full `PREMIUM` period. The versioned offset retains only
  eligible unused value, uses actual remaining UTC seconds, and is calculated
  in VND minor units. Booking and upgrade cannot use the same credit
  concurrently.
- Each credit snapshots a fixed VND `creditAllocation`; a completed appointment
  creates a 70% specialist share of that allocation. It is never 70% of the
  package price.
- Real payment and payout use MoMo only and remain disabled until exact VND
  prices, fixed `creditAllocation` values, and credentials are approved.
- Resources have no package-specific count limit. AI token/request quota,
  capability entitlement, and credits are the commercial controls.

### Appointments and bounded consultation

Appointment transitions:

```text
REQUESTED -> CONFIRMED | REJECTED | EXPIRED | CANCELLED
CONFIRMED -> IN_PROGRESS | CANCELLED
IN_PROGRESS -> SESSION_ENDED | USER_NO_SHOW | SPECIALIST_NO_SHOW
SESSION_ENDED -> COMPLETED | DISPUTED
```

- V2 modes are only `IN_APP_CHAT` and `IN_APP_VIDEO`; new `IN_PERSON`, phone,
  and external meeting-link appointments are disabled. Historical in-person
  appointments remain readable under their snapshotted v1 policy.
- A slot can belong to at most one active appointment and a credit to at most one active appointment; enforce both transactionally.
- Every slot is 60 minutes. A specialist publishes discrete bookable slots from their working schedule in an IANA timezone; the server stores UTC. The user chooses one slot and booking snapshots its start, end, timezone, mode, and applicable location on the appointment.
- A request must be made at least four hours before start. Booking holds one available credit and slot; it does not consume the credit. The specialist responds by `min(requestedAt + 24h, startsAt - 2h)`, otherwise the request expires and both holds release.
- Eligible cancellation, rejection, expiry, specialist suspension before start, specialist/system cancellation, or specialist no-show releases the appointment credit. User cancellation inside 24 hours of a confirmed start or user no-show forfeits it. Only evidence-based or user-confirmed completion consumes it and creates one earning; this is a credit outcome, never a cash refund.
- Rescheduling cancels the old appointment under its applicable policy and creates a new request. It never mutates the old slot snapshot.
- Participants may enter waiting ten minutes before start. At `endsAt`, the
  appointment becomes `SESSION_ENDED` and chat/video interaction closes.
  Elapsed time never creates `COMPLETED`. Completion requires accepted
  server-observed chat evidence or server/provider-observed video evidence; a
  reported unresolved issue creates `DISPUTED`.
- A specialist cannot finalize a session or consume a credit unilaterally. Direct or 24/7 friend-style specialist messaging is not a consultation path.
- Deleting a message is a tombstone operation; moderation/audit retention follows policy.
- Only a user from a completed appointment may create one review for that appointment.
- Continuity uses repeated appointments, a user-reviewed pre-session
  `ConsultationBrief`, and post-session `SessionSummary` plus
  `AgreedNextSteps`. Reuse requires user approval. A specialist-proposed
  resource becomes a `PlanChangeRequest`; Care revalidates eligibility and the
  user confirms it. Appointment existence never grants raw journal, raw answer,
  full AI-history, ongoing specialist access, or authority to create another
  SupportPlan.

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

**Main flow:** Care serves an immutable questionnaire version, validates complete answers, scores deterministically and returns screening guidance synchronously. On an explicit user request for one exact journal revision, Journal/AI checks current `AI_PROCESSING` consent, creates an asynchronous analysis job, invokes exactly one configured provider, and stores only the validated normalized result with provenance. Governed benchmark runs compare the same licensed/de-identified split through versioned provider configurations, initially OpenAI and Gemini; PhoBERT is an optional future third baseline.

**Exceptions and acceptance:** incomplete/invalid answers do not persist a final score; duplicate submission/analysis is idempotent; stale questionnaire requires restart; AI/provider failure never makes the journal or assessment unavailable. A run has a 30-second timeout per attempt and at most one retry for HTTP 429, provider 5xx, or transport failure, with no automatic cross-provider fallback. Raw journal content, raw provider output, and chain-of-thought do not enter persistence, events, or logs. AI cannot calculate PHQ/GAD scores, downgrade a safety path, determine resource eligibility, or mutate a SupportPlan. Analytics distinguish missing data from zero and expose source freshness.

### UC-03 Intervention & Support

**Actors:** User, Admin, System

**Scope:** deterministic and explicit-user safety signals, all-tier Support
Guide, domain-aware SupportEvaluation, paid SupportPlan, entitlement-aware
personalized support, reviewed safety guidance and directory, self-help
resources, and governed notification/follow-up triggers.

**Main flow:** Care evaluates versioned local scoring, domain and safety rules
and produces a one-time Support Guide for every package. For an entitled
`PLUS`/`PREMIUM` user, Care applies `mb-support-plan-selection-v1`, composes the
bounded proposal, and owns the single official SupportPlan. The user reviews
allowed choices; Care revalidates evaluation, template, entitlement, and exact
resource eligibility before activation or change. Specialist suggestions enter
as `PlanChangeRequest`, never a second plan. Content/Notification owns reviewed
localized resources and eligibility metadata. Safety guidance and
professional-support calls to action remain outside the plan and precede its
controls.

**Exceptions and acceptance:** missing/stale support inputs yield
`INSUFFICIENT_DATA`; AI cannot decide safety. Review/publication alone never
makes a resource plan-eligible, while package entitlement never imposes a
resource-count limit. Immediate guidance is returned before ordinary plan
controls and without waiting for optional dependencies. Area-filtered facility
results do not claim “nearest”; no safety email, automatic call, location
sharing, third-party notification, or guaranteed response occurs.

### UC-04 Specialist Discovery & Appointment

**Actors:** User, Specialist, Admin

**Scope:** specialist discovery/recommendation, plan/payment/subscription/upgrade/consultation credits, specialist profile approval, availability, booking/transitions, earnings, reviews, and related administration.

**Main flow:** an approved specialist publishes non-overlapping 60-minute
in-app chat or in-app video availability. A user with an available credit
requests a slot; Consultation atomically snapshots interval, timezone, and mode
and holds the slot and credit. At the scheduled end, the server closes the
channel and records `SESSION_ENDED`. Accepted server/provider evidence is still
required for `COMPLETED`; only then does Consultation consume one credit, create
one earning equal to 70% of its fixed `creditAllocation`, and enable review.

**Exceptions and acceptance:** concurrent booking or upgrade against the last
credit yields one winner; mutations/webhooks are idempotent; request expiry,
rejection, cancellation, replacement-request reschedule, no-show, dispute, and
completion apply the approved credit rule exactly once. `PLUS` to `PREMIUM` is
the only upgrade; downgrade and user refund APIs are unsupported. No earning is
created for `SESSION_ENDED`, cancellation, no-show, or dispute. Appointment
existence never grants health/journal access; sharing requires an approved
pre-session brief and grant.

### UC-05 Communication & Follow-up

**Actors:** User, Specialist, Admin, System

**Scope:** eligible consultation conversations, realtime
messages/receipts/tombstones/reports, follow-up/check-ins/reassessment,
wellbeing digest, opt-in resource reminders, one-time appointment reminders,
and progress comparison.

**Main flow:** Realtime authorizes an eligible relationship, persists a message
before acknowledgement and uses Redis only for ephemeral fan-out. Care records
milestones/check-ins and repeated assessments. Content/Notification schedules
and persists at most one default wellbeing digest per user/day, separately
opted-in resource reminders, one appointment reminder approximately one hour
before start, and delivery attempts. AI may phrase approved facts but cannot
decide scheduling.

**Exceptions and acceptance:** reconnect restores missed state through
cursor-based REST history; duplicate `clientMessageId` returns the original
message; Redis/Kafka/provider failure cannot lose durable chat/domain state.
No automatic safety email is created. Reports expose only minimal moderation
context, and follow-up charts avoid diagnostic or causal claims.

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

- specialist approval/profile/search without verification-document upload, using the approved non-clinical ranking order;
- `PLUS`/`PREMIUM` VND purchase/upgrade and consultation-credit workflow
  defined by ADR 0017;
- in-app chat/video availability plus race-safe 60-minute appointment booking;
- appointment-scoped `ConsultationBrief` consent and specialist view;
- evidence-based consultation completion, user-approved reuse of
  `SessionSummary`/`AgreedNextSteps`, chat, reminders, reviews, and follow-up.

### Research/governance release (iteration 4)

- moderation and account deletion orchestration;
- aggregate reporting and retention configuration;
- isolated provider-neutral benchmark dataset pipeline, with PhoBERT comparison optional after its activation gate passes.

In-app video is the second approved v2 mode but remains unavailable until its
call/signaling/provider/security/evidence contract passes. New in-person, phone,
external meeting links, room management, social/community feeds, organization
tenancy, automatic emergency dispatch, custom model training, automated cash
refunds, and Kubernetes are out of scope unless formally added. Real MoMo
payment/payout remains disabled until exact VND prices, fixed
`creditAllocation`, credentials, settlement, retention, and reconciliation
requirements are approved.

## 6. Open product decisions

These remain open for the affected production or optional feature. Under ADR 0010 they do not block a base questionnaire that has passed the controlled Capstone publication gate:

SupportPlan template ownership, core/optional slots, 1-5 resource bounds,
deterministic composition, safety-positive activation, `PRIMARY`/`ADJUNCT`
eligibility roles, and per-user lifecycle invariants are no longer open. They
were approved on 2026-09-13 in [ADR 0013](adr/0013-freeze-support-plan-policy-v1.md)
and [SupportPlan policy v2](policies/support-plan-policy-v2.md). Their provider,
runtime, and production-review gates remain open.

1. The current `mb-support-routing-capstone-v1` runtime remains immutable historical coarse routing. Compatible domain-bearing SupportEvaluation evolution, any automatic latest-result freshness window, and downstream plan use are tracked by issue #48. PHQ-9 item-9 core behavior is already executable through `MB-SAFETY-PHQ9-001`.
2. Specialist profile/approval/discovery policy is fixed by ADR 0014 and
   amended by ADR 0017. Compatible v2 APIs, persistence, seeded-demo data, and
   frontend implementation remain delivery work.
3. Exact Vietnamese production safety/disclaimer wording, directory source
   governance, and whether a specific emergency number may appear as versioned
   safety content. Directory entries must already meet ADR 0017 provenance and
   verification requirements.
4. Retention and amendment behavior for user-visible `SessionSummary` and
   `AgreedNextSteps`; user approval before reuse is already fixed by ADR 0017.
5. Minimum user age and guardian/consent behavior if expansion includes users under 18.
6. Consent text/versioning, retention periods, deletion SLA, export scope, and applicable Vietnamese regulation review before public real-user data collection. Synthetic controlled demos do not require these values to publish a questionnaire.
7. Chat/video duration and the separation of `SESSION_ENDED` from completion
   are fixed by ADR 0017. Video signaling/provider, presence evidence,
   recording prohibition, reconnect/failure behavior, and exact completion
   evidence remain open contract work.
8. Dataset licenses, label mapping, train/test leakage controls, and research ethics approval. PhoBERT additionally requires an approved narrow classification task, deterministic preprocessing, and a compatible versioned fine-tuned checkpoint before implementation.
9. Exact MoMo request type/payment methods, credential/key rotation, settlement
   delay, payout onboarding, VND `PLUS`/`PREMIUM` prices, fixed
   `creditAllocation`, chargeback reconciliation, and financial retention.
   Runtime FX, downgrade, and user refund remain unsupported.
10. Whether WBS 28-29 are end-user/research benchmark views distinct from admin WBS 155-156, or duplicate functions that should share one admin-only workflow.
The domain correction is recorded in [ADR 0012](adr/0012-two-domain-screening-and-system-proposed-support-plans.md); current cross-feature scope is governed by [ADR 0017](adr/0017-product-scope-v2.md).
