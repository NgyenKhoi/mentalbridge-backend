# Care support routing and SupportPlan policy

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-SUPPORT-CARE-001` |
| Policy version | `1.4-scope-v2` |
| Status | `CONTROLLED CAPSTONE V1 ROUTING PUBLISHED; SCOPE V2 PRODUCT POLICY APPROVED; V2 RUNTIME UNAVAILABLE` |
| Blueprint effective date | 2026-09-02 |
| Product Owner decision | Prior decisions retained; scope v2 amendments approved as `MB-SCOPE-V2-001` on 2026-09-15 |
| Mentor/domain review | Mentor closure review pending; domain review required before executable production routing |
| Owners | Care for selection; Content/Notification for reviewed resource content |
| Applies to | Registered MentalBridge users in Vietnam |
| Related decisions | [ADR 0012](../adr/0012-two-domain-screening-and-system-proposed-support-plans.md), [ADR 0013](../adr/0013-freeze-support-plan-policy-v1.md), [ADR 0015](../adr/0015-ai-companion-analysis-contract.md), and the [ADR 0017 scope v2 amendment](../adr/0017-product-scope-v2.md) |

V1 supports exactly `DEPRESSIVE_SYMPTOMS` through PHQ-9 and `ANXIETY_SYMPTOMS` through GAD-7, focused on generalized anxiety symptoms. Equal bands from the two instruments remain different domain evidence. The system never creates a global mental-health severity, and a new domain requires a separately approved product vertical.

## Independent decision dimensions

The system keeps these values independent:

| Dimension | Meaning | Must not be used as |
| --- | --- | --- |
| `screeningLevel` | Instrument-specific band from one questionnaire version and domain | Diagnosis, global mental-health severity or cross-instrument risk |
| `safetySignal` | PHQ-9 item-9 deterministic screen or explicit “Tôi cần hỗ trợ ngay” action | Suicide intent/urgency classification or a questionnaire band |
| `supportTier` | Approved platform support pathway | `LOW_RISK`, `MEDIUM_RISK`, or `HIGH_RISK` |
| `supportActions` | Versioned approved catalogue selections | AI-generated treatment instructions |
| `entitlementPlan` | Current commercial access | A reason to suppress safety output |

Reserved support-tier names are:

- `SELF_GUIDED_SUPPORT`;
- `PROFESSIONAL_SUPPORT_RECOMMENDED`;
- `SAFETY_FOLLOW_UP_RECOMMENDED`.

The names and mapping establish approved Capstone product vocabulary. Story 1103 makes this bounded routing version executable for authenticated controlled-Capstone use; it does not publish an intervention catalogue, specialist handoff, booking, sharing, or automatic follow-up.

## Published controlled-Capstone routing policy

The definition version is `mb-support-routing-capstone-v1`. An eligible input is a complete immutable result from a published questionnaire explicitly selected in the same user-initiated support evaluation. Sprint 2 does not infer an automatic latest-result window. Unpublished GAD-7 is not an eligible input and is not treated as missing evidence.

| Eligible evidence | PHQ-9 safety | Blueprint support tier |
| --- | --- | --- |
| Missing, foreign, voided, duplicate, incomplete, or incompatible explicit PHQ-9/GAD-7 pair | Not evaluated | Reject; do not infer a latest result or a tier |
| Explicit compatible PHQ-9 and GAD-7 are both Minimal or Mild | PHQ-9 negative | `SELF_GUIDED_SUPPORT` |
| Either explicit compatible result is Moderate or higher | PHQ-9 negative | `PROFESSIONAL_SUPPORT_RECOMMENDED` |
| Explicit compatible PHQ-9 has item 9 `>=1` | Positive | `SAFETY_FOLLOW_UP_RECOMMENDED` |

The item-9 rule affects only support routing and never overwrites the PHQ-9
band. Scope v2 also opens the safety flow when the user explicitly chooses
“Tôi cần hỗ trợ ngay”. A `High` or `Severe` band alone does not. Values `1`,
`2` and `3` do not classify intent, plan, imminence or urgency. GAD-7 has no
equivalent questionnaire rule. Safety is cross-cutting, not a screening domain,
and AI is not an input.

A future automatic latest-assessment calculation must define freshness, version compatibility and missing-input behavior in a new policy version. The governing forward flow is recorded in [ADR 0012](../adr/0012-two-domain-screening-and-system-proposed-support-plans.md) and [domain and use cases](../domain-and-use-cases.md).

Every result returns the exact `policyVersion`, stable ordered reason codes, both evidence references, independent per-instrument levels and Vietnamese 14-day meanings, one bounded next step, and the non-diagnostic limitation. It never creates a composite score. Repeating the same pair under the same policy reproduces the same persisted decision.

The published v1 `supportTier` is a coarse pathway and cannot by itself select a resource or SupportPlan. Existing v1 contracts and records remain immutable. Additive `mb-support-routing-capstone-v2` supplies explicit contributing domains, domain-specific reasons, and independent safety under #48; plan creation remains a later explicit workflow.

The Product Owner-approved minimum local safety fallback for this controlled Capstone version is:

> Nếu bạn cảm thấy mình không an toàn hoặc có nguy cơ gây hại cho bản thân, hãy chủ động liên hệ dịch vụ khẩn cấp hoặc cơ sở y tế phù hợp tại khu vực của bạn.

Care stores this wording locally in the immutable policy data and retains the same runtime fallback. The safety branch therefore does not wait for Content, notification, Kafka, AI, specialist availability, or a downstream network call.

## Intervention catalogue contract

Every selectable item requires at least:

| Field | Purpose |
| --- | --- |
| `activityId` | Stable non-clinical catalogue identifier |
| `title` | Reviewed localized title |
| `type` | Approved action category |
| `domainEligibilityRoles` | Explicit target-domain mapping to `PRIMARY` or `ADJUNCT`; only primary may satisfy a core slot |
| `eligibleInstrumentBands` | Exact instrument and screening-band applicability |
| `eligibleSupportTiers` | Exact tiers allowed to select the item |
| `source` | Evidence or content provenance |
| `reviewedBy` | Accountable reviewer identity |
| `contentVersion` | Immutable content revision |
| `active` | Whether new plans may select it |
| `effectiveAt` / `expiresAt` | Version validity window |
| `locale` | Reviewed BCP 47 locale |

Candidate identifiers for catalogue review include `BREATHING_BASIC_01`, `STRESS_JOURNALING_01`, `SLEEP_HYGIENE_01`, `PSYCHOEDUCATION_DEPRESSION_01`, `PROFESSIONAL_CONSULTATION_CTA`, and `REASSESSMENT_14D`. Their names do not approve their wording, domain eligibility, cadence, or clinical suitability. Cross-domain wellbeing content is possible only when that applicability is explicitly reviewed and versioned.

AI may explain only active approved entries already selected inside a deterministic bounded proposal. It cannot create an activity, determine or change eligibility, choose a SupportPlan template or resource, calculate screening results, change a safety status, or override a deterministic selection constraint.

No personalized intervention item is approved by the MB-179 routing decision. Published generic self-help resources may be displayed independently of personalized routing, but review/publication does not make them universally eligible for a plan.

The approved forward direction is: a one-time Support Guide for every package,
then an optional paid domain-aware SupportPlan. [SupportPlan policy v2](support-plan-policy-v2.md)
retains immutable Care templates, `CORE`/`OPTIONAL` slots, 1-5 selected-resource
bounds, compositional rules, and exact-version eligibility while limiting the
durable plan to `PLUS`/`PREMIUM`. Care owns one official current plan. A
specialist proposal enters through `PlanChangeRequest`; Care revalidates and the
user confirms. The client, AI, and specialist never author or mutate a plan
directly.

At reassessment, Care presents standardized screening trend, AI-derived
available-journal context trend, SupportPlan engagement, and user
helpfulness/reflection as four separate dimensions. It never creates a combined
improvement score. AI context may explain candidates for review, but Care finds
only allowed alternatives and the user confirms any SupportPlan change.

## Entitlement policy

- Anonymous: screening result, disclaimer, safety status, and safety guidance;
  no history, specialist access, or profile-dependent personalization.
- `FREE`: standard one-time Support Guide, Journal, emotion check-in, reviewed
  resources, and the default AI quota; no durable SupportPlan or consultation
  credit.
- `PLUS`: higher AI quota, durable SupportPlan/lifecycle tracking, and one
  consultation credit per paid period.
- `PREMIUM`: no displayed daily AI-response limit subject to server fair-use
  controls, optional stronger model, advanced recommendations, and three
  credits per paid period.

Resource count is not limited by package. Exact paid benefits remain governed
by the immutable Consultation plan version. Safety output, the one-time Support
Guide, and owned assessment access are never paid features.

## Approval and runtime gates

- [x] Product Owner approved `mb-support-routing-capstone-v1` as a definition-only product-support blueprint.
- [x] Current-result input, no-input behavior, item-9 independence and AI exclusion are defined.
- [x] Score, disclaimer and safety output are non-paywalled; specialist/paid capabilities fail explicitly when unavailable.
- [x] Versioned REST/event contracts, append-only persistence, deterministic runtime and automated tests implemented for Story 1103.
- [x] Two-domain V1 boundary, no-global-severity rule, cross-cutting safety and system-proposed-plan direction approved in ADR 0012.
- [ ] Compatible additive domain-aware SupportEvaluation v2 policy/contracts are implemented under #48 without changing v1 history; known-consumer and production domain approval remain release gates.
- [x] Product Owner approved the SupportPlan template, slot, bounds, composition, safety-presentation, role, and lifecycle policy under ADR 0013.
- [ ] Care, Content, and Frontend accept the server-proposed contract design before it becomes an active OpenAPI contract.
- [x] Domain/band/pathway exact-version Resource Eligibility v1 is delivered under #50; initial reviewed item mappings remain a separate gate.
- [ ] Every initial personalized intervention item, source, wording, eligibility and content version approved.
- [x] Exact minimum Vietnamese safety fallback reviewed by the Product Owner for the controlled Capstone web/API channel on 2026-09-10.
- [ ] Mentor/supervisor records Review 1 closure validation.
- [ ] Domain review is recorded before this becomes an executable production support policy.

Care may return the bounded v1 support-tier result in the controlled Capstone
runtime. Scope v2 behavior remains unavailable until its contracts, migrations,
frontend, directory content, notification scheduling, and production gates
pass. No safety flow automatically calls, shares location, sends email, or
notifies a third party.
