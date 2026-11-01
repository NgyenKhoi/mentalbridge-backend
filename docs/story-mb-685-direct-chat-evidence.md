# MB-685 direct Community chat delivery

Owner: Community backend (Device 1). Prerequisite ADR 0042 and its MB-684
acceptance/permission tables were approved and squash-merged by PR #206 at
`318248c732008512d110935b9576516f9d4ca084`. This implements the direct-chat
provider slice; MB-686 owns clients, MB-687 groups, MB-689/690 socket/presence
and MB-691 notification consumption. No UI-ready or live-provider claim.

## Commit slices and verification

1. Identity current-account eligibility provider: dedicated bounded internal
   read; no account metadata. Provider contract + PostgreSQL tests (5 passed).
2. Community privacy/direct requests: owner migrations/model/dictionary,
   explicit preference revisions, unique pairs, suppression, current eligibility,
   transactional blocks, opaque cursors and request facts. Focused Community
   PostgreSQL/HTTP, consumer failure and contract tests.
3. Durable text messages/history/read acknowledgement: encryption/retention,
   idempotency, private sequence/unread, minimized outbox, real concurrency,
   rollback and contract tests.
4. Kafka relay adapter: bounded owner outbox replay, topic provisioning and real
   Kafka outage/recovery evidence. Final affected module checks and exact-head CI.

## Acceptance states before provider implementation

The accepted MB-684 tables remain authoritative. This bounded table binds this
story's provider behavior without introducing a UI design or new product rule.

| State | Authoritative provider result | Must not expose |
| --- | --- | --- |
| Stranger/default | One text-free pending request; no ordinary send/history | Text preview, account metadata, presence |
| ANYONE | Reuse/create the pair's one accepted conversation | Duplicate pair, implicit unblock |
| Incoming request | Recipient may accept/decline/block with exact request revision | Another recipient's request or transcript |
| Declined/blocked | Original requester remains suppressed across new keys/preferences/unblock | Reason, block state or repeated solicitation |
| Accepted + changed privacy | Existing conversation remains usable | Automatic conversion of a pending request |
| Blocked/restricted/inactive/outsider | Uniform unavailable; dependency uncertainty is 503 | Old transcript, inferred target existence, stale positive grants |
| Send committed | SAVED + delivery UNCONFIRMED; same keys reconcile one message | Delivery/read/online claim, raw text in facts/logs |
| History/read acknowledgement | Ordered eligible page and opaque message-ID acknowledgement | Global sequence, another actor's cursor/unread |

## Acceptance and regression evidence

| Requirement | Implementation and automated evidence |
| --- | --- |
| Both policies, no implicit acceptance | `CommunityDirectChatIntegrationTests`: default APPROVAL_REQUIRED, ANYONE unique pair, crossed pending requests, explicit recipient decision, preference changes preserve accepted relationships. |
| Block/suspension and isolation | Same suite plus `CommunityChatMessageIntegrationTests`: wrong recipient/outsider/role, local restriction, fresh Identity suspension/outage, bilateral block, current history/unread/retry checks and block winning a waiting send. |
| No repeated solicitation | Same direct suite: one pending request, five new requests/day, seven-day expiry, permanent original-requester decline/block suppression across fresh keys, preferences and unblock; reverse explicit initiation stays separate. |
| Durable save and retry | Message suite: concurrent same-command retries commit one encrypted message, sequence, unread increment and logical facts; changed content/client/target conflicts; a forced outbox constraint failure rolls back message, command and sequence. |
| Bounded history and read state | Message suite: viewer/conversation/epoch-bound opaque cursor, private snapshot ceiling, expired-gap filtering, current eligible conversation ordering, exact readable message-ID acknowledgement and monotonic read max across reordered devices. No public sequence/gap counter. |
| Privacy and retention | Message/crypto suites: no raw content in database ciphertext/facts/captured diagnostics, purpose-bound AES-GCM and key rotation, logical 90-day expiry plus bounded ciphertext purge; tombstone retries cannot resurrect content. |
| Current dependency authority | `ChatAccountAuthorizerTests`, `ChatIdentityHttpConsumerTests` and Identity provider suite: current ACTIVE verified USER subset only, no positive cache, HTTP outside DB transaction, strict result identity binding, malformed/4xx/5xx/truncated/timeout fail closed without retry. |
| Contract integration | `CommunityChatContractTests`, `CommunityOpenApiContractTests`, `ChatWireContracts`: active ten-operation subset matches the accepted baseline; exercised public responses and actual persisted/published facts satisfy closed schemas. Existing appointment/socket schemas are unchanged. |
| Kafka outage/replay | `ChatKafkaRelayIntegrationTests`: real three-partition topic/conversation key, ACK-before-mark, crash before send/after ACK, stale lease worker, paused broker and recovery with stable event identity. At-least-once duplication is expected; no delivery claim. |
| Safe configuration | `ChatRelayPropertiesTests`, authorizer/crypto tests and Compose boundary script: bounded deadlines/batches/keyring, redacted diagnostics, eligibility credential only Identity/Community, encryption keys only Community. Flags default off. |

Concurrent preference changes lock affected conversation rows in UUID order
before allocating private authorization-event revisions. Profile locks alone
would not serialize two different members changing their own preferences.
Message/command/fact writes share one owner transaction. Identity checks precede
that transaction; this is a fresh owner read, not a distributed atomic account
revocation guarantee.

