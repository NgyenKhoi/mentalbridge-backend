# Journal and AI Service

NestJS service on Node.js that owns private journal entries and coordinates AI analysis for MentalBridge.

## MB-762 scoped Voice provider

Voice can independently pin `global.anthropic.claude-sonnet-4-6` in
`ap-southeast-1` using `JOURNAL_AI_VOICE_PROVIDER_MODE=APPROVED_BEDROCK`,
`JOURNAL_AI_VOICE_PROVIDER_APPROVAL_VERSION` and the existing
`AWS_BEARER_TOKEN_BEDROCK`. The model and region are validated by the dedicated
`JOURNAL_AI_VOICE_BEDROCK_MODEL` / `JOURNAL_AI_VOICE_BEDROCK_REGION` keys.
This configuration does not change AI Text, benchmark selection, consent,
entitlement or admission. `JOURNAL_AI_VOICE_ENABLED` remains false until the
[real preflight](../docs/mb-762-live-preflight.md) and LIVE_APPROVED gate pass.
Live Voice inference cannot fall back to fake, Gemini, Opus or another region. Existing
nonproduction deterministic tests remain available only while Voice is disabled.

For MB-762 the external `JOURNAL_AI_VOICE_PILOT` JSON contains UTC `startsAt`,
`endsAt` (positive window at most 24 hours) and one/two `ownerIds`. Never change
the window to retry this campaign. Existing retained session rows count every
admission in the window, including failed or refunded calls, in the same Mongo
transaction as the global capacity write. Replays do not allocate again. The
90-second deadline is frozen at admission and also bounded by the window end;
daily Text/Voice policy and financial credits are unchanged. No new collection
or migration is introduced.

Pilot Voice uses the same safety schema/validator with the schema included in
the system prompt, and omits provider-side `outputConfig` so the complete input
can be counted before inference. AWS Mantle Sonnet token counting in Tokyo
must succeed with at most 1,900 tokens (100-token headroom under the per-turn
2,000 ceiling), otherwise no Converse request is sent. Converse remains pinned
to the approved global profile and caps output at 300 tokens; four turns per
call/two calls bound output to 2,400. This capability/permission needs an actual
authorized runtime check and `bedrock-mantle:CountTokens` access; unit tests do
not establish account access. Singapore does not offer Mantle; counting uses
Tokyo under the disclosed AWS cross-region processing, while inference stays on
the approved Singapore Converse endpoint. There is
no fallback or retry when counting/inference fails. Text requests are unchanged.

## Current scope

MB-757 adds the official `livekit-server-sdk` 2.19.2 provider boundary for
AI-only rooms and explicit named dispatch. Its deterministic transport tests
exercise the real SDK against a local synthetic server; no Cloud credentials
or real media are used. Public session/lifecycle integration is described in
the [MB-757 evidence](../docs/story-mb-757-livekit-sessions.md).
MB-758 adds guarded ephemeral voice turns using the existing Companion provider;
see [turn evidence](../docs/story-mb-758-guarded-voice-turns.md).

| Optional configuration                  | Meaning                                                                                                                                                                    |
| --------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `JOURNAL_AI_VOICE_ENABLED`              | Defaults to `false`. Live enablement requires all owner/media credentials and reviewed downstream worker, speech, privacy and account evidence. Forbidden in test/CI.      |
| `JOURNAL_AI_VOICE_APPROVAL_VERSION`     | Reviewed operational evidence version required when enabling; configuration alone is not proof of readiness.                                                               |
| `JOURNAL_AI_VOICE_LIVEKIT_URL`          | Existing project `wss://<project>.livekit.cloud` origin only, without path, credentials, query, fragment or custom port.                                                   |
| `JOURNAL_AI_VOICE_LIVEKIT_API_KEY`      | Existing LiveKit project key, backend only.                                                                                                                                |
| `JOURNAL_AI_VOICE_LIVEKIT_API_SECRET`   | Existing LiveKit project secret, backend only; never placed in client grants or metadata.                                                                                  |
| `JOURNAL_AI_VOICE_AGENT_NAME`           | Explicit registered agent name, default `mentalbridge-ai-voice-v1`; MB-759 must deploy this named worker.                                                                  |
| `JOURNAL_AI_VOICE_WORKER_SERVICE_TOKEN` | Independent 32–256 printable ASCII secret without spaces; distinct from owner-read, reminder/SupportPlan and provider secrets. Sent only on internal worker REST requests. |

Blank optional secrets keep Voice disabled. No new provider registration or
plan upgrade is required for this implementation. Reuse the existing project;
do not copy actual secrets into repository files, issue descriptions or CI.
Media RPCs use a three-second timeout with SDK region failover disabled.
Participant tokens are microphone-only and at most 60 seconds, capped by the
original session hard stop. Explicit participant revocation uses the Cloud API's
Unix-second cutoff near current server time, followed by exact-room teardown.
Token expiration alone is never used as proof of media cutoff or quota release.

Public OpenAPI v1.15.0 adds the MB-758 internal turn route; v1.14.0 added
the MB-757 session and worker lifecycle routes below;
v1.13.0 introduced the MB-756 voice-eligibility read.
The preceding v1.12.0 adds the MB-642 content-free exact Journal creation
evidence read alongside MB-641 daily check-in evidence and retains MB-649
bounded SupportPlan Companion orchestration. The preceding provider enum amendment aligns exact-analysis,
longitudinal evidence and Support
Guide phrasing provider enums with the existing approved `BEDROCK` runtime
route and migration 011. Consumers must accept this provider without relaxing
other result validation. `contract:check` compares these public enums with the
migrated result validators. This publication does not activate a provider,
change routing/approval/consent gates, or change persisted data.

