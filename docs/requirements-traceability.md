# Requirements Traceability

This document maps the approved project sources to the backend architecture. The source documents remain authoritative for product scope:

- [Capstone registration](<../FA26_TraLTB_MentalBridge (1).docx>)
- [Project tracking workbook](../Report3_Project%20Tracking.xlsx), especially the 162 WBS functions, seven delivery use cases, and actor flows

The registration file contains student/supervisor contact details. Confirm repository visibility and team approval before publishing the binary; this traceability document intentionally does not reproduce those personal details.

Architecture may add safety, privacy, reliability, and implementation constraints, but must not silently remove a required feature. Any scope change requires supervisor/product approval and an ADR or updated tracking source.

## Product and technology alignment

| Source commitment | Architecture coverage |
| --- | --- |
| Consumer product for Vietnamese users aged 18–30 | User/guest APIs, `vi-VN` locale baseline, mobile-facing REST plus realtime WebSocket |
| V1 screening and post-screening scope | Exactly PHQ-9/`DEPRESSIVE_SYMPTOMS` and GAD-7/`ANXIETY_SYMPTOMS`; another domain is a separately approved product vertical |
| React web admin and end-user mobile application | Edge proxy exposes REST/JSON; only Realtime Service exposes WSS |
| Spring Boot and Node.js backend | Three Spring Boot and three NestJS/TypeScript services as fixed by ADR 0001 and ADR 0006 |
| PostgreSQL and MongoDB | PostgreSQL owns relational transactions; MongoDB owns journals, analysis documents, conversations/messages |
| Gemini/OpenAI through prompt engineering | Node.js Journal/AI provider adapters with strict versioned output schemas; no fine-tuning |
| PhoBERT inference-only comparison | Deferred optional Vietnamese NLP baseline under ADR 0011; activation requires an approved task, labels, governed data, preprocessing, and a pinned compatible fine-tuned checkpoint |
| Microservices and API integration | Owner databases, OpenAPI REST/JSON DTOs, Eureka discovery for Spring services, OpenFeign Java REST clients, Kafka async contracts, no cross-service table access |
| AWS EC2, Docker, Nginx, Docker Compose, GitHub Actions | Deployment baseline retained; Kafka and Redis included in local/hosted composition |
| Grafana, Prometheus, Swagger/OpenAPI | Metrics/observability and contract rules are required by engineering guides |
| Privacy, consent, audit, deletion and retention | Care consent owner, owner-enforced authorization, minimized audit projection, idempotent deletion workflow |
| Premium subscription, payment, upgrade, consultation credits, specialist earnings and payout history | Consultation/Billing ownership and core lifecycle are fixed by ADR 0005; downgrade/refund are unsupported; MoMo is the sole production payment/payout provider |

Kafka and Redis are architecture additions supporting realtime and asynchronous workloads. Kafka is the durable event/task backbone. Redis is limited to ephemeral presence/routing/fan-out, rate-limit, delivery/idempotency, and expiring hashed OTP state; it is not a database-query cache and never replaces PostgreSQL, MongoDB, or Kafka.

## WBS ownership map

