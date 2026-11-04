# ADR 0032: Payment and specialist payout baseline

- Decision ID: `MB-PAYMENT-PAYOUT-BASELINE-001`
- Status: Accepted
- Date: 2026-10-06
- Fixture-provider amendment: [ADR 0047](0047-payos-active-gateway-momo-dormant.md) (`MB-PAYOS-PAYIN-001`) explicitly permits unchanged 5,000/10,000 VND test prices through PayOS on staging only; synthetic earnings remain non-redeemable.
- Payout amendment: [ADR 0047, 2026-11-04](0047-payos-active-gateway-momo-dormant.md#mb-787-payout-amendment-2026-11-04) permits separately ready staging PAYOS bank payout after exact manual admin confirmation; synthetic earnings remain non-redeemable and production stays blocked.
- Staging demo amendment: [MB-812, 2026-11-04](#mb-812-funded-staging-demo-amendment-2026-11-04) prospectively permits newly funded, verified PayOS demo earnings with staging-only minimum/hold configuration. All historical and production rules below retain their original meaning.
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
per server day. Production payout remains disabled until explicit production
approval and provider credentials exist. The 2026-11-04 ADR 0047 amendment
separately permits staging-only PAYOS bank payout with its own complete
readiness, exact current bank confirmation and authenticated matching evidence;
this does not make test earnings redeemable.

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

### MB-622 sandbox amendment (2026-10-11)

The owner confirmed the 5,000/10,000 VND test catalogue and explicitly approved
`sandbox-full-price-v1`: a sandbox PLUS-to-PREMIUM upgrade charges the full
10,000 VND target price with zero offset. The 300,000 VND per-credit allocation
and 210,000 VND earning remain synthetic demonstration values, not funded
production liabilities or cash redeemable from a sandbox purchase. The sandbox
non-consultation value is zero. The nominal value of held unused credits remains
recorded separately from the zero applied offset.

Verified upgrade success revokes only upgrade-held unused PLUS credits and
grants ten new PREMIUM credits. Credits held by existing appointments retain
their original period/version/allocation. Failure or expiry restores the exact
upgrade-held credits under the existing expiry policy. No downgrade/refund is
introduced.

New immutable sandbox plan versions replace the old offers. Historical prices,
periods, financial facts and `upgrade-offset-v1` calculations are not rewritten.
Mixed historical/sandbox upgrades fail closed. The sandbox catalogue is allowed
only with FAKE checkout or the explicitly configured MoMo test origin, with the
explicit owner-approved [ADR 0047](0047-payos-active-gateway-momo-dormant.md)
exception permitting the same 5,000/10,000 VND test catalogue and zero-offset
upgrade policy through real PayOS collect on staging only. It must not be
charged by a production deployment. Sandbox earnings cannot enter a real
provider payout, even if production credentials are subsequently enabled.

This amendment does not enable production payment/payout or change ordinary
upgrade offsets. Deployment requires the new owner migration before new code;
drain old billing writers before enabling the sandbox offers because they do
not understand the zero-offset pricing policy.

- MB-516 implements the earning lifecycle, deterministic fake payout for
  local/CI, and a MoMo outbound adapter that cannot run without explicit
  production approval plus complete credentials.
- MB-515 may use the 0/5,000/10,000 VND test catalogue but must keep planned
  production prices out of runtime configuration.
- No production deployment may enable real payment or payout merely because
  the VND design values are documented.
- Historical USD examples in ADR 0005 remain historical context and are not
  the current catalogue authority.

### MB-812 funded staging demo amendment (2026-11-04)

The owner accepts decision `MB-STAGING-DEMO-PAYOUT-001` for MB-788 real-money
QA. This narrowly amends the fixed 300,000 VND earning allocation, seven-day
hold, 100,000 VND minimum and unconditional sandbox exclusion above only when
`CONSULTATION_PAYOUT_STAGING_DEMO_ENABLED=true`, profile `staging` is active,
and neither `prod` nor `production` is active. The flag defaults false. Outside
that effective gate, the configured minimum cannot be below 100,000 VND and
the hold cannot be below P7D. Production economics and enablement do not change.

Only a newly completed credit from `sandbox-full-price-v1`, bound to its exact
PAID period, frozen purchased plan and succeeded PayOS payment with matching
processed successful verified webhook or reconciliation receipt, may snapshot
`earning_policy_version=staging-demo-paid-v1` and `funding_payment_id`.
An upgrade cannot redirect an older held credit to the new plan's payment.
The earning allocation is `floor(actual collected VND / frozen plan credits)`;
the specialist receives `floor(allocation * 7000 / 10000)`. Both round down to
whole VND and leave remainders undistributed. Thus total specialist earnings
cannot exceed 70% of collected money. Nominal plan/period allocations are
unchanged. Legacy/default `specialist-earning-v1` rows retain their amounts
and null funding reference; no historical earning is converted or backfilled.

The funded snapshot is redeemable only through separately ready staging
PAYOS payout while the gate and exact verified lineage still hold, including
on explicit retry. Fixture, FAKE, unverified and legacy synthetic earnings
remain excluded from real payout. All MB-787 destination confirmation,
daily-cap, idempotency, polling, reservation and close/retry rules remain.

Staging operator values are minimum `2000` VND and hold `PT10M`, configured
through the existing environment settings rather than code. The pinned offline
SDK does not establish a payout minimum; 2,000 VND is the owner-directed
fallback, and MB-788 must confirm provider acceptance before money movement.
PLUS 5,000 VND / four credits gives 875 VND per session (two: 1,750; three:
2,625). PREMIUM 10,000 VND / ten gives 700 VND (three: 2,100). Three sessions
are required at the fallback minimum; prices, credit counts and share do not
change to force a one/two-session demonstration.

Append-only migration `20261104100000-staging-demo-payout.sql` adds the policy
and funding provenance, preserves existing rows with baseline defaults, and
relaxes the database withdrawal floor to positive VND. The service continues
to enforce its environment-specific minimum. Deploy migration before new
writers and drain older earning/payout writers before activation. Exact hold
seconds are additive API data; web validators/display must accept dynamic
values before screen-based MB-788 QA. No mobile payout surface changes.
