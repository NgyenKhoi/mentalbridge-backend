# ADR 0046: AI Voice Companion contract

- Decision ID: `MB-AI-VOICE-COMPANION-001`
- Date: 2026-10-23
- Status: Accepted target; contracts and policy only, no implemented voice runtime
- Delivery: [MB-755](https://vunguyenkhoi47.atlassian.net/browse/MB-755)
- Policy: [Companion Voice policy v1](../policies/companion-voice-policy-v1.md)
- Extends the AI Companion boundary in [ADR 0021](0021-ai-companion-conversation-and-quota.md)
- Preserves the appointment boundary in [ADR 0039](0039-livekit-cloud-video-consultation.md)

## Context

The existing AI Companion is a USER-to-AI conversation owned by Journal/AI.
Its text runtime persists encrypted messages, counts delivered text answers and
uses current Care consent and Consultation entitlement. Its synchronous,
non-streaming text contract does not define a microphone session, a live agent,
voice quotas or permission to send audio to speech providers.

The owner approved a separate Vietnamese voice mode for PLUS and PREMIUM.
FREE retains text access under its existing policy. Voice is not a specialist
appointment, consultation treatment, a paid consultation credit or a new
SupportPlan authority. MB-755 freezes the target so owner APIs, the Node worker
and later clients can be implemented against one reviewed boundary.

## Decision and ownership

| Boundary                                                                | Authority                                                                               |
| ----------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| Account identity and immutable USER role                                | Identity                                                                                |
| Current `AI_PROCESSING` and separate `AI_VOICE_PROCESSING` consent      | Care                                                                                    |
| Current package, entitlement provenance, version and expiry             | Consultation                                                                            |
| Voice capabilities, admission, session lifecycle, quota and termination | Journal/AI                                                                              |
| Voice usage and operational metadata                                    | Journal/AI MongoDB; runtime delivery belongs to MB-756                                  |
| Media room, explicit agent dispatch and Vietnamese STT/TTS transport    | Approved LiveKit Cloud project and LiveKit Inference, controlled by Journal/AI          |
| Node agent execution                                                    | Journal/AI-owned worker under MB-759; its business/model calls use Journal/AI REST only |
| Approved Claude inference and policy enforcement                        | Journal/AI's existing AWS Bedrock provider boundary                                     |

The worker is an execution component of the Journal/AI owner. It is not a new
data-owning service, Realtime feature or financial authority. It must not call
Bedrock directly or receive an end-user bearer token. All MentalBridge owner
queries and worker business calls remain authenticated REST/JSON. No Kafka
request/reply, new broker or cross-service database access is introduced.

The repository's restriction on client WebSocket delivery to Realtime remains
the application chat/notification rule. This accepted decision adds a narrow
provider-media exception for USER-to-AI LiveKit sessions and provider agent
control. It does not authorize business APIs over WebSocket or another
MentalBridge service's socket gateway. The existing appointment media exception
and its official provider SDK boundary remain governed by ADR 0039.

## Provider selection and deployment boundary

The approved target uses the existing `mentalbridge` LiveKit Cloud Free-plan
project, hosted agents and LiveKit Inference for Vietnamese STT/TTS. No extra EC2
instance or self-hosted media stack is introduced. Free-plan selection is not a
promise of free execution, sufficient quota, provider availability or permission
to upgrade to a paid plan automatically.

Journal/AI retains its existing approved package-to-model routing. The approved
Bedrock account configuration is `ap-southeast-1`; the accepted model candidates
are:

| Model             | Model identifier               | Global inference profile              |
| ----------------- | ------------------------------ | ------------------------------------- |
| Claude Sonnet 4.6 | `anthropic.claude-sonnet-4-6`  | `global.anthropic.claude-sonnet-4-6`  |
| Claude Opus 4.6   | `anthropic.claude-opus-4-6-v1` | `global.anthropic.claude-opus-4-6-v1` |

This list does not assign a new model to a package or prove model access.
Existing approval versions and route selection remain authoritative. A global
inference profile can process outside the configured AWS region; the voice
disclosure must state this cross-region boundary. No automatic cross-provider
fallback is approved.

Account inventory, model access, usable provider/free-plan quotas, hosted-worker
availability, Vietnamese speech quality, latency, interruption behavior and a
live approved benchmark remain unverified. MB-760/MB-762 must establish their
applicable gates before live enablement. MB-755 must not claim that a provider
contract, model identifier or fixture proves them.

## Independent consent and privacy

Voice requires two current, independent Care grants: existing `AI_PROCESSING`
and new `AI_VOICE_PROCESSING` at `ai-voice-processing-capstone-v1`. Existing
AI-processing disclosure/version and text behavior remain unchanged. An old
AI-processing grant, package, microphone permission or successful text session
does not grant voice consent.

Care owns the immutable Vietnamese voice disclosure and append-only voice
decision stream. The user must explicitly acknowledge the current voice notice
before microphone processing/provider connection. It identifies microphone
audio, transient transcription and response synthesis; LiveKit/Inference and
Bedrock processing; cross-region profiles; the absence of saved recordings or
transcripts; retained operational/usage metadata; and withdrawal/stop behavior.
Material changes to voice purpose or vendors require a new voice version and
fresh voice consent only. They must not rewrite historical consent or silently
invalidate the separate existing text grant.

Only the current call's in-memory conversation may inform a response. No prior
text conversation, saved Journal, mood/emotion record, assessment, SupportPlan,
ConsultationBrief, SessionSummary or private specialist note is loaded as voice
context. Voice output cannot diagnose, score screening, determine safety or
entitlement, mutate a plan, book/complete an appointment, consume consultation
credits or contact another party.

Current-call context is bounded to four recent user/assistant pairs and 2,000
tokens. Each transient recognized turn is at most 2,000 characters and each
spoken response at most 1,000 characters. These are processing bounds, never
permission to retain a transcript or carry context into another session.

No audio, recording, transcript, assembled prompt, model reply or synthesized
speech is persisted by the voice feature. LiveKit/agent content observability,
recording, egress and transcript capture are disabled. The only durable voice
data is bounded content-free session, quota and operational metadata, retained
for 30 days. Provider privacy/configuration checks must confirm the approved
no-content-retention mode before live use; this document does not assert an
unverified provider retention guarantee. Text chat's existing history and
retention are unchanged.

## Quotas and current-session limits

The approved `companion-voice-policy-v1` limits are separate from text answers,
text token budgets and consultation credits:

| Package | Successful voice sessions per local day | Maximum session ceiling | Daily chargeable ACTIVE time |
| ------- | --------------------------------------: | ----------------------: | ---------------------------: |
| FREE    |                                       0 |               0 seconds |                    0 seconds |
| PLUS    |                                       2 |             300 seconds |                  600 seconds |
| PREMIUM |                                       3 |             600 seconds |                1,800 seconds |

The authoritative day is frozen at admission in `Asia/Ho_Chi_Minh`; reset is
00:00 with no rollover. Midnight never renews an in-flight session. An upgrade
raises the applicable day's ceilings without clearing used/reserved sessions
or seconds. An admitted session's absolute deadline never extends.

Admission atomically reserves a session allowance, bounded seconds and capacity.
Across the demo, at most two reserving/live sessions may exist; each owner has
at most one across devices. A session without a first successful AI spoken reply
refunds its reserved session allowance and seconds. After that reply, the
session counts once and only ACTIVE time is charged. CONNECTING and RECONNECTING
do not consume ACTIVE seconds. The policy defines cutoff, idle, failure and
reconciliation behavior in detail.

At AUTHORIZED, admitted seconds equal the smaller of the package session maximum
and remaining daily seconds. `hardStopAt = authorizedAt + admittedSeconds` is an
absolute wall-clock cutoff, including setup and reconnect time. Chargeable
post-first-reply ACTIVE milliseconds are summed and rounded up to seconds once
per session at settlement. Non-replay start attempts have separate fair-use caps
of three per user per minute and ten per user per local day; retries of the same
accepted command do not consume another attempt or successful-session allowance.

## Lifecycle and authorization

The contract uses exactly these Jira states:

`REQUESTED`, `AUTHORIZED`, `CONNECTING`, `ACTIVE`, `RECONNECTING`, `ENDING`,
`ENDED`, `FAILED`, `EXPIRED`.

Journal/AI owns every transition. Client timers, room existence, dispatch
acknowledgement and worker claims cannot grant authorization or extend a lease.
The server enforces the absolute session ceiling, a 60-second idle cutoff and a
30-second reconnect grace. Entitlement loss, either consent withdrawal, quota
exhaustion, deadline or explicit stop triggers termination. A reconnect requires
fresh owner checks and never creates a new allowance or extends the deadline.

REQUESTED owner checks have a 10-second deadline; CONNECTING has a 45-second
deadline. The earlier absolute session cutoff always wins. Every live attempt
holds an authority lease of at most 10 seconds, renewed every five seconds with
fresh owner reads, each bounded by a two-second REST deadline. A failed renewal
halts all processing, purges current-call RAM and initiates cutoff. Observed
revocation cancels immediately; a lost signal is bounded by the 10-second lease,
not a claim of instantaneous termination during a network partition.

Public start/reconnect operations verify the current Identity USER principal.
Journal/AI creates opaque room/participant mappings and explicitly dispatches
only the approved agent. AI rooms are namespace-isolated from appointment
rooms. Client grants permit only required microphone publication and agent
audio subscription; no camera, recording, administration or arbitrary data
publication. Join credentials are short-lived, exact-room/identity-bound,
memory-only and `no-store`; no secret, subject JWT or health data enters room
names, URLs, metadata or dispatch metadata.

Token expiry alone does not disconnect an established LiveKit session. Journal/AI
must explicitly cut off participants/agent and close the room. Unconfirmed
cleanup keeps capacity reserved until fenced reconciliation proves the old
attempt cannot remain live. It must not admit a parallel session by merely
expiring a local TTL while provider media continues.

### Service-owned checks after dispatch

The existing Care consent and Consultation current-entitlement clients forward
the user's bearer and derive the subject from it. They do not authorize a
background voice session after that bearer is absent. Persisting or handing
that credential to the worker is not an accepted workaround.

MB-755 therefore requires additive planned owner contracts: a dedicated
Journal/AI-authenticated Care read of the exact session owner's two current
consent decisions, and a dedicated Journal/AI-authenticated Consultation read
of that owner's current entitlement. Their credentials are purpose-scoped,
separate from browser, worker, reminder and Community credentials. Each owner
validates caller and exact subject, returns only the minimized current decision
with `no-store`, and fails closed on uncertainty. Existing service-authorized
notification/Community projections establish the pattern, not an existing
voice authorization capability.

Journal/AI resolves the account from its owner-bound Mongo session; the worker
cannot submit a different subject or package. Before each model turn and every
start/reconnect, Journal/AI rechecks both consent streams and current entitlement.
The worker uses authenticated, attempt/session-bound Journal/AI REST to claim,
request a transient turn, report progress and finish. A stale/duplicate attempt
is fenced. LiveKit dispatch metadata carries only an opaque session/attempt
reference. Provider keys, user JWTs and authority-bearing secrets do not travel
in that metadata.

Authority renewal performs the same current owner checks; a successful prior
check is not a reusable authorization grant. No database transaction spans these
remote reads or provider work. Generation is not automatically retried after
any spoken prefix, avoiding duplicate speech or a falsely replayed answer.

## Contract and compatibility consequences

Public control, owner checks, stable Problem Details errors and the worker
envelope are planned design artifacts under `contracts/proposals/`. They are
not active endpoints. The accepted design sources are the
[planned REST contract](../../contracts/proposals/companion-voice-v1.openapi.json),
[worker envelope](../../contracts/proposals/companion-voice-worker-v1.schema.json),
[lifecycle](../../contracts/proposals/companion-voice-lifecycle-v1.json) and
[machine-readable policy](../policies/companion-voice-policy-v1.json).
Worker control/metadata excludes content; the separate
authorized inference request/response may carry bounded current-turn text in
memory only. Neither transcript text nor model replies may be written into an
idempotency replay snapshot, log, trace, event or crash report.

No production handler, worker, frontend, package, migration, cloud resource or
deployment configuration changes in MB-755. MB-756 owns future Mongo metadata,
admission and quota implementation; its migrations/document model changes must
arrive together with runtime. Care/Consultation service-check delivery is an
explicit prerequisite, not an assumed existing API. Existing text REST/schema,
consent, package routing, consultation credits and appointment media behavior
remain compatible. Accepted target status must never be labelled implemented.

## Verification and enablement gates

Contract fixtures cover all nine states, valid control/turn shapes, closed
payloads, owner and attempt binding, documented failures and privacy exclusions.
They do not establish live authorization, race safety or provider performance.
Future runtime gates must prove atomic owner/global admission, retry/dispatch
fencing, current consent/entitlement checks, exact usage accounting, cutoff and
cleanup after crashes/outages, and content-free observability. Paid provider
calls do not run in CI.

Live enablement additionally requires verified account/model/quota inventory,
provider privacy configuration, approved route/voice disclosure and consent,
Vietnamese speech/latency benchmark evidence and explicit operational approval
under the downstream stories. General text AI, deterministic safety entry and
manual consultation paths remain available when voice is unavailable.

## MB-782 additive Web prerequisite amendment — 2026-10-28

The product owner approved quota-aware display-only pre-call duration fields
and official `lk.agent.state` hints before MB-761. The Voice target is amended
to `1.1.0`; active Journal OpenAPI is `1.16.0`. Connection responses identify
the exact admitted room and Agent attempt for safe consumer filtering.
See the [implementation and consumer contract](../story-mb-782-voice-web-prerequisite.md).

The custom Node worker's Journal generation is outside AgentSession's LLM loop.
Keep participant metadata permission denied and use one authorized LiveKit
server API writer for fixed coarse media states. This avoids competing SDK
state writes and adds no business request/reply channel. The existing media SDK
exception applies only to provider media control; Journal REST remains the
business authority. USER permissions, consent, entitlement, quota, accounting,
retention and server lifecycle stay unchanged. There is no persisted migration.

The expanded closed DTOs require consumer regeneration. Attribute failure or
staleness only changes presentation; a consumer must bound its hint age and
fall back on disconnect/unknown state. No live provider usage or enablement is
approved by this amendment. MB-782 merges before the MB-761 consumer PR.
