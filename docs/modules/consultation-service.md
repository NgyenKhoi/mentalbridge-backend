# Consultation Service specification

## Business boundary

Consultation owns specialist profile approval, searchable discovery/matching, subscription/payment/upgrade, consultation credits, channel-specific availability, appointments, specialist earnings, provider payout reconciliation, reviews, and owner-specific review moderation. It owns billing and booking invariants in PostgreSQL. It does not own identity roles, consent grants, journal content, chat messages, or notification delivery. Specialist-document upload is removed from scope.

## Use cases and acceptance

| Capability | Main behavior | Acceptance |
| --- | --- | --- |
| Specialist approval | Submit a complete profile; admin approves, rejects, or suspends with a stable reason | Only approved specialists are discoverable/bookable; no credential document is collected |
| Subscription/billing | Publish immutable plan versions; accept payment webhooks; renew/cancel; upgrade Care to Plus; expose credit/earning history | Exact minor units; webhook/idempotency replay safe; immediate cancellation; downgrade/refund unsupported; no shadow balances |
| Discovery/matching | Filter approved specialists and return transparent recommendations | Deterministic pagination; criteria/policy version and explanation recorded; no hidden health-data join |
| Availability | Publish discrete non-overlapping slots from specialist working hours with UTC interval, IANA timezone, and channel | Invalid/overlapping slots rejected; approved standard duration validated once decided; concurrent edits conflict; only `IN_APP_CHAT` initially enabled |
| Appointment | Request, accept/reject, cancel/reschedule, complete/no-show for a scheduled consultation slot | Snapshotted interval/channel; join/send only inside it; exactly one active appointment per slot and credit; locally atomic transitions; no location/contact fields |
| Payout | Encrypt/verify specialist destinations; submit idempotent MoMo payouts; reconcile result/IPN/status | MoMo is the sole production provider after credentials; `UNKNOWN` queried, not blindly retried; real payout currency must be approved |
| Consented view/dashboard | Show own workload and fetch scoped owner data | Care authorization is current and fails closed; Journal/AI returns only allowed fields; no remote call inside transaction |
| Review/moderation | One review after completed appointment; owner applies reviewed action | Participant/completion verified; duplicate rejected; evidence minimized; action/history auditable |

## Implementation design

- Feature slices: `specialists`, `discovery`, `matching`, `billing`, `credits`, `availability`, `appointments`, `earnings`, `payouts`, `reviews`, `review-moderation`, `dashboard`.
- Contract-first OpenAPI covers client/admin APIs and internal appointment eligibility projections. Appointment/review/moderation event schemas are versioned.
- Liquibase constraints/exclusion/locking are the final defense against overlap and double booking; use optimistic locking for editable aggregates.
- Care and Journal/AI calls use narrow consumer-owned ports, explicit deadlines/breakers and owner-side authorization. Billing is authoritative locally under ADR 0005; payment provider details stay behind an adapter and raw provider payloads are not persisted.

## Ordered tasks

- [ ] CON-01 Resolve qualification, matching, appointment duration/join grace, cancellation cutoff, settlement delay, review and moderation policies; later define the in-app-video contract.
- [ ] CON-02 Define specialist, discovery, billing/upgrade/credit, availability, appointment, earnings/payout, dashboard and review OpenAPI.
- [ ] CON-03 Define subscription/appointment/earning/review/moderation event schemas and required Care/Journal/Realtime consumer contracts.
- [ ] CON-04 Add Liquibase histories, constraints, indexes and field dictionary entries.
- [ ] CON-05 Implement specialist profile approval without document upload and with admin audit.
- [ ] CON-06 Implement discovery/matching with versioned explainable provenance.
- [ ] CON-07 Implement payment/renewal/upgrade, credit ledger, availability and race-safe idempotent appointment transitions.
- [ ] CON-08 Implement consented view/dashboard, reviews and review moderation.
- [ ] CON-09 Verify simultaneous booking/upgrade, exact proration rounding, transition conflicts, authorization, provider timeout, webhook/command duplicates, expiry and outbox/event duplicates.
- [ ] CON-10 Add observability/configuration, update README, and pass module/contract/migration gates.
