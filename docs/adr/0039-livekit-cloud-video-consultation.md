# ADR 0039: LiveKit Cloud video consultation

- Status: Accepted provider, privacy, access, retention and Specialist completion command; automatic timeout settlement not approved
- Date: 2026-10-12
- Decision ID: `MB-LIVEKIT-VIDEO-001`
- Delivery: MB-384
- Extends: [ADR 0017](0017-product-scope-v2.md), [ADR 0029](0029-evidence-backed-chat-session-completion.md)

## Context and decision

The Product Owner selected the existing `mentalbridge` LiveKit Cloud project on
the Free plan. Managed WebRTC transports a single USER/SPECIALIST consultation;
Consultation retains appointment, authorization, credit, completion and earning
authority. This decision is not production or paid-plan approval. No alternative
provider, self-hosted media service or separate business workflow is introduced.

The official server SDK is isolated behind a narrow video-provider boundary.
The SDK's private Cloud room-management adapter uses HTTPS Twirp requests with
protobuf payloads, not MentalBridge gRPC or a new inter-service protocol.
All MentalBridge business endpoints and owner queries remain REST/JSON with
OpenAPI; signed provider webhooks are JSON. No SDK type crosses the owner API.
Next.js and React Native consume only appointment-authorized connection data;
they never receive an API secret. Backend configuration uses `LIVEKIT_URL`,
`LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` through Spring configuration, not an
assumed shell dotenv loader. Provider failure does not affect manual brief,
summary or appointment reads.

## Authorization and media boundary

Only the exact assigned actor in their immutable USER or SPECIALIST role can
obtain a token. The current specialist must remain approved. Cancelled,
rescheduled, completed, ended and wrong-modality appointments cannot join.
Opaque, server-owned room and participant identifiers contain no names,
health data, email, appointment reference or brief. Room names are not authority.
Tokens grant only camera/microphone publishing and subscription to the exact
room: no data publishing, screen sharing, administration, recording, ingress,
metadata updates or agents. Tokens remain in memory and response bodies only;
responses are `no-store`, logs and URLs never contain tokens.

The approved waiting period starts five minutes before the appointment. It is
local device preview, not a provider connection or attendance interval. The
actual call permits new connections only in `[startsAt, endsAt)`. Both actors
must be present in that valid interval before overlapping attendance accrues.
Reconnect does not extend the appointment. A new application-issued token
requires the same current authorization checks. SDK reconnect tokens can be
refreshed by LiveKit: expiry alone does **not** terminate a connected call.
At end/cancellation the server must revoke both identities with an explicit
Cloud cutoff and delete the room; failures remain pending verification and
retryable, never financial proof. A scheduler has bounded operational latency,
not a claim of exactly-at-the-millisecond media termination during an outage.
Explicit revocation uses a fresh Unix-seconds cutoff one second beyond issuance
so tokens created in the same second are rejected too. Servers must have accurate
time: Cloud requires that cutoff to be within its current-time tolerance.

## Approved local demo amendment, 2026-10-14

The owner approved immediate video access for product demonstrations. This is an
opt-in exception, not a production scheduling change. With
`CONSULTATION_DEMO_IMMEDIATE_VIDEO_ENABLED=true` and an explicit `local`, `dev`
or isolated `test` profile, only an effective PLUS/PREMIUM entitlement and held
credit with `DEMO` provenance qualify. Enabling this option in staging/production
or without an explicit development profile fails startup. PAID/FREE accounts,
chat slots and the disabled-by-default baseline retain the scheduled connection
window. The current booking contract independently permits unexpired slots
without advance booking delay; this option does not change that booking rule.

Booking, discovery and decision deadlines follow the current public appointment
contract, including its slot-end cutoff when the ordinary decision buffer has
elapsed. Specialist approval/explicit acceptance remains required: REQUESTED
alone cannot connect. Once CONFIRMED, both assigned actors may connect
immediately, including before scheduled start. Cancelled, ended, completed,
expired-entitlement and cross-appointment access remain forbidden. Scheduled
timestamps, the 60-minute booked interval and the existing end cutoff are not
rewritten or extended; operator demos must be brief and explicitly leave/end.

Early demo transport is not valid consultation attendance. Evidence before the
booked window remains out-of-window uncertainty under the unchanged evidence
policy. The assigned Specialist may explicitly end an early demo, persisting
SESSION_ENDED/pending verification with credit HELD. No early demo, missing
webhook or opt-in flag produces a completion fact, consumption, refund/release
or earning. Existing financial/completion invariants remain authoritative.
Operators may disable the flag after demonstrations; existing histories are
never rewritten. This exception is not approved for production or real care.

