# Consultation Service specification

## Business boundary

Consultation owns specialist profile and approval, private verification-file metadata, searchable discovery/matching, availability, appointments, reviews, and owner-specific review moderation. It owns booking invariants in PostgreSQL. It does not own identity roles, consent grants, journal content, chat messages, or notification delivery.

## Use cases and acceptance

| Capability | Main behavior | Acceptance |
| --- | --- | --- |
| Specialist verification | Submit metadata/files; admin approves or rejects with reason | Only approved specialists are discoverable/bookable; signed private file access is time-limited and audited |
| Discovery/matching | Filter approved specialists and return transparent recommendations | Deterministic pagination; criteria/policy version and explanation recorded; no hidden health-data join |
| Availability | Create/update non-overlapping slots in UTC plus IANA timezone | Invalid/overlapping slots rejected by database constraints; concurrent edits surface stable conflicts |
| Appointment | Request, accept/reject, cancel/reschedule, complete/no-show with an authoritative consultation-credit decision | Idempotency key; exactly one active appointment per slot; credit reservation/return/consume occurs exactly once through the financial owner; valid local transition/history/outbox atomic |
| Consented view/dashboard | Show own workload and fetch scoped owner data | Care authorization is current and fails closed; Journal/AI returns only allowed fields; no remote call inside transaction |
| Review/moderation | One review after completed appointment; owner applies reviewed action | Participant/completion verified; duplicate rejected; evidence minimized; action/history auditable |

## Implementation design

- Feature slices: `specialists`, `verification`, `discovery`, `matching`, `availability`, `appointments`, `reviews`, `review-moderation`, `dashboard`.
- Contract-first OpenAPI covers client/admin APIs and internal appointment eligibility projections. Appointment/review/moderation event schemas are versioned.
- Liquibase constraints/exclusion/locking are the final defense against overlap and double booking; use optimistic locking for editable aggregates.
- Care, Journal/AI and the future financial-owner calls use narrow consumer-owned ports, explicit deadlines/breakers and owner-side authorization. Cloudinary remains an adapter; store only safe provider identifiers. Do not implement a local credit balance before the financial ADR.

## Ordered tasks

- [ ] CON-01 Resolve qualification, matching, cancellation/reschedule/no-show, consultation channel, review and moderation policies; depend on the financial ADR for credit rules.
- [ ] CON-02 Define specialist, discovery, availability, appointment, dashboard and review OpenAPI.
- [ ] CON-03 Define appointment/review/moderation event schemas and required Care/Journal consumer contracts.
- [ ] CON-04 Add Liquibase histories, constraints, indexes and field dictionary entries.
- [ ] CON-05 Implement specialist verification with private signed assets and admin audit.
- [ ] CON-06 Implement discovery/matching with versioned explainable provenance.
- [ ] CON-07 Implement availability and race-safe idempotent appointment transitions.
- [ ] CON-08 Implement consented view/dashboard, reviews and review moderation.
- [ ] CON-09 Verify simultaneous booking, transition conflicts, authorization, provider timeout, uncertain mutation, outbox and event duplicates.
- [ ] CON-10 Add observability/configuration, update README, and pass module/contract/migration gates.
