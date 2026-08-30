# MentalBridge policy register

This directory contains versioned product, safety, privacy, and support policies that constrain executable behavior. A policy marked `DRAFT` is not production configuration and must not be treated as approved merely because its structure or cited scoring method is complete.

| Policy | Version | Status | Runtime use |
| --- | --- | --- | --- |
| [PHQ-9 screening and item-9 safety](phq9-screening-and-safety-policy.md) | `1.0-draft.1` | Ready for domain approval | Blocked until the approval record is complete |
| [GAD-7 screening](gad7-screening-policy.md) | `1.0-draft.1` | Research and domain review required | Not implementation-ready |
| [Care support tier and intervention catalogue](care-support-and-intervention-policy.md) | `1.0-draft.1` | Product and domain approval required | No support-tier mapping or personalized action may be inferred |
| [Care consent and assessment retention](care-consent-and-retention-policy.md) | `1.0-draft.1` | Product, security, and legal review required | Unapproved retention values must not be deployed |

## Approval rule

A policy becomes executable only when all mandatory approver fields, effective date, exact reviewed content, configuration values, and acceptance examples are complete. Approval creates a new immutable version; it does not edit historical assessment results.

Every assessment result must retain the exact `questionnaireVersion`, `scoringVersion`, and `safetyPolicyVersion` used. Support output additionally retains the approved `supportPolicyVersion` and every selected intervention `contentVersion`.
