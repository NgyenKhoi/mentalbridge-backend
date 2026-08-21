# Subscription and billing specification

The historical ownership gap is resolved by ADR 0005. This file keeps its existing path so module links remain stable.

## Boundary

The `billing` feature inside `consultation-service` owns immutable plan versions, paid subscription periods, payment attempts and deduplicated provider events, Care-to-Plus upgrades, consultation credits and their append-only ledger, specialist earnings, payout destinations/requests, and payout provider events. It shares the Consultation PostgreSQL transaction boundary with slot booking and appointment completion. No other service stores an authoritative entitlement, credit, earning, or payout balance.

## Current catalogue

| Plan | Price/month | Credits | Credit allocation | Specialist earning/completed credit |
| --- | ---: | ---: | ---: | ---: |
| Free | USD 0.00 | 0 | USD 0.00 | USD 0.00 |
| Premium Care | USD 9.99 | 1 | USD 5.00 | USD 3.50 |
| Premium Plus | USD 19.99 | 3 | USD 5.00 each | USD 3.50 each |

All plan facts are versioned. Paid plan prices comprise USD 4.99 of non-consultation features plus USD 5.00 per credit. Specialist earning is 70% of the explicit credit allocation; it is never calculated from the whole subscription. One credit funds one standard appointment; `IN_APP_CHAT` is the only initially enabled channel. Plus currently means more credits and priority/enhanced features, not a longer session.

## Subscription and payment

1. The server, never the client, resolves plan price/currency from a published plan version.
2. Checkout creates idempotent pending subscription/payment state.
3. Only a verified, deduplicated provider webhook may activate a period and grant credits.
4. The selected MoMo One-Time Payment contract does not create a provider-owned recurring subscription. MentalBridge owns the monthly lifecycle; each purchase, renewal, or upgrade creates a separate MoMo checkout/payment row. A successful renewal grants credits exactly once for the new period. Available credits expire at period end and do not roll over.
5. Cancellation stops paid access immediately and produces no refund. It cancels future appointments, closes their conversation eligibility, and revokes their unused/reserved credits. Only a confirmed appointment already inside its scheduled window may keep its reserved credit and finish at `scheduledEndAt`; the subscription remains `CANCEL_PENDING_SESSION_END` until then.
6. The MVP does not call a refund API. Provider chargebacks are ingested as immutable external facts for reconciliation.

Cancellation also fails a pending upgrade and revokes its held credits. No new booking or paid feature is authorized in `CANCEL_PENDING_SESSION_END`; this temporary state exists only so the already-started consultation can reach its scheduled end and settle normally.

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

Only Premium Care to Premium Plus is allowed. Free to paid is a purchase. Plus to Care returns `SUBSCRIPTION_DOWNGRADE_NOT_SUPPORTED`. A user can separately cancel Plus, losing paid access immediately without refund, and later buy Care as a new purchase; that is not a downgrade.

An immediate upgrade starts a full Plus period. A non-withdrawable offset is calculated in integer minor units:

```text
remainingFeatureValueMinor = floor(
    oldNonConsultationValueMinor * remainingPeriodSeconds / totalPeriodSeconds
)
availableCreditValueMinor = sum(allocatedValueMinor for AVAILABLE credits)
amountDueMinor = newPlanPriceMinor
    - remainingFeatureValueMinor
    - availableCreditValueMinor
```

The period fraction uses actual UTC seconds. Upgrade checkout changes included available credits to `UPGRADE_HELD` so booking cannot consume them concurrently. Verified payment revokes them, ends the old period, starts the full Plus period, and grants three new credits atomically. Failed/expired checkout releases them to `AVAILABLE` or `EXPIRED`.

Reserved credits are not offset or revoked by an upgrade; their appointments continue under the old snapshot. Consumed, expired, forfeited, and revoked credits have no upgrade value.

## Appointment and settlement

- Booking atomically reserves one slot and one earliest-expiring available credit.
- Specialist rejection/cancellation/no-show, platform failure, and eligible user cancellation release the credit.
- Rescheduling keeps the same credit reserved while swapping slots atomically.
- The specialist publishes discrete slots from their working schedule with start/end, IANA timezone, and channel; the exact standard duration remains a product decision. The user chooses one slot. Appointment creation snapshots those values, and chat join/send is authorized only in `[scheduledStartAt, scheduledEndAt)`.
- `IN_APP_CHAT` is enabled first. `IN_APP_VIDEO` is only a future intent until a separate call/signalling/provider/security contract is defined; there is no physical-location or external-meeting-link flow.
- Completion consumes the credit and creates one immutable earning snapshot.
- User late cancellation/no-show forfeits the credit but creates no earning under the current completed-only rule.
- Earnings move from `PENDING_SETTLEMENT` to `AVAILABLE`, then attach to at most one idempotent provider payout.
- MoMo Disbursement is the only planned production payout adapter. Local/CI uses the deterministic fake implementing the same state contract.
- A logical payout fixes its destination, currency, amount, and earning items. Each provider call is a numbered attempt: a definite `FAILED` attempt may be retried with a new provider idempotency key, while `UNKNOWN` is queried and blocks another transfer attempt. The payout becomes `SUCCEEDED` only after verified provider confirmation/status reconciliation.
- Real MoMo payment and payout remain disabled until VND plan versions or an explicit versioned FX policy are approved; current USD marketing prices cannot be silently converted at checkout or payout time.

## Required contracts and verification

- OpenAPI: catalogue, checkout/status/history/cancel, upgrade quote/checkout/status, credit balance/history, booking and earnings/payout views.
- Provider webhook schema: the complete MoMo required-field contract above, full signature verification, partner/order/request/amount checks, replay, status lookup, chargeback, and safe failure handling.
- Kafka facts: minimized subscription, appointment, and earning status changes through the transactional outbox.
- Tests: concurrent last-credit booking versus upgrade, duplicate checkout/webhook/completion/payout, exact month/second rounding, payment/payout timeout and unknown outcome, renewal failure, expiry/held/reserved races, immediate cancellation with future/live appointment, chargeback, and authorization.

## Remaining provider/configuration decisions

- exact MoMo `requestType`/payment methods enabled per environment and hosted-checkout behavior;
- MoMo credential provisioning, signature-key versioning/rotation, IP allow-list decision, and status-query reconciliation schedule;
- settlement delay and dispute handling;
- MoMo payout product access/credentials and encrypted destination onboarding;
- VND plan prices or an explicit versioned FX policy;
- financial retention and chargeback reconciliation;
- standard appointment duration, chat eligibility window, and late-cancellation cutoff.
