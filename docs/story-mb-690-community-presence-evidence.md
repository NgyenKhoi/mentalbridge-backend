# MB-690: Privacy-aware Community foreground presence

## Accepted scope and implementation slices

On 2026-10-21 the owner requested MB-690 implementation after reviewing its
presence brainstorm and confirming the separate message-lifecycle work.
The Jira Story and its DEVICE-1 boundary cover backend presence only; typing,
shared read receipts, screens, notifications and appointment chat are outside
this delivery. MB-745/746 track message retraction and per-user conversation
deletion separately, assigned to the owner-requested Jira account.

ADR 0042 and community-chat-policy-v1 remain authoritative. MB-121 was examined:
its appointment Redis service provides TTL routing but has different keys,
45/15-second defaults and no Community foreground/privacy semantics. This slice
reuses RedisService and established atomic lease patterns, while retaining
separate Community presence keys and the accepted 60/20-second policy.

The ordered review slices are (1) Community provider projection/OpenAPI and
PostgreSQL-backed permission tests; (2) Realtime heartbeat/leases/projection
delivery, socket schemas and real Redis/two-replica tests; (3) current source
maps, protocol/rollout guide and evidence. The owner subsequently authorized
commit, push and a pull request into `dev` for review.

## Delivered behavior

Community returns only current other peers whose opt-in, invisible setting,
account state, membership and bilateral blocks permit observation. Delegation
is scoped to the exact conversation and viewer; no client actor selector is
accepted. Private subject-routing references remain internal and are not grants.
Identity eligibility precedes local profile/conversation locking; privacy is
read again inside the consistent owner transaction.

Realtime tracks one foreground lease per authenticated socket in Redis, using
Redis server time and atomic prune/update/read. Leases expire after 60 seconds
or original Identity expiry, whichever is sooner. Another foreground device
keeps ONLINE after one device backgrounds or disconnects. The server's routing
maintenance does not renew foreground leases. A newer background intent defeats
an older foreground authorization read still in flight.

Subscribe and bounded refresh read current owner projections before and after
sampling Redis. Known revocation invalidates queued work; hidden subjects never
receive OFFLINE/UNKNOWN marker events. Generic invalidation clears badges
without disclosing the affected peer. Redis-only triggers fan out across replicas;
presence/heartbeats create no Kafka facts, database records or history timeline.
An owner outage fails closed; Redis uncertainty is UNKNOWN only for peers whose
current observation eligibility was independently verified.

The existing credential keys and opt-in feature flags are reused. No migration,
persisted domain field, canonical logical-model change or new configuration key
is needed. The accepted proposal artifacts and appointment protocol remain
unchanged. Public events contain only the Community profile ID and state.

## Acceptance states

| State / trigger | Observable result | Must not disclose |
| --- | --- | --- |
| Default opt-out or invisible | No presence row/event; prior badge cleared by generic invalidation | Hidden-subject ID, exact last-seen, OFFLINE/UNKNOWN marker for opt-out |
| Eligible peer, no foreground heartbeat | OFFLINE despite a live routing socket | Connectivity interpreted as availability |
| One or more unexpired foreground devices | ONLINE for currently allowed observers | Account routing ID, device count or lease timestamp |
| One of two devices backgrounds/disconnects | Other live device keeps ONLINE | False OFFLINE caused by removing another socket's lease |
| Last device backgrounds/disconnects/crashes | OFFLINE after current Redis evaluation/expiry reconciliation | Cached ONLINE from expired leases |
| Foreground read pending, newer background arrives | Background wins; no restored foreground lease | A stale ONLINE transition from the old command |
| Opt-out/block/removal/restriction | Pending projections discarded; generic conversation invalidation | Blocked-person identity or hidden traffic |
| Invalidation lost | Fresh snapshots repair eligible badges within the bounded check cycle | Reused permission from the old snapshot |
| Redis failure | UNKNOWN only for freshly authorized peers, or clear on transport loss | Cached ONLINE or hidden subjects |
| Owner uncertainty or session expiry | Socket/view cleared; fresh reconnect required | Cached authorization, prolonged Identity expiry |

Clients clear badges on disconnect/reconnect/invalidation. Periodic authorization
repair runs at most five seconds apart while dependencies respond; expiry checks
are scheduled sooner, with a 500ms timer and existing HTTP deadlines. No instant
distributed revocation, throughput SLA or willingness-to-talk claim is made.

## Verification

