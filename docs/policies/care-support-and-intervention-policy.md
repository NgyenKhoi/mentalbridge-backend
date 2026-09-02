# Care support tier and intervention catalogue policy

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-SUPPORT-CARE-001` |
| Policy version | `1.0-capstone-blueprint` |
| Status | `CAPSTONE BLUEPRINT APPROVED — RUNTIME UNAVAILABLE` |
| Blueprint effective date | 2026-09-02 |
| Product Owner decision | Definition approved through MB-179 on 2026-09-02 |
| Mentor/domain review | Mentor closure review pending; domain review required before executable production routing |
| Owners | Care for selection; Content/Notification for reviewed resource content |
| Applies to | Registered MentalBridge users in Vietnam |

## Independent decision dimensions

The system keeps these values independent:

| Dimension | Meaning | Must not be used as |
| --- | --- | --- |
| `screeningLevel` | Severity band from one questionnaire version | Diagnosis or cross-instrument risk |
| `safetyStatus` | PHQ-9 item-9 deterministic screen | Suicide intent/urgency classification |
| `supportTier` | Approved platform support pathway | `LOW_RISK`, `MEDIUM_RISK`, or `HIGH_RISK` |
| `supportActions` | Versioned approved catalogue selections | AI-generated treatment instructions |
| `entitlementPlan` | Current commercial access | A reason to suppress safety output |

Reserved support-tier names are:

- `SELF_GUIDED_SUPPORT`;
- `PROFESSIONAL_SUPPORT_RECOMMENDED`;
- `SAFETY_FOLLOW_UP_RECOMMENDED`.

The names and mapping establish approved Capstone product vocabulary. They remain non-executable until versioned contracts, persistence and tests exist and the applicable content/runtime gate passes.

## Approved non-executable Capstone routing blueprint

The definition version is `mb-support-routing-capstone-v1`. An eligible input is a complete immutable result from a published questionnaire explicitly selected in the same user-initiated support evaluation. Sprint 2 does not infer an automatic latest-result window. Unpublished GAD-7 is not an eligible input and is not treated as missing evidence.

| Eligible evidence | PHQ-9 safety | Blueprint support tier |
| --- | --- | --- |
| No eligible published result | Not available | `INSUFFICIENT_DATA` |
| Every available PHQ-9/GAD-7 level is Minimal or Mild | Negative or not applicable | `SELF_GUIDED_SUPPORT` |
| Any available PHQ-9/GAD-7 level is Moderate or higher | Negative or not applicable | `PROFESSIONAL_SUPPORT_RECOMMENDED` |
| Any PHQ-9 result has item 9 `>=1` | Positive | `SAFETY_FOLLOW_UP_RECOMMENDED` plus an optional professional-support recommendation |

The item-9 rule affects only support routing and never overwrites the PHQ-9 band. Values `1`, `2` and `3` do not classify intent, plan, imminence or urgency. GAD-7 has no equivalent safety rule. AI is not an input to this blueprint.

A future automatic latest-assessment calculation must define freshness, version compatibility and missing-input behavior in a new policy version. The complete journey and examples are in the [MB-179 blueprint](../sprints/mb-179-screening-to-support-blueprint.md#versioned-severity-to-support-decision-table).

## Intervention catalogue contract

Every selectable item requires at least:

| Field | Purpose |
| --- | --- |
| `activityId` | Stable non-clinical catalogue identifier |
| `title` | Reviewed localized title |
| `type` | Approved action category |
| `eligibleSupportTiers` | Exact tiers allowed to select the item |
| `source` | Evidence or content provenance |
| `reviewedBy` | Accountable reviewer identity |
| `contentVersion` | Immutable content revision |
| `active` | Whether new plans may select it |
| `effectiveAt` / `expiresAt` | Version validity window |
| `locale` | Reviewed BCP 47 locale |

Candidate identifiers for catalogue review include `BREATHING_BASIC_01`, `STRESS_JOURNALING_01`, `SLEEP_HYGIENE_01`, `PSYCHOEDUCATION_DEPRESSION_01`, `PROFESSIONAL_CONSULTATION_CTA`, and `REASSESSMENT_14D`. Their names do not approve their wording, eligibility, cadence, or clinical suitability.

AI may rank, select, or explain only active approved entries eligible for the resolved support tier. It cannot create a new activity, change eligibility, calculate screening results, change a safety status, or override a deterministic selection constraint.

No personalized intervention item is approved by the MB-179 routing decision. Published generic self-help resources may be displayed independently of personalized routing, but their availability does not mean that a `supportTier` or `supportActions` runtime exists.

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
- [ ] Versioned runtime contract, persistence and automated tests implemented.
- [ ] Every initial personalized intervention item, source, wording, eligibility and content version approved.
- [ ] Exact localized fallback content reviewed for each runtime channel.
- [ ] Mentor/supervisor records Review 1 closure validation.
- [ ] Domain review is recorded before this becomes an executable production support policy.

Until the unchecked applicable gates pass, Care returns no executable support-tier or personalized-action result. Production clinical deployment remains outside Sprint 2.
