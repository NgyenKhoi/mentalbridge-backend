# MB-698 commercial parity contract audit

Audited against `contracts/openapi/consultation-service-v1.yaml` on the `mb-698`
branch, based on `dev` at `1c6247d4`.

## MB-708 rating authority

No provider change is required. Consultation already owns the complete rating
contract:

- `GET /api/v1/appointments/{appointmentId}/rating`
- `PUT /api/v1/appointments/{appointmentId}/rating`
- integer ratings from 1 through 5
- appointment-owner and evidence-completed eligibility checks
- quoted `If-Match` for edits and a returned rating `version`
- transactional rating/aggregate updates and natural no-op handling
- authoritative specialist aggregate (`averageRating`, `ratingCount`)

Provider verification is covered by
`AppointmentRatingFlowIntegrationTests`, `ConsultationJourneyIntegrationTests`,
and `ConsultationOpenApiContractTests`.

## MB-710 commercial authority

No additional mobile read contract is required. Consultation already publishes:

| Concern | Authoritative endpoint | Consumer rule |
| --- | --- | --- |
| Catalogue and checkout readiness | `GET /api/v1/service-plans` | Use version IDs, prices, credits, upgrade options and `checkoutEnabled` exactly as returned. |
| Purchase/upgrade command | `POST /api/v1/subscriptions/checkout` | Preserve `Idempotency-Key` for reconciliation of the same logical command. |
| Payment history | `GET /api/v1/payments` | Display the returned bounded owner history without local settlement inference. |
| Payment reconciliation | `GET /api/v1/payments/{paymentId}` | Only `SUCCEEDED` is confirmed; return/cancel links are never settlement evidence. |
| Credits and reservation capacity | `GET /api/v1/service-credits` | Booking remains gated by the returned balance/capacity, never by plan name or a payment redirect. |
| Specialist rating and period metrics | `GET /api/v1/specialist/analytics` | Display explicit `AVAILABLE`, `EMPTY`, or `BLOCKED` sections. |
| Specialist earning balances | `GET /api/v1/specialist/earnings` | Display server amounts and payout states without client-side financial arithmetic. |

The provider intentionally exposes no user downgrade/refund command. Mobile must
not invent one. A pending, expired, declined, replayed, or unavailable payment
must remain non-authoritative for booking until a fresh credit/entitlement read
confirms the resulting rights.

## Delivery boundary

MB-708 and MB-710 are `CODE_CHANGE_NOT_REQUIRED` after this audit. MB-709,
MB-711, and MB-712 consume the pinned Consultation v1 contract in the frontend
repository. Native E2E is authored on the feature branch and executed only at
the MB-666 protected staging milestone.
