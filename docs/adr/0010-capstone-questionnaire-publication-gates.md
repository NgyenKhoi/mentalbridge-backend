# ADR 0010: Separate Capstone questionnaire publication from production governance

- Status: Accepted
- Date: 2026-09-01

## Context

ADR 0009 correctly separated questionnaire scoring, PHQ-9 item-9 safety status, support tiers, interventions, and commercial entitlements. The first policy implementation nevertheless combined exact localized questionnaire provenance, clinical/domain signatures, support-catalogue completion, consent/retention decisions, and production legal review into one publication gate.

That combined gate prevented a controlled academic demonstration of an established questionnaire even when the instrument evidence, localized source, deterministic backend scoring, non-diagnostic presentation, and automated verification were complete. It also made unrelated future capabilities such as personalization and specialist handoff prerequisites for base screening.

## Decision

MentalBridge uses separate readiness gates:

1. A research-evidence gate permits versioned PHQ-9 or GAD-7 publication in controlled local/demo Capstone environments after the checklist in `MB-CAPSTONE-SCREENING-PUBLICATION-001` passes and the Product Owner records the decision.
2. Support-tier mapping, intervention content, specialist handoff, follow-up, and entitlement behavior each remain unavailable until their own policy and implementation gate passes.
3. A public deployment that collects real-user health data remains subject to production privacy, security, retention, legal, safety-content, and operational review. Academic status is not an exemption from those obligations.

External psychiatrist, clinical-domain, or legal signatures are not mandatory for the bounded Capstone questionnaire-publication gate. They remain recommended evidence review and may be mandatory for a later production gate depending on deployment and applicable requirements.

The following safeguards remain unchanged:

- exact localized content must come from a traceable source and cannot be translated by engineering;
- Care remains authoritative for scoring and deterministic safety status;
- PHQ-9 item 9 remains independent from total-score severity;
- UI and APIs must not diagnose, stratify suicide risk, promise emergency response, or imply 24/7 human monitoring;
- AI cannot calculate or override questionnaire and safety results;
- unavailable optional support is explicit rather than invented.

## Consequences

- PHQ-9 and GAD-7 policy documents distinguish research-basis status, localized-content readiness, Capstone publication, and production approval.
- Questionnaire publication no longer waits for a support-tier matrix, intervention catalogue, specialist workflow, or paid-plan behavior.
- Consent and retention do not block synthetic controlled demos, but they still block public real-user data collection where those rules are unresolved.
- A language listing in a repository is provenance evidence, not proof that the exact artifact and its response semantics are ready to seed.
- Existing runtime and persistence contracts do not change through this ADR; publishing a new localized definition still requires an append-only migration, contract-compatible behavior, and tests.

## Rejected alternatives

- Keep one production-grade approval gate for all environments: rejected because it creates unrelated dependencies and misrepresents the academic delivery scope.
- Remove safety, provenance, and privacy gates because this is a Capstone: rejected because academic status does not justify invented medical content or unsafe real-user handling.
- Treat a Vietnamese language listing as sufficient localized content: rejected because exact wording, response semantics, source artifact, and version still need verification.
- Treat research verification as MentalBridge-specific clinical validation: rejected because available studies do not establish diagnostic performance for every Vietnamese adult aged 18–30.
