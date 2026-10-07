# ADR 0032: Payment and specialist payout baseline

- Decision ID: `MB-PAYMENT-PAYOUT-BASELINE-001`
- Status: Accepted
- Date: 2026-10-06
- Amends: [ADR 0005](0005-consultation-billing-and-credit-settlement.md),
  [ADR 0017](0017-product-scope-v2.md), and
  [ADR 0022](0022-current-product-blueprint-amendments.md)

## Context

The v2 catalogue fixed credit quantities and the 70% specialist share but left
VND price, credit-allocation, settlement, and withdrawal values pending. Local
integration also needs deliberately small payment fixtures without presenting
them as production economics.

## Decision

Production-planned values are documentation and design inputs only. They do
not become runtime prices and do not enable real-money production:

| Plan | Planned VND price per billing period | Credits | Allocation per consumed credit |
| --- | ---: | ---: | ---: |
| `PLUS` | 1,390,000 | 4 | 300,000 |
| `PREMIUM` | 3,490,000 | 10 | 300,000 |

Each evidence-backed completed appointment that consumes one credit creates
one immutable earning. The earning snapshots plan version, consumed credit,
the allocation attached to that credit period (300,000 VND for this policy),
7000 basis-point share, the arithmetically derived specialist amount,
currency, specialist, appointment, completion fact, and idempotency source.
For this policy the resulting 210,000 VND specialist share and remaining
90,000 VND allocation are not described as profit because the remainder also
funds payment fees, AI, infrastructure, tax, operations, and support. Specialist
earning is never calculated from subscription price.

The settlement/dispute hold is seven days. Minimum withdrawal is 100,000 VND.
Payout is specialist-initiated and limited to one logical request per specialist
per server day. Real payout remains disabled until explicit production approval
and provider credentials exist.

The current runtime integration-test catalogue is exactly:

| Plan | Runtime test price |
| --- | ---: |
| `FREE` | 0 VND |
| `PLUS` | 5,000 VND |
| `PREMIUM` | 10,000 VND |

These values are fixtures only and cannot be used for profitability analysis.
The server owns the charged amount. A 5,000 VND `PLUS` checkout uses MoMo
One-Time E-Wallet `captureWallet`; it does not use generic `initiate`, whose
minimum is 10,000 VND. MoMo callbacks verify signature, `partnerCode`,
`orderId`, `requestId`, amount, and idempotent provider result. Sandbox
callbacks use a public HTTPS staging endpoint. Local and CI use deterministic
fake adapters and never depend on MoMo availability.

## Consequences

- MB-516 implements the earning lifecycle, deterministic fake payout for
  local/CI, and a MoMo outbound adapter that cannot run without explicit
  production approval plus complete credentials.
- MB-515 may use the 0/5,000/10,000 VND test catalogue but must keep planned
  production prices out of runtime configuration.
- No production deployment may enable real payment or payout merely because
  the VND design values are documented.
- Historical USD examples in ADR 0005 remain historical context and are not
  the current catalogue authority.
