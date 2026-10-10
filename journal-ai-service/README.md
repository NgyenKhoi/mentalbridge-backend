# Journal and AI Service

NestJS service on Node.js that owns private journal entries and coordinates AI analysis for MentalBridge.

## Current scope

Public OpenAPI v1.8.0 aligns exact-analysis, longitudinal evidence and Support
Guide phrasing provider enums with the existing approved `BEDROCK` runtime
route and migration 011. Consumers must accept this provider without relaxing
other result validation. `contract:check` compares these public enums with the
migrated result validators. This publication does not activate a provider,
change routing/approval/consent gates, or change persisted data.

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

| Variable                                         | Required                 | Default                                          | Purpose                                                                                 |
| ------------------------------------------------ | ------------------------ | ------------------------------------------------ | --------------------------------------------------------------------------------------- |
| `JOURNAL_AI_PORT`                                | No                       | `3000`                                           | HTTP port, from 1 through 65535                                                         |
| `NODE_ENV`                                       | No                       | `development`                                    | Runtime environment: `development`, `test`, or `production`                             |
| `JOURNAL_AI_LOG_LEVEL`                           | No                       | `info`                                           | Pino log level                                                                          |
| `JOURNAL_AI_MONGODB_URI`                         | Production               | `mongodb://localhost:27017` outside production   | MongoDB server used by the service and `migrate-mongo`                                  |
| `JOURNAL_AI_MONGODB_DATABASE`                    | Production               | `mentalbridge_journal_ai` outside production     | MongoDB database owned by this service                                                  |
| `JOURNAL_AI_MONGODB_CONNECTION_TIMEOUT_MS`       | No                       | `2000`                                           | MongoDB connect/server-selection timeout from 100 through 30000 milliseconds            |
| `JOURNAL_AI_ENCRYPTION_KEY`                      | Production               | Local-only deterministic development key         | Canonical base64 encoding of the 32-byte AES-256-GCM journal encryption key             |
| `JOURNAL_AI_IDEMPOTENCY_HMAC_KEY`                | Production               | Local-only deterministic development key         | Canonical base64 encoding of a separate 32-byte key for command hashes and fingerprints |
| `IDENTITY_JWT_ISSUER`                            | Yes                      | None                                             | Exact Identity issuer accepted by this resource service                                 |
| `IDENTITY_JWT_AUDIENCE`                          | Yes                      | None                                             | Exact MentalBridge API audience accepted by this resource service                       |
| `IDENTITY_JWT_KEY_ID`                            | Yes                      | None                                             | Exact active Identity signing-key identifier accepted by this resource service          |
| `IDENTITY_JWT_PUBLIC_KEY`                        | Yes                      | None                                             | X.509 RSA public key matching the Identity signing key; the private key is never shared |
| `JOURNAL_AI_CARE_BASE_URL`                       | Production               | `http://localhost:8081` outside production       | Care base URL for current AI-processing consent checks                                  |
| `JOURNAL_AI_CARE_TIMEOUT_MS`                     | No                       | `2000`                                           | Bounded Care consent REST timeout from 100 through 5000 milliseconds                    |
| `JOURNAL_AI_CONTENT_BASE_URL`                    | Production               | `http://localhost:3003` outside production       | Content base URL for validating and assembling selected resource context                |
| `JOURNAL_AI_CONTENT_TIMEOUT_MS`                  | No                       | `2000`                                           | Bounded Content resource/progress REST timeout from 100 through 5000 milliseconds       |
| `JOURNAL_AI_CONSULTATION_BASE_URL`               | Production               | `http://localhost:8082` outside production       | Consultation base URL for authoritative current entitlement lookup                      |
| `JOURNAL_AI_CONSULTATION_TIMEOUT_MS`             | No                       | `2000`                                           | Bounded Consultation entitlement REST timeout                                           |
| `JOURNAL_AI_REMINDER_SERVICE_TOKEN`              | Reminder scheduler       | None                                             | Dedicated 32+ character credential for Content's minimized activity projection          |
| `JOURNAL_AI_PROVIDER_MODE`                       | No                       | `DETERMINISTIC_FAKE`                             | Selects fake runtime or explicitly approved real routes; tests require fake             |
| `JOURNAL_AI_ROUTING_POLICY_VERSION`              | No                       | `exact-revision-routing-v1`                      | Version attached to every workload/package route                                        |
| `JOURNAL_AI_PROVIDER_APPROVAL_VERSION`           | Approved real only       | None                                             | Accepted benchmark approval identifier required before real routing                     |
| `JOURNAL_AI_FREE_PLUS_PROVIDER/MODEL`            | Approved real only       | None                                             | Pinned baseline route; provider is `GEMINI`, `OPENAI`, or `BEDROCK`                     |
| `JOURNAL_AI_PREMIUM_PROVIDER/MODEL`              | Approved real only       | None                                             | Pinned Premium route; it may be stronger but is never selected from client input        |
| `JOURNAL_AI_GEMINI_API_KEY`                      | Selected route/benchmark | None                                             | Gemini credential; never committed or logged                                            |
| `JOURNAL_AI_OPENAI_API_KEY`                      | Selected route/benchmark | None                                             | OpenAI credential; never committed or logged                                            |
| `JOURNAL_AI_BEDROCK_REGION`                      | Bedrock route/benchmark  | `ap-southeast-1`                                 | Region used to build the Bedrock Runtime Converse endpoint                              |
| `AWS_BEARER_TOKEN_BEDROCK`                       | Bedrock route/benchmark  | None                                             | Bedrock long-term API key used as a bearer token; never committed or logged             |
| `JOURNAL_AI_PROVIDER_TIMEOUT_MS`                 | No                       | `30000`                                          | Per-provider HTTP timeout                                                               |
| `JOURNAL_AI_BEDROCK_SCHEMA_WARMUP_TIMEOUT_MS`    | No                       | `300000`                                         | Bedrock-only timeout for the explicit cold structured-output compile command            |
| `JOURNAL_AI_BENCHMARK_ENABLED`                   | No                       | `false`                                          | Explicit paid-run gate; rejected in test/CI                                             |
| `JOURNAL_AI_BENCHMARK_DATASET_PATH`              | No                       | synthetic v1 dataset path                        | Version-controlled exact-revision benchmark input                                       |
| `JOURNAL_AI_BENCHMARK_*_MODEL`                   | Benchmark only           | None                                             | At least one complete pinned Gemini, OpenAI, or Bedrock candidate; no latest alias      |
| `JOURNAL_AI_*_COST_MICRO_USD_PER_MILLION_TOKENS` | Real route/benchmark     | None                                             | Explicit pricing snapshot used only for cost estimation                                 |
| `JOURNAL_AI_ANALYSIS_ENABLED`                    | No                       | `true` outside production; `false` in production | Enables the deterministic exact-revision backend runtime; production remains gated      |
| `JOURNAL_AI_ANALYSIS_POLL_INTERVAL_MS`           | No                       | `250`                                            | Interval for due/expired-lease job claims                                               |
| `JOURNAL_AI_ANALYSIS_LEASE_MS`                   | No                       | `35000`                                          | Claim lease, always longer than the fixed 30-second provider-attempt timeout            |
| `JOURNAL_AI_CHAT_RETENTION_DAYS`                 | No                       | `90`                                             | Conversation expiry and encrypted-message retention                                     |
| `JOURNAL_AI_CHAT_FREE_DAILY_ANSWERS`             | No                       | `5`                                              | Successfully delivered answers per local day for `FREE`                                 |
| `JOURNAL_AI_CHAT_PLUS_DAILY_ANSWERS`             | No                       | `30`                                             | Configurable successfully delivered answers per local day for `PLUS`                    |
| `JOURNAL_AI_CHAT_PREMIUM_FAIR_USE_DAILY_ANSWERS` | No                       | `200`                                            | Hidden backend fair-use ceiling for `PREMIUM`; never displayed as a daily quota         |
| `JOURNAL_AI_CHAT_RATE_LIMIT_PER_MINUTE`          | No                       | `10`                                             | Per-owner bounded request-rate control                                                  |
| `JOURNAL_AI_CHAT_DAILY_TOKEN_BUDGET`             | No                       | `100000`                                         | Per-owner provider token/cost guard across all plans                                    |
| `JOURNAL_AI_CHAT_DEFAULT_TIMEZONE`               | No                       | `Asia/Ho_Chi_Minh`                               | Server-authoritative IANA timezone used for stable quota reset                          |
| `JOURNAL_AI_CHAT_ROUTING_POLICY_VERSION`         | No                       | `companion-chat-routing-v1`                      | Version persisted with every assistant response                                         |

