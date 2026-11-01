# MB-756 Consultation Voice prerequisite

Story: [MB-756](https://vunguyenkhoi47.atlassian.net/browse/MB-756).
Delivery order is Care prerequisite, this Consultation prerequisite, then
Journal/AI eligibility/quota. Baseline: `463a15ee` from `origin/dev`.

`POST /internal/v1/ai-companion/voice-entitlement-authorizations` implements
the exact accepted [MB-755 proposal](../contracts/proposals/companion-voice-v1.openapi.json)
through the active [Consultation OpenAPI](../contracts/openapi/consultation-service-v1.yaml).
It calls the existing current-entitlement owner service and returns only
package, source, policy/version provenance, expiry, checked time and the exact
owner/session/purpose assertion. The existing end-user bearer endpoint stays
unchanged. No financial service, repository mutation, migration, booking,
reservation, credit, settlement or earning behavior changes.

The new purpose-scoped server credential is optional/disabled when empty and
validated when configured. Authentication does not accept a browser/worker
credential. Current PAID/DEMO PLUS/PREMIUM grants have a strict future expiry;
missing, expired or not-yet-effective grants follow the existing FREE decision.
Uncertainty returns a sanitized failure. JSON is closed, duplicate/trailing
tokens are rejected and all success/errors use `no-store`.

JDK 21 and cached Maven 3.9.16 ran the focused
`VoiceEntitlementPropertiesTests`, `VoiceEntitlementControllerTests`,
`ConsultationOpenApiContractTests`, and
`AdminOperationsSecurityIntegrationTests`: 34 tests passed, zero failures,
errors or skips in the final reports. The real current-entitlement service uses
an owner repository test double and fixed clock; this change adds no persistence
adapter requiring a new database integration test. Final-head full regression
and the exact command are recorded in the PR.

No `.env`, shared database, provider account, paid inference, LiveKit or
deployment was used. Final Journal/AI integration tests follow only after Care
and Consultation prerequisites are merged to `dev`; this slice alone does not
complete MB-756 or enable Voice calls.
