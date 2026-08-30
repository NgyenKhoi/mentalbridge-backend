# ADR 0005: Consultation billing and credit settlement

- Status: Accepted
- Date: 2026-08-21

## Context

The product now has three subscription tiers: Free, Premium Care, and Premium Plus. Paid plans grant consultation credits; booking reserves a credit; a completed consultation consumes it and creates specialist earnings. The existing architecture did not assign subscription, payment, credit, earning, or payout facts to an owner. Keeping booking and credit state in separate owners would require compensation for every timeout and crash, while adding an eighth deployable would increase the operating burden for the capstone team.

The specialist-document upload use case has been removed. Specialist eligibility still requires an administrator decision, but Consultation no longer stores or reviews credential-file metadata.

## Decision

### Ownership and storage

`consultation-service` owns a cohesive `billing` feature alongside specialist approval, availability, and appointments. It is the sole authority for plan versions, paid subscriptions, MoMo payment records/IPNs, consultation credits and their ledger, specialist earnings, settlement, and MoMo payouts. These aggregates live in `mentalbridge_consultation`; no other service stores a shadow entitlement, credit, or payable balance.

This does not add another deployable. Care, Realtime, and other consumers request the minimum current entitlement or appointment-eligibility decision through REST. Safe dashboard/reporting projections may consume versioned Kafka facts but are not authorization or balance truth.

### Plan catalogue

Plan versions are immutable after publication. Prices and allocations use integer minor units with ISO 4217 currency codes.

| Plan | Monthly price | Credits per paid period | Current consultation allocation | Specialist earning per completed credit |
| --- | ---: | ---: | ---: | ---: |
| Free | USD 0.00 | 0 | USD 0.00 | USD 0.00 |
| Premium Care | USD 9.99 | 1 | USD 5.00 | USD 3.50 |
| Premium Plus | USD 19.99 | 3 | USD 5.00 each | USD 3.50 each |

The current paid prices deliberately decompose into USD 4.99 for non-consultation premium features plus USD 5.00 for each included credit. The specialist share is 70% of the explicit USD 5.00 credit allocation, not 70% of the whole subscription price. The remaining USD 1.50 allocation and the non-consultation portion belong to the platform. A later price, currency, allocation, or share change creates a new plan version and never rewrites an existing credit or earning snapshot.

Free is the default entitlement when a user has no active paid subscription; it does not create a zero-value payment. Safety guidance, PHQ-9/GAD-7 scoring, and access to owned data must never be disabled by billing status.

Premium Plus provides more consultation credits and higher booking/matching priority. The ambiguous promise of a longer consultation is not part of the current catalogue: one credit always funds one standard appointment slot. A different duration or specialist compensation requires a new plan version and an explicit scheduling policy.

### Subscription and payment lifecycle

1. The client reads published plan versions and sends only a selected plan-version ID plus an idempotency key. The server supplies the authoritative price, currency, and benefits.
2. Consultation creates a `PENDING_PAYMENT` subscription and payment attempt, then redirects to or initializes the configured provider adapter.
3. A successful client redirect is not payment proof. Consultation verifies a signed provider webhook, deduplicates its provider event ID, validates the expected amount/currency, and atomically marks the payment successful, activates the billing period, and grants one durable credit row per included credit.
4. Every successful renewal grants a new non-overlapping period of credits exactly once. Credits do not roll over and available credits expire at their source period end.
5. User cancellation is immediate and produces no refund. It revokes non-consumed credits, cancels future appointments, closes their conversation eligibility, and disables every paid entitlement. The only exception is one confirmed appointment whose scheduled window has already started: it may finish at its snapshotted end instant, while every other paid feature stops immediately. The subscription uses `CANCEL_PENDING_SESSION_END` until that session completes, then becomes `CANCELLED`.
6. An unexpected provider chargeback is recorded and reconciled without deleting payment, credit, appointment, or earning history. User-initiated refund is an unsupported product operation and no refund adapter/API is implemented.

MoMo is the only production payment provider. The One-Time Payment v2 contract supplies `orderId`, `requestId`, and `transId`; the system stores these explicitly and derives a SHA-256 deduplication key from the contract version plus verified `partnerCode|orderId|requestId|transId|resultCode|responseTime` tuple. `momo_payment_ipn` snapshots every required non-sensitive reconciliation field, hashes `orderInfo`, `extraData`, and the full payload, and supports only an allow-listed JSON object for optional MoMo fields. Raw payload/signature, decoded arbitrary extra data, and wallet identifiers are not persisted.

This One-Time Payment integration does not create a MoMo-owned recurring subscription or automatic debit. MentalBridge owns the subscription periods, and every initial purchase, renewal, and upgrade creates its own idempotent MoMo payment attempt. Adding MoMo recurring/tokenized payment later requires a separate accepted contract and data review.

