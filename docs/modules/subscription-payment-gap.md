# Subscription and billing specification

The historical ownership gap is resolved by ADR 0005. ADR 0017 amends the
catalogue, currency, supported appointment modes, and earning gate for scope
v2. ADR 0022 prospectively amends credit quantities, rollover, and concurrent
reservation capacity. This file keeps its existing path so module links remain
stable.

## Boundary

The `billing` feature inside `consultation-service` owns immutable plan versions,
paid periods, payment attempts and deduplicated provider events,
`PLUS`-to-`PREMIUM` upgrades, consultation credits and their append-only ledger,
specialist earnings, payout destinations/requests, and payout provider events.
It shares the Consultation PostgreSQL transaction boundary with booking and
evidence-backed completion. No other service stores an authoritative
entitlement, credit, earning, or payout balance.

## Approved production-planned economics

| Plan | V2 price/paid period | Credits | Max active reservations | Credit allocation | Specialist earning/completed credit |
| --- | ---: | ---: | ---: | ---: | ---: |
| `FREE` | VND 0 | 0 | 0 | Not applicable | Not applicable |
| `PLUS` | VND 1,390,000 | 4 | 2 | VND 300,000 | VND 210,000 |
| `PREMIUM` | VND 3,490,000 | 10 | 4 | VND 300,000 each | VND 210,000 each |

These are production-planned values, not the active test prices. MB-622 offers
new immutable sandbox versions at PLUS 5,000 and PREMIUM 10,000 VND. The owner
approved `sandbox-full-price-v1`: upgrade charges the full 10,000 VND with zero
offset while preserving the nominal held-credit value as audit evidence.
Allocations/earnings are synthetic values, never cash redeemable from the test
purchase. See the [ADR 0032 amendment](../adr/0032-payment-and-specialist-payout-baseline.md).
[ADR 0047](../adr/0047-payos-active-gateway-momo-dormant.md) expressly permits
this unchanged test catalogue and zero-offset policy through real PayOS collect
on staging only (`https://staging.mentalbridge.online`). Production remains
disabled, MoMo stays dormant, and synthetic allocations/earnings cannot enter
real provider payout. Local/CI uses deterministic adapters without provider calls.
The credit quantities are the approved `consultation-credit-v2` runtime target.
The MB-377 `consultation-credit-v1` ledger remains `FREE=0`, `PLUS=1`,
`PREMIUM=3`; existing periods and ledger facts keep that exact provenance and
are never rewritten.

All plan facts are immutable and versioned. Specialist earning is 70% of the
fixed `creditAllocation` snapshotted on the consumed credit; it is never
calculated from the whole package price. One credit funds one 60-minute
`IN_APP_CHAT` or `IN_APP_VIDEO` appointment. Reviewed resources are not
count-limited by package; packages differentiate capabilities, credits, and AI
quota/model routing.

`FREE` includes the standard persisted Support Guide, Journal, emotion check-in, and a
default five successfully delivered AI responses per day. `PLUS` adds a higher
AI quota, the persistent SupportPlan capability, and four credits per paid
period. `PREMIUM` adds ten credits, advanced recommendation capability, and
may use a stronger model; no daily response limit is displayed, but server-side
token, rate, abuse, cost, and fair-use limits still apply.

## Subscription and payment

1. The server, never the client, resolves plan price/currency from a published plan version.
2. A new `PLUS`/`PREMIUM` purchase or `PLUS`-to-`PREMIUM` upgrade creates
   idempotent pending subscription/payment state.
3. Only a verified, deduplicated provider webhook or fully matched authenticated, signed-response-verified PayOS status reconciliation may activate a period and grant credits through the same locked atomic settlement path.
4. PayOS collect and the retained MoMo One-Time Payment contract do not create a provider-owned recurring subscription. MentalBridge owns paid periods; each purchase or upgrade creates a separate provider checkout/payment row and grants credits exactly once after verified success. Available credits expire at period end and do not roll over.
5. The v2 user-facing commercial API supports only new purchase and upgrade.
   It exposes no downgrade or user-initiated refund operation. Provider
   chargebacks remain immutable external reconciliation facts.

## Payment webhook/IPN contracts

PayOS is the active staging-only collect provider under ADR 0047; MoMo remains dormant and its IPN/storage contracts are retained. Local/CI uses deterministic FAKE with simulated in-app QR details and no real bank transfer. Provider payloads are never deserialized directly into `payment_transaction`. The receiver verifies the complete original request in memory before business coercion, records minimized verified receipt evidence and then locks/updates the matched payment. Raw payloads, signatures, decoded `orderInfo`/`extraData` and account identifiers are discarded after hashing/verification.

