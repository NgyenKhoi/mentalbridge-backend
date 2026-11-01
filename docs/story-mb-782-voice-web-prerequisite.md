# MB-782 Web Voice prerequisite

[MB-782](https://vunguyenkhoi47.atlassian.net/browse/MB-782), Epic MB-754; Journal/AI
owner Thảo Nguyên Trần. Baseline `3a054a9d` contains merged MB-760 #236.
This is the approved backend/agent prerequisite for MB-761 Web Voice.

## Brainstorm and ordered delivery

1. Add `maxSessionSeconds` from the existing policy and
   `availableSessionSeconds` from the current unreserved daily allowance to the
   eligibility response. Denied decisions expose no positive available duration.
   The preview never reserves or extends a session; POST still reauthorizes and
   admits atomically. Pair runtime, versioned OpenAPI/schema and focused tests.
2. Publish only the official fixed `lk.agent.state` display hint from the exact
   admitted agent participant. Match genuine worker transitions, with thinking
   while waiting for Journal and speaking only after first audio publication.
   Preserve USER metadata-update denial and all server lifecycle/quota semantics.
   Test identity, privacy, interruption/stop/reconnect and stale-hint fallback.
3. Document the consumer contract, review overlap, refresh dev and publish the
   separate backend PR. Require current-head CI/review before merge into dev;
   only then publish the MB-761 frontend consumer PR.

No provider call, quota/consent/entitlement/payment change, recording/export or
Voice enablement is authorized. Existing MB-760 live allowance/privacy gates stay
blocked. Media state is untrusted presentation data, never authorization,
playback-success proof, ACTIVE-time accounting or a client deadline.

## Delivered contract and consumer handoff

Active Journal OpenAPI is `1.16.0`; the approved Voice target receives the
additive `1.1.0` MB-782 amendment. The policy remains
`companion-voice-policy-v1`. Older strict consumers must regenerate their DTOs
before consuming the expanded closed responses.

`maxSessionSeconds` is the existing package cap. `availableSessionSeconds` is
`min(maxSessionSeconds, quota.remainingSeconds)` only for `ELIGIBLE`, otherwise
zero. The ledger already subtracts reservations from `remainingSeconds`; do not
subtract them again. Unknown owner/dependency decisions return the existing
sanitized error, never a positive fallback. `checkedAt` and `no-store` remain.
POST rechecks owners and reserves atomically; even a fresh positive GET grants
no slot, duration or deadline. The server's admitted seconds/hard stop remain
authoritative.

The connection response now includes `roomName` and `agentIdentity`, copied
from the admitted provider reference. These opaque bindings let a consumer
match the exact connected room and `ParticipantKind.AGENT` identity. Never
select an agent by a `va-` prefix or by its attribute value alone. The USER
token still denies metadata updates, data publishing and room administration.

The pinned Node SDK publishes its own media states automatically, but cannot
observe the custom Journal request and can announce speaking before trusted
publication acknowledgement. Its participant metadata permission therefore
remains **false**. A single authorized `RoomServiceClient` writer publishes the
same official `lk.agent.state` key, scoped to the verified current room,
dispatch and Agent identity. The existing server credential stays in the worker;
no new secret, grant, environment key or consumer-visible credential is added.
The SDK is declared directly at its already locked version `2.19.2`.

Writes are serialized and coalesced; they contain exactly one fixed key/value.
Each request has a two-second timeout, no regional failover/retry. Failure is
isolated from worker control, admission, billing and teardown. Close clears the
key with an empty string while connected; room teardown and consumer disconnect
fallback handle a lost connection. Late transitions cannot restore a terminal
hint. No heartbeat or content is published in attributes.

| Worker condition | Attribute value |
| --- | --- |
| Connecting | `initializing` |
| Listening or collecting accepted final segments | `listening` |
| Processing an accepted turn, awaiting Journal or first playback | `thinking` |
| Actual audio publication, trusted start acknowledged | `speaking` |
| Interrupted | `listening` |
| Muted or disconnected | `idle` |
| Ending/ended/error cleanup | Empty string (delete) |

MB-761 must reset hints on room/attempt change and disconnect; missing, unknown,
late or wrong-participant attributes show connecting/unavailable. Bound each
received hint to at most 30 seconds from observation; expiry shows unavailable,
including a legitimate long speaking/thinking state. This is a conservative
display timeout, not a new media cutoff or reason to end a session. Do not
refresh age by polling the same cached attribute. After reconnect, wait for
current-room participant state, with the same bounded fallback. Captions are
not transported through these attributes.

## Acceptance and verification

| State | Expected display data | Must not imply |
| --- | --- | --- |
| PLUS / PREMIUM eligible | Cap 300 / 600; available bounded by daily balance | Reserved slot or guaranteed call |
| Daily balance 120 seconds | Available 120 | Full package cap is usable |
| FREE, consent denied, quota/capacity/provider denied | Available 0 | Call enabled |
| Concurrent positive previews | One atomic final-slot admission | Multiple capacity reservations |
| Waiting for Journal | `thinking` from exact admitted agent | Successful AI reply or charged usage |
| Muted/disconnected/stopped | Idle/cleared; consumer bounded fallback | Indefinite thinking |

Focused tests cover policy bounds, no-store/USER authorization, stale GET,
consent changes, final-slot race on a real disposable Mongo replica set,
fixed attribute payloads, exact participant scope, serialized stop, failure
isolation, actual publication, interruption and reconnect. Full Journal and
Agent gates run without provider calls. MB-761 owns browser stale-hint and
identity-filter rendering tests after this prerequisite merges.

Official references: [participant attributes](https://docs.livekit.io/transport/data/state/participant-attributes/)
and [agent sessions](https://docs.livekit.io/agents/logic/sessions/).
No new registration is needed. MB-760 live allowance/recording/export gates
remain unverified; no live provider, microphone, Bedrock or deployment was run.
