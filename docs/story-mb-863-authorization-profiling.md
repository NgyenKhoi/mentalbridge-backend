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
rounds totaling median 2,579 ms; publication median 1,104 ms/p95 1,196 ms. Its temporally
associated ListRooms read differs from publication by 7–9 ms. All 74 room reads
have p50 740 ms/p95 1,245 ms. Context is 0–1 ms. These are within-call historical
observations, not a matched A/B sample or a representative E2E percentile.

All three media reads already start concurrently. The pinned SDK 2.19.2 uses
native fetch with failover disabled and a 3-second request timeout. Why ListRooms
is slow remains UNKNOWN: the old timers combine signing/serialization, queue,
network, provider processing and parsing, without a direct inspection/turn edge.
Existing native fetch also means missing keep-alive is not established.

## Unchanged execution and new metadata

Every turn keeps these three boundaries:

| Purpose       | Position                                                                | Mandatory current checks                                                                                                                    |
| ------------- | ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| INITIAL       | Before context/generation admission                                     | Owned active attempt/lease, bound campaign, current Care/Consultation, room/dispatch/participants, common-return freshness                  |
| PRE_INFERENCE | Immediately before provider dispatch                                    | Same fresh owner/state checks; room/dispatch/participants are inspected again unless a successful inspection is reusable (MB-867, below)    |
| PUBLICATION   | After full END_TURN/schema/safety validation, before reply registration | Same as PRE_INFERENCE; consent loss and stale authority always deny; recording or invalid media deny only when this round inspects (MB-867) |

**Amended by MB-867 (owner decision 2026-11-17).** PRE_INFERENCE and PUBLICATION
inspect LiveKit media only when no reusable result exists. A result is reusable
when it came from a fully successful authorization of the same session, attempt
and media identity, is at most 5 s old both when the round starts and at its
common return, and the authority and lease are unexpired. If the age passes 5 s
during the owner/state reads, the whole round reruns with a fresh inspection.
Failed or denied inspections are never reused; INITIAL always inspects. Owner
authority, state/lease, media document and measurement checks run on every
round. A reused round emits no `MEDIA_INSPECTION` stage; a rerun round emits
its owner, media and STATE_AFTER stages twice under the same authorizationId.

Accepted observation gap: during the up-to-5 s window a room or participant
disconnect, a started recording or a foreign participant is not seen by the
reusing round. A disconnect is stopped by the worker on `ROOM_DISCONNECTED`;
a recording or foreign participant is caught by the next real inspection. In
exchange a turn saves about 2 s. The reuse is held per journal-ai replica.

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

| Field                        | Meaning / limit                                                                                              |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------ |
| sdkBeforeRequestMs           | Read invocation to native request creation; SDK preparation, not pure signing time                           |
| requestToSendMs              | Request creation to first request write; may include queue/connect work, not isolated DNS/TLS                |
| sendToHeadersMs              | First request write to response headers; includes network and server work, not model TTFT                    |
| headersToBodyMs              | Headers to completed wire response; not JSON validation time                                                 |
| sdkAfterBodyMs               | Wire completion to SDK read settlement; includes parsing/scheduling, not pure parser CPU                     |
| nativeRequests               | Bounded observed request count, not permission to retry                                                      |
| connection                   | FIRST_OBSERVED_SOCKET, REUSED_OBSERVED_SOCKET or UNKNOWN; first observed does not mean newly connected       |
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
TTFT remain unmeasured; the 1.5 s/3 s targets are not claimed as achieved.

## Reproducible local comparison

After `npm ci` and `npm run build` in Journal, run:

```sh
node scripts/benchmark-voice-authorization.mjs
```

The script transpiles the two unchanged adapters directly from git base
`a51edb8f` and compares them with the profiled build. It creates only a loopback
HTTP fixture, refuses non-loopback fetches and loads no env/credentials. Each
variant runs 20 samples of three consecutive current owner/media bundles,
five reads per round. Paired alternating order follows one warm-up per variant;
there are 600 measured and 30 warm-up local HTTP requests, zero live admissions
or provider requests. The fixture deliberately delays room headers 45–75 ms and
room body 4 ms; the other reads delay 5 ms. Actual timer scheduling, SDK signing,
HTTP and parsing run locally. These are simulated dependencies, not Cloud time.

| Three-round network bundle      | p50 ms   | p95 ms   | Worst ms |
| ------------------------------- | -------- | -------- | -------- |
| Before profiling, base a51edb8f | 250.7941 | 300.5870 | 301.5926 |
| Profiled runtime d009c4df       | 251.8660 | 301.4009 | 317.0611 |

There is **no latency improvement claim**. This measures instrumentation
overhead/control-path parity, with an in-RAM sink and small samples; production
logger I/O and complete Mongo/session/model/audio time are excluded. The median
delta is +1.0719 ms and p95 delta +0.8139 ms in this run, not an overhead guarantee.
Windows timer scheduling affects the deliberately short fixture delays.

All 60 profiled rounds/inspections join directly to 180 native requests. All 180
use previously observed sockets after warm-up. Injected room header wait is
identified separately: send-to-headers p50/p95 65/81 ms; body 16/19 ms. Room total
83/100 ms versus dispatch and participants 17/19 ms each. This validates that
the profiler finds the deliberately slow layer; it does not prove the same
layer is slow in LiveKit Cloud. [Minimized numeric evidence](evidence/mb-863-authorization-profiling.json)
contains no raw request, response, account/room/turn identity or credentials.

The benchmark's runtime commit is the independently tested source slice;
the evidence/reproducer commit changes only scripts and documentation. Source
and metrics must not be presented as deployed-stage before/after measurements.

## Proposed optimization decision after review

| Correlated evidence                                                  | Next bounded proposal                                                                                                  | Risk / gate                                                                               |
| -------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| Long send-to-headers on reused sockets, short SDK/queue/body phases  | Operator/provider review of ListRooms control-plane route/region and expected latency using sanitized request evidence | No endpoint/routing change without owner review and comparable authorized measurements    |
| Long create-to-send and missing reuse                                | Evaluate supported transport reuse/pool settings after isolating queue versus connection work                          | Do not replace global fetch, assume a cold socket, or weaken deadlines/failure handling   |
| Long SDK preparation or post-body phases                             | Inspect signing/serialization/decoding CPU and payload shape on the known revision                                     | Preserve token grants, closed validation and current state checks; no speculative caching |
| Proposed lower-latency observation API or fewer authorization rounds | First prove current recording/consent/entitlement/lease/publication equivalence in a separate design                   | Not approved or implemented here; unknown state still denies                              |

Before correlated staging evidence, no behavioral candidate has sufficient
causal evidence for implementation. The largest historical target remains
the ListRooms-dominated critical path, not context construction or a conjectured
free-model penalty. A separately permitted read-only/metadata capture can use
this source after Code Owner review and independently authorized deployment;
this delivery performs neither. Any later Voice acceptance still requires a
current bounded window and MB-861/862 runtime/credit/privacy/authority preflight.
