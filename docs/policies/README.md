# MentalBridge policy register

This directory contains versioned product, safety, privacy, and support policies that constrain executable behavior. A policy marked `DRAFT` is not production configuration and must not be treated as approved merely because its structure or cited scoring method is complete.

Read [Current Product Blueprint](../CURRENT_PRODUCT_BLUEPRINT.md) first for the current authority map. Current cross-feature scope is based on `MB-SCOPE-V2-001` and amended by `MB-SCOPE-V2-002`. Historical policy versions remain for provenance and must not override a later explicit amendment.

| Policy | Version / decision | Status | Runtime use |
| --- | --- | --- | --- |
| [Capstone questionnaire evidence and publication](capstone-questionnaire-publication-policy.md) | `1.0` | Effective for controlled Capstone local/demo use | Defines the bounded research-verification gate; does not approve production deployment |
| [PHQ-9 screening and item-9 safety](phq9-screening-and-safety-policy.md) | `1.0-capstone` | Capstone published | Executable in controlled local/demo use; production review remains separate |
| [GAD-7 screening](gad7-screening-policy.md) | `1.0-capstone` | Capstone published | Executable in controlled local/demo use; production review remains separate |
| [Care support routing and SupportPlan](care-support-and-intervention-policy.md) | `1.4-scope-v2` | Controlled Capstone v1/v2 evaluation, Support Guide, and paid SupportPlan foundations implemented | Keeps deterministic screening/safety and domain-aware evaluation while deferring to current SupportPlan policy for lifecycle/reassessment amendments |
| [SupportEvaluation policy v2](support-evaluation-policy-v2.md) | `mb-support-routing-capstone-v2` | Controlled Capstone implementation; production domain review pending | Preserves two domain-local evidence snapshots and independent PHQ-9 item-9 safety without global severity |
| [SupportPlan policy v1](support-plan-policy-v1.md) | `mb-support-plan-selection-v1` | Historical policy; amended by ADR 0017/0022 | Retains the original template, composition, eligibility, lifecycle, and safety rationale |
| [SupportPlan policy v2](support-plan-policy-v2.md) | `MB-SCOPE-V2-001` + `MB-SCOPE-V2-002` | Support Guide, draft, activation, lifecycle/history, scheduling, engagement and MB-386 baseline implemented; explicit self-report and governed review amendment delivery-gated | Distinguishes persisted all-tier Support Guide from paid durable SupportPlan and defines four-dimension reassessment with explicit user self-report as the canonical fourth dimension |
| [Consultation and specialist policy v1](consultation-specialist-policy-v1.md) | `MB-CONSULTATION-FLOW-001` | Historical policy; amended by ADR 0017/0022 | Retains original consultation rationale and historical behavior |
| [Consultation and specialist policy v2](consultation-specialist-policy-v2.md) | `MB-SCOPE-V2-001` + `MB-SCOPE-V2-002` | Product policy approved; availability runtime implemented; discovery/appointment/credit-v2/session/payment/payout remain delivery-gated | Online-only chat/video, evidence-backed completion, `consultation-credit-v2` target `0/4/10`, no rollover, reservation caps `0/2/4`, and governed specialist-to-plan handoff |
| [AI Companion policy v1](ai-companion-policy-v1.md) | `MB-AI-COMPANION-001` | Historical policy; amended by ADR 0017 | Retains exact-source analysis, longitudinal evidence, provider execution, and AI/Care authority rationale |
| [AI Companion policy v2](ai-companion-policy-v2.md) | `MB-SCOPE-V2-001` + `MB-SCOPE-V2-002` | Chat/quota and exact-revision runtimes implemented; real provider route approval-gated | Adds package quotas/model routing and bounded SupportPlan/reassessment/reminder accompaniment; AI never supplies the explicit reassessment self-report |
| [Care consent and assessment retention](care-consent-and-retention-policy.md) | `1.1-capstone` | Product Owner approved for controlled Capstone use; production security/legal review required | `privacy-capstone-v3` gates PHQ-9/GAD-7; `ai-processing-capstone-v1` gates exact-revision/bounded-longitudinal analysis; specialist sharing remains separately scoped |
| [Daily emotion check-in policy v1](daily-emotion-check-in-policy-v1.md) | `MB-DAILY-EMOTION-CHECK-IN-001` | Implemented for MB-510 controlled development/demo use | Governs non-clinical daily values, local-day edits, encrypted note, deletion, and consented note-free AI projection |

## Current cross-feature amendments

`MB-SCOPE-V2-002` fixes the following target rules prospectively:

- Support Guide is a persisted immutable historical guidance snapshot, not an ephemeral result and not a lifecycle-tracked SupportPlan.
- Reassessment dimensions are Screening change, Journal context, Plan engagement, and explicit Self-reported experience. Activity helpfulness/reflection can support but cannot replace the explicit self-report.
- Reassessment never mutates SupportPlan automatically; Care freshly revalidates before continue/keep/replace options are presented.
- Specialist resource proposals enter a Care-owned `PlanChangeRequest`; Consultation/specialist never mutate SupportPlan directly.
- New `consultation-credit-v2` periods target `FREE=0`, `PLUS=4`, `PREMIUM=10`, with no rollover and concurrent active-reservation caps `0/2/4`.
- Historical `consultation-credit-v1` `0/1/3` periods and existing MB-386 snapshots remain immutable under their original provenance.

See [ADR 0022](../adr/0022-current-product-blueprint-amendments.md).

## Approval rule

A questionnaire may be published for controlled Capstone local/demo use when the evidence checklist in `MB-CAPSTONE-SCREENING-PUBLICATION-001` passes and the Product Owner records the publication decision. This gate does not require unrelated support, intervention, specialist, or commercial features to be complete.

Production deployment and each optional support capability retain their own approval gates. A policy becomes executable for its stated environment only when its required evidence, decision owner, effective date, exact content, configuration values, and acceptance examples are complete. A new decision creates an immutable version; it does not edit historical assessment, credit, plan, or reassessment records.

Every assessment result must retain its exact instrument domain,
`questionnaireVersion` and `scoringVersion`. PHQ-9 also retains its exact
`safetyPolicyVersion`; GAD-7 deliberately stores null with
`safetyStatus = NOT_APPLICABLE` because no questionnaire safety policy is
evaluated. No result may claim global mental-health severity. Support Guide and
SupportPlan output retain policy/content provenance; current SupportPlans also
retain entitlement and `PlanChangeRequest` confirmation provenance under the
accepted scope ADR chain.
