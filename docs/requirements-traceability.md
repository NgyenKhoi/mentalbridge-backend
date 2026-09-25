# Requirements Traceability

This document maps the approved project sources to the backend architecture. For current product behavior, start with the [Current Product Blueprint](CURRENT_PRODUCT_BLUEPRINT.md) and follow its authority chain: latest accepted scope ADR/amendment, current approved domain policy, versioned contract and owner migration, integrated domain/architecture documentation, runtime evidence, then Jira delivery state.

The original project sources remain traceability inputs:

- [Capstone registration](<../FA26_TraLTB_MentalBridge (1).docx>)
- [Project tracking workbook](../Report3_Project%20Tracking.xlsx), especially the 162 WBS functions, seven delivery use cases, and actor flows

The registration file contains student/supervisor contact details. Confirm repository visibility and team approval before publishing the binary; this traceability document intentionally does not reproduce those personal details.

Architecture may add safety, privacy, reliability, and implementation constraints, but must not silently remove a required feature. Any scope change requires supervisor/product approval and an accepted ADR or amendment. Jira records delivery; it does not redefine the business rule.

## Product and technology alignment

| Source commitment                                                                                           | Architecture coverage                                                                                                                                                               |
| ----------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Consumer product for Vietnamese users aged 18–30                                                            | User/guest APIs, `vi-VN` locale baseline, mobile-facing REST plus realtime WebSocket                                                                                                |
| V1 screening and post-screening scope                                                                       | Exactly PHQ-9/`DEPRESSIVE_SYMPTOMS` and GAD-7/`ANXIETY_SYMPTOMS`; another domain is a separately approved product vertical                                                          |
| React web admin and end-user mobile application                                                             | Edge proxy exposes REST/JSON; only Realtime Service exposes WSS                                                                                                                     |
| Spring Boot and Node.js backend                                                                             | Three Spring Boot and three NestJS/TypeScript services as fixed by ADR 0001 and ADR 0006                                                                                            |
| PostgreSQL and MongoDB                                                                                      | PostgreSQL owns relational transactions and Care consent; MongoDB owns all Journal/AI operational data including jobs/results, plus Realtime conversations/messages                 |
| Gemini/OpenAI through prompt engineering                                                                    | Node.js Journal/AI provider adapters with ADR 0015 normalized output, one provider per run, bounded retry, and no raw-response persistence; no fine-tuning                          |
| PhoBERT inference-only comparison                                                                           | Deferred optional Vietnamese NLP baseline under ADR 0011; activation requires an approved task, labels, governed data, preprocessing, and a pinned compatible fine-tuned checkpoint |
| Microservices and API integration                                                                           | Owner databases, OpenAPI REST/JSON DTOs, Eureka discovery for Spring services, OpenFeign Java REST clients, Kafka async contracts, no cross-service table access                    |
| AWS EC2, Docker, Nginx, Docker Compose, GitHub Actions                                                      | Deployment baseline retained; Kafka and Redis included in local/hosted composition                                                                                                  |
| Grafana, Prometheus, Swagger/OpenAPI                                                                        | Metrics/observability and contract rules are required by engineering guides                                                                                                         |
| Privacy, consent, audit, deletion and retention                                                             | Care consent owner, owner-enforced authorization, minimized audit projection, idempotent deletion workflow                                                                          |
| `FREE`/`PLUS`/`PREMIUM`, VND payment, upgrade, consultation credits, specialist earnings and payout history | ADR 0017 as amended by ADR 0022 fixes the target at `PLUS=4` / `PREMIUM=10`, no rollover, active-reservation caps `2/4`, purchase and `PLUS`-to-`PREMIUM` upgrade only, 70% of fixed `creditAllocation`, and MoMo-only real payment/payout; historical `consultation-credit-v1` `0/1/3` periods remain immutable |

Kafka and Redis are architecture additions supporting realtime and asynchronous workloads. Kafka is the durable event/task backbone only for features that meet ADR 0016's asynchronous/fan-out/replay criteria; synchronous owner-local features do not depend on it. Redis is limited to ephemeral presence/routing/fan-out, rate-limit, delivery/idempotency, and expiring hashed OTP state; it is not a database-query cache and never replaces PostgreSQL, MongoDB, or Kafka where those dependencies are actually selected.

## WBS ownership map

