# MentalBridge policy register

This directory contains versioned product, safety, privacy, and support policies that constrain executable behavior. A policy marked `DRAFT` is not production configuration and must not be treated as approved merely because its structure or cited scoring method is complete.

| Policy | Version | Status | Runtime use |
| --- | --- | --- | --- |
| [Capstone questionnaire evidence and publication](capstone-questionnaire-publication-policy.md) | `1.0` | Effective for controlled Capstone local/demo use | Defines the bounded research-verification gate; does not approve production deployment |
| [PHQ-9 screening and item-9 safety](phq9-screening-and-safety-policy.md) | `1.0-capstone` | Capstone published | Executable in controlled local/demo use; production review remains separate |
| [GAD-7 screening](gad7-screening-policy.md) | `1.0-capstone` | Capstone published | Executable in controlled local/demo use; production review remains separate |
| [Care support tier and intervention catalogue](care-support-and-intervention-policy.md) | `1.0-capstone-blueprint` | Controlled Capstone routing runtime published | `mb-support-routing-capstone-v1` returns a tier, reasons, meanings and one bounded next step; intervention/specialist automation remains unavailable |
| [Care consent and assessment retention](care-consent-and-retention-policy.md) | `1.1-capstone` | Product Owner approved for controlled Capstone use; production security/legal review required | `privacy-capstone-v3` gates new PHQ-9/GAD-7 processing; specialist sharing and unapproved retention values remain unavailable |

## Approval rule

A questionnaire may be published for controlled Capstone local/demo use when the evidence checklist in `MB-CAPSTONE-SCREENING-PUBLICATION-001` passes and the Product Owner records the publication decision. This gate does not require unrelated support, intervention, specialist, or commercial features to be complete.

Production deployment and each optional support capability retain their own approval gates. A policy becomes executable for its stated environment only when its required evidence, decision owner, effective date, exact content, configuration values, and acceptance examples are complete. A new decision creates an immutable version; it does not edit historical assessment results.

Every assessment result must retain the exact `questionnaireVersion` and `scoringVersion` used. PHQ-9 also retains its exact `safetyPolicyVersion`; GAD-7 deliberately stores null with `safetyStatus = NOT_APPLICABLE` because no questionnaire safety policy is evaluated. Support output additionally retains the approved `supportPolicyVersion` and every selected intervention `contentVersion`.
