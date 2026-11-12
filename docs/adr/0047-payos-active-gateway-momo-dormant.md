# ADR 0047: PayOS active gateway, MoMo dormant

- Decision ID: `MB-PAYOS-PAYIN-001`
- Status: Accepted by owner
- Date: 2026-10-30
- Delivery: MB-786 pay-in; MB-787 payout remains blocked; MB-788 live staging QA
- Later amendments: MB-787 below authorizes staging payout; [MB-812](#mb-812-funded-staging-demo-amendment-2026-11-04) adds the prospective funded-demo exception. Earlier baseline clauses remain historical context where amended.

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
activate a subscription. Exactly matched verified `PAID` evidence may settle a
still-pending payment after its local checkout expiry: PayOS enforces the payment
link deadline, while webhook delivery and authenticated status observation may
arrive later. Local processing time alone cannot reject that proof. A provider
status query that fails, or returns `PAID` without exact matching evidence,
cannot itself cause local expiry. During retry backoff or an in-flight query,
another owner read or scheduler tick preserves the `PENDING` payment. A later
successful non-`PAID` provider answer may trigger ordinary local expiry.
At 24 hours after `expires_at`, any still-`PENDING` PayOS payment closes as
`FAILED` with `terminalReason=EXPIRED` and reconciliation failure code
`PAYOS_REVIEW_DEADLINE`, even when retry backoff remains. This restores
upgrade-held credits, attempts provider-link cancellation, and lists the
payment as `PAYMENT_REVIEW` in admin commercial discrepancies. A later verified
`PAID` receipt for that terminal payment is `VERIFIED_UNMATCHED` with
`PAYMENT_ALREADY_FINAL`; it grants no entitlement or automatic refund.
Reconcile pending attempts with bounded cadence/backoff; provider outages
preserve uncertainty until the hard cap. An admin close endpoint is a follow-up.
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

## MB-787 payout amendment (2026-11-04)

- Decision ID: `MB-PAYOS-PAYOUT-001`
- Status: Accepted by owner; the channel prerequisite is unblocked and the
  brief's recommended choices are accepted. MB-788 owns live interoperability.

This prospective amendment supersedes this ADR's earlier "payout remains
blocked", missing-channel and "does not enable a PayOS payout mode" clauses,
and ADR 0005's sole-MoMo payout clause. Consultation remains the financial
owner. PAYOS payout is permitted only on the approved staging deployment and
has separate credentials/readiness from collect. Complete payout credentials,
valid destination encryption and the approved official HTTPS provider origin
are required; production remains blocked. DISABLED, deterministic FAKE and
dormant independently gated MOMO remain supported; unknown modes fail closed.
The collect test-pricing exception does not permit redemption of synthetic
300,000 VND allocations or 210,000 VND earnings. Seven-day hold, 100,000 VND
minimum, one logical request per server day and 70% allocation are unchanged.

Specialists save encrypted immutable bank revisions using a listed six-digit
BIN, account and holder name. Every material edit, including a holder-only
change or return to an old account, requires a new current
PENDING_CONFIRMATION revision. Only an ADMIN may inspect exact decrypted
account/name for a manual holder check and confirm its locked version; reads
and confirmation are audited without bank data and return no-store. Lists,
specialist responses and payout history remain masked. Confirmation is bound
to that exact revision, makes no provider call and is separate from provider
validation. It is not a per-withdrawal approval: subsequent specialist requests
to an unchanged confirmed account dispatch automatically when eligible.

Use the pinned official `vn.payos:payos-java:2.0.1` payout client with collect's
bounded timeout, no automatic HTTP retries/redirects or sensitive logging.
Create a one-item batch with `validateDestination=true`. Persist a UUIDv4
attempt key before HTTP and pass it explicitly as `x-idempotency-key`; the
outer batch reference is that key and the child reference is the separate
attempt UUID. A logical payout fixes its provider, destination revision,
currency, amount and earning items. Keep the outer payout ID distinct from a
child transaction ID. No database transaction spans a provider call.

Only authenticated, SDK signature-verified, exactly matched child transaction
evidence proves payment: expected reference, integer VND amount, bank BIN and
account must match the encrypted snapshot and child state must be SUCCEEDED.
Outer approval/COMPLETED/PARTIAL_COMPLETED and balance are not settlement
proof. A definite matched no-payment rejection may fail; timeout, duplicate
key, interruption, unknown result or inconsistent evidence preserves UNKNOWN
and reserved funds. Keep only safe public failure codes, never provider text
or account payload. Preserve terminal success and reject stale prior-attempt
evidence under the current-attempt lock.

Recover uncertain creation by authenticated lookup using the stored outer
reference followed by detail query. Query existing attempts through their
stored provider and retained credentials independently of the new-submission
mode. Poll with persisted schedule/lease at 5s, 15s, 30s, 60s, 2m and then at
most every 5m, in bounded batches. At the absolute 24-hour horizon from first
submit start, stop automatic polling and retain UNKNOWN/NEEDS_REVIEW plus
reserved funds. Never automatically create another transfer. No payout
webhook is introduced; collection callback examples are not payout authority.

Specialist command replay observes the original outcome, including FAILED.
Only an explicit keyed ADMIN retry may create the next numbered attempt after
definite FAILED, retaining original provider and frozen items/destination.
Revalidate current confirmation, readiness, original hold/dispute/fixture
eligibility and lineage. Unknown/active/review attempts cannot retry. Linked
AVAILABLE failed-payout items remain owned by their original payout and are
reported as retryPendingVnd, excluded from new withdrawals and their minimum.
The owner funds PayOS balance/service credits manually before explicit retry.

Proxy VietQR's public bank list server-side with bounded timeout and a 24-hour
in-process cache, returning only BIN/name/shortName. No fresh cache on upstream
failure yields 503 without hiding existing destinations. This list makes no
PayOS eligibility or automatic account-name verification claim.

Migration is additive: retain encrypted historical destination revisions,
legacy verification semantics and MoMo keys/IPNs; add PAYOS provider checks,
pending nullable verification and actor/version, immutable current revisions,
outer/child references and polling/lease/review fields. Preserve earning/item,
logical daily-request and provider-key uniqueness. Deploy the owner migration
before new writers; synchronize frontend consumers and drain old writers
before enabling PAYOS. Paired owner data models/dictionaries and isolated
contract, migration, readiness, authorization, idempotency, concurrency,
uncertainty, retry and signed SDK fixtures are required. No real provider call
or shared database mutation is implementation evidence; funded live staging
interoperability and eligible funded earning QA remain MB-788.


MB-787 failed-destination recovery amendment (2026-11-04): an explicit keyed
ADMIN POST `/api/v1/admin/payouts/{payoutId}/close-failed` checks the latest
FAILED/COMPLETE attempt and fixed DESTINATION_UNUSABLE reason, records
`closed_at`/`closed_by`, releases active item ownership and audits
`consultation.payout.closed` / `PAYOUT_CLOSED` atomically without a provider call.
The payout remains FAILED and its original destination, provider, amount and
item history stay immutable. `closedAt` is exposed on specialist/admin payout
projections and forbids retry. Partial unique active earning ownership preserves
one live allocation while retaining released historical links. The next
withdrawal uses the current confirmed destination and all normal eligibility,
minimum and daily-cap gates; closure never releases UNKNOWN/active/review money.
Migration `20261104090000-close-failed-payout.sql` must precede new writers, and
legacy writers must be drained before closure is enabled. Claude must sync the
contract and add the admin action; MB-788 still owns live interoperability.

## MB-812 funded staging demo amendment (2026-11-04)

- Decision ID: `MB-STAGING-DEMO-PAYOUT-001`
- Status: Accepted by owner; MB-788 owns provider minimum confirmation and live QA.

This prospectively amends D1's synthetic-only earning and unchanged hold/minimum
clauses, the MB-787 blanket sandbox exclusion, and ADR 0032's fixed allocation
and baseline hold/minimum only for the explicit staging demo gate. The exact
rule, rounding and compatibility authority is the
[ADR 0032 MB-812 amendment](0032-payment-and-specialist-payout-baseline.md#mb-812-funded-staging-demo-amendment-2026-11-04).

The flag defaults false and requires `staging` without `prod`/`production`, even
for mixed profiles. Newly created `staging-demo-paid-v1` earnings bind an exact
succeeded real PAYOS payment and matching processed successful verified receipt
to the paid credit's immutable purchased plan. Allocation rounds collected
VND per frozen plan credit down, then the 70% specialist share rounds down.
Legacy, FAKE, fixture and unverified earnings remain non-redeemable. Only
separately ready PAYOS payout may redeem the funded exception; prepare and
explicit retry revalidate the gate and lineage. No production rule or older
earning snapshot changes.

Configured staging minimum `2000` and hold `PT10M` permit three PLUS sessions
(2,625 VND) or three PREMIUM sessions (2,100 VND) to qualify. SDK 2.0.1 offline
material does not confirm the provider floor, so MB-788 must verify acceptance
of the owner-directed 2,000 VND fallback before any live transfer. This does
not authorize deployment, migration execution or provider calls in MB-812.
MB-787's daily cap, exact destination confirmation, idempotency, provider
affinity, signed child proof, polling and close/retry rules stay unchanged.

## MB-813 payout rejection runtime clarification (2026-11-05)

The existing definite no-payment rejection rule includes HTTP 401/403 returned
by the initial payout create call. They become `FAILED` with safe failure code
`PAYOUT_PROVIDER_ACCESS_DENIED` and reconciliation `COMPLETE`, using the existing
atomic terminal-failure transition and FAILED-only explicit ADMIN retry. This
depends on the existing single submission per persisted attempt, disabled SDK
and HTTP retries/redirects, and replay observing rather than submitting again.
It does not classify a later query's authentication failure as proof of no payout.

The [PayOS API reference](https://payos.vn/docs/api/) documents create HTTP
401/403, a separate `x-idempotency-key` and a `referenceId` list filter. It gives
no bounded list-visibility guarantee that would make an empty result definitive
after a grace window. The pinned SDK exposes HTTP status and provider code in
[APIException](https://raw.githubusercontent.com/payOSHQ/payos-lib-java/v2.0.1/src/main/java/vn/payos/exception/APIException.java)
and honors disabled automatic retries in
[PayOS.execute](https://raw.githubusercontent.com/payOSHQ/payos-lib-java/v2.0.1/src/main/java/vn/payos/PayOS.java).
An empty list, query rejection, timeout or ambiguous result therefore retains
UNKNOWN and reserved funds, including NEEDS_REVIEW at the existing 24-hour
horizon. This clarification grants no new manual authority to mark uncertainty
FAILED or release its funds.

Diagnostics retain only operation, payout/attempt UUIDs, HTTP status and a
bounded sanitized provider code, excluding provider text, payloads, credentials,
bank details and exception messages. Existing `failureCode` strings accommodate
the new safe value without a contract or consumer change. There is no migration
or web slice; only consultation requires rebuilding/restarting. MB-787/MB-812
gates and immutable financial facts remain intact.