| WBS functions         | Functional area                                                                                            | Authoritative owner                                                        | Main integration/storage                                                                                                                                                                               |
| --------------------- | ---------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1–5, 7, 111, 113–116  | registration, login/logout, reset, RBAC, admin login and user administration                               | Identity Service (Spring)                                                  | PostgreSQL; REST/JWT; account Kafka events                                                                                                                                                             |
| 6, 20–24              | anonymous/authenticated PHQ-9/GAD-7, results, history and deletion                                         | Care Service (Spring)                                                      | PostgreSQL with expiry/retention policy; deterministic scoring                                                                                                                                         |
| 8–14                  | profile, consent, specialist grants and deletion request                                                   | Care for profile/consent; Identity coordinates deletion                    | REST owner checks; Kafka deletion fan-out                                                                                                                                                              |
| 15–19, 25–27          | journal CRUD, AI Companion analysis/result/re-run                                                          | Journal/AI Service (Node.js)                                               | Exact-revision request, current Care consent, Consultation-owned package lookup, MongoDB durable job/lease, versioned one-provider route, normalized MongoDB result; no Journal/AI PostgreSQL or Kafka |
| 28–29, 150–156        | benchmark execution/results and dataset administration                                                     | Journal/AI; optional future PhoBERT inference only                         | Private object storage, MongoDB-owned metadata and provider adapters; production-provider and publication design remain gated                                                                          |
| 30–31, 89–94, 98–101  | safety/explicit help, Support Guide, SupportPlan, follow-up and personal analytics                         | Care; Journal/AI supplies approved structured indicators                   | Local safety authority, one-time all-tier guide, one paid official plan, area-directory result, and bounded projections                                                                                |
| 32–35, 95–97, 130–139 | self-help content, area directory, reminders, notification history and content administration              | Content/Notification Service (Node.js)                                     | PostgreSQL; reviewed resources/directory provenance, at-most-daily digest, opt-in resource reminders, separate appointment reminder, durable delivery state                                            |
| 36–41                 | specialist discovery, filtering and matching                                                               | Consultation Service (Spring)                                              | Approved profiles only; transparent ADR 0014 non-clinical ranking order and seeded-demo disclosure                                                                                                     |
| 42–51                 | `FREE`/`PLUS`/`PREMIUM`, VND purchase/payment, `PLUS`-to-`PREMIUM` upgrade, and consultation-credit ledger | Consultation/Billing                                                       | PostgreSQL authority; signed MoMo webhook; exact VND minor-unit upgrade offset; no downgrade/user-refund API                                                                                           |
| 52–61, 117–122        | specialist profile, approval, historical location requirement, online availability and administration      | Consultation Service                                                       | PostgreSQL with audited stable approval/suspension reasons; MB-362 v2 availability has no PracticeLocation; no verification/license workflow; WBS 54 document upload removed                           |
| 62–73, 148–149        | in-app chat/video booking, transitions, evidence, history and read-only admin monitoring                   | Consultation/Billing                                                       | 60-minute slot; channel closes at `SESSION_ENDED`; server/provider evidence gates completion, credit consumption, and earning                                                                          |
| 74–82, 145–147        | conversations/chat/receipts/tombstone/report and message moderation                                        | Realtime Service (Node.js)                                                 | MongoDB truth; Redis ephemeral fan-out; Kafka facts                                                                                                                                                    |
| 83–88, 140–144        | specialist reviews and moderation                                                                          | Consultation Service                                                       | PostgreSQL; minimized evidence and audit events                                                                                                                                                        |
| 102–107               | specialist dashboard and consented user data                                                               | Consultation composes workload; Care and Journal/AI own sensitive data     | Current owner authorization; no shared DB                                                                                                                                                              |
| 108–110, 123–129      | specialist earnings/payout views and administration                                                        | Consultation/Billing                                                       | MoMo-only payout after credentials plus approved VND price/`creditAllocation`; no runtime FX; MoMo-shaped fake for local/CI                                                                            |
| 112, 157–158          | admin dashboard, activity and platform trends                                                              | Owner-specific projections; financial facts from Consultation/Billing      | Bounded queries; freshness/cohort protections; no runtime distributed join                                                                                                                             |
| 159–162               | audit search and retention policy                                                                          | Identity coordinates minimized projection; every owner enforces its policy | Kafka audit facts and owner administration                                                                                                                                                             |

## Actor-flow coverage

