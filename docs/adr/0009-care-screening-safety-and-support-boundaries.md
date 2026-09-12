# ADR 0009: Care screening, safety, and support boundaries

- Status: Accepted
- Date: 2026-08-30
- Amended by: [ADR 0012](0012-two-domain-screening-and-system-proposed-support-plans.md) for the explicit two-domain scope, no-global-severity rule, domain-aware evaluation, resource eligibility, and system-proposed SupportPlan direction. The scoring and safety decisions below remain in force.

## Context

MB-89 requires deterministic PHQ-9 submission and an approved item-9 response. Existing documentation mixed questionnaire severity, platform risk, crisis/hotline content, support actions, and subscription access. That ambiguity could allow engineering or AI to infer suicide urgency, overwrite a score band, paywall safety output, or claim an unavailable human/emergency response.

The initial product serves adults aged 18–30 in Vietnam using `vi-VN` and `Asia/Ho_Chi_Minh`. The project has no 24/7 human-response operation, geolocation/current-facility database, or approved hotline catalogue. Specialist operating hours exist only for availability and appointment scheduling.

## Decision

Care keeps independent outputs: instrument-specific `screeningLevel`, item-specific `safetyStatus`, policy-derived `supportTier`, approved `supportActions`, and commercial `entitlementPlan`. ADR 0012 further requires explicit screening domain provenance and forbids a combined or global mental-health severity.

PHQ-9 and GAD-7 scores are calculated deterministically in Care from complete `0..3` answers. Bands come from immutable versioned reference data. These values are screening symptom severity, not diagnoses.

For PHQ-9 v1, item 9 is positive when its value is at least `1`. A positive item never changes the PHQ-9 band and values `1`, `2`, and `3` do not establish intent, plan, imminence, or urgency. MentalBridge does not automatically notify a specialist, administrator, family member, emergency service, or external notification provider.

Scoring, disclaimer, safety status, and reviewed safety guidance are synchronous local Care behavior and are available to anonymous and every subscription tier. They cannot depend on AI, Kafka, Redis, realtime delivery, notifications, or billing. AI may explain an already approved bounded proposal; it never scores, changes bands/safety status, determines resource eligibility, selects a SupportPlan, or invents interventions.

Anonymous users may view their current result and safety guidance but receive no longitudinal history, specialist access, or profile-dependent personalization. Registered Free users may receive basic support from the approved catalogue; paid plans may add deeper longitudinal personalization, advanced follow-up, and consultation benefits defined by immutable plan versions.

The hotline catalogue, hotline CRUD, and hotline database are removed from product scope. Safety guidance is versioned reviewed Care content and may use only facts the product can support. It cannot say “nearest facility” without a current location/facility capability. A specific emergency number is included only after separate legal/domain approval.

Self-screening may be available 24/7, but MentalBridge does not provide 24/7 human monitoring, emergency dispatch, or guaranteed response. `Asia/Ho_Chi_Minh` operating hours govern specialist slots only; durable instants remain UTC.

This ADR accepts architecture and product boundaries. It does not itself publish exact Vietnamese questionnaire wording, disclaimer/safety text, a support-tier matrix, intervention content, consent text, or retention values. [ADR 0010](0010-capstone-questionnaire-publication-gates.md) separates the bounded Capstone questionnaire evidence gate from production governance and from optional support-feature gates.

## Consequences

- Assessment results need questionnaire, scoring, and safety-policy provenance.
- Support results need support-policy and selected-content provenance.
- A positive item 9 remains testable even when the total band is low.
- Optional dependency failures cannot suppress or delay safety output.
- Content/Notification retains reviewed self-help resources and notification delivery, but no longer owns hotline records.
- Existing planned hotline OpenAPI and executable schema are removed through compatible documentation/contract cleanup and an append-only owner migration.
- Exact localized content and evidence remain required before a `vi-VN` questionnaire is published. Controlled Capstone publication follows `MB-CAPSTONE-SCREENING-PUBLICATION-001`; public real-user deployment remains blocked until its production privacy, retention, safety-content, legal, security, and operational gates are complete.

## Rejected alternatives

- Treat item 9 as an automatic `SEVERE` PHQ-9 result: rejected because frequency and total screening severity are different facts.
- Infer low/medium/high suicide risk from item-9 value: rejected because the product has no validated risk assessment or clinician workflow for that conclusion.
- Ask AI to choose safety behavior or generate interventions: rejected because safety and entitlement behavior must be deterministic, reviewed, and reproducible.
- Notify humans or emergency services automatically: rejected because the project cannot guarantee consent, routing, receipt, or 24/7 response.
- Maintain a hotline/facility catalogue: rejected because freshness, geographic correctness, and operational ownership are outside current product capability.