### MB-756 independent voice admission foundation

The [active Journal contract](../contracts/openapi/journal-ai-service-v1.yaml)
introduced `GET /api/v1/ai-companion/voice-eligibility` from the accepted
[Voice ADR](../docs/adr/0046-ai-voice-companion-contract.md) and
[policy](../docs/policies/companion-voice-policy-v1.md). It requires an Identity
USER bearer, derives the owner from that principal, returns `no-store`, and
reads current Care dual consent, Consultation entitlement and independent
voice quota. It creates no reservation, room, token, dispatch or provider call.
Uncertain owner reads fail closed. Missing consent, FREE, occupied capacity,
exhausted quota and provider unavailability never become eligible access.

The application exports `VoiceAccessService` for server-owned reserve and
current-session authorization. Admission reads current owners before the short
MongoDB transaction, hashes retry intent with the existing Journal HMAC secret,
and atomically reserves quota plus the global two-session/per-owner one-session
capacity. Current authorization re-resolves both grants and entitlement; the
stored admission snapshot cannot authorize a later turn. MB-756 delivered only
this foundation. MB-757 adds the session routes below while voice admission
remains disabled by default, pending reviewed MB-758/759/760/762 evidence.

| Optional variable                            | Purpose                                                                                                                                                  |
| -------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `JOURNAL_AI_VOICE_CARE_SERVICE_TOKEN`        | Dedicated Journal-to-Care credential matching Care's `CARE_VOICE_JOURNAL_SERVICE_TOKEN`; sent only as `X-MentalBridge-Voice-Care-Token`.                 |
| `JOURNAL_AI_VOICE_ENTITLEMENT_SERVICE_TOKEN` | Dedicated Journal-to-Consultation credential matching `CONSULTATION_VOICE_JOURNAL_SERVICE_TOKEN`; sent only as `X-MentalBridge-Voice-Entitlement-Token`. |

Blank values disable the corresponding authorization read without changing
existing text configuration. Configured secrets must contain 32–256 printable
ASCII characters without spaces, be distinct from each other, and differ from
configured reminder/SupportPlan service tokens. The clients reuse the existing
Care/Consultation base URLs, enforce a fixed two-second read deadline, reject
redirects, malformed/stale responses and mismatched owner/session/purpose, and
require `no-store`. They do not forward or persist the user's bearer, replicate
grants, query another owner's database or retry a stale positive decision.