### PayOS webhook and authenticated reconciliation

Use official `vn.payos:payos-java:2.0.1` for create/get/cancel and webhook
verify/confirm. Confirmed PAYOS/FAKE checkout returns VietQR payload, bank BIN,
account number/name, amount/description and chosen expiry for in-app rendering;
a hosted HTTPS link is an optional fallback. The chosen expiry survives a null
provider expiry. Replay an uncertain create as the original pending command,
without automatically sending a second create.

The public `/internal/v1/payments/payos/webhook` preserves complete signed data
for SDK verification, then requires successful envelope/data codes, exact
provider/order/link/amount and a unique usable transaction reference on a
pending unexpired payment. A previously unknown link may bind from the first
authenticated exact event/query; a known link must match. Exact redelivery is
idempotent. Signed unknown confirmation samples, mismatches, reused references
and terminal late success remain `VERIFIED_UNMATCHED` for admin handling.

Bounded pending reads/expiry work may reconcile authenticated signed-response
SDK queries, with exact successful transaction identity/reference and amount
checks. UNDERPAID, pending states and outages never activate. Expiry cancels
the provider link while preserving local finality and upgrade-held credit
restoration. No database transaction spans provider calls. Return/cancel URLs
are not payment evidence. Nullable `terminalReason` records EXPIRED, CANCELLED
or PROVIDER_FAILED on FAILED only; legacy null remains supported.

### MoMo One-Time Payment v2 IPN

For the initial `captureWallet`/`payWithMethod` adapter, the signed IPN contract requires `partnerCode`, `orderId`, `requestId`, `amount`, `orderInfo`, `orderType`, `transId`, `resultCode`, `message`, `payType`, `responseTime`, `extraData`, and `signature`. Method-specific fields such as `partnerUserId`, `storeId`, `paymentOption`, `userFee`, and `promotionInfo` are optional and must not be required by the base DTO.

- Verify HMAC-SHA256 over MoMo's exact documented canonical field sequence before trusting any value.
- Match `partnerCode`, `orderId`, `requestId`, and `amount` against local configuration/payment; currency is contract-fixed to VND for this adapter.
- Map `orderId` to `momo_order_id`, `requestId` to `momo_request_id`, positive/usable `transId` to `momo_trans_id` (otherwise keep the transaction field null), `resultCode` to `momo_result_code`, `payType` to `momo_pay_type`, retain exact `momo_response_time_epoch_ms`, and parse it to UTC `provider_occurred_at`.
- Only `resultCode = 0` is a completed successful payment. `9000` is authorized, not sufficient to activate a subscription; other codes follow the provider result-code policy.
- A SHA-256 retry key is derived from the contract version and verified tuple `partnerCode|orderId|requestId|transId|resultCode|responseTime`.
- Persist/ack the result quickly and return HTTP `204` within MoMo's documented 15-second limit; downstream Kafka/notification work occurs after commit.

`safe_optional_details` is the versioned extension point for allow-listed non-sensitive optional MoMo fields. Supporting a new optional MoMo field changes the DTO/allow-list and tests, not the relational schema. A new business invariant or queryable identifier still requires deliberate modelling rather than hiding it in JSON.