| WBS functions | Functional area | Authoritative owner | Main integration/storage |
| --- | --- | --- | --- |
| 1–5, 7, 111, 113–116 | registration, login/logout, reset, RBAC, admin login and user administration | Identity Service (Spring) | PostgreSQL; REST/JWT; account Kafka events |
| 6, 20–24 | anonymous/authenticated PHQ-9/GAD-7, results, history and deletion | Care Service (Spring) | PostgreSQL with expiry/retention policy; deterministic scoring |
| 8–14 | profile, consent, specialist grants and deletion request | Care for profile/consent; Identity coordinates deletion | REST owner checks; Kafka deletion fan-out |
| 15–19, 25–27 | journal CRUD, LLM analysis/result/re-run | Journal/AI Service (Node.js) | MongoDB plus PostgreSQL job/outbox; current Care consent |
| 28–29, 150–156 | benchmark execution/results and dataset administration | Journal/AI; optional future PhoBERT inference only | Private object storage, MongoDB/PostgreSQL metadata, provider adapters, and Kafka jobs/results when required |
| 30–31, 89–94, 98–101 | safety/support intervention, follow-up and personal analytics | Care; Journal/AI supplies approved structured indicators | Local deterministic safety, approved support policy and bounded projections |
| 32–35, 95–97, 130–139 | self-help content, notification history and content administration | Content/Notification Service (Node.js) | PostgreSQL; reviewed content and durable notification state; no hotline catalogue |
| 36–41 | specialist discovery, filtering and matching | Consultation Service (Spring) | PostgreSQL; transparent versioned matching criteria |
| 42–51 | subscription plans, payment, subscription state, Care-to-Plus upgrade and consultation-credit ledger | Consultation/Billing | PostgreSQL authority; signed payment webhook; exact minor-unit upgrade offset; no downgrade/refund |
| 52–61, 117–122 | specialist profile, approval, availability and administration | Consultation Service | PostgreSQL with audited profile approval; WBS 54 document upload removed by the 2026-08-21 product decision |
| 62–73, 148–149 | booking, transitions, history and admin monitoring | Consultation/Billing | Specialist-authored slot, snapshotted appointment, credit and earning transitions are race-safe and locally transactional |
| 74–82, 145–147 | conversations/chat/receipts/tombstone/report and message moderation | Realtime Service (Node.js) | MongoDB truth; Redis ephemeral fan-out; Kafka facts |
| 83–88, 140–144 | specialist reviews and moderation | Consultation Service | PostgreSQL; minimized evidence and audit events |
| 102–107 | specialist dashboard and consented user data | Consultation composes workload; Care and Journal/AI own sensitive data | Current owner authorization; no shared DB |
| 108–110, 123–129 | specialist earnings/payout views and administration | Consultation/Billing | MoMo-only payout request/history after M4B credentials and VND/FX decision; MoMo-shaped fake for local/CI |
| 112, 157–158 | admin dashboard, activity and platform trends | Owner-specific projections; financial facts from Consultation/Billing | Bounded queries; freshness/cohort protections; no runtime distributed join |
| 159–162 | audit search and retention policy | Identity coordinates minimized projection; every owner enforces its policy | Kafka audit facts and owner administration |

## Actor-flow coverage

- **Anonymous:** one supported-domain questionnaire → instrument-specific result → screening/safety guidance → optional register. Anonymous data is never silently linked to the new account.
- **User:** assessment/journal → analysis → instrument/domain-specific screening plus independent safety → domain-aware SupportEvaluation → system-proposed draft → bounded choice/revalidation/explicit activation → optional specialist discovery → premium/payment/credit or Care-to-Plus upgrade → choose a specialist-authored slot → scoped consent → appointment chat only during that slot → review → follow-up/analytics.
- **Specialist:** register → complete profile → admin approval → publish channel-specific availability → appointments/consented data → consult during the scheduled window → complete session → earnings/provider-payout projection → reviews.
- **Admin:** login → bounded dashboard → accounts/specialists → subscriptions/payments/payouts → reviewed content → moderation → appointments → datasets/evaluation → reporting/audit/retention.

## Safety clarifications added by architecture

These constraints refine rather than contradict the source documents:

- PHQ-9/GAD-7 scoring is authoritative and deterministic; AI is a supporting indicator.
- PHQ-9 and GAD-7 bands remain instrument/domain-specific; no combined score or global mental-health severity exists.
- Safety is a cross-cutting PHQ-9 item-9 layer, not another screening domain, and never changes a questionnaire band.
- Reviewed/published content is not automatically eligible for a SupportPlan; future eligibility must be domain-, band-, pathway-, locale-, version-, and effective-window-aware.
- The system proposes a bounded SupportPlan draft; the user controls allowed choices and explicit activation but does not author an arbitrary initial resource set.
- AI output cannot downgrade or change a deterministic safety status.
- Approved safety guidance is returned synchronously and remains available when AI, Kafka, Redis, WebSocket, email, or push delivery fails.
- The platform provides screening/referral support, not diagnosis, treatment, continuous monitoring, or guaranteed emergency response.
- Admin dashboards do not imply unrestricted raw journal/chat access.
- Production journals are excluded from research benchmarks by default; explicit governed consent is required for any exception.

## Baseline design gaps to resolve during module contracts

The requirements are represented in domain/architecture documentation, but the logical database baseline still needs explicit aggregates or decisions before the affected iteration is implemented:

