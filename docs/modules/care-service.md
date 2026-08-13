# Care Service specification

## Business boundary

Care owns user profiles, consent decisions, specialist access grants, questionnaire definitions, assessment attempts/results, deterministic risk classification, intervention selection, follow-up, and wellbeing projections. It is the authority for current consent and safety decisions. These paths remain correct without AI, Kafka, Redis, WebSocket, or notification delivery.

## Use cases and acceptance

| Capability | Main behavior | Acceptance |
| --- | --- | --- |
| Profile/privacy | Update profile and independent consent decisions | Versioned consent evidence; unsupported/null fields rejected; revoked consent blocks new reads immediately |
| Anonymous screening | Fetch current PHQ-9/GAD-7 and submit opaque attempt | Complete 0..3 answers only; deterministic score; short-lived result; no silent account link; guidance in response |
| Authenticated assessment | Start and idempotently submit immutable versioned attempt | Exact question version; no partial final score; duplicate key returns original; score/result/outbox atomically commit |
| Risk/intervention | Evaluate versioned local policy from eligible inputs | Missing/stale input gives insufficient data; safety flag cannot be downgraded by AI; reason/source provenance stored; severe guidance synchronous |
| Specialist grant | Grant exact scopes, range/entries, purpose and expiry; authorize reads | Appointment never implies journal access; current grant checked by owner; revoke/read race fails closed and is audited |
| Follow-up/analytics | Manage milestones/check-ins and compare valid results | Missing differs from zero; source/freshness exposed; no causal/diagnostic claims |
| Export/deletion | Export owned data and participate in deletion workflow | Scope and retention policy explicit; retries idempotent; evidence contains no deleted content |

## Implementation design

- Feature slices: `profile`, `consent`, `assessment`, `risk`, `intervention`, `followup`, `analytics`, `data-rights`.
- OpenAPI defines public assessment/profile APIs and minimal internal consent-authorization decisions. Kafka schemas carry minimized assessment/risk/consent/follow-up facts.
- PostgreSQL and Liquibase own scoring inputs/results, policy provenance, grants and outbox. Constraints enforce immutable published questionnaires and unique submissions.
- Risk calculation and crisis fallback are pure local domain behavior. Structured journal indicators arrive asynchronously and never include raw journal text.
- Exceptional Identity lookups use a consumer-owned Feign port outside transactions with timeout/breaker and safe failure semantics.

## Ordered tasks

- [ ] CARE-01 Obtain approved scoring/risk/safety, consent, anonymous expiry, export and retention policies; blocked decisions stay unimplemented.
- [ ] CARE-02 Define profile, consent authorization, questionnaire, assessment, risk, intervention, follow-up and analytics OpenAPI.
- [ ] CARE-03 Define assessment/risk/consent/follow-up event schemas and journal-indicator consumer contract.
- [ ] CARE-04 Add Liquibase histories, reference-data versions, constraints, indexes and field dictionary entries.
- [ ] CARE-05 Implement profile and independent consent/grant decisions with concurrent revoke/read protection.
- [ ] CARE-06 Implement anonymous and authenticated assessment, validation, scoring and idempotency.
- [ ] CARE-07 Implement deterministic risk/intervention with synchronous severe fallback and provenance.
- [ ] CARE-08 Implement follow-up, comparison projections, export and deletion participation.
- [ ] CARE-09 Verify scoring boundaries, item-9 safety, stale/missing input, concurrency, rollback/outbox, duplicate/reordered events and dependency failures.
- [ ] CARE-10 Add safe observability/configuration, update README, and pass module/contract/migration gates.