- **Anonymous:** one supported-domain questionnaire → instrument-specific result → screening/safety guidance → optional register. Anonymous data is never silently linked to the new account.
- **User:** assessment/journal → optional AI Companion → screening/safety → one-time Support Guide → optional paid SupportPlan → explicit activation/change confirmation → specialist discovery → VND purchase/upgrade/credit → request a 60-minute in-app chat/video slot → approve pre-session brief → bounded session → completion evidence → approve summary reuse → review/follow-up.
- **Specialist:** register → complete public profile → admin approval → publish chat/video availability → accept/reject bounded requests → read only the approved pre-session brief → consult during the scheduled window → create post-session `SessionSummary`/`AgreedNextSteps` → optionally submit `PlanChangeRequest` → earnings/provider-payout projection after evidence-backed completion → reviews.
- **Admin:** login → specialist approval/suspension → discovery/recommendation operations → read-only appointment monitoring → Identity account-state administration → bounded content/moderation/reporting/audit. Admin uses owner APIs and receives no raw journals, answers, or private chat. Dataset/benchmark and financial administration remain deferred batches.

## Safety clarifications added by architecture

These constraints refine rather than contradict the source documents:

- PHQ-9/GAD-7 scoring is authoritative and deterministic; AI is a supporting indicator.
- PHQ-9 and GAD-7 bands remain instrument/domain-specific; no combined score or global mental-health severity exists.
- Safety is activated by positive PHQ-9 item 9 or explicit “Tôi cần hỗ trợ ngay”, not by a `High`/`Severe` band alone, and never changes a questionnaire band.
- Reviewed/published content is not automatically eligible for a SupportPlan; future eligibility must be domain-, band-, pathway-, locale-, version-, and effective-window-aware.
- The system proposes a bounded SupportPlan draft; the user controls allowed choices and explicit activation but does not author an arbitrary initial resource set.
- AI output cannot downgrade or change a deterministic safety status.
- Approved safety guidance is returned synchronously and remains available when AI, Kafka, Redis, WebSocket, email, or push delivery fails.
- Area-filtered directory results require source/provenance, review/verification timestamps, address, phone, coverage, and active state; without coordinates/distance they never claim “nearest”.
- Safety never automatically calls, shares location, emails an alert, or notifies a third party.
- The platform provides screening/referral support, not diagnosis, treatment, continuous monitoring, or guaranteed emergency response.
- Admin dashboards do not imply unrestricted raw journal/chat access.
- Production journals are excluded from research benchmarks by default; explicit governed consent is required for any exception.

## Baseline design gaps to resolve during module contracts

The requirements are represented in domain/architecture documentation, but the logical database baseline still needs explicit aggregates or decisions before the affected iteration is implemented:

1. Referral status/history for the Moderate intervention path.
2. Discovery persistence and API shape for the accepted ADR 0014 ranking order, result provenance, explanation, and seeded-demo disclosure.
3. Notification templates, at-most-daily digest deduplication, explicit
   per-resource opt-in, one-time appointment reminders, quiet-hour behavior,
   and provider delivery-attempt history.
4. Dedicated read projections for personal analytics, admin activity, platform trends, and consolidated audit search.
5. Explicit anonymous-assessment expiry/cleanup configuration and deletion evidence.
6. Moderation evidence snapshot/access policy and appeal/action history.
7. Dataset metadata edit semantics: immutable version replacement versus narrowly editable administrative metadata.
8. Exact MoMo payment method/request type, credentials/key rotation,
   status-query schedule, settlement delay, chargeback reconciliation, payout
   onboarding, VND `PLUS`/`PREMIUM` prices, fixed `creditAllocation`, and
   financial retention. ADR 0017 prohibits runtime FX, downgrade, and user
   refund APIs and gates earnings on evidence-backed completion.
9. WBS 28-29 and 155-156 both describe running/viewing AI benchmark evaluation; confirm whether they are different actor views or duplicate catalogue entries before defining benchmark/admin contracts. This does not block ADR 0015 contract, adapter, or async-job implementation.
   Agents must not invent these behaviors independently. Resolve the relevant rule through product/domain review, then update the contract, data dictionary, migration, tests, and this traceability document together.

## Approved scope changes

