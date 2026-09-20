# ADR 0020: Service-plan consultation credits

- Status: Accepted
- Date: 2026-09-20
- Decision ID: `MB-CONSULTATION-CREDIT-001`
- Implements: MB-377, MB-461, MB-462
- Refines: [ADR 0005](0005-consultation-billing-and-credit-settlement.md) and [ADR 0017](0017-product-scope-v2.md)

## Context

Consultation already resolves a server-authoritative current entitlement, but clients cannot safely turn a package name into a mutable credit balance. The paid purchase flow is not yet enabled, while controlled demos still need truthful, isolated provenance.

## Decision

- Consultation alone provisions and reports consultation credits.
- Policy `consultation-credit-v1` allocates `FREE=0`, `PLUS=1`, and `PREMIUM=3` indivisible credits for one effective entitlement period.
- Provisioning is idempotent for account, entitlement plan version, and exact period. Re-reading never duplicates a credit. An in-period `PLUS` to `PREMIUM` upgrade adds only ordinals two and three; downgrade is rejected.
- `DEMO` and `PAID` are persisted provenance, returned in the contract and shown explicitly to the user. Demo provisioning does not claim payment evidence.
- Current states are `AVAILABLE`, `HELD`, `CONSUMED`, and `FORFEITED`. `PROVISIONED`, `HELD`, `RELEASED`, `CONSUMED`, and `FORFEITED` are append-only ledger facts. Release returns the current credit to `AVAILABLE` while preserving the release fact.
- Only a held credit for the same appointment may be released, consumed, or forfeited. Every owner transition has an account-scoped idempotency key.
- The browser reads `GET /api/v1/service-credits` through the authenticated same-origin BFF. It displays the returned balance and never derives it from the plan.
- Purchase, renewal, cancellation, appointment booking, and real payment remain separate stories. This slice consumes the existing entitlement projection and introduces no payment endpoint.

## Consequences

The owner can later attach appointment commands to the credit transition API without changing browser balance semantics. Historical ledger facts and expired periods remain queryable. Real paid provisioning is not claimed until a verified payment flow writes a `PAID` entitlement period.
