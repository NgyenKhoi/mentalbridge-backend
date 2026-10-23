# Approved scope and architecture decisions

[Requirements traceability index](../requirements-traceability.md)

Records retain the wording and recorded order of the original traceability document.

## Record 25

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
  [ADR 0022](../adr/0022-current-product-blueprint-amendments.md), the
  [SupportPlan policy v2](../policies/support-plan-policy-v2.md), and the
  [Consultation policy v2](../policies/consultation-specialist-policy-v2.md).

## Record 31

- 2026-09-15: `MB-SCOPE-V2-001` standardizes packages as `FREE`, `PLUS`, and
  `PREMIUM`; separates the all-tier one-time Support Guide from the paid durable
  SupportPlan; fixes AI quotas/model routing and the “AI supports, Care decides,
  user confirms” authority; governs specialist resource proposals through
  `PlanChangeRequest`; limits new appointments to 60-minute `IN_APP_CHAT` and
  `IN_APP_VIDEO`; separates channel-end `SESSION_ENDED` from evidence-backed
  completion; and requires user approval before summary reuse. Default
  wellbeing reminders are at most daily, resource-specific reminders are
  opt-in, appointment reminders are separate and target exactly 60 minutes
  before start under `MB-APPOINTMENT-EMAIL-REMINDER-001`, and no automatic
  safety email exists. V2 real money is VND/MoMo only, with
  the original historical one/three paid-credit target and specialist earnings
  equal to 70% of fixed per-credit `creditAllocation`; ADR 0022 prospectively
  supersedes those target quantities without rewriting v1 periods. Real
  enablement waits for prices, allocations, and credentials. Safety activates
  from positive PHQ-9 item 9 or explicit
  “Tôi cần hỗ trợ ngay”; area results require provenance and cannot claim
  “nearest” without coordinates/distance. See [ADR 0017](../adr/0017-product-scope-v2.md)
  and the [SupportPlan](../policies/support-plan-policy-v2.md),
  [Consultation](../policies/consultation-specialist-policy-v2.md), and
  [AI Companion](../policies/ai-companion-policy-v2.md) v2 policies.

## Record 33

- 2026-09-14: ADR 0016 makes Kafka and OpenTelemetry feature-scoped rather than blanket service dependencies. Synchronous owner-local CRUD/approval uses REST and the owner database without Kafka/outbox/broker tests. OpenTelemetry is added only for a demonstrated distributed or asynchronous diagnostic need; structured logs, correlation IDs, health/readiness, and focused Prometheus metrics remain the lightweight baseline.

## Record 41

- 2026-09-13: `MB-AI-COMPANION-001` freezes exact-revision, explicit-request, consent-gated asynchronous journal analysis plus bounded longitudinal journal/context comparison. Each run uses one provider, a 30-second timeout per attempt, and at most one retry for 429/5xx/transport; no automatic cross-provider fallback or raw-response/hidden-reasoning persistence is allowed. Care presents standardized screening trend, model-derived available-journal context trend with coverage, SupportPlan engagement, and user reflection as four separate reassessment dimensions—never one improvement score. AI cannot score, diagnose, decide clinical improvement/eligibility, or mutate a SupportPlan. Benchmark results gate final production-provider selection and official controlled-demo enablement, not contract/adapters/job-runtime implementation. See [ADR 0015](../adr/0015-ai-companion-analysis-contract.md) and [AI Companion policy v1](../policies/ai-companion-policy-v1.md).

## Record 47

- 2026-09-13: `MB-CONSULTATION-FLOW-001` approved the historical v1
  `IN_APP_CHAT`/`IN_PERSON` flow, completion evidence, brief, summary,
  discovery, and specialist states. ADR 0017 amends new v2 modes to
  `IN_APP_CHAT`/`IN_APP_VIDEO`, while historical records keep v1 provenance.
  See [ADR 0014](../adr/0014-appointment-specialist-and-consultation-continuity.md)
  and [Consultation and specialist policy v1](../policies/consultation-specialist-policy-v1.md).