- 2026-09-24: `MB-SCOPE-V2-002` prospectively amends the current product
  blueprint. A Support Guide is persisted immutable history for one screening
  context, not ephemeral data and not a lifecycle-tracked SupportPlan.
  Reassessment keeps Screening change, Journal context, Plan engagement, and
  explicit user-authored Self-reported experience as four separate dimensions;
  occurrence helpfulness/reflection is supporting evidence only. Care freshly
  revalidates the current plan and alternatives into three canonical outcomes,
  and reassessment never mutates a plan automatically. Consultation records a
  specialist's exact-version resource proposal, while Care owns the governed
  `PlanChangeRequest` and applies a change only after user confirmation. New
  `consultation-credit-v2` periods target `FREE=0`, `PLUS=4`, `PREMIUM=10`, no
  rollover, and concurrent active-reservation caps `0/2/4`; historical v1
  `0/1/3` periods remain immutable. MB-558 implements the credit-v2 allocation,
  distinct reservation-cap response/enforcement, race-safe capacity check, and
  atomic replacement-request path. MB-559 adds canonical explicit reassessment
  self-report without rewriting v1 snapshots. See
  [ADR 0022](adr/0022-current-product-blueprint-amendments.md), the
  [SupportPlan policy v2](policies/support-plan-policy-v2.md), and the
  [Consultation policy v2](policies/consultation-specialist-policy-v2.md).
- 2026-09-22: MB-376 adds an owner-only SupportPlan engagement workspace on
  exact scheduled occurrences. Care accepts idempotent optimistic replacement,
  reopen, hide/show, helpfulness, coded barriers, bounded private reflection,
  and deletion that resets mutable engagement while retaining schedule/source
  provenance. A minimized atomic event excludes reflection and requires
  explicit per-occurrence summary-reuse approval; specialists receive no
  continuous checklist endpoint. Reassessment may consume the coded evidence
  only as the separate SupportPlan-engagement dimension, never as adherence,
  treatment success, clinical improvement, or a recovery score. See
  [ADR 0021](adr/0021-support-plan-engagement.md) and
  [MB-376 evidence](story-mb-376-support-plan-engagement-evidence.md).
- 2026-09-21: MB-513 implements Care-owned
  `support-plan-activity-schedule-v1` schedules and persisted occurrences for
  the single active/paused SupportPlan. The owner APIs expose bounded local-date
  windows, exact source/version provenance, and explicit user occurrence
  complete/skip input. Deterministic identity plus database uniqueness prevents
  retry/reload duplicates; schedules respond consistently to lifecycle changes.
  MB-374 owns the pause/resume/complete/discard journey and immutable terminal
  history. Timezone/DST, replacement effects, stale versions, and concurrency
  have focused coverage. Journal/emotion prompts remain distinct self-reported
  wellbeing sources, never treatment adherence. AI cannot select lifecycle or
  occurrence state. See [ADR 0020](adr/0020-support-plan-activity-occurrence-scheduling.md)
  and [MB-513 evidence](story-mb-513-support-plan-activity-occurrences-evidence.md).
- 2026-09-19: MB-372 implements the first Care-owned SupportPlan runtime slice:
  an authenticated `PLUS`/`PREMIUM` user may request one bounded deterministic
  `DRAFT` from an owned current-policy SupportEvaluation v2 and exact Content
  eligibility. Consultation remains entitlement authority; Care forwards the
  bearer, fails closed for `FREE`, stale or uncertain facts, and opens its
  local transaction only after dependency resolution. The stored snapshot
  includes rationale, safety, entitlement, policy/template, exact resource and
  immutable display provenance; owner-only reload performs no recomputation.
  Idempotency and the Care profile lock converge duplicate/concurrent requests
  on one current draft. AI is absent. Choice mutation, activation,
  replacement, activity, reminders, and `PlanChangeRequest` remain deferred.
  See [MB-372 evidence](story-mb-372-support-plan-draft-evidence.md).
- 2026-09-17: MB-511 implements the registered-user Support Guide across Care,
  Content, and the web BFF/UI. Care deterministically derives an immutable
  `mb-support-guide-capstone-v1` result from exact v2 PHQ-9/GAD-7 evidence,
  persists local synchronous safety plus exact resource/display provenance, and
  exposes owner-only generation/history/detail. Content returns reviewed
  exact-version snapshots. The UI distinguishes the one-time guide from
  SupportPlan, displays stable empty/stale/unavailable states, and uses approved
  Care copy when optional AI phrasing is unavailable. No entitlement gate,
  diagnosis/treatment field, raw answer, plan lifecycle, or AI decision is
  introduced.