No payment succeeds from shape or signature alone. MoMo must match configured `partnerCode` plus local `orderId`, `requestId`, and amount, and only final `resultCode = 0` activates the period. A verified but unmatched IPN is acknowledged without business mutation. The versioned DTO verifies every required signature field, tolerates documented optional fields, and never binds MoMo JSON directly to the domain entity. Invalid/malformed requests do not persist attacker-controlled fields.

Exact MoMo payment method/request type, credential/key rotation, settlement delay, payout onboarding, and financial retention remain deployment/product configuration that must be approved before real-money integration is enabled. Local and CI environments use deterministic MoMo-shaped fake payment/payout adapters and synthetic accounts; no second production provider is supported.

### Upgrade and unsupported downgrade

Free to a paid plan is a new subscription. The only in-period upgrade is Premium Care to Premium Plus. A move from Premium Plus to Premium Care is rejected with `SUBSCRIPTION_DOWNGRADE_NOT_SUPPORTED`. A user may separately cancel Plus, lose paid access immediately without refund, and later buy Care as a new purchase; that is not a downgrade. Same-tier changes are renewal, not upgrade.

An immediate upgrade starts a full new Plus billing period when its payment webhook is confirmed. There is no cash or wallet refund. A short-lived, non-withdrawable upgrade offset recognizes only unused Care value:

```text
remainingFeatureValueMinor = floor(
    oldNonConsultationValueMinor * remainingPeriodSeconds / totalPeriodSeconds
)

availableCreditValueMinor = sum(allocatedValueMinor for credits in AVAILABLE)

upgradeOffsetMinor = remainingFeatureValueMinor + availableCreditValueMinor
amountDueMinor = newPlanPriceMinor - upgradeOffsetMinor
```

The calculation uses UTC instants and the actual number of seconds in the current provider period, not an assumed 30-day month. Money remains integer minor units; the remaining feature value rounds down to a minor unit. The server rejects expired periods, non-positive remaining time, currency mismatch, and an offset greater than the target price.

For example, exactly halfway through a Care period, an unused available credit produces `floor(499 * 1/2) + 500 = 749` minor units of offset, so Plus costs `1999 - 749 = 1250` minor units (USD 12.50). If the Care credit was already consumed, only 249 minor units remain and the charge is USD 17.50. A reserved credit follows the second calculation and stays with its existing appointment.

Creating an upgrade checkout locks the subscription and changes the `AVAILABLE` credits included in the offset to `UPGRADE_HELD`. That prevents the same credit from concurrently reducing the upgrade price and booking an appointment. Payment failure or quote expiry releases a held credit to `AVAILABLE`, or `EXPIRED` if its source period has ended. On a verified successful payment, those credits become `REVOKED`, the old period ends, a full Plus period starts, and three new Plus credits are granted atomically.

Consumed, expired, forfeited, and revoked credits have no upgrade value. A credit already `RESERVED` for an appointment is neither offset nor revoked: its appointment continues under the immutable Care allocation, while the newly paid Plus period receives its three credits. This can leave one old reserved credit plus three new credits, but the old credit was already purchased and was not used to reduce the upgrade price.

### Appointment, chat, and credit lifecycle

An appointment remains necessary even though the consultation channel is chat. It is the authoritative scheduled unit that prevents slot conflicts, attaches one credit, opens one appointment-scoped conversation, proves completion, permits one review, and creates at most one earning. Unrestricted direct specialist messaging would bypass Free/Premium entitlements and make completion and payout unverifiable, so it is not supported.

The first implemented consultation channel is `IN_APP_CHAT`. A specialist publishes discrete bookable slots from their own working schedule, choosing local start/end and IANA timezone; the server converts them to UTC and must validate them against the approved standard-duration policy once that duration is decided. The user chooses one exact slot. Booking copies its `scheduledStartAt`, `scheduledEndAt`, timezone, and channel into the appointment so history remains stable if availability changes. Send/join authorization is limited to that scheduled window; the conversation may remain readable outside it but cannot become 24/7 friend-style messaging.

`IN_APP_VIDEO` is a planned second channel because a bounded call is easier to start, end, and prove against an appointment. This decision reserves only the channel value; signaling, WebRTC/provider choice, call-room credentials, participant presence, recording prohibition, failure fallback, and completion evidence require a later contract/ADR before the channel can be enabled. Slots and appointments store no physical address, phone number, generic location, or external meeting link. In-person consultation is not planned by this decision.

Credit transitions are:

```text
AVAILABLE -> RESERVED -> CONSUMED
    |            |
    +-> EXPIRED  +-> AVAILABLE    (eligible rejection/cancellation)
    |            +-> FORFEITED    (policy-defined late cancellation/user no-show)
    |            +-> REVOKED      (subscription cancellation before session starts)
    +-> UPGRADE_HELD -> REVOKED   (successful upgrade)
             |
             +-> AVAILABLE/EXPIRED (failed or expired upgrade)
             +-> REVOKED           (subscription cancellation)
```

