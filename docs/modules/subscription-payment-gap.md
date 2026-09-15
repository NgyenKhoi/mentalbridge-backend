# Subscription and billing specification

The historical ownership gap is resolved by ADR 0005. ADR 0017 amends the
catalogue, currency, supported appointment modes, and earning gate for scope
v2. This file keeps its existing path so module links remain stable.

## Boundary

The `billing` feature inside `consultation-service` owns immutable plan versions,
paid periods, payment attempts and deduplicated provider events,
`PLUS`-to-`PREMIUM` upgrades, consultation credits and their append-only ledger,
specialist earnings, payout destinations/requests, and payout provider events.
It shares the Consultation PostgreSQL transaction boundary with booking and
evidence-backed completion. No other service stores an authoritative
entitlement, credit, earning, or payout balance.

## Current catalogue

| Plan | V2 price/paid period | Credits | Credit allocation | Specialist earning/completed credit |
| --- | ---: | ---: | ---: | ---: |
| `FREE` | VND 0 | 0 | Not applicable | Not applicable |
| `PLUS` | VND amount pending approval | 1 | Fixed VND amount pending approval | 70% of the credit's allocation |
| `PREMIUM` | VND amount pending approval | 3 | Fixed VND amount pending approval for each credit | 70% of each credit's allocation |

All plan facts are immutable and versioned. Specialist earning is 70% of the
fixed `creditAllocation` snapshotted on the consumed credit; it is never
calculated from the whole package price. One credit funds one 60-minute
`IN_APP_CHAT` or `IN_APP_VIDEO` appointment. Reviewed resources are not
count-limited by package; packages differentiate capabilities, credits, and AI
quota/model routing.

`FREE` includes the standard Support Guide, Journal, emotion check-in, and a
default five successfully delivered AI responses per day. `PLUS` adds a higher
AI quota, the persistent SupportPlan capability, and one credit per paid
period. `PREMIUM` adds three credits, advanced recommendation capability, and
may use a stronger model; no daily response limit is displayed, but server-side
token, rate, abuse, cost, and fair-use limits still apply.

## Subscription and payment

1. The server, never the client, resolves plan price/currency from a published plan version.
2. A new `PLUS`/`PREMIUM` purchase or `PLUS`-to-`PREMIUM` upgrade creates
   idempotent pending subscription/payment state.
3. Only a verified, deduplicated provider webhook may activate a period and grant credits.
4. The selected MoMo One-Time Payment contract does not create a provider-owned recurring subscription. MentalBridge owns paid periods; each purchase or upgrade creates a separate MoMo checkout/payment row and grants credits exactly once after verified success. Available credits expire at period end and do not roll over.
5. The v2 user-facing commercial API supports only new purchase and upgrade.
   It exposes no downgrade or user-initiated refund operation. Provider
   chargebacks remain immutable external reconciliation facts.

## Payment webhook/IPN contracts

MoMo is the only production payment provider. Local/CI may use a deterministic fake that implements the same MoMo-shaped contract; it is not a second payment method. The versioned IPN DTO is never deserialized directly into `payment_transaction`. The receiver validates the complete request in memory, verifies its signature, creates `momo_payment_ipn`, and only then locks and updates the matched payment. Raw payloads, signatures, decoded `orderInfo`/`extraData`, and wallet identifiers are discarded after hashing/verification.

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
is calculated in integer VND minor units from the published plan-version facts:

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
period, and grants three new credits atomically. Failed/expired checkout
releases them to `AVAILABLE` or `EXPIRED`.

Reserved credits are not offset or revoked by an upgrade; their appointments continue under the old snapshot. Consumed, expired, forfeited, and revoked credits have no upgrade value.

## Appointment and settlement

- Booking atomically reserves one slot and one earliest-expiring available credit.
- Specialist rejection/cancellation/no-show, platform failure, and eligible user cancellation release the credit.
- Rescheduling cancels the old appointment under its applicable credit rule and creates a new request; it never swaps or rewrites the old slot snapshot.
- The specialist publishes discrete 60-minute `IN_APP_CHAT` or `IN_APP_VIDEO`
  slots. Appointment creation snapshots interval, IANA timezone, and mode.
- At scheduled end the channel closes and the appointment becomes
  `SESSION_ENDED`; time expiry never auto-completes the appointment.
- Only `COMPLETED` backed by accepted server/provider evidence consumes the
  credit and creates one immutable earning snapshot. The specialist cannot
  complete unilaterally.
- `SESSION_ENDED`, cancellation, either no-show, and dispute create no earning.
- Earnings move from `PENDING_SETTLEMENT` to `AVAILABLE`, then attach to at most one idempotent provider payout.
- MoMo Disbursement is the only planned production payout adapter. Local/CI uses the deterministic fake implementing the same state contract.
- A logical payout fixes its destination, currency, amount, and earning items. Each provider call is a numbered attempt: a definite `FAILED` attempt may be retried with a new provider idempotency key, while `UNKNOWN` is queried and blocks another transfer attempt. The payout becomes `SUCCEEDED` only after verified provider confirmation/status reconciliation.
- Real MoMo payment and payout remain disabled until the VND price table, fixed
  VND `creditAllocation` values, and MoMo credentials are approved and
  configured. Runtime FX conversion is prohibited.

## Required contracts and verification

- OpenAPI: catalogue, purchase checkout/status/history, upgrade
  quote/checkout/status, credit balance/history, booking, and earnings/payout
  views; no downgrade or user-refund endpoint.
- Provider webhook schema: the complete MoMo required-field contract above, full signature verification, partner/order/request/amount checks, replay, status lookup, chargeback, and safe failure handling.
- Kafka facts, only where ADR 0016 criteria require them: minimized
  subscription, appointment, and earning status changes through the
  transactional outbox.
- Tests: concurrent last-credit booking versus upgrade, duplicate
  purchase/webhook/completion/payout, exact period-second rounding,
  payment/payout timeout and unknown outcome, period expiry and
  held/reserved-credit races, appointment cancellation/no-show/dispute,
  chargeback, and authorization.

## Remaining provider/configuration decisions

- exact MoMo `requestType`/payment methods enabled per environment and hosted-checkout behavior;
- MoMo credential provisioning, signature-key versioning/rotation, IP allow-list decision, and status-query reconciliation schedule;
- settlement delay and dispute handling;
- MoMo payout product access/credentials and encrypted destination onboarding;
- exact VND `PLUS`/`PREMIUM` prices and fixed per-credit `creditAllocation`;
- financial retention and chargeback reconciliation;
- `IN_APP_VIDEO` signaling/provider/security/evidence/failure contract; v2
  rejects new in-person, phone, and external-link appointments.
