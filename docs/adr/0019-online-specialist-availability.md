# ADR 0019: Online specialist availability v1

- Status: Accepted
- Date: 2026-09-17
- Decision ID: `MB-SPECIALIST-AVAILABILITY-001`
- Implements: MB-362
- Amends runtime delivery under: [ADR 0017](0017-product-scope-v2.md)

## Context

ADR 0017 permits new consultation availability only for `IN_APP_CHAT` and
`IN_APP_VIDEO`, while the video session provider and evidence contract remain
gated. Story MB-362 needs an executable owner flow without restoring the
historical in-person location model or implying that a video room exists.

## Decision

Consultation owns exact, discrete 60-minute availability slots. An approved
specialist publishes a future UTC interval plus one IANA timezone used for
display. New slots contain no practice location, phone number, external meeting
link, room, or user health data.

Publication accepts only `IN_APP_CHAT` and `IN_APP_VIDEO`. Chat is available by
default. Video publication succeeds only while the typed
`CONSULTATION_VIDEO_AVAILABILITY_ENABLED` capability gate is enabled by an
operator after the required provider contract is accepted. Disabling the gate
does not rewrite historical video slots; the owner API reports them as
`VIDEO_DISABLED`. It never falls back to an external meeting link.

Publication is idempotent per specialist and serialized by locking the owned
specialist profile. PostgreSQL also enforces exact duration and excludes
overlapping active half-open intervals. Withdrawal is an owner-only optimistic
mutation that retains a `WITHDRAWN` tombstone. Started slots are stale and
cannot be withdrawn through the future-availability command.

The frontend uses an authenticated same-origin BFF. It renders only persisted
slots and the provider's current capability/readiness facts. Recurrence,
time-off rules, global accepting-bookings state, appointment creation, and
video-room authorization are separate features.

## Consequences

- PracticeLocation is not a dependency of the v2 availability API,
  persistence, or UI.
- Availability remains a synchronous owner-local REST/PostgreSQL feature and
  does not add Kafka, an outbox, Redis, or a remote provider call.
- Enabling video slot publication does not enable a video session. The later
  signaling/provider/security/evidence contract remains mandatory.
- Historical v1 in-person documentation and records remain historical and are
  not rewritten by this additive runtime slice.