- 2026-09-15: `MB-SCOPE-V2-001` standardizes packages as `FREE`, `PLUS`, and
  `PREMIUM`; separates the all-tier one-time Support Guide from the paid durable
  SupportPlan; fixes AI quotas/model routing and the “AI supports, Care decides,
  user confirms” authority; governs specialist resource proposals through
  `PlanChangeRequest`; limits new appointments to 60-minute `IN_APP_CHAT` and
  `IN_APP_VIDEO`; separates channel-end `SESSION_ENDED` from evidence-backed
  completion; and requires user approval before summary reuse. Default
  wellbeing reminders are at most daily, resource-specific reminders are
  opt-in, appointment reminders are separate and once near one hour before,
  and no automatic safety email exists. V2 real money is VND/MoMo only, with
  the original historical one/three paid-credit target and specialist earnings
  equal to 70% of fixed per-credit `creditAllocation`; ADR 0022 prospectively
  supersedes those target quantities without rewriting v1 periods. Real
  enablement waits for prices, allocations, and credentials. Safety activates
  from positive PHQ-9 item 9 or explicit
  “Tôi cần hỗ trợ ngay”; area results require provenance and cannot claim
  “nearest” without coordinates/distance. See [ADR 0017](adr/0017-product-scope-v2.md)
  and the [SupportPlan](policies/support-plan-policy-v2.md),
  [Consultation](policies/consultation-specialist-policy-v2.md), and
  [AI Companion](policies/ai-companion-policy-v2.md) v2 policies.
- 2026-09-15: MB-337 publishes six controlled-demo `vi-VN` content versions under `content-eligibility-v1`, with explicit item-level domain roles, instrument bands, support tiers, locale, effective state, and synthetic administrator provenance. MB-350 inventories the versions, MB-351 records the machine-readable item review and rationale in `contracts/fixtures/content/resource-eligibility-v1-controlled-demo.json`, MB-352 publishes the append-only mapping, and MB-353 verifies coverage and the Care fixture. The matrix supplies separate depression and anxiety psychoeducation/activity `PRIMARY` alternatives, keeps sleep and professional-support preparation `ADJUNCT`, records missing domain declarations as explicit ineligibility, and publishes an ordered Care-compatible exact-version/no-match fixture. Safety guidance, booking, SupportPlan proposal/lifecycle runtime, and type-based automatic eligibility remain outside this data story.
- 2026-09-14: ADR 0016 makes Kafka and OpenTelemetry feature-scoped rather than blanket service dependencies. Synchronous owner-local CRUD/approval uses REST and the owner database without Kafka/outbox/broker tests. OpenTelemetry is added only for a demonstrated distributed or asynchronous diagnostic need; structured logs, correlation IDs, health/readiness, and focused Prometheus metrics remain the lightweight baseline.
- 2026-09-14: Story 6101 implements the first Consultation runtime slice: specialist-owned save/read/submit of the six approved public profile fields and administrator pending-list/detail/approve with optimistic concurrency and an approval-status audit history. It collects no credential, license, certificate, or verification document. Rejection/suspension/restoration, discovery, availability, and appointments remain separate runtime stories.
- 2026-09-24: MB-360/Story 6102 completes the Consultation-owned specialist exception lifecycle. Rejection and suspension require closed stable reason codes and every transition is audited. Rejected specialists edit and explicitly resubmit the same profile. Suspension atomically withdraws future availability, cancels future not-started appointments, releases each exact held credit, and records both histories; any mismatch rolls back. Restoration returns the specialist to approved without reviving withdrawn or cancelled records. MB-360 extends the MB-378 appointment aggregate with cancellation evidence and serializes booking against specialist suspension.
- 2026-09-17: MB-362 implements approved-specialist publication, owner listing,
  and tombstone withdrawal for exact non-overlapping 60-minute online slots.
  The additive contract and migration accept `IN_APP_CHAT` and capability-gated
  `IN_APP_VIDEO`, store UTC plus an IANA display timezone, persist idempotency,
  and contain no PracticeLocation, phone, or external meeting-link field. See
  [ADR 0019](adr/0019-online-specialist-availability.md).
- 2026-09-23: MB-378 implements the first authoritative appointment slice.
  A `PLUS` or `PREMIUM` user selects one exact 60-minute online slot;
  Consultation locks the slot and earliest-expiring eligible credit in one
  local PostgreSQL transaction, persists a `REQUESTED` snapshot and decision
  deadline, and returns exact idempotent replays. Active slot/credit uniqueness,
  lead time, stale selection, modality mismatch, video disablement, and
  concurrent double-hold behavior fail closed. Specialist decision and expiry
  execution remain MB-379 and consume the persisted deadline handoff.

