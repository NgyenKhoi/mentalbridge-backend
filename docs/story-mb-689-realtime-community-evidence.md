# MB-689: Community realtime delivery

## Brainstorm and implementation contract

The owner requested MB-689 implementation on 2026-10-21 after MB-685 and MB-687
merged. ADR 0042 and community-chat-policy-v1 remain authoritative. Community
owns durable messages, authorization and private read state; Realtime owns only
the `/community` edge and ephemeral routing. No appointment behavior changes.

Alternatives considered: SSE duplicates an already accepted Socket.IO transport;
writing messages in Realtime creates a second authority; broadcasting raw content
through Redis exposes private text. The chosen design consumes minimized Kafka
facts, distributes only those facts through Redis, and obtains each viewer's
exact message projection immediately before sending it.

An owner-encrypted, viewer/visibility/membership-bound delivery cursor supplies
ordered message IDs. It starts at the current tail on subscribe. A client always
repairs its view with the existing ascending, cursor-paged REST history after
subscribe/reconnect and merges by messageId. Live delivery never promises exactly
once receipt. Absolute owner read state replaces client-side unread increments.
No private global sequence is exposed to clients. No new durable table is needed.

The BFF signs a maximum-30-second, audience/scope-bound credential delegation,
including the expiry of the verified Identity session. A one-use opaque ticket
is isolated from appointment tickets. Realtime signs separate, target-bound
owner delegations. Trusted keys are deployment configuration, never browser input.

## Acceptance states

| State / trigger | Observable result | Owner / evidence |
| --- | --- | --- |
| Active USER + valid one-use ticket | `/community` connects until Identity expiry | Realtime credential and socket tests |
| Wrong namespace, signature, audience, role or expired identity | Connection denied; no routing identity leaked | Delegation and credential tests |
| Subscribe to eligible direct/group conversation | ACK after owner authorization; resync hint and absolute read state | Owner provider and gateway tests |
| Persisted message fact | Ordered IDs resolved at owner, exact projection reauthorized per device | Provider and two-node integration tests |
| Duplicate/reordered fact or REST retry | No phantom message or additive unread | Cursor and retry tests |
| Reconnect / offline | Fresh authorization, REST history repair, messageId dedupe | Reconnect integration tests |
| Block, leave, removal, restriction | Current owner denial; queued content discarded; subscription invalidated | Privacy integration tests |
| Hidden shared-group traffic | No message, unread update or resync caused by the hidden message | Owner visibility and fanout tests |
| Read on a second device | Same owner watermark and absolute unread on every subscribed device | Read-state fact tests |
| Owner/Redis/Kafka failure or bounded queue overflow | No stale content fallback; disconnect/resubscribe or REST repair | Failure integration tests |

## Ordered review slices

1. `feat(community): expose current realtime projections`: additive OpenAPI,
   signed owner delegation, current exact-message and ordered-ID/read-state
   projections, transactional self read-state facts, provider/privacy tests.
2. `feat(realtime): deliver authorized community messages`: namespace-isolated
   credentials, bounded subscriptions, Kafka-to-Redis minimized fanout, ordered
   per-device delivery, expiry/revocation/backpressure, contract/unit/integration
   tests, configuration and module documentation.
3. `docs(realtime): record community delivery evidence`: actual verification,
   release/consumer handoff, limitations and operational recovery.

The owner authorized commit, push and PR publication for review on 2026-10-21.
MB-690 owns online/typing/presence and MB-691 owns notification delivery.

## Delivered behavior and compatibility

The provider adds current signed session/subscribe/exact-message/ordered-reference
projections. Its Identity calls precede local locking; blocks, restrictions,
membership and visibility epochs are checked again with the owner read. Public
read cursors remain monotonic and emit a minimized self-routing fact only when
the watermark advances. No table, field, migration or logical-model change is
needed; the outbox field dictionary now describes that technical fact.

Realtime adds namespace-isolated one-use tickets from trusted BFF delegation,
cross-replica routing leases and minimized Kafka-to-Redis fanout. Message IDs are
resolved in owner order, and each content projection is reauthorized immediately
before its device emission. Known revocation cancels queued work. Absolute owner
read state replaces additive unread. Expiry, connection/subscription limits,
bounded queues, owner deadlines/circuit and transport loss fail closed.

The accepted proposal artifacts and appointment WebSocket contracts are unchanged.
Active schemas add only the delivered Community variants and a private read-state
fact; prior fact variants remain byte-equivalent. Canonical Community OpenAPI
includes the four internal operations. Realtime readiness adds an optional
Community transport status when enabled. Existing notification ownership and
appointment storage/eligibility/completion stay separate.

## Verification on 2026-10-21

`$node24` below denotes the installed Node.js 24.19 executable. Commands were run
from the owning module, or the backend root for Maven/Compose. Java used JDK 21
with `SPRINGDOTENV_ENABLED=false`. All infrastructure was disposable; neither the
developer `.env` nor shared durable data was used.

