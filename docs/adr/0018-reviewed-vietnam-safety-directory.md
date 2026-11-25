# ADR 0018: Reviewed Vietnam safety directory and area lookup

- Status: Accepted
- Date: 2026-09-17
- Decision ID: `MB-VN-SAFETY-DIRECTORY-001`
- Refines: [ADR 0017](0017-product-scope-v2.md)
- Amends: [ADR 0009](0009-care-screening-safety-and-support-boundaries.md)

## Context

ADR 0009 removed the earlier hotline catalogue because MentalBridge had no
approved freshness, geographic, review, or operational ownership model. ADR
0017 later approved a provenance-controlled Vietnam area directory as target
scope, without making any directory endpoint or contact executable. The
remaining decisions must be fixed before contract, persistence, seed, Care
integration, or frontend work can safely begin.

The directory is supporting information, not emergency dispatch or a claim
that a listed organization will respond. A positive PHQ-9 item 9 and an
explicit user request for immediate help must remain deterministic Care-owned
safety triggers even if every optional dependency is unavailable.

## Decision

### Ownership and authority

Content/Notification owns directory records, provenance, review, verification,
publication state, and area lookup. Only an authenticated `ADMIN` may create,
review, verify, activate, update, or deactivate a record. An active flag alone
never makes a record eligible for user lookup.

Care owns both safety triggers, the synchronous user-facing safety response,
the approved local fallback, and the composition returned to the frontend. The
frontend uses Care for the safety flow; it does not treat a direct Content call
as safety truth. Neither service reads the other's database.

### Review, freshness, and publication

A user-visible record must be active, reviewed, source-backed, within its
reviewed coverage, and verified within the preceding 90 days. Freshness uses
the half-open interval `verifiedAt <= now < verifiedAt + 90 days`. At the upper
boundary the record is stale and is excluded from user lookup. Editing contact,
address, coverage, type, name, or provenance invalidates the prior review and
verification until an administrator reviews and verifies the new version.

Inactive, unreviewed, stale, future-verified, or out-of-coverage records remain
available only to authorized administration and audit flows. They are never
silently presented as current options. Reverification creates new accountable
evidence; it does not rewrite prior review history.

The exact schema, state rules, seed controls, and review evidence are governed
by [Vietnam safety directory policy v1](../policies/vietnam-safety-directory-policy-v1.md).

### Area input and truthful wording

The user manually selects a supported province or district, or enters a
coarse manual location that resolves only through versioned reviewed area
aliases. MentalBridge does not request background geolocation, infer precise
coordinates, or share location. Unmatched or ambiguous manual input produces
an explicit invalid/empty-area state rather than a guessed area.

V1 stores no coordinates and calculates no distance. User-facing copy therefore
says “cơ sở trong khu vực đã chọn” and never “gần nhất”. A future nearest claim
requires a separately accepted coordinate, distance, privacy, accuracy, and
tie-breaking contract.

### Trigger and synchronous fallback

Care opens the same safety-directory flow when either:

1. the versioned PHQ-9 item-9 rule returns a positive safety screen; or
2. the user explicitly selects “Tôi cần hỗ trợ ngay”.

A `High` or `Severe` screening band alone is not a trigger. Both triggers are
available without AI or subscription eligibility. Care returns its approved
local safety copy synchronously and performs at most a bounded read-only REST
lookup of current directory entries. Timeout, connection failure, malformed
response, invalid/empty area, or zero eligible results returns the local Care
fallback and an explicit directory state; it never suppresses or delays the
underlying safety response beyond the bounded dependency deadline.

The flow works without AI, Kafka, Redis, WebSocket, email, notification
delivery, or external directory availability. No database transaction spans
the Content call.

### Prohibited implications and side effects

The directory does not automatically call a number, open an outbound call,
notify a specialist or third party, send a safety email, share location, start
background geolocation, promise 24/7 monitoring, or guarantee a response. A
specific hotline or emergency number is publishable only as a reviewed current
directory record with its own source evidence; this ADR publishes no number.

## Actor flow

```text
positive item 9 OR explicit help-now action
  -> Care returns approved local safety copy
  -> user manually selects/enters a coarse Vietnam area
  -> Care requests active reviewed current entries from Content/Notification
  -> eligible results: show facilities/hotlines in the selected area
  -> invalid/empty area or dependency failure: show Care fallback and retry option
```

## Compatibility and implementation consequences

- ADR 0009 remains the historical reason the ungoverned hotline table was
  removed. This ADR supersedes only its no-directory conclusion for the
  governed v2 capability.
- Existing Care and Content OpenAPI descriptions remain current-runtime truth
  until additive directory and Care-composition contracts are implemented.
- Content/Notification requires an append-only owner migration and review
  history; the removed historical hotline table is not restored or reused.
- Care requires a consumer-owned REST adapter with explicit deadline, safe
  response validation, and local fallback. No Kafka request/reply is added.
- No real contact is seeded or exposed merely because this decision is
  accepted. Content evidence and runtime delivery retain separate gates.

## Rejected alternatives

- Restore the former hotline table: rejected because it lacks the required
  versioned provenance, review, verification, coverage, and history model.
- Let the browser call Content directly for the safety outcome: rejected
  because Care must preserve its local synchronous fallback and trigger truth.
- Keep stale contacts visible with a warning: rejected because a warning does
  not make an unverified safety contact current.
- Infer an area from IP, device location, or free text: rejected because the
  approved flow requires deliberate coarse user input and no location sharing.
- Call area-filtered results nearest: rejected because V1 has no coordinates or
  distance calculation.
- Trigger email, dispatch, or third-party contact: rejected because the product
  has neither consent nor a guaranteed response operation for those actions.
