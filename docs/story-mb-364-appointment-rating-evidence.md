# MB-364 Appointment Rating Evidence

## Delivered boundary

- Only the authenticated owner of an appointment whose authoritative state,
  session outcome, and completion fact all prove completion can rate it.
- The accepted value is an integer from 1 through 5. One appointment has one
  current row; a later edit replaces the value using quoted-version optimistic
  concurrency.
- The rating row and specialist aggregate change in the same Consultation
  transaction. The aggregate stores exact count and sum; API responses derive
  a two-decimal average.
- Discovery v2 displays the aggregate when it exists. Only PREMIUM includes it
  in comparison, after compatibility, language, availability, and timezone.
- No comment, anonymous flag, health content, diagnosis, delete, moderation,
  or cross-service dependency is introduced.

## Contract and persistence

- Canonical API: `contracts/openapi/consultation-service-v1.yaml`
- Owner migration: `consultation-service/src/main/resources/db/changelog/changes/015-appointment-rating.sql`
- Runtime: `consultation-service/.../rating/AppointmentRatingController.java`
  and `AppointmentRatingService.java`
- Browser boundary: `/api/consultation/appointments/[appointmentId]/rating`

## Verification map

- `AppointmentRatingFlowIntegrationTests`: eligibility, wrong actor, bounds,
  create/read/edit, missing/stale version, aggregate arithmetic, and concurrent
  first-save convergence on one contribution.
- `DiscoveryFlowIntegrationTests`: aggregate disclosure and PREMIUM-only final
  tie-break behavior.
- `AppointmentRatingDialog.test.tsx`: first rating and versioned edit behavior.
- `appointment-rating-route.test.ts`: authenticated BFF forwarding, ETag, and
  input rejection.
- Consultation OpenAPI, generated frontend types, runtime validators, build,
  lint, and focused tests must pass in CI. PostgreSQL integration results are
  classified separately from compilation when Docker is unavailable.