| Exact CLI / gate | Result |
| --- | --- |
| `mvn -q -f community-service/pom.xml verify` | Exit 0; 182 tests, zero failures/errors/skips; real PostgreSQL migrations, provider/security/contract/privacy, direct/group and Kafka relay coverage |
| `& $node24 node_modules/vitest/vitest.mjs run --configLoader bundle --maxWorkers 2` | Exit 0; 115 unit/HTTP/contract tests |
| `& $node24 node_modules/vitest/vitest.mjs run --configLoader bundle --maxWorkers 2 test/contract` | Exit 0; 72 contract tests after canonical API registration, including one additional boundary assertion; its four-test file passed again after type-safe assertions, with typecheck/format/lint passing |
| `mvn -q -f community-service/pom.xml -Dtest=CommunityChatContractTests,CommunityOpenApiContractTests test` | Exit 0 after final canonical delegation-scheme registration; initial sandbox attempt could not access Maven Central (`Permission denied: getsockopt`), authorized retry used the established dependency cache successfully |
| `& $node24 node_modules/vitest/vitest.mjs run --config vitest.integration.config.ts --configLoader bundle test/integration/community.integration.test.ts` | Exit 0; 10 Community integration tests on two real Socket.IO replicas, Kafka and Redis |
| `& $node24 node_modules/vitest/vitest.mjs run --config vitest.integration.config.ts --configLoader bundle` | Exit 0; final complete integration gate, 28 tests across both Community and unchanged appointment chat |
| `& $node24 node_modules/prettier/bin/prettier.cjs --check .` | Exit 0 after normalizing Windows checkout CRLF to LF for verification |
| `& $node24 node_modules/eslint/bin/eslint.js .` | Exit 0 |
| `& $node24 node_modules/typescript/bin/tsc -p tsconfig.json --noEmit` | Exit 0 |
| `& $node24 scripts/validate-contracts.mjs` | Exit 0; existing and Community runtime OpenAPI/socket schemas |
| `& $node24 scripts/validate-migrations.mjs` | Exit 0; existing Realtime MongoDB migration baseline unchanged |
| `& $node24 node_modules/typescript/bin/tsc -p tsconfig.build.json` | Exit 0 |
| `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-compose-service-boundaries.ps1` | Exit 0; synthetic values prove private Realtime key and BFF verification key reach only Realtime, owner verification key only Community |
| `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-repository.ps1 -BaseSha origin/dev` | Exit 0 after making the existing paired-change check namespace-specific; positive and missing/wrong-namespace fixtures confirm Community and appointment schemas cannot substitute for one another |
| `git diff --check` | Exit 0 |

The integration matrix covers duplicate/reordered facts, per-device ordering,
blocked group traffic without resync leakage, queued projection revocation,
cross-device reads, offline reconnect, namespace isolation, ticket reuse and
Identity expiry, five concurrent devices across two replicas, a 20-message
ordered catch-up, poison dead-letter privacy, a real broker restart, and owner /
Redis failure. Java additionally verifies signed audience/scope/target/expiry,
unknown actor selectors, outsider/current-account rejection, direct blocks,
leave/rejoin epochs, 50+5 reference pagination, and idempotent self-read facts.

Final coverage: 326 passing tests across the two affected modules, zero skipped
(325 in the full module gates plus the added canonical API registration test).
The full Community and Realtime gates were warranted by the new service security
chain, optional application module/readiness wiring, and the independent
appointment regression boundary. Unrelated backend/frontend suites remain for CI.

This is bounded acceptance/load evidence, not a throughput SLA. The Node suite
uses a contract-validating signed HTTP owner fixture; the real Spring provider,
PostgreSQL transactions and public REST history are tested in the Java lane.
No cross-stack browser/UI walkthrough is claimed.

The restart fixture pins the host port after KafkaContainer's setup hook. Docker
Desktop otherwise reallocates an ephemeral host port on restart while Kafka's
saved advertised listener still points to the old port; a minimal TCP probe
proved this before the test was corrected. Recovery uses one service-owned
five-second loop with bounded Kafka attempts, avoiding a competing SDK restart.
Node 24 reports an upstream KafkaJS 2.2.4 `TimeoutNegativeWarning` from its request
queue timer; it is not suppressed, and all final assertions/gates still pass.

## Consumer and release handoff

See [Realtime Community guide](../realtime-service/COMMUNITY.md) for exact BFF
claims, independent key provisioning, flags, socket commands/events and reconnect
handling. Frontend/BFF delivery belongs to MB-686/688; MB-690 retains presence,
typing and shared receipt projections, and MB-691 retains notifications.
Every reconnect needs a newly issued ticket and fresh authorized REST repair.
Clients merge by messageId, preserve server order, replace unread with the owner
result and discard an invalidated conversation view.

Both new feature flags default false. Deployment/key provisioning and activation
were not performed. Jira transitions, assignee changes and a `ui-ready` handoff
remain outside this delivery. The review PR records remote CI results separately
from the local evidence above. The backend root is on synchronized `dev`;
implementation is isolated in `feat/realtime-community-chat-(MB-689)` at the
MB-687 merge base `f7530a3f`.
