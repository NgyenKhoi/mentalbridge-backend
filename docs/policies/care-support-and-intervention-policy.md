# Care support routing and SupportPlan policy

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-SUPPORT-CARE-001` |
| Policy version | `1.1-domain-scope-correction` |
| Status | `CONTROLLED CAPSTONE V1 ROUTING PUBLISHED; DOMAIN-AWARE PLAN SELECTION NOT APPROVED` |
| Blueprint effective date | 2026-09-02 |
| Product Owner decision | Definition approved through MB-179 on 2026-09-02; minimum Vietnamese safety fallback approved for Story 1103 on 2026-09-10; two-domain/system-proposed-plan correction approved as `MB-SCOPE-DOMAIN-001` on 2026-09-12 |
| Mentor/domain review | Mentor closure review pending; domain review required before executable production routing |
| Owners | Care for selection; Content/Notification for reviewed resource content |
| Applies to | Registered MentalBridge users in Vietnam |
| Related decision | [ADR 0012](../adr/0012-two-domain-screening-and-system-proposed-support-plans.md) |

V1 supports exactly `DEPRESSIVE_SYMPTOMS` through PHQ-9 and `ANXIETY_SYMPTOMS` through GAD-7, focused on generalized anxiety symptoms. Equal bands from the two instruments remain different domain evidence. The system never creates a global mental-health severity, and a new domain requires a separately approved product vertical.

## Independent decision dimensions

The system keeps these values independent:

| Dimension | Meaning | Must not be used as |
| --- | --- | --- |
| `screeningLevel` | Instrument-specific band from one questionnaire version and domain | Diagnosis, global mental-health severity or cross-instrument risk |
| `safetyStatus` | PHQ-9 item-9 deterministic screen | Suicide intent/urgency classification |
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

The item-9 rule affects only support routing and never overwrites the PHQ-9 band. Values `1`, `2` and `3` do not classify intent, plan, imminence or urgency. GAD-7 has no equivalent safety rule. Safety is a cross-cutting layer, not a screening domain. AI is not an input to this blueprint.

A future automatic latest-assessment calculation must define freshness, version compatibility and missing-input behavior in a new policy version. The complete journey and examples are in the [MB-179 blueprint](../sprints/mb-179-screening-to-support-blueprint.md#versioned-domain-aware-support-decision-table).

Every result returns the exact `policyVersion`, stable ordered reason codes, both evidence references, independent per-instrument levels and Vietnamese 14-day meanings, one bounded next step, and the non-diagnostic limitation. It never creates a composite score. Repeating the same pair under the same policy reproduces the same persisted decision.

The published v1 `supportTier` is a coarse pathway and cannot by itself select a resource or SupportPlan. Existing v1 contracts and records remain immutable. A forward plan-driving evaluation requires a compatible version with explicit contributing domains and domain-specific reasons under #48.

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
| `targetDomains` | Explicit approved primary or adjunct screening-domain applicability |
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

The approved forward direction is: domain-aware SupportEvaluation, approved template/resource policy, system-proposed `DRAFT` SupportPlan, user choices inside the proposal, Care revalidation, then explicit activation. The client never supplies an arbitrary initial resource set. Template persistence, required/optional resources, choice bounds, selection-rule storage, and any extra safety-positive acknowledgement remain unresolved in #49; resource eligibility semantics remain unresolved in #50.

## Entitlement policy

- Anonymous: screening result, disclaimer, safety status, and safety guidance only; no history, specialist access, or profile-dependent personalization.
- Free registered user: owned assessment history plus basic support selected from the approved catalogue.
- Premium Care: deeper longitudinal personalization, advanced follow-up, and the plan's consultation entitlement.
- Premium Plus: Premium Care behavior plus the plan's additional consultation credits and priority rules.

Exact paid benefits remain governed by the immutable Consultation plan version. Safety output and owned assessment access are never paid features.

## Approval and runtime gates

- [x] Product Owner approved `mb-support-routing-capstone-v1` as a definition-only product-support blueprint.
- [x] Current-result input, no-input behavior, item-9 independence and AI exclusion are defined.
- [x] Score, disclaimer and safety output are non-paywalled; specialist/paid capabilities fail explicitly when unavailable.
- [x] Versioned REST/event contracts, append-only persistence, deterministic runtime and automated tests implemented for Story 1103.
- [x] Two-domain V1 boundary, no-global-severity rule, cross-cutting safety and system-proposed-plan direction approved in ADR 0012.
- [ ] Compatible domain-aware SupportEvaluation policy/contracts are approved and delivered under #48.
- [ ] SupportPlan proposal decisions and contracts are approved under #49.
- [ ] Domain/band/pathway resource eligibility is approved and delivered under #50.
- [ ] Every initial personalized intervention item, source, wording, eligibility and content version approved.
- [x] Exact minimum Vietnamese safety fallback reviewed by the Product Owner for the controlled Capstone web/API channel on 2026-09-10.
- [ ] Mentor/supervisor records Review 1 closure validation.
- [ ] Domain review is recorded before this becomes an executable production support policy.

Care may return this bounded v1 support-tier result in the controlled Capstone runtime. It returns no personalized intervention item or SupportPlan and performs no contact, booking, sharing, reminder, or clinical action. Production clinical deployment and domain-aware plan selection remain blocked until their separate gates pass.