## Local verification — 2026-10-20

Windows, Java 21, Docker Desktop; synthetic JWT/key/credential fixtures and real
disposable PostgreSQL/Kafka. `SPRINGDOTENV_ENABLED=false` for every Maven run.
No shared dev/staging data plane, paid provider, frontend, or personal data used.

| Command (from module unless stated) | Result |
| --- | --- |
| Community `./mvnw.cmd -B verify` | 142 passed; 0 failed/errors/skipped; executable JAR built. Includes 28 direct/message PostgreSQL regressions and real Kafka tests. |
| Identity `./mvnw.cmd -B verify` | 159 passed; 0 failed/errors/skipped; executable JAR built. |
| Identity `./mvnw.cmd -B -Dtest=CommunityChatAccountAuthorizationIntegrationTests,IdentityOpenApiContractTests verify` | 5 passed and JAR rebuilt after final diagnostic redaction. |
| Community `./mvnw.cmd -Dtest=ChatKafkaRelayIntegrationTests,ChatRelayPropertiesTests test` | 4 passed against real PostgreSQL/Kafka. |
| Root `powershell -File scripts/verify-repository.ps1 -BaseSha origin/dev` | Repository architecture and paired-change policy passed. |
| Root `docker compose --env-file .env.compose.example --profile demo --profile full-test config --quiet` | Passed; configuration validation only, no deployment. |
| Root `powershell -File scripts/verify-compose-service-boundaries.ps1` | Passed, including exact JSON keyring interpolation and chat credential isolation. |

The first full Community run exposed the existing exact migration inventory
still ending at 0016. It was extended to require 0017/0018 and all seven chat
tables; the full rerun passed. Earlier focused fixture failures (Mockito
re-stubbing, raw surrogate JSON and JDBC Instant binding) were corrected in the
tests. OpenAPI external path braces were URI-encoded and parameter shapes
resolved without relaxing validator checks. The Kafka outage test originally
assumed one broker record after an ambiguous ACK; it now checks the accepted
at-least-once stable identity and unchanged owner effects.

Required exact-head CI status/run link is recorded in the PR after push. Local
results alone do not authorize merge or mark Jira Done. No UI screenshot applies
to this backend-only slice.

## Device-2 MB-686 handoff

Use the active [`community-chat-v1.json`](../contracts/openapi/community-chat-v1.json),
not planned group/internal operations from the frozen proposal. All ten public
operations are below `/api/v1/community/chat`: GET/PUT privacy, POST
direct-conversations, GET requests, POST requests/{requestId}/decision, GET
conversations and conversations/{conversationId}, GET/POST
conversations/{conversationId}/messages, PUT conversations/{conversationId}/read-cursor.
Only the authenticated current USER supplies ownership; no browser owner selector.

Synthetic examples (UUID values are fixture references, not real accounts):

```json
{"recipientCommunityProfileId":"11111111-1111-4111-8111-111111111111"}
```

POST direct-conversations uses a stable UUID `Idempotency-Key`. A 201 result is
exactly `{"state":"PENDING","requestId":"22222222-2222-4222-8222-222222222222"}`
or `{"state":"ACTIVE","conversationId":"33333333-3333-4333-8333-333333333333"}`.
PENDING never grants history/send. Recipient decisions use the quoted request
version in `If-Match` and `{"decision":"ACCEPT"}`, `DECLINE` or `BLOCK`;
accept returns ACTIVE, decline/block return 204.

```json
{"clientMessageId":"44444444-4444-4444-8444-444444444444","content":"Nội dung tổng hợp dùng cho kiểm thử."}
```

Send also keeps the same UUID `Idempotency-Key` through an uncertain response.
A 201 contains `message`, `persistence:"SAVED"`, `delivery:"UNCONFIRMED"`.
History uses `{items,nextCursor}` (limit 1–50); do not decode opaque cursors.
PUT read-cursor sends only `{"lastReadMessageId":"55555555-5555-4555-8555-555555555555"}`.
Use returned unread state; do not infer receipt/online status. Problems are
closed `{type,title,status,code,correlationId}`; unavailable dependencies return
503, inaccessible targets are uniform 404, conflicts 409, throttling 429 with
Retry-After. Keep explicit pending/error/retry states and dedupe message IDs.

## Release and remaining epic work

Apply additive Liquibase 0017/0018, deploy Identity's dedicated provider, install
externally managed versioned keys/service credential, provision
`mentalbridge.community.chat.v1`, then opt in API and relay flags. Broker outages
leave durable facts pending. Rollback disables flags and retains durable rows
and required key versions; canonical documentation SQL never provisions storage.
Operational details and complete key set are in the Community README.

MB-686 owns clients; MB-687 owns groups; MB-689/690 own delegated current
authorization/projection endpoints and realtime/presence integration; MB-691
owns notification reauthorization, materialization and deduplication. Consumers
must recheck exact current recipients and never use a historical fact as a
permission grant. Moderation case/evidence, account-deletion orchestration and
epic-wide end-to-end acceptance remain their approved downstream work. No
friend/discovery feature, group capability, clinical inference, provider delivery
or production deployment is claimed here. Existing appointment chat is unchanged.