- 2026-09-13: Story 5103 delivers Content-owned Resource Eligibility v1 for exact immutable resource versions. The additive contract and PostgreSQL model record explicit approved domain, role, instrument band, support tier, locale, policy version, publication state, and effective window; reviewed or published content receives no implicit plan eligibility. Content resolves bounded request-order-stable batches, while the Care adapter applies explicit deadlines, bounded transient-only retry, circuit breaking, strict response attribution, and fail-closed `UNAVAILABLE` outcomes without opening a Care transaction. The delivery does not publish the separate initial demo eligibility matrix or enable SupportPlan proposal/lifecycle runtime.
- 2026-09-13: `MB-AI-COMPANION-001` freezes exact-revision, explicit-request, consent-gated asynchronous journal analysis plus bounded longitudinal journal/context comparison. Each run uses one provider, a 30-second timeout per attempt, and at most one retry for 429/5xx/transport; no automatic cross-provider fallback or raw-response/hidden-reasoning persistence is allowed. Care presents standardized screening trend, model-derived available-journal context trend with coverage, SupportPlan engagement, and user reflection as four separate reassessment dimensions—never one improvement score. AI cannot score, diagnose, decide clinical improvement/eligibility, or mutate a SupportPlan. Benchmark results gate final production-provider selection and official controlled-demo enablement, not contract/adapters/job-runtime implementation. See [ADR 0015](adr/0015-ai-companion-analysis-contract.md) and [AI Companion policy v1](policies/ai-companion-policy-v1.md).
- 2026-09-16: MB-421 amends the executable direction to Mongo-only Journal/AI ownership. MB-367 uses a durable `analysis_jobs` collection with unique idempotency, atomic claim and lease recovery, a separate normalized-result collection, forwarded end-user JWT checks against Care at request/pre-attempt time, and a deterministic fake provider. Care activates independent `AI_PROCESSING` decisions at `ai-processing-capstone-v1`; frontend consent/reflection remains 6203 and real provider/benchmark selection remains 6204/MB-369.
- 2026-09-20: MB-371 implements consented bounded longitudinal context over
  exact current journal revisions selected from two equal 7-31 day periods.
  Mongo-owned jobs/results retain exact source versions, coverage, route, and
  provider provenance without raw text; sparse or greater-than-2:1 imbalanced
  periods force `INSUFFICIENT_DATA`. Owner polling and a minimized current-
  consent Care projection are implemented as the Journal/AI boundary consumed
  by MB-386's Story 6501 Reassessment Summary composition.
- 2026-09-24: MB-386 implements Story 6501 in Care. The authenticated owner
  idempotently composes selected current PHQ-9/GAD-7 results, the canonical
  minimized Journal/AI projection, and explicitly reusable SupportPlan
  engagement/reflection into one immutable `reassessment-summary-v1` snapshot.
  Current/detail/history APIs preserve exact source versions, periods, coverage,
  provenance, and explicit `UNAVAILABLE`/`INSUFFICIENT_DATA` states. Care never
  creates a combined direction, improvement score, recovery claim, or fallback
  trend. Story 6502 remains the separate actor-facing frontend consumer.
- 2026-09-24: MB-559 adds `reassessment-self-report-v1` owner
  create/current/replace/delete behavior and `reassessment-summary-v2`.
  Categorical current experience is explicit and non-diagnostic; optional
  helpful/difficult context is bounded to 500 characters. Summary composition
  snapshots the exact source revision separately from occurrence
  helpfulness/reflection, preserves contradictions, and represents absent or
  deleted sources without inference. Care issues versioned adjacent 14-day
  periods and current compatible assessment IDs, then resolves the supplied
  Journal job status itself. The frontend creates and polls that job, composes
  the snapshot, and renders four separate dimensions with no overall verdict.
  Historical v1 summaries remain immutable; later MB-387 work is limited to
  history and presentation refinement where it is not already satisfied.
- 2026-09-16: MB-369 is bounded to provider/model routing and benchmark
  enablement. Consultation adds an authoritative current-entitlement read model
  with `DEFAULT_FREE`, explicit `DEMO`, and future `PAID` provenance but no
  purchase, upgrade, MoMo, billing-period, credit, or ledger lifecycle.
  Journal/AI forwards the end-user bearer, snapshots one versioned
  workload/package route, never trusts a client tier, and never falls back
  across providers. Gemini/OpenAI real execution remains config-, credential-,
  and benchmark-approval-gated; the harness accepts one or both configured
  candidates without requiring an unused provider credential, and CI uses the
  deterministic fake. AI Chat quota, RAG/vector storage, and PhoBERT are outside
  the Story.