Tests use synthetic data, disposable PostgreSQL/Kafka/Redis/MongoDB, Java 21,
`SPRINGDOTENV_ENABLED=false` and Node 24.19 for runtime verification. Developer
`.env` and shared durable databases are not used. `$node24` below is the installed
Node 24.19 executable. Commands run from the owning module unless stated otherwise.

| Command | Result |
| --- | --- |
| `mvn -q -Dtest=CommunityChatPresenceIntegrationTests,CommunityChatContractTests test` | Exit 0; initial focused provider/contract baseline, 8 tests |
| `mvn -q verify` | Full Community run exercised 189 tests. Initially failed in the newly added Mockito race fixture and canonical route availability inventory; neither failure required a production-code change |
| `mvn -q -Dtest=CommunityChatPresenceIntegrationTests,CommunityOpenApiContractTests verify` | Exit 0 after correcting fixture stubbing and registering the new route in both expected inventories; 12 tests. Current complete Surefire reports contain 189 tests across 34 suites, zero failures/errors/skips. Unaffected suites were not rerun after these test-only corrections |
| `$node24 node_modules/vitest/vitest.mjs run --configLoader bundle --maxWorkers 2` | Exit 0; 121 unit/HTTP/contract tests |
| `$node24 node_modules/vitest/vitest.mjs run --config vitest.integration.config.ts --configLoader bundle` | Exit 0; 39 integration tests: two real Community replicas/Kafka/Redis, standalone Redis leases, and unchanged appointment MongoDB/Redis/Socket.IO regression |
| `$node24 node_modules/prettier/bin/prettier.cjs --check .` | Exit 0; Windows checkout CRLF normalized to repository-required LF for verification, with no unrelated content diff |
| `$node24 node_modules/eslint/bin/eslint.js .` | Exit 0 |
| `$node24 node_modules/typescript/bin/tsc -p tsconfig.json --noEmit` | Exit 0 |
| `$node24 scripts/validate-contracts.mjs` | Exit 0; active Community and appointment schemas |
| `$node24 scripts/validate-migrations.mjs` | Exit 0; Realtime MongoDB migration baseline unchanged |
| `$node24 node_modules/typescript/bin/tsc -p tsconfig.build.json` | Exit 0 |
| `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-repository.ps1 -BaseSha origin/dev` | Exit 0 at backend root; architecture/paired-change policy |
| `git diff --check` | Exit 0 |

Full affected-module verification was warranted by the new Community provider
route/security boundary and Realtime gateway/module/fanout wiring. Other backend
modules and frontend gates remain for their normal CI lanes. No browser E2E was
run for this backend-only change.

Before publication, `origin/dev` was fetched again and the branch base remained
`cb6609d0`, with no commits behind the intended base.
Unchanged tracked files had their original Windows CRLF restored after the LF
format check; Git status contains only the 30 story paths and the pre-existing
untracked resource draft, which was not read or modified.

Initial sandbox attempts could not resolve Maven Central or access the Docker
runtime; authorized retries used disposable infrastructure successfully. Locked
Node dependencies were installed before final verification. The system npm
launcher used Node 22 and reported engine warnings during installation; every
runtime test/static/build command above explicitly used Node 24.19. The upstream
KafkaJS `TimeoutNegativeWarning` seen in MB-689 remains visible during broker
restart; it was not suppressed, and all final assertions passed.

Authored tests are `CommunityChatPresenceIntegrationTests.java`,
`test/unit/community-presence.test.ts`, and
`test/integration/community-presence.integration.test.ts`. The existing
Community two-replica suite now covers foreground/background aggregation,
crash/expiry/reconnect, hidden subjects, lost invalidation, and an older
foreground command racing a newer background command. Provider and canonical
contract inventories and the socket runtime contract assertions were updated
with the route/variants they certify.

## Rollout and consumer handoff

Deploy compatible Community provider before enabling the consuming Realtime
build. Existing feature flags default false; no shared environment was enabled.
Clients send the versioned foreground heartbeat every 20 seconds and immediate
background clearing, respect the ten-second foreground renewal limit, consume
only server-owned state, and acquire fresh credentials/snapshots on reconnect.
See [Community integration guide](../realtime-service/COMMUNITY.md).

This is backend/service integration evidence. A signed contract-validating HTTP
fixture supplies the Node lane; Spring/PostgreSQL checks the real owner provider.
Separate lanes do not claim live cross-stack browser evidence or `ui-ready`.
The pull request is a review handoff; no Jira Done transition or shared-environment
activation is part of this delivery. Required CI results are recorded on the PR.
