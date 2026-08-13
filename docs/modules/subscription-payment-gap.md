# Subscription and payment architecture gap

## Requirement

The updated project-tracking workbook adds WBS 42-51, 108-110, and 123-129: plan discovery, premium subscription, payment/status/history, consultation-credit balance/history, specialist earnings/payout views, and financial administration. Booking WBS 62-72 now also reserves, returns, or consumes consultation credits.

## Why implementation is blocked

ADR 0001 fixes seven deployables and does not assign an owner for subscription, payment, an immutable financial ledger, consultation credits, specialist earnings, or payouts. The current PostgreSQL baseline has no authoritative financial aggregates. Assigning these facts opportunistically to Identity or Consultation would couple authentication/booking to provider webhooks and create ambiguous balances.

An accepted ADR must decide whether the financial context is a new deployable or a cohesive bounded context inside an existing deployable. It must identify authoritative storage and transaction boundaries without introducing distributed transactions.

## Required decisions

- Plan versioning, price/currency, duration, benefits, renewal and cancellation semantics.
- Payment provider/methods, checkout/idempotency, signed webhook verification, unknown outcomes and reconciliation.
- Immutable payment/ledger model, refunds, chargebacks, duplicate/out-of-order provider events and financial retention.
- Consultation-credit grant, reservation, consume, return, expiry and adjustment rules.
- Appointment protocol when credit reservation succeeds but booking fails, and the reverse; compensating actions and recovery ownership.
- Specialist earning calculation, settlement delay, adjustments, payout provider, payout failure/retry and reconciliation.
- User/specialist/admin authorization, minimized projections, audit and observability.

## Acceptance required before implementation

- Exactly one authoritative balance/ledger exists; no service derives a mutable balance independently.
- Replayed client commands and provider webhooks cannot double-charge, double-credit, double-consume or double-pay.
- Booking and financial state converge after timeout/crash without a distributed database transaction.
- Rejection/cancellation/reschedule/completion transitions apply the approved credit rule exactly once.
- Monetary values use explicit currency and exact decimal/minor-unit semantics; no floating-point contract values.
- Financial admin access does not expose unnecessary health, journal, chat or payment-provider payload data.

## Follow-up artifacts after ADR approval

1. Update architecture, module boundaries, ADR 0001 relationship, deployment and ownership maps.
2. Add OpenAPI and provider webhook schemas before implementation.
3. Add owner migrations and field dictionary descriptions for plans, subscriptions, payments, ledger entries, credits, earnings, settlements and payouts.
4. Update Consultation consumer contracts and compensation workflows.
5. Add concurrency, idempotency, webhook signature/replay/order, timeout, refund/chargeback, reconciliation and payout-failure tests.