Migration [`013_companion_voice_quota.cjs`](migrations/013_companion_voice_quota.cjs)
owns the three closed, content-free MongoDB collections described in the
[database documentation](../docs/database/mongodb.md#ai-companion-voice-admission-and-usage-collections).
MongoDB must support replica-set transactions. The service reconciliation loop
uses server time for authority/deadline expiry, safe unissued-session settlement
and retention cleanup; possibly issued media remains held until verified
teardown. Session and usage records expire 30 days from their own creation,
without extension by activity or settlement. Session deletion scrubs capacity
references transactionally. An unknown old room leaves an anonymous quarantined
slot occupied after identifying metadata is purged; no TTL or current ledger
method makes that slot free.

Voice usage is independent of text answers and Consultation credits. PLUS has
two successful sessions/day, at most 300 seconds/session and 600 ACTIVE seconds/day;
PREMIUM has three, at most 600 seconds/session and 1,800 ACTIVE seconds/day. The
quota day is `Asia/Ho_Chi_Minh`, frozen at admission. The first trusted AI audio
publication while the owner is connected consumes one session and starts ACTIVE
accounting; summed eligible milliseconds are rounded up once on settlement.
No first reply refunds reserved quota. These are ledger rules tested with
synthetic signals; MB-756 does not produce or hear real audio. Audio, transcript,
prompts/replies, prior Journal/chat history and provider/user tokens are excluded
from voice persistence and operational logging.

Delivery is ordered Care [PR #229](https://github.com/NgyenKhoi/mentalbridge-backend/pull/229),
then Consultation [PR #230](https://github.com/NgyenKhoi/mentalbridge-backend/pull/230),
then Journal. Both prerequisites are now on `dev` (`8f295d71`, `5206b3a2`).
The new owner HTTP/Mongo integration validates synthetic owner decisions against
the merged active contracts and proves admission, revocation/expiry compensation
and unchanged Text quota. It runs in `npm run test:integration`; it does not prove
a running Spring/cloud/audio stack. See the
[MB-756 Journal evidence](../docs/story-mb-756-journal-voice-evidence.md) for the
remaining checkpoint and provider gates.

### MB-757 authorized LiveKit session lifecycle

The active contract publishes these additive routes. Public commands derive the
owner from the Identity USER bearer, accept only `{}`, require a UUID
`Idempotency-Key`, and return `Cache-Control: no-store` on success and failure.
Other owners receive 404. Status is an owner-only read without a command key.

| Method and path                                                                | Result                                                                                        |
| ------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------- |
| `POST /api/v1/ai-companion/voice-sessions`                                     | 202 minimized session; atomic quota/capacity reservation and fenced AI room/explicit dispatch |
| `GET /api/v1/ai-companion/voice-sessions/{voiceSessionId}`                     | 200 current minimized state, without media credentials                                        |
| `POST /api/v1/ai-companion/voice-sessions/{voiceSessionId}/connection`         | 200 ephemeral, freshly authorized microphone-only token                                       |
| `POST /api/v1/ai-companion/voice-sessions/{voiceSessionId}/end`                | 202 terminal state or truthful ENDING while provider cleanup is uncertain                     |
| `POST /internal/v1/ai-companion/voice-sessions/{voiceSessionId}/agent-claim`   | 200 attempt/dispatch-bound worker lease                                                       |
| `POST /internal/v1/ai-companion/voice-sessions/{voiceSessionId}/agent-control` | 200 fenced CONTINUE/STOP control response                                                     |

Worker routes require only the dedicated `X-MentalBridge-Voice-Worker-Token`,
reject USER bearers and closed-schema violations, and bind the exact session,
attempt, logical dispatch, lease and sequence. The worker must connect as
`va-<attemptId>`, claim before processing audio and renew every five seconds;
loss of its ten-second lease must stop processing and clear RAM. MB-759 owns
that worker implementation. A server-side `VoiceSessionsService.registerReply`
hook lets MB-758 register opaque turn/reply evidence before publication; arbitrary
worker reply IDs cannot commit usage. The internal turn route is implemented
below; the hosted worker remains MB-759 work.

Migration [`014_companion_voice_media.cjs`](migrations/014_companion_voice_media.cjs)
persists only bounded media progress, sequencing and keyed connection/end
bindings. It must follow 013 before starting this revision. Unknown create or
dispatch outcomes reconcile the original references without duplicate dispatch;
unverified cleanup retains capacity. A one-second non-overlapping maintenance
loop enforces deadlines, current-owner revocation and cleanup retries. Before
claim it can renew authorized setup for at most 45 seconds; after claim it
observes revocation without renewing worker liveness. Heartbeats never reset
the 60-second conversation-idle deadline. Reconnect stays within 30 seconds and
the original hard stop. Metadata expiry preserves anonymous occupied quarantine.

Rollback: disable admission, keep media credentials available for cleanup, end
all voice sessions and verify teardown before rolling back 014. Its down migration
refuses non-STOPPED media. Do not clear capacity manually or roll back 013 while
an uncertain room may still run. See the [controlled-demo runbook](../docs/ai-voice-demo-runbook.md#mb-757-control-plane-checkpoint)
for opt-in checks and the [MB-757 evidence](../docs/story-mb-757-livekit-sessions.md)
for deterministic tests. No real Cloud/audio success is claimed by this story.

### MB-758 guarded ephemeral Voice turns

`POST /internal/v1/ai-companion/voice-sessions/{voiceSessionId}/turns` accepts the
frozen `VoiceTurn` body and UUID `Idempotency-Key`, with the same dedicated
worker header as claim/control. It returns a closed `VoiceReply` with opaque
stable reply ID, Vietnamese spoken text, policy and expiry, always `no-store`.
There is no USER-selected owner, room, model, history or authority. Invalid final
speech/instruction overrides fail 422, stale/conflicting work 409, expired
authority 410, unknown/unapproved provider 503 and provider timeout 504.

The turn adapter reuses `selectCompanionProviderRoute`, `RoutedChatProvider` and
the normalized Companion validator without invoking persisted chat `send` or
its context/quota repositories. The existing common 500-character response limit
stays within Voice's 1,000-character ceiling. All inputs/context are untrusted
data; ambiguous recognition requests a short clarification. No diagnosis,
scoring, safety/eligibility decision, autonomous action or prior Journal/chat
context is introduced. Both Care grants and current Consultation entitlement
are checked before every generation and publication. Real Voice accepts only
the current approved Claude Bedrock route, pins its model/approval/prompt/routing
versions for the call and has no fallback. Fake routes are test/development
only while live Voice is disabled. No new configuration key or provider signup
is required; the existing provider timeout and route settings apply.

Migration [`015_companion_voice_turn_fences.cjs`](migrations/015_companion_voice_turn_fences.cjs)
must follow 014. It adds a content-free `turn` variant to the existing media
command collection, retaining connection/end compatibility. Unique session/
sequence and session/turn-ID claims precede provider work. Only the latest live
RAM reply can replay exactly. Lost RAM or another context process returns stale
rather than invoking the provider again. Demo operation therefore uses one
Journal process or turn routing affinity to the original context process;
replica failover/restart never restores dialogue. A fresh call needs a fresh
authorized session, not an automatic generation retry.

Recent context is at most four current-call pairs and a conservative 2,000-byte
UTF-8 allowance, including the current recognized text and serialization margin.
Older pairs are dropped first; speech that cannot fit returns 422 before a claim
or provider call, even below the structural 2,000-character ceiling. This admits
less history than a characters/4 token estimate for Vietnamese. Actual provider
token usage and approximate route cost are content-free telemetry; missing usage
is labelled ESTIMATED. No prompt-cache checkpoint, transcript/reply snapshot,
recording, raw-content log or text quota mutation is added.

HTTP disconnect, worker cancellation, stop, revoked authority, elapsed lease or
provider failure abort/discard outstanding generation and clear RAM. Cancelling
a pending turn ends that session safely; TTS-only INTERRUPTED control does not
regenerate or bill another reply. Between turns, bounded RECONNECTING preserves
RAM for the same session; only ACTIVE grants authorize another generation.
Publication registers the reply with MB-757, but only trusted connected-audio
playback commits first-reply usage. Generated/replayed text alone never does.

Only a newly fenced, authorized final-speech turn records user conversational
activity before generation, so speech near the idle cutoff remains active while
the model responds. Replay/heartbeat never resets idle; this update neither
consumes worker-control sequence nor extends the lease or absolute deadline.

Shutdown/expiry clears RAM; the existing one-second lifecycle sweep removes
expired command metadata. Fence expiry stays capped by the original session's
30-day window. Disable admission, stop sessions/handlers and expire or separately
review safe fence cleanup before rollback; 015 down refuses surviving turn rows
and never drops the shared command collection. Hosted worker, Vietnamese speech,
account quota/privacy and real-device gates remain in the
[runbook](../docs/ai-voice-demo-runbook.md#mb-758-turn-checkpoint).

The service provides:

- Node.js 22 or newer runtime
- strict TypeScript with ESM
- NestJS application bootstrap
- local-only dotenv loading with Zod configuration validation
- Identity-issued RS256 JWT verification with exact issuer and audience checks
- MongoDB-aware liveness and readiness endpoints
- Pino request logs with correlation IDs
- Prometheus metrics endpoint
- graceful shutdown for `SIGINT` and `SIGTERM`
- owner-scoped journal create, list, detail, revise, and tombstone deletion
- one encrypted owner-scoped daily self-reported emotion check-in per local day
- authoritative factual emotion history, streaks, and 7/14/30-day coverage
- owner-scoped note-free Journal/emotion completion and streak facts for Content-owned notification policy
- AES-256-GCM encrypted journal revisions with lifetime idempotency records
- optional backwards-compatible, user-selected mood encrypted with each revision
- optimistic concurrency through `If-Match` and deterministic cursor pagination
- consented idempotent longitudinal comparison across two bounded journal periods
- minimized exact-source longitudinal evidence for future Care reassessment composition
- encrypted AI Companion conversations with persisted owner-controlled context,
  server-side plan quotas, and deletion
- an internal consent-gated ConsultationBrief suggestion endpoint that accepts only
  a saved situation, owner goals, and minimized PHQ-9/GAD-7 level provenance;
  it returns editable text with provider/model/prompt/schema provenance and stores
  no ConsultationBrief content
- lint, type-check, test, and build scripts
- production multi-stage Docker image

MB-236 implements the authenticated private journal CRUD contract. ADR 0015
freezes a Mongo-only AI Companion runtime: an explicit exact-revision request,
current `AI_PROCESSING` consent checked through Care, a durable leased job, one
provider per run, normalized result, bounded retry, and no raw-response or
bearer-token persistence. MB-367 adds the backend runtime with a deterministic
fake provider. MB-369 adds Consultation-authoritative package lookup,
workload/package routing, gated Gemini/OpenAI adapters, prompt/provenance
versioning, and a synthetic benchmark harness. MB-568 extends that shared
provider boundary and AI Companion with Amazon Bedrock Converse support.
MB-371 adds exact-source
longitudinal jobs, conservative coverage handling, deletion coupling, and a
minimized Care read. Frontend reassessment presentation, AI Chat quota, general
dataset import, and billing lifecycle remain separate.

### MB-641 completion evidence provider

`GET /api/v1/emotion-check-ins/{localDate}/completion-evidence` resolves only
the authenticated owner's record. It returns `RECORDED`, `MISSING`, or
`DELETED`, with the subject/local date, verification time and, only when
recorded, the opaque fact ID, creation revision `1`, creation time and frozen
timezone. Same-day revisions never produce another completion fact. Deleted
becomes missing after the existing content-free tombstone expires.

The read never decrypts or returns emotion, intensity, factors, notes, AI
output, or encrypted payload. It does not require AI-processing consent or
invoke a provider. Store failures return 503 rather than false absence, and
responses are `Cache-Control: no-store`. No schema, retention, source record,
plan selection, or plan lifecycle changes are made.

Care consumes this projection to verify the exact action/date/source link and
reconcile through its transactional evidence boundary. The web/mobile consumers
return to the originating action and require an explicit completion check;
unavailable/deleted source states remain distinct from accepted history. Native
device execution is separately gated by MB-666. Browser-supplied IDs or a
successful navigation alone must never complete an occurrence.

### MB-642 Journal creation evidence provider

`GET /api/v1/journals/{journalId}/completion-evidence` returns only the
authenticated owner's exact entry existence and stable creation fact. Missing
and other-owner IDs are indistinguishable; an owned tombstone is `DELETED`.
`occurredAt` in this fact is the immutable, server-generated entry `createdAt`,
not the user-selected date describing the Journal entry. The creation-fact
revision remains `1` after private content edits; editing an old entry never
counts as a newly completed writing session.

The read never decrypts content or mood, invokes AI, checks processing consent,
or returns tags, preview, revision text, analysis or encrypted envelopes. A
factual completion check does not authorize reflection reuse or specialist
sharing. Store/malformed-source failures return 503 rather than false absence;
responses are `Cache-Control: no-store`. No migration, retention or source
mutation is introduced.

Care must verify subject, exact selected entry, occurrence local day in the
schedule timezone, plan activation and one-fact/one-occurrence before accepting
this reference. Deletion blocks future acceptance; already accepted completion
and immutable review snapshots are historical facts and are not rewritten.
Longitudinal analysis continues selecting exact current source revisions under
its separate explicit AI-processing consent and coverage/provenance contract;
completion facts are not Journal context or treatment success.

## Requirements

- Node.js 22 or newer
- npm 11
- Docker, when building the container image

## Commands

```bash
npm ci
npm run dev
npm run lint
npm run typecheck
npm test
npm run test:integration
npm run contract:check
npm run migration:check
npm run benchmark:exact-revision # only with explicit paid-benchmark config
npm run bedrock:warm-structured-outputs # explicit paid warm-up; never run in CI
npm run build
npm start
```

## Configuration

| Variable                                         | Required                 | Default                                          | Purpose                                                                                                   |
| ------------------------------------------------ | ------------------------ | ------------------------------------------------ | --------------------------------------------------------------------------------------------------------- |
| `JOURNAL_AI_PORT`                                | No                       | `3000`                                           | HTTP port, from 1 through 65535                                                                           |
| `NODE_ENV`                                       | No                       | `development`                                    | Runtime environment: `development`, `test`, or `production`                                               |
| `JOURNAL_AI_LOG_LEVEL`                           | No                       | `info`                                           | Pino log level                                                                                            |
| `JOURNAL_AI_MONGODB_URI`                         | Production               | `mongodb://localhost:27017` outside production   | MongoDB server used by the service and `migrate-mongo`                                                    |
| `JOURNAL_AI_MONGODB_DATABASE`                    | Production               | `mentalbridge_journal_ai` outside production     | MongoDB database owned by this service                                                                    |
| `JOURNAL_AI_MONGODB_CONNECTION_TIMEOUT_MS`       | No                       | `2000`                                           | MongoDB connect/server-selection timeout from 100 through 30000 milliseconds                              |
| `JOURNAL_AI_ENCRYPTION_KEY`                      | Production               | Local-only deterministic development key         | Canonical base64 encoding of the 32-byte AES-256-GCM journal encryption key                               |
| `JOURNAL_AI_IDEMPOTENCY_HMAC_KEY`                | Production               | Local-only deterministic development key         | Canonical base64 encoding of a separate 32-byte key for command hashes and fingerprints                   |
| `IDENTITY_JWT_ISSUER`                            | Yes                      | None                                             | Exact Identity issuer accepted by this resource service                                                   |
| `IDENTITY_JWT_AUDIENCE`                          | Yes                      | None                                             | Exact MentalBridge API audience accepted by this resource service                                         |
| `IDENTITY_JWT_KEY_ID`                            | Yes                      | None                                             | Exact active Identity signing-key identifier accepted by this resource service                            |
| `IDENTITY_JWT_PUBLIC_KEY`                        | Yes                      | None                                             | X.509 RSA public key matching the Identity signing key; the private key is never shared                   |
| `JOURNAL_AI_CARE_BASE_URL`                       | Production               | `http://localhost:8081` outside production       | Care base URL for current AI-processing consent checks                                                    |
| `JOURNAL_AI_CARE_TIMEOUT_MS`                     | No                       | `2000`                                           | Bounded Care consent REST timeout from 100 through 5000 milliseconds                                      |
| `JOURNAL_AI_CONTENT_BASE_URL`                    | Production               | `http://localhost:3003` outside production       | Content base URL for validating and assembling selected resource context                                  |
| `JOURNAL_AI_CONTENT_TIMEOUT_MS`                  | No                       | `2000`                                           | Bounded Content resource/progress REST timeout from 100 through 5000 milliseconds                         |
| `JOURNAL_AI_CONSULTATION_BASE_URL`               | Production               | `http://localhost:8082` outside production       | Consultation base URL for authoritative current entitlement lookup                                        |
| `JOURNAL_AI_CONSULTATION_TIMEOUT_MS`             | No                       | `2000`                                           | Bounded Consultation entitlement REST timeout                                                             |
| `JOURNAL_AI_REMINDER_SERVICE_TOKEN`              | Reminder scheduler       | None                                             | Dedicated 32+ character credential for Content's minimized activity projection                            |
| `JOURNAL_AI_SUPPORT_PLAN_CARE_SERVICE_TOKEN`     | SupportPlan Companion    | None                                             | Dedicated 32+ character credential proving the caller is Care; the forwarded user bearer remains required |
| `JOURNAL_AI_PROVIDER_MODE`                       | No                       | `DETERMINISTIC_FAKE`                             | Selects fake runtime or explicitly approved real routes; tests require fake                               |
| `JOURNAL_AI_ROUTING_POLICY_VERSION`              | No                       | `exact-revision-routing-v1`                      | Version attached to every workload/package route                                                          |
| `JOURNAL_AI_PROVIDER_APPROVAL_VERSION`           | Approved real only       | None                                             | Accepted benchmark approval identifier required before real routing                                       |
| `JOURNAL_AI_FREE_PLUS_PROVIDER/MODEL`            | Approved real only       | None                                             | Pinned baseline route; provider is `GEMINI`, `OPENAI`, or `BEDROCK`                                       |
| `JOURNAL_AI_PREMIUM_PROVIDER/MODEL`              | Approved real only       | None                                             | Pinned Premium route; it may be stronger but is never selected from client input                          |
| `JOURNAL_AI_GEMINI_API_KEY`                      | Selected route/benchmark | None                                             | Gemini credential; never committed or logged                                                              |
| `JOURNAL_AI_OPENAI_API_KEY`                      | Selected route/benchmark | None                                             | OpenAI credential; never committed or logged                                                              |
| `JOURNAL_AI_BEDROCK_REGION`                      | Bedrock route/benchmark  | `ap-southeast-1`                                 | Region used to build the Bedrock Runtime Converse endpoint                                                |
| `AWS_BEARER_TOKEN_BEDROCK`                       | Bedrock route/benchmark  | None                                             | Bedrock long-term API key used as a bearer token; never committed or logged                               |
| `JOURNAL_AI_PROVIDER_TIMEOUT_MS`                 | No                       | `30000`                                          | Per-provider HTTP timeout                                                                                 |
| `JOURNAL_AI_BEDROCK_SCHEMA_WARMUP_TIMEOUT_MS`    | No                       | `300000`                                         | Bedrock-only timeout for the explicit cold structured-output compile command                              |
| `JOURNAL_AI_BENCHMARK_ENABLED`                   | No                       | `false`                                          | Explicit paid-run gate; rejected in test/CI                                                               |
| `JOURNAL_AI_BENCHMARK_DATASET_PATH`              | No                       | synthetic v1 dataset path                        | Version-controlled exact-revision benchmark input                                                         |
| `JOURNAL_AI_BENCHMARK_*_MODEL`                   | Benchmark only           | None                                             | At least one complete pinned Gemini, OpenAI, or Bedrock candidate; no latest alias                        |
| `JOURNAL_AI_*_COST_MICRO_USD_PER_MILLION_TOKENS` | Real route/benchmark     | None                                             | Explicit pricing snapshot used only for cost estimation                                                   |
| `JOURNAL_AI_ANALYSIS_ENABLED`                    | No                       | `true` outside production; `false` in production | Enables the deterministic exact-revision backend runtime; production remains gated                        |
| `JOURNAL_AI_ANALYSIS_POLL_INTERVAL_MS`           | No                       | `250`                                            | Interval for due/expired-lease job claims                                                                 |
| `JOURNAL_AI_ANALYSIS_LEASE_MS`                   | No                       | `35000`                                          | Claim lease, always longer than the fixed 30-second provider-attempt timeout                              |
| `JOURNAL_AI_CHAT_RETENTION_DAYS`                 | No                       | `90`                                             | Conversation expiry and encrypted-message retention                                                       |
| `JOURNAL_AI_CHAT_FREE_DAILY_ANSWERS`             | No                       | `5`                                              | Successfully delivered answers per local day for `FREE`                                                   |
| `JOURNAL_AI_CHAT_PLUS_DAILY_ANSWERS`             | No                       | `30`                                             | Configurable successfully delivered answers per local day for `PLUS`                                      |
| `JOURNAL_AI_CHAT_PREMIUM_FAIR_USE_DAILY_ANSWERS` | No                       | `200`                                            | Hidden backend fair-use ceiling for `PREMIUM`; never displayed as a daily quota                           |
| `JOURNAL_AI_CHAT_RATE_LIMIT_PER_MINUTE`          | No                       | `10`                                             | Per-owner bounded request-rate control                                                                    |
| `JOURNAL_AI_CHAT_DAILY_TOKEN_BUDGET`             | No                       | `100000`                                         | Per-owner provider token/cost guard across all plans                                                      |
| `JOURNAL_AI_CHAT_DEFAULT_TIMEZONE`               | No                       | `Asia/Ho_Chi_Minh`                               | Server-authoritative IANA timezone used for stable quota reset                                            |
| `JOURNAL_AI_CHAT_ROUTING_POLICY_VERSION`         | No                       | `companion-chat-routing-v1`                      | Version persisted with every assistant response                                                           |

Local `.env` files are loaded only outside production and never override real
environment variables. Copy `.env.example` to `.env` for local development,
then replace placeholder JWT values with local credentials. Generate one
stable encryption key and one different HMAC key (for example, with
`crypto.randomBytes(32).toString("base64")`) and keep them unchanged while
encrypted demo data exists. Do not commit local `.env` files or secrets.

## Operations endpoints

| Method   | Path                                                              | Purpose                                                                                                                     |
| -------- | ----------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `GET`    | `/health/live`                                                    | Process liveness probe                                                                                                      |
| `GET`    | `/health/ready`                                                   | Readiness probe that returns success only when owned MongoDB responds                                                       |
| `GET`    | `/metrics`                                                        | Prometheus metrics scrape endpoint                                                                                          |
| `POST`   | `/api/v1/journals`                                                | Create an encrypted journal entry with an idempotency key                                                                   |
| `GET`    | `/api/v1/journals`                                                | List the authenticated owner's entries by opaque cursor                                                                     |
| `GET`    | `/api/v1/journals/{journalId}`                                    | Read one owner-scoped entry                                                                                                 |
| `GET`    | `/api/v1/journals/{journalId}/completion-evidence`                | Resolve a content-free immutable creation fact for exact owned Journal reconciliation                                       |
| `PATCH`  | `/api/v1/journals/{journalId}`                                    | Append a revision guarded by `If-Match`                                                                                     |
| `DELETE` | `/api/v1/journals/{journalId}`                                    | Create an idempotent owner-scoped tombstone                                                                                 |
| `POST`   | `/api/v1/journals/{journalId}/revisions/{revision}/analysis-jobs` | Request one consented exact-revision analysis job                                                                           |
| `GET`    | `/api/v1/analysis-jobs/{jobId}`                                   | Read the owner-scoped job state and normalized result                                                                       |
| `POST`   | `/api/v1/longitudinal-analysis-jobs`                              | Compare exact owned revisions across two bounded periods                                                                    |
| `GET`    | `/api/v1/longitudinal-analysis-jobs/{jobId}`                      | Read the owner-scoped longitudinal job and safe evidence                                                                    |
| `GET`    | `/internal/v1/users/{userId}/longitudinal-analyses/{analysisId}`  | Return minimized consented evidence for Care reassessment                                                                   |
| `POST`   | `/api/v1/emotion-check-ins`                                       | Create the current local-day self-reported emotion check-in                                                                 |
| `GET`    | `/api/v1/emotion-check-ins`                                       | List owner-scoped self-reported emotion history                                                                             |
| `GET`    | `/api/v1/emotion-check-ins/{localDate}`                           | Reload one owned local-day check-in                                                                                         |
| `GET`    | `/api/v1/emotion-check-ins/{localDate}/completion-evidence`       | Resolve the owner's content-free check-in existence fact for Care reconciliation                                            |
| `PATCH`  | `/api/v1/emotion-check-ins/{localDate}`                           | Optimistically update the current local-day check-in                                                                        |
| `DELETE` | `/api/v1/emotion-check-ins/{localDate}`                           | Erase encrypted revisions and retain a bounded tombstone                                                                    |
| `GET`    | `/api/v1/emotion-check-in-context`                                | Return note-free AI context after current Care consent                                                                      |
| `GET`    | `/api/v1/notification-activity`                                   | Return independent note-free Journal/emotion activity for the authenticated owner                                           |
| `POST`   | `/internal/v1/notification-activity`                              | Return minimized owner-bound activity and bounded streak milestone facts to Content using the scoped reminder service token |
| `POST`   | `/api/v1/ai-companion/conversations`                              | Start an encrypted owner-scoped conversation                                                                                |
| `GET`    | `/api/v1/ai-companion/conversations`                              | List bounded retained conversation summaries without message bodies                                                         |
| `GET`    | `/api/v1/ai-companion/conversations/{conversationId}`             | Resume one owned conversation                                                                                               |
| `PUT`    | `/api/v1/ai-companion/conversations/{conversationId}/context`     | Replace the owned conversation's persisted context-source selection                                                         |
| `POST`   | `/api/v1/ai-companion/conversations/{conversationId}/messages`    | Deliver one quota-governed normalized assistant response                                                                    |
| `POST`   | `/internal/v1/support-guide-phrasing`                             | Rephrase exact Care-approved guide copy after current AI consent                                                            |
| `POST`   | `/internal/v1/support-plan-companion/messages`                    | Phrase one Care-authenticated policy-selected SupportPlan intent from exact minimized context, with deterministic fallback  |
| `DELETE` | `/api/v1/ai-companion/conversations/{conversationId}`             | Hard-delete the conversation and replay snapshots                                                                           |

Incoming requests echo a valid bounded `x-correlation-id` or receive a generated one. Request logs include the same correlation ID and redact authorization, cookie, and service-token headers. Non-public application routes require an Identity-issued RS256 bearer token; signature, issuer, audience, lifetime, subject, token ID, and roles are validated before a principal is attached to the request. The internal notification activity projection instead requires the dedicated `JOURNAL_AI_REMINDER_SERVICE_TOKEN` and exposes only note-free factual activity. SupportPlan Companion requires both the forwarded end-user bearer and `JOURNAL_AI_SUPPORT_PLAN_CARE_SERVICE_TOKEN`; a USER bearer alone fails before context validation, consent lookup, or provider access.

SupportPlan Companion v1 has no separate persisted owner quota ledger. Care owns explicit-request admission and pending-click deduplication, while Journal/AI uses only its configured approved provider route and bounded timeout. Provider rate or quota rejection returns deterministic template copy and never retries through another provider. The dedicated Care caller boundary prevents public USER-only requests from consuming that provider route.

## Contracts and migrations

- Journal CRUD OpenAPI: `../contracts/openapi/journal-ai-service-v1.yaml`
- MongoDB migration baseline: `migrations/001_journal_entries_baseline.cjs`
- Journal mutation-command validator and unique index: `migrations/002_journal_mutation_commands.cjs`
- Immutable replay metadata and cursor-index alignment: `migrations/003_journal_replay_snapshots_and_cursor_index.cjs`
- Encrypted per-revision mood validation: `migrations/004_journal_revision_mood.cjs`
- Exact-revision analysis job/result validators and indexes: `migrations/005_exact_revision_analysis.cjs`
- Daily emotion check-in validator, owner/day, replay, history, and TTL indexes: `migrations/006_daily_emotion_check_ins.cjs`
- Entitlement-aware route/provenance validator expansion: `migrations/007_entitlement_aware_model_routing.cjs`
- Synthetic benchmark metadata/run/case-result collections: `migrations/008_ai_benchmark_metadata.cjs`
- Longitudinal analysis job/result validators and indexes: `migrations/009_longitudinal_context_analysis.cjs`
- AI Companion conversation, command, quota, rate, and TTL indexes: `migrations/010_ai_companion_chat_quotas.cjs`
- Bedrock provider provenance and three-candidate benchmark validation: `migrations/011_bedrock_provider.cjs`
- Persisted AI Companion source selection and RESOURCE provenance: `migrations/012_ai_companion_conversation_context.cjs`

AI Companion chat follows ADR 0021. List responses contain metadata-only
summaries; full bounded message history is returned only by the owner-scoped
detail endpoint. Real-provider replies use strict structured output and a
fail-closed authority validator before persistence. It counts only successfully
persisted assistant responses, derives the reset from a server-configured IANA timezone,
and keeps Premium UI copy free of an infrastructure-unlimited claim. Context
selection is stored on the owner-scoped conversation; message payload context
from older clients is accepted only for compatibility and is ignored. The
server assembles only the enabled plan, recent journal, screening, and validated
resource sources, while persisting provenance labels rather than assembled
plaintext. MongoDB
must provide replica-set transaction semantics. Local/test/CI use the
deterministic fake; a real route additionally requires the existing approved
provider/model configuration and the corresponding external credential.

Longitudinal requests use equal, half-open, non-overlapping periods of 7-31
days. The current period cannot end in the future. The service selects no more
than 50 active current revisions per period after optional exclusions. At least
three entries per period and no greater than a 2:1 count imbalance are required
for directional comparison; otherwise the response exposes
`INSUFFICIENT_DATA`. Source text is decrypted only for the provider attempt and
is never stored in jobs, results, logs, or the Care projection. Deleting any
source journal removes every dependent longitudinal job and result.

The Care projection requires purpose `REASSESSMENT_SUMMARY`, a matching
forwarded end-user bearer, and current `AI_PROCESSING` consent. MB-386 implements
Care Story 6501 as the composition consumer and calls this endpoint with its standard
2-second internal REST deadline and fail safely when Journal/AI or consent is
unavailable.

ADR 0018 freezes the daily check-in as self-reported reflection rather than a
clinical score or safety classifier. The five emotion labels reuse Journal's
reviewed mood vocabulary; intensity means strength only. The IANA timezone is
frozen at creation, updates use `If-Match-Revision`, optional notes remain inside
the encrypted envelope, and deletion immediately removes all encrypted
revisions. Only a note-free AI projection is exposed under current Care
`AI_PROCESSING` consent. Reminder composition remains unavailable because it has
no approved consent authorization contract.

ADR 0025 extends that aggregate with a recomputed, note-free progress read
model. The caller's valid IANA timezone anchors the server-derived current
local date. One active local date counts once, same-day revisions do not
increment coverage, and deleted or missing dates break continuity. The response
contains factual current/longest streaks and 7/14/30-day label counts only; it
does not average intensity or claim adherence, improvement, or recovery.

The committed benchmark dataset is synthetic and CC0-labelled. A run evaluates
one or more explicitly configured Gemini/OpenAI/Bedrock candidates; credentials for an
unconfigured provider are not required. Running the harness does not approve a
candidate. An accepted result must be reviewed and recorded separately as
`JOURNAL_AI_PROVIDER_APPROVAL_VERSION` before `APPROVED_REAL` runtime mode can
start. The router never falls back to a second provider after a failure.
The benchmark retries a transient 429, provider 5xx, or transport failure at
most once on the same route and emits only safe failure metadata such as HTTP
status, provider code, retry delay, quota identifier, finish reason, and schema
issue paths. It never emits the API key, request headers, raw provider response,
or hidden reasoning. Daily-quota fail-fast is deferred to MB-432 follow-up
verification.

### Bedrock enablement

Bedrock uses the Runtime `Converse` HTTPS endpoint with
`Authorization: Bearer <AWS_BEARER_TOKEN_BEDROCK>`. IAM/SigV4 support is
intentionally deferred. Structured-output schemas are adapted for Bedrock on a
deep copy; the canonical Zod-derived schema and normalized result validation
remain unchanged. Provider failures use the existing bounded retry policy and
never fall back to Gemini or OpenAI.

After the AWS account has Bedrock model access and sufficient quota:

1. Set `AWS_BEARER_TOKEN_BEDROCK`, `JOURNAL_AI_BEDROCK_REGION`, and a pinned
   Bedrock model ID. Do not commit the token.
2. Configure the matching Bedrock input/output cost snapshot. For benchmark
   evidence, set the three `JOURNAL_AI_BENCHMARK_BEDROCK_*` variables and
   explicitly enable `JOURNAL_AI_BENCHMARK_ENABLED` only for the paid run.
3. Run `npm run bedrock:warm-structured-outputs` immediately before the
   controlled traffic window. It sends one paid Converse request for each of
   the exact-revision, longitudinal, Support Guide phrasing, SupportPlan Companion, and Companion Chat
   schemas to every distinct configured Bedrock route model. The command uses
   only `JOURNAL_AI_BEDROCK_SCHEMA_WARMUP_TIMEOUT_MS`; ordinary runtime calls
   retain the bounded provider timeout. Repeat after a model/schema change and
   before another traffic window when AWS's 24-hour schema cache may be cold.
4. Run `npm run benchmark:exact-revision` and review the recorded quality,
   safety, latency, token, cost, and failure evidence.
5. Record the accepted evidence as `JOURNAL_AI_PROVIDER_APPROVAL_VERSION`, set
   the applicable route provider to `BEDROCK`, and only then switch
   `JOURNAL_AI_PROVIDER_MODE` to `APPROVED_REAL`.

No paid live Bedrock call is part of automated tests or this implementation
evidence. Until account/model quota is available and the approval steps above
are completed, production routing must remain unchanged.

Story 6201 keeps journal content plain text and adds the stable `GREAT`, `GOOD`,
`OKAY`, `LOW`, and `VERY_LOW` mood labels. The API accepts an omitted mood for
older clients and legacy entries return `null`; the current editor requires an
explicit selection. Mood values are encrypted in the owning revision and are
never indexed or logged. Omitting mood during a revision preserves the current
value. Draft protection is client-owned, explicit-save only, and does not put
raw journal text in browser persistence.

MB-658 extends the daily emotion owner value with at most three unique optional
factor codes from `sleep`, `work`, `study`, `family`, `friends`, `health`, and
`other`. The existing optional note and these factors share the encrypted
revision payload. Omitting factors remains valid and legacy payloads normalize
to an empty list. The Mongo validator continues to see only the encrypted
envelope, so no collection rewrite is needed. Factors and raw notes are absent
from AI context, notification activity, SupportPlan, reassessment, analytics,
events, metrics, and logs; delete erases them with the encrypted revisions.

Run `npm run contract:check` and `npm run migration:check` for static validation. `npm run test:integration` builds the service and runs the HTTP CRUD/concurrency suite against the explicitly configured disposable MongoDB database; it covers cursor tie-breakers/index use, exact mutation replay after more than 32 later commands, conflicting key reuse, owner isolation, tombstones, revision concurrency, and bounded dependency failure. The test refuses a non-disposable database name and drops its database in cleanup.

## Graceful shutdown

The service stops accepting new connections after receiving `SIGINT` or `SIGTERM`. Existing connections have up to 10 seconds to close before they are terminated.
