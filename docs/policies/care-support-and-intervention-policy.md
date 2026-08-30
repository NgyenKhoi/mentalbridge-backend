# Care support tier and intervention catalogue policy

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-SUPPORT-CARE-001` |
| Policy version | `1.0-draft.1` |
| Status | `DRAFT — PRODUCT AND DOMAIN APPROVAL REQUIRED` |
| Effective date | Pending approval |
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

The names establish vocabulary only. They are not executable until the mapping below is approved.

## Proposed mapping for domain review

| PHQ-9 | GAD-7 | Safety | Proposed support tier |
| --- | --- | --- | --- |
| Minimal/Mild | Minimal/Mild | Negative | `SELF_GUIDED_SUPPORT` |
| Moderate or higher | Any available level | Negative | `PROFESSIONAL_SUPPORT_RECOMMENDED` |
| Any available level | Moderate or higher | Negative | `PROFESSIONAL_SUPPORT_RECOMMENDED` |
| Any or unavailable | Any or unavailable | Positive | `SAFETY_FOLLOW_UP_RECOMMENDED` plus professional-support recommendation |

This table is a review proposal, not an engineering decision. Domain approval must also define missing/stale questionnaire behavior, recency windows, whether one instrument may be absent, and conflict examples before Care implements it.

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

## Entitlement policy

- Anonymous: screening result, disclaimer, safety status, and safety guidance only; no history, specialist access, or profile-dependent personalization.
- Free registered user: owned assessment history plus basic support selected from the approved catalogue.
- Premium Care: deeper longitudinal personalization, advanced follow-up, and the plan's consultation entitlement.
- Premium Plus: Premium Care behavior plus the plan's additional consultation credits and priority rules.

Exact paid benefits remain governed by the immutable Consultation plan version. Safety output and owned assessment access are never paid features.

## Approval blockers

- [ ] Support-tier matrix and missing/stale input behavior approved.
- [ ] Every initial intervention item, source, wording, eligibility, and content version approved.
- [ ] Free versus paid personalization positioning approved.
- [ ] Fallback output approved for empty catalogue, unavailable AI, unavailable specialist, and unavailable entitlement/slot.
- [ ] Product owner, supervisor, and domain expert approvals recorded.