- 2026-09-13: `MB-CONSULTATION-FLOW-001` approved the historical v1
  `IN_APP_CHAT`/`IN_PERSON` flow, completion evidence, brief, summary,
  discovery, and specialist states. ADR 0017 amends new v2 modes to
  `IN_APP_CHAT`/`IN_APP_VIDEO`, while historical records keep v1 provenance.
  See [ADR 0014](adr/0014-appointment-specialist-and-consultation-continuity.md)
  and [Consultation and specialist policy v1](policies/consultation-specialist-policy-v1.md).
- 2026-09-16: `mb-support-routing-capstone-v2` adds owner-scoped domain-aware SupportEvaluation resources, immutable exact PHQ-9/GAD-7 snapshots, independent item-9 safety, per-domain pathways/reasons, request idempotency, and a minimized atomic v2 outbox event under #48. It leaves every v1 API, row, and event unchanged and does not implement SupportPlan lifecycle. See [SupportEvaluation policy v2](policies/support-evaluation-policy-v2.md) and [Story 5102 verification evidence](story-5102-support-evaluation-v2-evidence.md).
- 2026-09-16: MB-510 adds one Journal/AI-owned encrypted daily emotion check-in per owner/local day under `MB-DAILY-EMOTION-CHECK-IN-001`. History is labelled self-reported and non-clinical; optimistic revisions and hashed idempotency cover duplicate/concurrent saves; deletion removes encrypted payloads and retains a bounded tombstone. Only a note-free AI projection is available under current Care `AI_PROCESSING` consent; reminder access remains unavailable pending a separate consent contract. See [ADR 0018](adr/0018-daily-emotion-check-in.md), [policy v1](policies/daily-emotion-check-in-policy-v1.md), and [verification evidence](story-mb-510-daily-emotion-check-in-evidence.md).
- 2026-09-20: MB-373 implements bounded user choice and explicit activation
  for the MB-372 paid SupportPlan draft. Care accepts only exact alternatives
  already admitted for the current slot, permits removal only for optional
  slots, and uses `If-Match` plus natural PUT no-op behavior for choice
  replacement. Immediately before activation, Care revalidates paid
  entitlement, current-compatible SupportEvaluation, template composition, and
  every exact resource version, then atomically records the versioned outcome,
  audit provenance, and minimized activation event. Owner locking plus database
  uniqueness enforce one official current
  plan; the frontend reloads the authoritative current query after activation.
  Pause/resume, completion, replacement, activities, and specialist
  `PlanChangeRequest` remain unavailable. See [SupportPlan policy v2](policies/support-plan-policy-v2.md)
  and [MB-373 evidence](story-mb-373-support-plan-activation-evidence.md).
- 2026-09-24: MB-375 implements truthful SupportPlan replacement after
  reassessment. Care returns one of the three policy outcomes while exposing
  screening, Journal context, plan engagement, and explicit self-report as
  separate evidence with periods and provenance. The current plan remains
  usable until an explicit idempotent confirmation repeats all authoritative
  checks and atomically records `SUPERSEDED` plus replacement `ACTIVE` state.
  The command audit links both plan versions and the exact immutable
  `reassessment-summary-v2`; failures and unchanged proposals do not mutate
  either plan. Specialist `PlanChangeRequest` must reuse this governed path and
  has no second replacement mutation. See [MB-375 evidence](story-mb-375-support-plan-replacement-evidence.md).

- 2026-09-25: MB-292 corrective delivery persists Care-owned
  `screening_episode` grouping for guided initial checks and reassessments.
  Frontend session cookies and latest-history lookup are no longer evidence
  authority. SupportPlan proposal requires episode-backed v2 evaluation, and
  replacement fails with `REASSESSMENT_SCREENING_CONTEXT_MISMATCH` when the
  summary and proposed draft do not use the same exact PHQ-9/GAD-7 pair. See
  [ADR 0023](adr/0023-persist-guided-screening-episodes.md).
