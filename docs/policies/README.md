# MentalBridge policy register

This directory contains versioned product, safety, privacy, and support policies that constrain executable behavior. A policy marked `DRAFT` is not production configuration and must not be treated as approved merely because its structure or cited scoring method is complete.

| Policy | Version | Status | Runtime use |
| --- | --- | --- | --- |
| [Capstone questionnaire evidence and publication](capstone-questionnaire-publication-policy.md) | `1.0` | Effective for controlled Capstone local/demo use | Defines the bounded research-verification gate; does not approve production deployment |
| [PHQ-9 screening and item-9 safety](phq9-screening-and-safety-policy.md) | `1.0-capstone` | Capstone published | Executable in controlled local/demo use; production review remains separate |
| [GAD-7 screening](gad7-screening-policy.md) | `1.0-draft.2` | Research basis and Vietnamese source identified; format mapping pending | Not implementation-ready until the exact self-administered content mapping and tests pass |
| [Care support tier and intervention catalogue](care-support-and-intervention-policy.md) | `1.0-draft.1` | Product and domain approval required | No support-tier mapping or personalized action may be inferred |
| [Care consent and assessment retention](care-consent-and-retention-policy.md) | `1.0-draft.1` | Product, security, and legal review required | Unapproved retention values must not be deployed |

## Approval rule

A questionnaire may be published for controlled Capstone local/demo use when the evidence checklist in `MB-CAPSTONE-SCREENING-PUBLICATION-001` passes and the Product Owner records the publication decision. This gate does not require unrelated support, intervention, specialist, or commercial features to be complete.

Production deployment and each optional support capability retain their own approval gates. A policy becomes executable for its stated environment only when its required evidence, decision owner, effective date, exact content, configuration values, and acceptance examples are complete. A new decision creates an immutable version; it does not edit historical assessment results.

Every assessment result must retain the exact `questionnaireVersion`, `scoringVersion`, and `safetyPolicyVersion` used. Support output additionally retains the approved `supportPolicyVersion` and every selected intervention `contentVersion`.
