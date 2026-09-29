# ADR 0027: Approved online specialist discovery

- Status: Accepted
- Date: 2026-09-27
- Decision ID: `MB-SPECIALIST-DISCOVERY-001`
- Implements: MB-363
- Amends discovery under: [ADR 0014](0014-appointment-specialist-and-consultation-continuity.md)
- Current scope: [ADR 0017](0017-product-scope-v2.md), amended by [ADR 0022](0022-current-product-blueprint-amendments.md)
- Availability dependency: [ADR 0019](0019-online-specialist-availability.md)

## Context

The user-facing specialist page currently contains static profiles, unsupported
specialties and credentials, fabricated prices and ratings, physical and phone
modes, and links that do not represent current Consultation state. MB-359 and
MB-360 make Consultation authoritative for profile approval and suspension;
MB-362 makes it authoritative for exact online availability. Discovery must
compose those current owner facts without turning screening evidence into a
clinical matcher or moving booking authority out of MB-378 and MB-558.

## Decision

### Ownership and current eligibility

Consultation owns discovery, ranking, public specialist detail, and the exact
selectable-slot projection. A specialist is discoverable only while the
current Consultation profile state is `APPROVED`. `PENDING`, `REJECTED`, and
`SUSPENDED` always fail closed. Suspension and restoration are read from the
same owner state used by MB-360; discovery does not authorize from a cache,
eventual projection, browser claim, or copied approval flag.

A discoverable profile must have at least one current selectable slot. Every
returned slot must be an active, unheld, future, exact 60-minute
`IN_APP_CHAT` or enabled `IN_APP_VIDEO` slot at the request's server time.
Withdrawn, started, held, overlapping-invalid, disabled-video, and otherwise
stale slots are omitted. Each reload and detail request evaluates current
profile and slot state again; detail returns `SPECIALIST_NOT_DISCOVERABLE`
when no selectable slot remains.

The discovery response carries the exact owner `slotId`, specialist identity,
interval, IANA timezone, modality, and current version needed for the MB-378
handoff. Discovery does not reserve a slot. MB-378 consumes that identity and
revalidates current slot and specialist eligibility transactionally; it does
not invent a replacement slot or reconstruct eligibility from display data.

### Screening context and privacy

Personalized discovery accepts only an optional opaque `supportEvaluationId`.
The browser never supplies raw answers, scores, inferred disease labels, or a
free-form health description. Consultation reads the exact owned Care
SupportEvaluation with authenticated end-user context and reduces it in memory
to its versioned domain contributions: `domain`, instrument-specific
`screeningLevel`, and domain-local `supportPathway`. Consultation does not
persist or return that health context and never logs it.

The level is used only through Care's versioned domain-local pathway. Ranking
does not compare PHQ-9 and GAD-7 numeric scores, create one global severity, or
claim that a specialist is clinically suitable. A specialist matching a
domain whose pathway recommends professional support receives the strongest
compatibility class; a match to another contributing domain receives the next
class; no match is neutral. User-visible explanations say only that an
approved support area aligns with a screening area. They do not expose the
instrument, level, score, reason code, safety evidence, or assessment ID.

Direct browsing without a SupportEvaluation remains available. If the exact
Care context is missing, forbidden, malformed, timed out, or unavailable,
Consultation must not use a stale or guessed context. It applies neutral
compatibility to every candidate, returns the approved catalogue with a
non-personalized explanation, and records only safe dependency telemetry.

### Deterministic ranking

Ranking is a lexicographic comparison under policy
`specialist-discovery-v1`; a later factor can never outweigh an earlier one:

1. screened domain/pathway compatibility class;
2. requested language match;
3. current availability, with the earliest selectable `startAt` first among
   profiles that each have at least one selectable slot;
4. timezone compatibility, using exact requested IANA-zone match before the
   absolute UTC-offset distance calculated at the response `generatedAt`;
5. for a current `PREMIUM` entitlement only, an authoritative rating aggregate
   as a tie-breaker after all four primary factors;
6. `specialistAccountId` ascending as the final stable tie-breaker.

`FREE` and `PLUS` do not receive a rating-based rank advantage. Rating is
neutral when the rating capability or an eligible aggregate is absent. Seeded,
hard-coded, or otherwise fabricated ratings must not fill that gap. Rating can
never outrank compatibility, language, availability, or timezone and can never
be the sole reason for a recommendation.

The response records `rankingPolicyVersion`, `generatedAt`, applied non-health
preference fields, stable explanation codes, and the factor order. It does not
return a hidden weighted score. Filters are closed, supported fields such as
language, support area, modality, and bounded availability window; arbitrary
specialty or clinical labels are rejected. Stable pagination carries the
policy version, factor tuple, and final specialist identifier so ties cannot
shuffle between pages under unchanged owner state.

### Entitlement and actor flow

Discovery is an authenticated browse capability for `FREE`, `PLUS`, and
`PREMIUM`. Package never hides an otherwise discoverable specialist or current
slot.

1. The user opens discovery directly or from a server-held SupportEvaluation
   reference.
2. The UI loads the current ranked list, supported filters, a short ranking
   explanation, and only current selectable online slots.
3. The user opens a profile detail and selects one exact slot.
4. `FREE` sees that browsing is available but booking requires an eligible paid
   plan; no booking command is sent.
5. `PLUS` and `PREMIUM` hand the exact `slotId` to MB-378. The UI does not infer
   credit balance, reservation capacity, or booking success from the package.
6. MB-378 and MB-558 remain authoritative for paid-plan, available-credit,
   reservation-capacity, lead-time, concurrency, and stale-slot outcomes.

Loading, empty catalogue, dependency degradation, video disabled, and
slot-stale-on-selection are explicit states. Approved profiles without a
selectable slot are part of the empty catalogue rather than visible cards.
A stale selection triggers a reload and never silently chooses another time or
modality.

### Public and prohibited fields

Discovery may expose only the approved public profile fields from MB-359:
specialist account ID, display name, bio, approved support areas, languages,
years of experience, timezone, plus the current discovery explanation and
selectable-slot fields. Years of experience is informational and is not a rank
factor in `specialist-discovery-v1`.

The discovery contract and UI contain no `PracticeLocation`, address, phone,
external meeting link, price, free-introduction claim, credential, degree,
license, certificate, unapproved title, unsupported specialty, raw assessment
answer, assessment score, Journal content, chat content, or invented clinical
matching claim. `IN_APP_VIDEO` availability never implies that an external
room or current video session exists.

## Consequences

- Approval and slot changes are visible on the next request and uncertainty
  cannot expose a suspended specialist or stale slot.
- Compatibility remains explainable without copying health content into
  Consultation persistence or discovery responses.
- All packages can inspect the same real catalogue, while booking and credits
  remain in their existing authoritative owner flow.
- The static specialist page and unsupported profile modal are replacement
  targets for the MB-363 consumer subtask; this ADR does not claim that UI is
  already delivered.
- A later rating story may add an authoritative aggregate additively, but it
  must preserve the primary factor order and Premium-only tie-break rule.

## Rejected alternatives

- A weighted composite score: rejected because a high rating could obscure a
  primary compatibility difference and the result would be harder to audit.
- Browser-supplied screening fields: rejected because they are untrusted and
  would spread health context into URLs, logs, or display contracts.
- Cached approval for discovery: rejected because suspension must fail closed
  immediately on the owner read.
- Hiding discovery from `FREE`: rejected because browsing is explicitly an
  all-package capability; only booking is paid and credit-gated.
- Showing seeded ratings, prices, credentials, or physical/contact details:
  rejected because no current owner capability supports those claims.
