# ADR 0047: PayOS active gateway, MoMo dormant

- Decision ID: `MB-PAYOS-PAYIN-001`
- Status: Accepted by owner
- Date: 2026-10-30
- Delivery: MB-786 pay-in; MB-787 payout remains blocked; MB-788 live staging QA

## Context

MoMo has no approved live-money history and its account is disabled. The owner
approved PayOS collect, an in-app VietQR checkout and the official Java SDK.
PayOS has no sandbox; any provider transfer is real money. The owner has not
created the payout channel or supplied payout credentials.

## Decision

Consultation remains the sole owner of subscription payment, credits and
settlement. PayOS is the active real collect provider. MoMo implementation,
configuration, IPN endpoint and historical storage remain dormant and can be
selected again by configuration after their existing readiness gates pass.
DISABLED and deterministic FAKE remain supported. A mode change never relabels
historical payments or resubmits an existing provider attempt.

The only authorized PayOS real-money environment is
`https://staging.mentalbridge.online`. There is no production enablement or
production cutover in this decision. Collect readiness requires complete
collect credentials, approved HTTPS provider/webhook/return/cancel URLs and the
staging-only pricing/environment gate; no payout keys are required for pay-in.
Production remains blocked even when collect credentials exist. MB-788 owns
webhook registration, real transfer execution and live verification evidence.

**Owner decision D1:** the existing immutable test catalogue (`FREE=0`,
`PLUS=5,000 VND`, `PREMIUM=10,000 VND`) may be charged through PayOS on staging
only. `sandbox-full-price-v1` continues to charge a PLUS-to-PREMIUM upgrade the
full 10,000 VND target price with zero offset. This is an explicit exception
to ADR 0032's FAKE-or-MoMo-test-only fixture gate, not a new price policy.
The 300,000 VND credit allocation and 210,000 VND earning remain synthetic,
non-redeemable demonstration values and can never enter real provider payout.
Production-planned 1,390,000/3,490,000 VND prices remain documentation inputs.
Credit quantities `0/4/10`, no rollover, held-credit restoration, seven-day
settlement hold, 100,000 VND minimum withdrawal and historical facts are unchanged.

Use official `vn.payos:payos-java:2.0.1`, mapped from typed collect settings.
Boot 4.1/Java 21 compatibility must be demonstrated by compilation. Creation,
query, cancellation, webhook verification and confirmation use SDK APIs;
do not replace the SDK with a hand-written client. Numeric order codes are
provider-scoped unique sequence values within the SDK safe-integer range.
Descriptions have at most nine characters and contain no health information.

The primary checkout is VietQR rendered inside MentalBridge. The server returns
QR payload, bank BIN, account number/name, exact amount/description and chosen
expiry; a hosted HTTPS checkout URL is optional fallback only. Persist a pending
attempt before creating a provider link, call the provider outside database
transactions and replay the original command without automatic second creation
when the result is uncertain. FAKE returns deterministic simulated QR details
without a network request or a real-transfer claim.

A signed webhook or an authenticated, signed-response-verified SDK status query
may prove settlement. Both enter the same locked atomic transition that marks
the payment successful, activates the exact immutable plan and grants credits
exactly once. A successful envelope and signed data code, expected PAYOS
provider/order code/payment-link ID, exact integer VND amount and unique usable
transaction reference are required. `PAID` alone, UNDERPAID, a client redirect,
scan or return/cancel URL cannot prove payment. Preserve all signed webhook
fields for SDK verification before parsing or coercing business fields.
If creation was uncertain and no link ID was stored, the first authenticated
exact event/query may bind that ID under the same lock; a known ID must match.

Exact redelivery acknowledges the prior result without a second grant. Signed
unknown confirmation samples, mismatches, reused references and success after
a committed terminal state are `VERIFIED_UNMATCHED` for admin review and never
activate a subscription. A success after page return may settle only while the
payment is still pending and unexpired. Reconcile pending attempts with bounded
cadence/backoff, including before expiry; provider outages preserve uncertainty.
Cancel expired provider links while preserving local locked finality and exact
upgrade-held credit restoration. No transaction spans a provider network call.
Persist only minimized sanitized receipt evidence; discard signatures, raw
payloads and bank identifiers from the receipt trail under current privacy and
retention rules.

**Owner decision D2:** preserve ledger states
`PENDING|SUCCEEDED|FAILED|CHARGEBACK` and add nullable
`terminalReason=EXPIRED|CANCELLED|PROVIDER_FAILED`. It is persisted from server
expiry or a verified provider result on FAILED; legacy failed rows may have
null. Return/cancel navigation never sets this field. Terminal payment outcomes
are never reopened.

No user refund API or refund adapter is introduced. PayOS payout/bank destination
work requires MB-787 after channel/keys exist, including the separate approved
first-bank-account admin confirmation. Existing payout behavior stays gated;
this decision does not enable a PayOS payout mode.

## Explicit amendments and compatibility

This decision prospectively amends the sole-MoMo and webhook-only proof clauses
in [ADR 0005](0005-consultation-billing-and-credit-settlement.md), the provider
and real-money gates in [ADR 0017](0017-product-scope-v2.md#payment-credits-earnings-and-payout)
and [ADR 0022](0022-current-product-blueprint-amendments.md#7-financial-semantics-are-unchanged-per-consumed-credit),
and the fixture-provider gate in
[ADR 0032](0032-payment-and-specialist-payout-baseline.md#mb-622-sandbox-amendment-2026-10-11).
The [blueprint](../CURRENT_PRODUCT_BLUEPRINT.md),
[decisions log](../requirements-traceability/decisions.md),
[billing specification](../modules/subscription-payment-gap.md) and
[Consultation policy v2](../policies/consultation-specialist-policy-v2.md#financial-boundary)
follow these provider/proof exceptions without changing their credit, earning,
purchase or refund rules. Historical provider and policy snapshots are retained.

Migration is additive: retain MoMo columns/receipts, introduce PayOS-specific
identifiers and minimized receipts, allow PAYOS in payment provider checks and
add nullable terminal reason/QR transfer fields. Deploy the owner migration
before new writers. New public QR fields and terminalReason allow absence/null
for legacy, non-QR and unconfirmed rows. Consumers must accept PAYOS before the
mode is enabled and must not require a hosted URL for confirmed QR checkout.

## Consequences

- Backend provider contract precedes frontend generated consumers.
- Signature, mismatch, duplicate/late/terminal callbacks, reconciliation,
  upgrade restoration, migration constraints, readiness and FAKE QR require
  isolated automated evidence; no staging keys are needed for those gates.
- Real PayOS staging interoperability and financial execution remain MB-788
  evidence, separate from deterministic local/CI tests.
