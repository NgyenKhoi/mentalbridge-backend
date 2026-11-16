# MB-863 — current authorization profiling

[MB-863](https://vunguyenkhoi47.atlassian.net/browse/MB-863) follows the
[MB-854 roadmap](https://vunguyenkhoi47.atlassian.net/browse/MB-854?focusedCommentId=10450).
The user authorized profiling/tests and confirmed MB-862 PR302 merged at
`a51edb8f580be326fbfd23ef0e1f6936e1ea4cc1`. This source starts there. It does not
alter PR299, deploy/restart staging, use credits, run providers or open a window.
Migration019/020 deployment prerequisites remain separate from source merge.

## Proven baseline and unknowns

The 16 November read-only audit binds running Journal/Agent images to staging
`c5a6c890`, not current dev. Do not infer a new deployment from this PR or from
an EC2 checkout. The supplied 11-turn call has three current-authorization
rounds totaling median2579ms; publication median1104ms/p951196ms. Its temporally
associated ListRooms read differs from publication by7–9ms. All74 room reads
have p50740ms/p951245ms. Context is0–1ms. These are within-call historical
observations, not a matched A/B sample or a representative E2E percentile.

All three media reads already start concurrently. The pinned SDK2.19.2 uses
native fetch with failover disabled and a3second request timeout. Why ListRooms
is slow remains UNKNOWN: the old timers combine signing/serialization, queue,
network, provider processing and parsing, without a direct inspection/turn edge.
Existing native fetch also means missing keep-alive is not established.

## Unchanged execution and new metadata

Every turn keeps these three boundaries:

| Purpose | Position | Mandatory current checks |
| --- | --- | --- |
| INITIAL | Before context/generation admission | Owned active attempt/lease, bound campaign, current Care/Consultation, room/dispatch/participants, common-return freshness |
| PRE_INFERENCE | Immediately before provider dispatch | Same fresh checks; no reuse of the INITIAL result |
| PUBLICATION | After full END_TURN/schema/safety validation, before reply registration | Same fresh checks; consent loss, stale authority, recording or invalid media still deny speech |

`voice_authorization_profile` records a generated authorizationId, closed purpose
and stage, validated optional attemptId/turnId/turnSequence, bounded monotonic
startedMs/elapsedMs and SUCCESS/FAILED. Stages distinguish local state/document
reads, owner authority (Care and Consultation separately), media inspection,
final state and denial cleanup. Parallel child durations overlap: never sum
them as sequential work. TOTAL includes denial cleanup on failed authorizations;
later-finishing parallel reads may legitimately log after TOTAL failure.

`voice_media_inspection` adds one inspectionId shared by its three reads and,
for a turn, the matching authorization/turn/purpose. Background reads remain
UNSCOPED and must not be joined to a turn by time alone. The attempt identity
continues to come from the validated media reference.

Each observed native request can report:

| Field | Meaning / limit |
| --- | --- |
| sdkBeforeRequestMs | Read invocation to native request creation; SDK preparation, not pure signing time |
| requestToSendMs | Request creation to first request write; may include queue/connect work, not isolated DNS/TLS |
| sendToHeadersMs | First request write to response headers; includes network and server work, not model TTFT |
| headersToBodyMs | Headers to completed wire response; not JSON validation time |
| sdkAfterBodyMs | Wire completion to SDK read settlement; includes parsing/scheduling, not pure parser CPU |
| nativeRequests | Bounded observed request count, not permission to retry |
| connection | FIRST_OBSERVED_SOCKET, REUSED_OBSERVED_SOCKET or UNKNOWN; first observed does not mean newly connected |
| httpStatusClass / httpResult | Closed status class and COMPLETE/ERROR/INCOMPLETE; a completed HTTP response is not an authorization success |

No native event gives UNAVAILABLE, not zero latency. Multiple requests give
MULTIPLE_REQUESTS without attributing one request's durations to the whole read.
DNS, TCP, TLS, control-plane region and provider-only processing remain UNKNOWN.
Unavailable/changed experimental diagnostics channels affect evidence only,
never authorization. Node22's bundled native events and the installed SDK are
verified by loopback tests. The [Undici channel definitions](https://github.com/nodejs/undici/blob/main/docs/docs/api/DiagnosticsChannel.md)
describe why pooled connection establishment cannot be reliably assigned to an
individual request; this implementation does not make that assignment.

## Privacy, compatibility and tests

Profiling receives no transcript/context. It validates correlation fields and
copies only explicitly named metadata. Native request/socket objects are used
only as weak identity keys, never serialized; URLs, addresses, headers, body,
error text and account/room/participant identities are not read into evidence.
Observer exceptions cannot replace a business result or error. There is no new
REST payload/header, persisted shape, migration, dependency, runtime flag or
transport replacement. Existing log access/retention rules still apply.

Tests use synthetic metadata, native SDK HTTP loopback and disposable MongoDB.
They cover interleaving, missing/native-error/multiple-request observations,
strict privacy, observer exceptions, all three distinct round IDs, consent
revocation before publication, and the existing lease/freshness/campaign,
interruption/context/safety/teardown regressions. No paid sandbox is contacted.

## Optimization design gate

This is the authorized profiling slice, recorded in Jira10465. It does not cache
or remove any freshness check, collapse rounds, reorder business requests,
change deadlines/retry/connection routing or stream model output. A behavioral
optimization needs an owner-reviewed design supported by correlated transport
evidence; this PR does not self-approve one. In particular, provider routing or
a different room-observation API is a proposal only until its privacy/authority
equivalence and measured benefit are reviewed. Browser-audible E2E and Sonnet
TTFT remain unmeasured; the1.5s/3s targets are not claimed as achieved.