## Record 48

- 2026-09-16: `mb-support-routing-capstone-v2` adds owner-scoped domain-aware SupportEvaluation resources, immutable exact PHQ-9/GAD-7 snapshots, independent item-9 safety, per-domain pathways/reasons, request idempotency, and a minimized atomic v2 outbox event under #48. It leaves every v1 API, row, and event unchanged and does not implement SupportPlan lifecycle. See [SupportEvaluation policy v2](../policies/support-evaluation-policy-v2.md) and [Story 5102 verification evidence](../story-5102-support-evaluation-v2-evidence.md).

## Record 61

- 2026-09-13: `MB-SUPPORT-PLAN-POLICY-001` freezes SupportPlan policy v1. Care owns immutable versioned templates and compositional `mb-support-plan-selection-v1` rules; templates use `CORE` and `OPTIONAL` slots; an activatable plan contains 1-5 resources; exact eligibility roles are `PRIMARY` and `ADJUNCT`; safety-positive activation uses the normal explicit action after higher-priority safety/professional presentation; and each user has at most one draft plus one active-or-paused plan with atomic explicit replacement. This product-policy approval did not itself implement SupportEvaluation v2, Resource Eligibility v1, plan persistence, endpoints, or frontend runtime. See [ADR 0013](../adr/0013-freeze-support-plan-policy-v1.md) and [SupportPlan policy v1](../policies/support-plan-policy-v1.md).

## Record 62

- 2026-09-12: `MB-SCOPE-DOMAIN-001` limits V1 screening and post-screening support to PHQ-9/`DEPRESSIVE_SYMPTOMS` and GAD-7/`ANXIETY_SYMPTOMS`, keeps safety cross-cutting, prohibits global severity, and changes the forward SupportPlan flow to a domain-aware system proposal followed by bounded user choice, revalidation and explicit activation. Active v1 contracts/history remain immutable; #48 tracks compatible evaluation, #49 the plan-policy gate, #50 resource eligibility, and #51 frontend impact. Runtime stories remain separately gated. See [ADR 0012](../adr/0012-two-domain-screening-and-system-proposed-support-plans.md).

## Record 63

- 2026-09-11: ADR 0011 keeps benchmark execution provider-neutral and defers `phobert-worker` as an optional Vietnamese NLP baseline. Initial AI implementation and benchmark work may use OpenAI and Gemini without a Python worker. PhoBERT activation requires an approved narrow task, label taxonomy, governed dataset/evaluation split, deterministic preprocessing, and a pinned compatible fine-tuned checkpoint; it is not a current Compose, readiness, Sprint, or release dependency.

## Record 66

- 2026-08-26: each account has exactly one immutable actor role. Public registration creates only `USER` or `SPECIALIST`; one dedicated `ADMIN` account is provisioned operationally, and no user/specialist promotion or runtime role replacement is in scope. ADR 0008 records the compatibility and schema consequences.

## Record 67

- 2026-08-21: remove WBS 54, `Upload Specialist Verification Document`. Consultation retains specialist profile submission and audited admin approval, but no verification-file table, object metadata, upload endpoint, or review flow.

## Record 68

- 2026-08-21: support the historical Premium Care to Premium Plus upgrade
  using ADR 0005's remaining-time and unused-credit offset. ADR 0017 maps the
  v2 path to `PLUS` to `PREMIUM`; downgrade and user refund remain unsupported.

## Record 69

- 2026-08-21: select MoMo as the sole production provider for subscription
  payment and specialist payout. ADR 0017 removes the FX option: real enablement
  requires VND prices, fixed `creditAllocation`, and credentials.

## Record 70

- 2026-08-21: consultation messaging is appointment-scoped and writable only
  during the snapshotted slot. ADR 0017 approves `IN_APP_VIDEO` as the only
  second v2 mode, still gated by its contract and implementation evidence.

## Record 71

- 2026-08-22: supersede the Express-only ADR 0003 choice with NestJS 11 for all three Node.js services through ADR 0006.