Official references: [MoMo One-Time Payment/IPN](https://developers.momo.vn/v3/docs/payment/api/wallet/onetime/) and [MoMo Payment Notification](https://developers.momo.vn/v3/docs/payment/api/result-handling/notification/).

## Upgrade

Only `PLUS` to `PREMIUM` is an upgrade. `FREE` to either paid package is a new
purchase. A `PREMIUM` to `PLUS` request returns
`SUBSCRIPTION_DOWNGRADE_NOT_SUPPORTED`; no user refund endpoint exists.

An immediate upgrade starts a full `PREMIUM` period. A non-withdrawable offset
under historical/ordinary `upgrade-offset-v1` is calculated in integer VND
minor units from the published plan-version facts:

```text
remainingFeatureValueMinor = floor(
    oldNonConsultationValueMinor * remainingPeriodSeconds / totalPeriodSeconds
)
availableCreditValueMinor = sum(allocatedValueMinor for AVAILABLE credits)
amountDueMinor = newPlanPriceMinor
    - remainingFeatureValueMinor
    - availableCreditValueMinor
```

The period fraction uses actual UTC seconds. Upgrade checkout changes included
available credits to `UPGRADE_HELD` so booking cannot consume them concurrently.
Verified payment revokes them, ends the old period, starts the full `PREMIUM`
period, and grants ten new v2 credits atomically. Failed/expired checkout
releases them to `AVAILABLE` or `EXPIRED`.

Reserved credits are not offset or revoked by an upgrade; their appointments continue under the old snapshot. Consumed, expired, forfeited, and revoked credits have no upgrade value.

For sandbox-full-price-v1 only, the applied offset is zero regardless of nominal
available credit value. Both versions must use the same pricing policy; mixing
historical ordinary periods into sandbox pricing is rejected. The pricing policy
is snapshotted on each upgrade. The existing hold/revoke/release lifecycle is
unchanged. A new billing-created credit period snapshots the purchased immutable
plan-version UUID and its per-credit allocation; existing legacy/demo periods
retain their exact prior provenance, without reprovisioning.

## Appointment and settlement

- Booking requires a package allowed to book, one earliest-expiring `AVAILABLE`
  credit, active reservations below the package cap, and a still-selectable
  slot; Consultation holds the slot and credit atomically.
- Specialist rejection/cancellation/no-show, platform failure, and eligible user cancellation release the credit.
- Rescheduling preserves the old appointment snapshot and creates a linked
  replacement request as one logical reservation replacement. The old
  reservation is released/rebound atomically, or excluded from the cap check,
  so the user's own occupied cap cannot by itself block rescheduling.
- The specialist publishes discrete 60-minute `IN_APP_CHAT` or `IN_APP_VIDEO`
  slots. Appointment creation snapshots interval, IANA timezone, and mode.
- At scheduled end the channel closes and the appointment becomes
  `SESSION_ENDED`; time expiry never auto-completes the appointment.
- Only `COMPLETED` backed by accepted server/provider evidence consumes the
  credit and creates one immutable earning snapshot. The specialist cannot
  complete unilaterally.
- `SESSION_ENDED`, cancellation, either no-show, and dispute create no earning.
- Earnings move from `PENDING_SETTLEMENT` to `AVAILABLE`, then attach to at most one idempotent provider payout.
- The settlement/dispute hold is seven days. Minimum withdrawal is VND 100,000,
  requested on demand, with at most one logical payout per specialist per day.
- MoMo Disbursement is the only planned production payout adapter. Local/CI uses the deterministic fake implementing the same state contract.
- A logical payout fixes its destination, currency, amount, and earning items. Each provider call is a numbered attempt: a definite `FAILED` attempt may be retried with a new provider idempotency key, while `UNKNOWN` is queried and blocks another transfer attempt. The payout becomes `SUCCEEDED` only after verified provider confirmation/status reconciliation.
- Real MoMo payment and payout remain disabled until separate production
  approval and credentials are configured. Runtime FX conversion is prohibited.
- Dormant MoMo checkout uses One-Time E-Wallet `requestType=captureWallet`.
  PayOS pay-in uses in-app VietQR. Local/CI uses deterministic FAKE and never
  depends on either provider's availability.

## Required contracts and verification

- OpenAPI: catalogue, purchase checkout/status/history, upgrade
  quote/checkout/status, credit balance/history, booking, and earnings/payout
  views; no downgrade or user-refund endpoint.
- Provider webhook schemas: the complete retained MoMo contract and complete PayOS signed envelope/data, full SDK signature verification and provider-specific identity/amount checks, duplicate/late/terminal receipts, authenticated status reconciliation, chargeback and safe failure handling.
- Kafka facts, only where ADR 0016 criteria require them: minimized
  subscription, appointment, and earning status changes through the
  transactional outbox.
- Tests: concurrent last-credit booking versus upgrade, duplicate
  purchase/webhook/completion/payout, exact period-second rounding,
  payment/payout timeout and unknown outcome, period expiry and
  held/reserved-credit races, appointment cancellation/no-show/dispute,
  chargeback, and authorization.

## Remaining provider/configuration decisions

- production MoMo payment methods and hosted-checkout behavior beyond the
  approved sandbox `captureWallet` test path;
- MoMo credential provisioning, signature-key versioning/rotation, IP allow-list decision, and status-query reconciliation schedule;
- production MoMo payout product access/credentials and operational onboarding;
- financial retention and chargeback reconciliation;
- `IN_APP_VIDEO` signaling/provider/security/evidence/failure contract; v2
  rejects new in-person, phone, and external-link appointments.