1. Referral status/history for the Moderate intervention path.
2. Specialist matching configuration, result provenance, and recommendation explanation.
3. Notification templates and provider delivery-attempt history for email/push retries.
4. Dedicated read projections for personal analytics, admin activity, platform trends, and consolidated audit search.
5. Explicit anonymous-assessment expiry/cleanup configuration and deletion evidence.
6. Moderation evidence snapshot/access policy and appeal/action history.
7. Dataset metadata edit semantics: immutable version replacement versus narrowly editable administrative metadata.
8. Exact MoMo payment method/request type, credentials/key rotation, status-query schedule, settlement delay, chargeback reconciliation, payout onboarding, VND plan prices or versioned FX policy, and financial retention. ADR 0005 and the billing specification already fix the MoMo-only IPN field/signature contract, ownership, plan values, Care-to-Plus upgrade math, no downgrade/refund, credit transitions, earnings, and payout states.
9. WBS 28-29 and 155-156 both describe running/viewing AI benchmark evaluation; confirm whether they are different actor views or duplicate catalogue entries before defining contracts.
10. Whether SupportPlan templates are persisted, how required/optional resources and choice bounds work, whether selection is code or persisted mapping, and whether safety-positive activation needs additional confirmation. Issue #49 owns these decisions.
11. Primary-domain versus cross-domain adjunct resource eligibility and its versioned contract shape. Issue #50 owns this decision.

Agents must not invent these behaviors independently. Resolve the relevant rule through product/domain review, then update the contract, data dictionary, migration, tests, and this traceability document together.

## Approved scope changes

- 2026-09-12: `MB-SCOPE-DOMAIN-001` limits V1 screening and post-screening support to PHQ-9/`DEPRESSIVE_SYMPTOMS` and GAD-7/`ANXIETY_SYMPTOMS`, keeps safety cross-cutting, prohibits global severity, and changes the forward SupportPlan flow to a domain-aware system proposal followed by bounded user choice, revalidation and explicit activation. Active v1 contracts/history remain immutable; #48–#51 track compatible evaluation, plan, resource and frontend work. See [ADR 0012](adr/0012-two-domain-screening-and-system-proposed-support-plans.md).
- 2026-09-11: ADR 0011 keeps benchmark execution provider-neutral and defers `phobert-worker` as an optional Vietnamese NLP baseline. Initial AI implementation and benchmark work may use OpenAI and Gemini without a Python worker. PhoBERT activation requires an approved narrow task, label taxonomy, governed dataset/evaluation split, deterministic preprocessing, and a pinned compatible fine-tuned checkpoint; it is not a current Compose, readiness, Sprint, or release dependency.
- 2026-09-06: MB-205 implements authenticated descriptive assessment progress in Care and the web client. The selected owned result is compared only with the immediately preceding non-voided same-instrument result using an identical scoring version and deterministic `(submittedAt, assessmentId)` ordering. The output is limited to versioned score/band/duration facts and arithmetic direction; anonymous access, clinical/causal interpretation, safety-resolution claims and optional-service side effects remain unavailable. Its durable behavior is recorded in the Care contract, migrations, tests, module documentation, Jira, and merged pull-request history.
- 2026-09-02: MB-179 adopts the imported Story description as the authoritative Review 1 summary. The controlled Capstone targets people aged 18–30 in Vietnam as a product cohort, not a medical cutoff. PHQ-9 is Care-authoritative and Capstone-published; GAD-7 engineering may proceed without external domain approval but remains unpublished until exact `vi-VN` mapping, reference data and tests pass. Support routing, specialist handoff, follow-up and descriptive progress are definition-complete boundaries and must remain explicitly unavailable until their separate runtime gates pass. Sprint 2 validation uses synthetic/test data; public real-user and specialist-sharing deployment remains blocked on its production reviews. The durable decisions are represented by ADRs 0009 and 0012, the Care policies, the domain model, Jira, and merged pull-request history.
- 2026-08-26: each account has exactly one immutable actor role. Public registration creates only `USER` or `SPECIALIST`; one dedicated `ADMIN` account is provisioned operationally, and no user/specialist promotion or runtime role replacement is in scope. ADR 0008 records the compatibility and schema consequences.
- 2026-08-21: remove WBS 54, `Upload Specialist Verification Document`. Consultation retains specialist profile submission and audited admin approval, but no verification-file table, object metadata, upload endpoint, or review flow.
- 2026-08-21: support immediate Premium Care to Premium Plus upgrade using ADR 0005's remaining-time and unused-credit offset. Downgrade and user-initiated refund are unsupported.
- 2026-08-21: select MoMo as the sole production provider for subscription payment and specialist payout. Local/CI uses MoMo-shaped fakes; real enablement requires credentials and compatible VND plan pricing or an approved FX policy.
- 2026-08-21: consultation messaging is appointment-scoped and writable only during the specialist's snapshotted slot. `IN_APP_VIDEO` is planned but requires a later contract before enablement.
- 2026-08-22: supersede the Express-only ADR 0003 choice with NestJS 11 for all three Node.js services through ADR 0006.
