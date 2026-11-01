# MB-674 commercial reconciliation contract

Consultation owns payment, subscription credit, appointment settlement, specialist earning, and payout facts. The ADMIN APIs read those owner records. The frontend BFF forwards the authenticated ADMIN request and validates the returned owner DTOs; it does not calculate money or join another service's database.

| Owner fact | Authoritative identifiers available for filtering | Safe ADMIN view |
| --- | --- | --- |
| `payment_transaction` | payment ID, subscription ID, `idempotency_key`, `momo_order_id`, `momo_request_id` | `/api/v1/admin/payments`; exact payment provenance through `/api/v1/admin/commercial/provenance` |
| `specialist_payout` and latest `specialist_payout_attempt` | payout ID, specialist account ID, `idempotency_key`, `provider_payout_reference` | `/api/v1/admin/payouts` and payout detail |
| `service_credit_period`, `service_credit`, `specialist_earning`, `specialist_payout_item` | explicit owner foreign keys and immutable settlement snapshots | ADMIN subscription, payout detail, and provenance reads |

`X-Correlation-Id` identifies an HTTP request. It is not a persisted payment or payout correlation field and must never be used to infer financial ownership. The persisted idempotency keys and provider order/request/reference values are business transaction identifiers, so the owner filters them exactly. A payout provider reference matches any persisted attempt; list rows show the latest attempt while payout detail shows every attempt. There is no generic historical correlation ID spanning payment and payout. If operations need that distinct capability, add a nullable owner-generated business correlation field at transaction creation, index and expose exact filters in both owner list APIs, then update OpenAPI, BFF validation, and UI. Backfill only from verified owner records; leave unprovable historical values null.

The `from` bound includes its timestamp. Legacy `to` includes its timestamp. New `toExclusive` excludes its timestamp and cannot be combined with `to`; it must be later than `from`. The ADMIN UI converts selected `Asia/Ho_Chi_Minh` calendar days into `[local start, next local midnight)` UTC instants. This includes fractional-second transactions before midnight and excludes transactions at the following midnight.

Payment and payout list `count` is the number of rows in the response page, never the total number of matching transactions. Both lists order by their owner timestamp and UUID descending. The UI requests 51 rows, displays at most 50, and uses the extra row only to enable the next page. Offset pagination is stable for a fixed dataset; concurrent inserts can shift later pages. No total transaction count is claimed.

The ADMIN reconciliation journey is read-only except for the separately approved appointment dispute decision flow. Direct refunds, arbitrary earning adjustments, direct MoMo settlement, and automatic payout retry are outside this contract. Real-money gates remain disabled pending separate operational approval.