Local `.env` files are loaded only outside production and never override real
environment variables. Copy `.env.example` to `.env` for local development,
then replace placeholder JWT values with local credentials. Generate one
stable encryption key and one different HMAC key (for example, with
`crypto.randomBytes(32).toString("base64")`) and keep them unchanged while
encrypted demo data exists. Do not commit local `.env` files or secrets.

## Operations endpoints

| Method   | Path                                                              | Purpose                                                                            |
| -------- | ----------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `GET`    | `/health/live`                                                    | Process liveness probe                                                             |
| `GET`    | `/health/ready`                                                   | Readiness probe that returns success only when owned MongoDB responds              |
| `GET`    | `/metrics`                                                        | Prometheus metrics scrape endpoint                                                 |
| `POST`   | `/api/v1/journals`                                                | Create an encrypted journal entry with an idempotency key                          |
| `GET`    | `/api/v1/journals`                                                | List the authenticated owner's entries by opaque cursor                            |
| `GET`    | `/api/v1/journals/{journalId}`                                    | Read one owner-scoped entry                                                        |
| `PATCH`  | `/api/v1/journals/{journalId}`                                    | Append a revision guarded by `If-Match`                                            |
| `DELETE` | `/api/v1/journals/{journalId}`                                    | Create an idempotent owner-scoped tombstone                                        |
| `POST`   | `/api/v1/journals/{journalId}/revisions/{revision}/analysis-jobs` | Request one consented exact-revision analysis job                                  |
| `GET`    | `/api/v1/analysis-jobs/{jobId}`                                   | Read the owner-scoped job state and normalized result                              |
| `POST`   | `/api/v1/longitudinal-analysis-jobs`                              | Compare exact owned revisions across two bounded periods                           |
| `GET`    | `/api/v1/longitudinal-analysis-jobs/{jobId}`                      | Read the owner-scoped longitudinal job and safe evidence                           |
| `GET`    | `/internal/v1/users/{userId}/longitudinal-analyses/{analysisId}`  | Return minimized consented evidence for Care reassessment                          |
| `POST`   | `/api/v1/emotion-check-ins`                                       | Create the current local-day self-reported emotion check-in                        |
| `GET`    | `/api/v1/emotion-check-ins`                                       | List owner-scoped self-reported emotion history                                    |
| `GET`    | `/api/v1/emotion-check-ins/{localDate}`                           | Reload one owned local-day check-in                                                |
| `PATCH`  | `/api/v1/emotion-check-ins/{localDate}`                           | Optimistically update the current local-day check-in                               |
| `DELETE` | `/api/v1/emotion-check-ins/{localDate}`                           | Erase encrypted revisions and retain a bounded tombstone                           |
| `GET`    | `/api/v1/emotion-check-in-context`                                | Return note-free AI context after current Care consent                             |
| `GET`    | `/api/v1/notification-activity`                                   | Return independent note-free Journal/emotion activity for the authenticated owner  |
| `POST`   | `/internal/v1/notification-activity`                              | Return minimized owner activity to Content using the scoped reminder service token |
| `POST`   | `/api/v1/ai-companion/conversations`                              | Start an encrypted owner-scoped conversation                                       |
| `GET`    | `/api/v1/ai-companion/conversations`                              | List bounded retained conversation summaries without message bodies                |
| `GET`    | `/api/v1/ai-companion/conversations/{conversationId}`             | Resume one owned conversation                                                      |
| `PUT`    | `/api/v1/ai-companion/conversations/{conversationId}/context`     | Replace the owned conversation's persisted context-source selection                |
| `POST`   | `/api/v1/ai-companion/conversations/{conversationId}/messages`    | Deliver one quota-governed normalized assistant response                           |
| `POST`   | `/internal/v1/support-guide-phrasing`                             | Rephrase exact Care-approved guide copy after current AI consent                   |
| `DELETE` | `/api/v1/ai-companion/conversations/{conversationId}`             | Hard-delete the conversation and replay snapshots                                  |

Incoming requests echo a valid bounded `x-correlation-id` or receive a generated one. Request logs include the same correlation ID and redact authorization, cookie, and reminder service-token headers. Non-public application routes require an Identity-issued RS256 bearer token; signature, issuer, audience, lifetime, subject, token ID, and roles are validated before a principal is attached to the request. The internal notification activity projection instead requires the dedicated `JOURNAL_AI_REMINDER_SERVICE_TOKEN` and exposes only note-free factual activity.

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
   the exact-revision, longitudinal, Support Guide phrasing, and Companion Chat
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

Run `npm run contract:check` and `npm run migration:check` for static validation. `npm run test:integration` builds the service and runs the HTTP CRUD/concurrency suite against the explicitly configured disposable MongoDB database; it covers cursor tie-breakers/index use, exact mutation replay after more than 32 later commands, conflicting key reuse, owner isolation, tombstones, revision concurrency, and bounded dependency failure. The test refuses a non-disposable database name and drops its database in cleanup.

## Graceful shutdown

The service stops accepting new connections after receiving `SIGINT` or `SIGTERM`. Existing connections have up to 10 seconds to close before they are terminated.
