# MentalBridge policy register

This directory contains versioned product, safety, privacy, and support policies that constrain executable behavior. A policy marked `DRAFT` is not production configuration and must not be treated as approved merely because its structure or cited scoring method is complete.

| Policy | Version | Status | Runtime use |
| --- | --- | --- | --- |
| [Capstone questionnaire evidence and publication](capstone-questionnaire-publication-policy.md) | `1.0` | Effective for controlled Capstone local/demo use | Defines the bounded research-verification gate; does not approve production deployment |
| [PHQ-9 screening and item-9 safety](phq9-screening-and-safety-policy.md) | `1.0-capstone` | Capstone published | Executable in controlled local/demo use; production review remains separate |
| [GAD-7 screening](gad7-screening-policy.md) | `1.0-capstone` | Capstone published | Executable in controlled local/demo use; production review remains separate |
| [Care support routing and SupportPlan](care-support-and-intervention-policy.md) | `1.4-scope-v2` | Controlled Capstone v1/v2 evaluation, Support Guide, and initial paid SupportPlan activation implemented | Keeps v1 routing immutable while adding domain-aware evaluation, both safety triggers, all-tier Support Guide, and paid single official SupportPlan authority |
| [SupportEvaluation policy v2](support-evaluation-policy-v2.md) | `mb-support-routing-capstone-v2` | Controlled Capstone implementation; production domain review pending | Preserves two domain-local evidence snapshots and independent PHQ-9 item-9 safety without global severity |
| [SupportPlan policy v1](support-plan-policy-v1.md) | `mb-support-plan-selection-v1` | Historical policy; amended by ADR 0017 | Retains the original template, composition, eligibility, lifecycle, and safety rationale |
| [SupportPlan policy v2](support-plan-policy-v2.md) | `MB-SCOPE-V2-001` | Draft, activation, scheduling, lifecycle/history, and owner engagement implemented through MB-376 | Distinguishes the all-tier Support Guide from the `PLUS`/`PREMIUM` durable plan and keeps lifecycle and engagement changes user-confirmed and Care-owned |
| [Consultation and specialist policy v1](consultation-specialist-policy-v1.md) | `MB-CONSULTATION-FLOW-001` | Historical policy; amended by ADR 0017 | Retains the original chat/in-person, completion, brief, and summary rationale |
| [Consultation and specialist policy v2](consultation-specialist-policy-v2.md) | `MB-SCOPE-V2-001` | Product policy approved; runtime not implemented | Allows only chat/video, separates `SESSION_ENDED` from evidence-backed completion, and governs summary reuse |
| [AI Companion policy v1](ai-companion-policy-v1.md) | `MB-AI-COMPANION-001` | Historical policy; amended by ADR 0017 | Retains exact-source analysis, longitudinal evidence, provider execution, and AI/Care authority rationale |
| [AI Companion policy v2](ai-companion-policy-v2.md) | `MB-SCOPE-V2-001` | Chat/quota and exact-revision runtimes implemented; real provider route approval-gated | Adds package quotas/model routing and bounded SupportPlan/reassessment/reminder accompaniment; reminder context still fails closed pending its owner contract |
| [Care consent and assessment retention](care-consent-and-retention-policy.md) | `1.1-capstone` | Product Owner approved for controlled Capstone use; production security/legal review required | `privacy-capstone-v3` gates PHQ-9/GAD-7; `ai-processing-capstone-v1` gates exact-revision/bounded-longitudinal analysis; specialist sharing remains unavailable |
| [Daily emotion check-in policy v1](daily-emotion-check-in-policy-v1.md) | `MB-DAILY-EMOTION-CHECK-IN-001` | Implemented for MB-510 controlled development/demo use | Governs non-clinical daily values, local-day edits, encrypted note, deletion, and consented note-free AI projection |

## Approval rule

A questionnaire may be published for controlled Capstone local/demo use when the evidence checklist in `MB-CAPSTONE-SCREENING-PUBLICATION-001` passes and the Product Owner records the publication decision. This gate does not require unrelated support, intervention, specialist, or commercial features to be complete.

Production deployment and each optional support capability retain their own approval gates. A policy becomes executable for its stated environment only when its required evidence, decision owner, effective date, exact content, configuration values, and acceptance examples are complete. A new decision creates an immutable version; it does not edit historical assessment results.

Every assessment result must retain its exact instrument domain,
`questionnaireVersion` and `scoringVersion`. PHQ-9 also retains its exact
`safetyPolicyVersion`; GAD-7 deliberately stores null with
`safetyStatus = NOT_APPLICABLE` because no questionnaire safety policy is
evaluated. No result may claim global mental-health severity. Support Guide and
SupportPlan output retain policy/content provenance; scope v2 SupportPlans also
retain entitlement and `PlanChangeRequest` confirmation provenance under ADR
0017.
