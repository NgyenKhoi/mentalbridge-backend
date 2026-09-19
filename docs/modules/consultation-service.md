# Consultation Service specification

## Business boundary

Consultation owns specialist approval/discovery, `FREE`/`PLUS`/`PREMIUM` plan
versions, VND/MoMo purchase and upgrade, consultation credits, chat/video
availability, appointments and completion evidence, `SessionSummary`,
`AgreedNextSteps`, specialist earnings, payout reconciliation, and reviews. It
does not own identity roles, consent, journals, chat messages, reminder
delivery, or SupportPlan state.

## Use cases and acceptance

| Capability | Main behavior | Acceptance |
| --- | --- | --- |
| Current entitlement | Resolve the authenticated user's effective `FREE`/`PLUS`/`PREMIUM` package for owner-to-owner capability checks | No effective row returns `FREE`/`DEFAULT_FREE`; explicit `DEMO` and future `PAID` rows retain provenance and bounded windows; no client tier is trusted |
| Specialist approval | Submit public profile fields; admin approves, rejects, suspends, or restores with a stable reason | Only approved specialists are discoverable/bookable; suspension cancels future unstarted appointments with credit release; no credential document is collected |
| Subscription/billing | Publish immutable VND plan versions; accept MoMo webhooks; purchase paid packages; upgrade `PLUS` to `PREMIUM`; expose credit/earning history | Exact minor units; replay safe; no downgrade/user-refund API; real money disabled until price/allocation/credentials gates pass |
| Discovery/matching | Filter approved specialists and rank domain/support-area match, availability, language, timezone, rating, then experience | Deterministic pagination; criteria/policy version and explanation recorded; no disease/global-severity/clinical matcher or hidden health-data join |
| Availability | Publish non-overlapping 60-minute `IN_APP_CHAT` or `IN_APP_VIDEO` slots | New in-person/phone/external links rejected; invalid overlap rejected; video runtime requires its detailed contract |
| Appointment | Request, accept/reject/expire, cancel/reschedule, end channel, evaluate evidence, complete/no-show/dispute | At 60 minutes record `SESSION_ENDED` and close channel; only accepted server/provider evidence completes and consumes credit |
| Brief, summary, and next steps | Expose a user-approved pre-session `ConsultationBrief`; create post-session `SessionSummary`/`AgreedNextSteps`; submit resource proposal | No raw journals/answers/full AI history; reuse requires user approval; resource proposal becomes Care-owned `PlanChangeRequest`, not another plan |
| Payout | Encrypt/verify specialist destinations; submit idempotent MoMo payouts; reconcile result/IPN/status | MoMo is the sole production provider after credentials; `UNKNOWN` queried, not blindly retried; real payout currency must be approved |
| Consented view/dashboard | Show own workload and fetch scoped owner data | Care authorization is current and fails closed; Journal/AI returns only allowed fields; no remote call inside transaction |
| Review/moderation | One review after completed appointment; owner applies reviewed action | Participant/completion verified; duplicate rejected; evidence minimized; action/history auditable |

## Implementation design

- Feature slices: `specialists`, `discovery`, `matching`, `billing`, `credits`,
  `availability`, `appointments`, `consultation-briefs`, `session-summaries`,
  `plan-change-requests`, `earnings`, `payouts`, `reviews`,
  `review-moderation`, `dashboard`.
- Contract-first OpenAPI covers client/admin APIs and internal appointment eligibility projections. Appointment/review/moderation event schemas are versioned.
- Liquibase constraints/exclusion/locking are the final defense against overlap and double booking; use optimistic locking for editable aggregates.
- Care and Journal/AI calls use narrow consumer-owned ports, explicit deadlines/breakers and owner-side authorization. Billing is authoritative locally under ADR 0005; payment provider details stay behind an adapter and raw provider payloads are not persisted.
- MB-369 adds only `current_service_entitlement` as the authoritative current
  read model and `GET /internal/v1/entitlements/current`. It does not implement
  plan catalogue publication, subscription/payment lifecycle, MoMo, purchase,
  upgrade, consultation credits, or ledgers. Future billing may project a
  bounded `PAID` row into this model in its own transaction; explicit demo/test
  rows use `DEMO`, and no effective row is returned as `FREE`/`DEFAULT_FREE`.

## Ordered tasks

- [x] CON-01 Retain the historical ADR 0014 rationale and adopt ADR 0017's
  chat/video, evidence, summary reuse, and PlanChangeRequest target rules.
- [~] CON-02 Specialist approval and MB-362 availability OpenAPI are implemented; discovery, billing/upgrade/credit, appointment, earnings/payout, dashboard and review remain.
- [ ] CON-03 Define subscription/appointment/earning/review/moderation event schemas and required Care/Journal/Realtime consumer contracts.
- [~] CON-04 Story 6101 adds profile/approval persistence and MB-362 adds availability constraints, overlap exclusion, indexes, tombstones, and dictionary entries; other Consultation aggregates remain pending.
- [~] CON-05 Story 6101 implements save, submit, pending-admin queue/detail, and approve without document upload and with admin audit. Rejection/suspension/restoration remains Story 6102.
- [ ] CON-06 Implement discovery/matching with versioned explainable provenance.
- [ ] CON-07 Implement VND/MoMo purchase/upgrade, credit ledger, chat/video
  availability, channel end, and race-safe evidence-backed appointment
  transitions.
- [ ] CON-08 Implement consented view/dashboard, reviews and review moderation.
- [ ] CON-09 Verify simultaneous booking/upgrade, exact proration rounding, transition conflicts, authorization, provider timeout, webhook/command duplicates, expiry and outbox/event duplicates.
- [ ] CON-10 Add observability/configuration, update README, and pass module/contract/migration gates.