- 2026-09-13: `MB-SUPPORT-PLAN-POLICY-001` freezes SupportPlan policy v1. Care owns immutable versioned templates and compositional `mb-support-plan-selection-v1` rules; templates use `CORE` and `OPTIONAL` slots; an activatable plan contains 1-5 resources; exact eligibility roles are `PRIMARY` and `ADJUNCT`; safety-positive activation uses the normal explicit action after higher-priority safety/professional presentation; and each user has at most one draft plus one active-or-paused plan with atomic explicit replacement. This product-policy approval did not itself implement SupportEvaluation v2, Resource Eligibility v1, plan persistence, endpoints, or frontend runtime. See [ADR 0013](adr/0013-freeze-support-plan-policy-v1.md) and [SupportPlan policy v1](policies/support-plan-policy-v1.md).
- 2026-09-12: `MB-SCOPE-DOMAIN-001` limits V1 screening and post-screening support to PHQ-9/`DEPRESSIVE_SYMPTOMS` and GAD-7/`ANXIETY_SYMPTOMS`, keeps safety cross-cutting, prohibits global severity, and changes the forward SupportPlan flow to a domain-aware system proposal followed by bounded user choice, revalidation and explicit activation. Active v1 contracts/history remain immutable; #48 tracks compatible evaluation, #49 the plan-policy gate, #50 resource eligibility, and #51 frontend impact. Runtime stories remain separately gated. See [ADR 0012](adr/0012-two-domain-screening-and-system-proposed-support-plans.md).
- 2026-09-11: ADR 0011 keeps benchmark execution provider-neutral and defers `phobert-worker` as an optional Vietnamese NLP baseline. Initial AI implementation and benchmark work may use OpenAI and Gemini without a Python worker. PhoBERT activation requires an approved narrow task, label taxonomy, governed dataset/evaluation split, deterministic preprocessing, and a pinned compatible fine-tuned checkpoint; it is not a current Compose, readiness, Sprint, or release dependency.
- 2026-09-06: MB-205 implements authenticated descriptive assessment progress in Care and the web client. The selected owned result is compared only with the immediately preceding non-voided same-instrument result using an identical scoring version and deterministic `(submittedAt, assessmentId)` ordering. The output is limited to versioned score/band/duration facts and arithmetic direction; anonymous access, clinical/causal interpretation, safety-resolution claims and optional-service side effects remain unavailable. Its durable behavior is recorded in the Care contract, migrations, tests, module documentation, Jira, and merged pull-request history.
- 2026-09-02: MB-179 adopts the imported Story description as the authoritative Review 1 summary. The controlled Capstone targets people aged 18–30 in Vietnam as a product cohort, not a medical cutoff. PHQ-9 is Care-authoritative and Capstone-published; GAD-7 engineering may proceed without external domain approval but remains unpublished until exact `vi-VN` mapping, reference data and tests pass. Support routing, specialist handoff, follow-up and descriptive progress are definition-complete boundaries and must remain explicitly unavailable until their separate runtime gates pass. Sprint 2 validation uses synthetic/test data; public real-user and specialist-sharing deployment remains blocked on its production reviews. The durable decisions are represented by ADRs 0009 and 0012, the Care policies, the domain model, Jira, and merged pull-request history.
- 2026-08-26: each account has exactly one immutable actor role. Public registration creates only `USER` or `SPECIALIST`; one dedicated `ADMIN` account is provisioned operationally, and no user/specialist promotion or runtime role replacement is in scope. ADR 0008 records the compatibility and schema consequences.
- 2026-08-21: remove WBS 54, `Upload Specialist Verification Document`. Consultation retains specialist profile submission and audited admin approval, but no verification-file table, object metadata, upload endpoint, or review flow.
- 2026-08-21: support the historical Premium Care to Premium Plus upgrade
  using ADR 0005's remaining-time and unused-credit offset. ADR 0017 maps the
  v2 path to `PLUS` to `PREMIUM`; downgrade and user refund remain unsupported.
- 2026-08-21: select MoMo as the sole production provider for subscription
  payment and specialist payout. ADR 0017 removes the FX option: real enablement
  requires VND prices, fixed `creditAllocation`, and credentials.
- 2026-08-21: consultation messaging is appointment-scoped and writable only
  during the snapshotted slot. ADR 0017 approves `IN_APP_VIDEO` as the only
  second v2 mode, still gated by its contract and implementation evidence.
- 2026-08-22: supersede the Express-only ADR 0003 choice with NestJS 11 for all three Node.js services through ADR 0006.