## Trusted evidence and completion

The official webhook receiver verifies the signed Authorization JWT and SHA-256
of the original request body. Only verified room/participant identifiers are
matched to owner-created mappings. Event IDs deduplicate delivery; participant
session SIDs distinguish reconnects. Reordered join/leave facts are paired by
session, clipped to the consultation window and unioned before overlap is
calculated. Unpaired, conflicting or missing events are uncertainty, not absence.
Provider room end is not appointment completion.

The Product Owner approved a five-minute late webhook grace; it is **not** call
extension. At least one minute of common attendance is supporting operational
evidence, not a clinical claim or sufficient completion authority. Neither
webhooks, both participants joining, elapsed time nor disconnect completes an
appointment. Missing evidence/provider failure keeps `SESSION_ENDED` pending
verification, without automatic no-show, credit consumption or earning.

Before MB-384, dev had only evidence-backed chat settlement and its immutable
`completion_fact_id`, without a Specialist video confirmation endpoint.
The owner approved the additive video-confirmation command on 2026-10-12:
the assigned approved Specialist may confirm when the actual session ends,
without waiting for grace; complete evidence plus verified closure and no
dispute/failure permits one local transaction to reuse
the existing credit ledger, completion fact and MB-516 earning service.
Missing, delayed or uncertain evidence enters pending verification / EVIDENCE_REVIEW
while the exact credit remains HELD. Reconciliation may retry after 24 hours and
flag manual review, but elapsed time must never complete the appointment, release
or refund credit, or create earning. The proposed automatic 24-hour release was
not approved and must remain unimplemented. A manual review/dispute-resolution
workflow requires separate review before production; current settled-appointment
dispute semantics are not silently extended to pending verification.
Chat policy/history and ConsultationBrief/SessionSummary consent stay unchanged.
The final command/DTO/state/error/payment/timeout proposal is in
[MB-384 video completion proposal](../../contracts/proposals/video-completion-mb-384.md).

## Privacy and retention

No recording, audio/video persistence, transcript, transcription, AI Agent or
automatic AI participant is enabled. Provider metadata is operational only;
brief, summary, answers and Journal content never enter LiveKit metadata.
This is managed encrypted transport, not an unverified end-to-end encryption
claim. Participants can still capture content outside MentalBridge's control.

The owner approved **90 days** for minimized LiveKit participation evidence only.
Cleanup must preserve any appointment with a dispute, explicit legal hold or
other valid retention hold. Cleanup removes participation/session metadata, not
Appointment, ConsultationBrief, SessionSummary, completion fact or financial
history. Removal must not allow old provider replay to restore expired evidence.
Cleanup is bounded, tested and separate from completion evaluation. Production
requires a legal/privacy review and Cloud region/subprocessor review first.

## Failure cases and limitations

Either actor may arrive first and wait for the other; this alone proves nothing.
Network loss, application crash and device permission/unavailability have safe
leave/retry states. A reconnect preserves the appointment room mapping but may
have a new participant SID. Token expiration requires reauthorization when
fresh connection is needed. Rescheduling creates a replacement appointment and
never repurposes the old room. Completed calls are read-only. A party who never
joins, delayed/duplicate webhook or leaving without confirmation stays pending
verification rather than being inferred completed/no-show.

Free-tier quotas and availability are provider limits, not a guaranteed clinical
service. Operators must monitor current Cloud quotas; no automatic paid upgrade
is authorized. No staging/production deployment is part of MB-384. Real Cloud
and Android interoperability results require actual execution evidence.

## Provider references

- [Official JVM server SDK](https://github.com/livekit/server-sdk-kotlin)
- [Tokens, refresh and Cloud revocation](https://docs.livekit.io/frontends/reference/tokens-grants/)
- [Room API and explicit revocation cutoff](https://docs.livekit.io/reference/other/roomservice-api/)
- [Signed webhooks](https://docs.livekit.io/intro/basics/rooms-participants-tracks/webhooks-events/)
- [Official Expo integration](https://docs.livekit.io/transport/sdk-platforms/expo/)

## Alternatives and compatibility

Other providers/self-hosting are outside the approved decision. A client timer,
provider webhook or specialist assertion alone cannot weaken completion fact
requirements. Additive APIs and Liquibase migrations preserve overlapping old
clients and historical chat facts; the canonical SQL remains documentation only.