Booking locks one eligible slot and one earliest-expiring available credit in the same local database transaction, creates the appointment, changes the credit to `RESERVED`, and appends a ledger entry. The appointment must start within the credit's billing period. Repeating the command with the same user-scoped idempotency key returns the original result.

Specialist rejection, specialist cancellation, platform failure, and a user cancellation before the configured cutoff release the credit. Rescheduling keeps the same reserved credit and swaps slots atomically. A completed appointment consumes the credit and creates one immutable specialist earning snapshot in the same transaction. User late cancellation or no-show forfeits the credit under the initial policy but creates no specialist earning because the current product rule pays only completed consultations. Specialist no-show returns the credit.

Subscription cancellation is a different command from appointment cancellation. It fails any pending upgrade, revokes its held credits, cancels every future requested/confirmed appointment, and revokes those reserved credits instead of returning reusable value. The sole live-session exception keeps its credit `RESERVED` until completion or the normal in-window terminal outcome; no new booking or paid feature is authorized while the subscription is `CANCEL_PENDING_SESSION_END`.

Confirmation creates or enables exactly one Realtime conversation keyed by appointment ID. Realtime checks the current appointment eligibility for every open, subscribe, and send operation and fails closed when Consultation is unavailable. Chat access is never granted merely by account role or subscription status: join/send uses the appointment's snapshotted `[scheduledStartAt, scheduledEndAt)` window, while a separately approved retention policy may allow read-only history afterward.

### Earnings and payout

Completion snapshots the credit allocation, currency, 70% share basis points, specialist amount, and platform amount. An earning starts `PENDING_SETTLEMENT` and becomes `AVAILABLE` after the configured dispute/settlement delay.

Automatic payout remains in scope through MoMo only. Official MoMo documentation exposes wallet/bank disbursement, balance, status/IPN behavior, a test environment, and Single/Batch Disbursement test collections. MoMo Disbursement is enabled only after the project's M4B account is granted the required product credentials; local/CI uses a MoMo-shaped fake.

A logical payout idempotently fixes its destination, currency, amount, and earning items. Its numbered provider attempts move through `PENDING`, `PROCESSING`, `SUCCEEDED`, `FAILED`, or `UNKNOWN`; a definite failure may be retried with a new provider idempotency key, while an unknown result must be queried and blocks another transfer attempt. Only a verified provider result/IPN/status query marks the logical payout successful. Destination data is encrypted and never enters events/logs. MoMo payment and domestic payout use VND, while the current product prices are expressed in USD. Real payment and payout must remain disabled until product approval publishes VND plan versions or accepts an explicit versioned FX policy; the system must never convert a charge or earning silently at transaction time.

Official provider references:

- [MoMo One-Time Payment/IPN](https://developers.momo.vn/v3/docs/payment/api/wallet/onetime/)
- [MoMo Payment Notification handling](https://developers.momo.vn/v3/docs/payment/api/result-handling/notification/)
- [MoMo single disbursement](https://developers.momo.vn/v3/docs/payment/api/disbursement-v2/)
- [MoMo test/production integration environments](https://developers.momo.vn/v3/docs/payment/onboarding/integration-process/)
- [MoMo Single/Batch Disbursement collections](https://developers.momo.vn/v3/docs/payment/api/other/postman/)

Admin, user, and specialist views expose only role-appropriate projections. They never expose raw provider webhook payloads, payment credentials, journal/chat content, or unrelated health data.

## Consequences

- Booking, credit reservation/release/consume, and earning creation can be transactionally consistent without a distributed transaction.
- `consultation-service` gains MoMo payment/payout ports isolated from appointment domain code; real payment and payout stay disabled until credentials and settlement currency are approved.
- Care and Realtime gain narrow entitlement/eligibility REST dependencies with explicit deadlines and fail-closed behavior.
- The database needs plan/version/entitlement, subscription/payment/provider-event, upgrade/held-credit, credit/ledger, earning, payout destination/request/attempt/item/provider-event aggregates plus concurrency and idempotency constraints.
- Tests must cover simultaneous booking, simultaneous last-credit use, replayed checkout/webhooks/completion/payout, immediate cancellation with future/live appointments, renewal failure, expiry versus reservation, upgrade quote races/rounding, chargebacks, unknown payout outcomes, and provider timeouts.
- Removing verification documents reduces sensitive object-storage scope; specialist approval decisions and audit facts remain required.

## Rejected alternatives

- A new billing deployable: rejected for the current capstone scale because it adds operations and a cross-service booking saga without a demonstrated need.
- Identity-owned billing: rejected because authentication/account lifecycle must not own appointment credits or specialist payables.
- Realtime-owned consultation state: rejected because chat delivery cannot prove booking, completion, or earnings and MongoDB conversations are not the financial authority.
- Consuming a credit at booking: rejected because a rejected or eligible cancelled request is not a delivered consultation. Booking reserves; completion consumes.
- Paying 70% of the full subscription: rejected because the subscription also funds non-consultation features and unused credits must not create specialist payables.
