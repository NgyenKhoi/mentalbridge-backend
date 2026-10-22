# MB-693 Community chat safety QA

## Brainstorm and delivery boundary

The use case is to prove that a Community user's current permission wins over
queued work, another device's old state, retries and notification delivery.
This is QA of the frozen contracts, not a new moderation or chat capability.

Owner decision on 2026-10-22: start independent MB-693 work while MB-688 web
groups is implemented on another device. Read frontend PR #215 as reference;
do not rewrite its feature files. Finish its dependent browser cases after it
merges. Real Android execution remains in the MB-666 staging batch.

Baseline: backend `d53fc722187f9f03c802c6b8cbe0b9fae79725ba`, frontend
`7be39d3adc0402152c989dde34ae3847b8bb4e6a`. Jira UC/QA owner: Thao Nguyen.
Community owns permissions, history and isolated report intake; Realtime owns
transport/presence; Content/Notification owns inbox delivery and preferences.

Sources of truth:

- [ADR 0042](adr/0042-community-chat-contract.md),
  [ADR 0043](adr/0043-native-community-credential-exchange.md).
- [Community chat policy](policies/community-chat-policy-v1.md).
- [Community API](../contracts/openapi/community-service-v1.yaml), owner Liquibase
  migrations 0017–0021 and versioned Community chat event/socket contracts.
- [MB-691 evidence](story-mb-691-community-notification-evidence.md),
  [MB-689 evidence](story-mb-689-realtime-community-evidence.md),
  [MB-690 evidence](story-mb-690-community-presence-evidence.md).

## Permission and acceptance states

| State / actor | What the client may show/do | Must not show/do | Rule / automated evidence |
| --- | --- | --- | --- |
| Stranger / pending direct request | Minimal request and decision controls | Transcript, preview, presence or message send before acceptance | Policy: Direct messaging; `CommunityChatMessageIntegrationTests`, `CommunityChatNotificationIntegrationTests` |
| Accepted eligible direct peers | Authorized REST history, send, private unread | Identity subject/email, clinical records, global sequence; SAVED does not mean read | Policy: Messages and history; `CommunityChatMessageIntegrationTests` |
| Blocked pair (either direction) | Generic unavailable state; other eligible group traffic remains | Queued content, typing/presence, receipts or new notices from blocked actor | Policy: Blocks / Realtime; owner projection tests plus MB-693 cross-service test |
| Pending private-group invitee | Exact invitation and consent controls | Group transcript/presence or membership before acceptance | Policy: Private groups; `CommunityGroupLifecycleIntegrationTests` |
| Current group member | Current membership-epoch history and role-permitted actions | Prior-epoch messages; hidden traffic counts; owner/admin-only mutations | Policy: Private groups; group lifecycle/message tests |
| Removed / left member, then rejoined | New-epoch eligible history only | Old cursor, queued old message notification, previous membership's content | Policy: Group history / Realtime; owner projection tests plus MB-693 cross-service test |
| Suspended / ineligible account | Uniform unavailable result | Reading, sending, socket projection or notice delivery via cached grant | Current Identity eligibility; owner projection/notification tests |
| Identity / Community authorization unavailable | Retryable failure; durable queued work retained | Cached text or authorization; materialized notice while uncertain | Fail closed 503; cross-service recovery test |
| Quiet hours / recipient opt-out | Existing inbox readable; new notice delayed or cancelled | New inbox publication during quiet hours; delivery after permission loss | Content-owned preference; MB-691 tests plus MB-693 deferred revocation test |
| Reporter with current message access | Idempotent report of one exact message | Surrounding transcript or unrelated message evidence | `CommunityChatReportIntegrationTests`; isolated encrypted evidence |
| ADMIN / specialist / outsider | Only separately authorized public capabilities | Arbitrary chat browse/join/presence or transcript access through report intake | USER-only chat; no general chat moderation browse API in this baseline |
| Multiple devices / reconnect | Another foreground lease keeps presence; fresh authorization/history repair | Duplicated durable message, stale badge, raw bearer/ticket persistence | MB-689/690 integration tests; true browser/device pairing remains pending |

## Ordered slices and smallest checks

1. Record this permission matrix, dependency partition and evidence rules.
   Validate links/content and inspect the diff.
2. Add a separately runnable Community → Kafka → Content/Notification → signed
   Community authorization → inbox integration test. Use real isolated PostgreSQL
   and Kafka, real Content process, current owner REST decisions. Cover delayed
   delivery after block/removal/rejoin, authorization outage/recovery, duplicate
   facts, generic inbox data and safe process logs. Identity eligibility is an
   explicit test double; this is service integration, not full-stack/device E2E.
   Check: Content build, Java test compilation and focused cross-service IT.
3. Reuse the existing owner/service regression suites for report evidence, spam
   budgets, concurrent seat/owner changes, signed projection and multi-device
   presence. Record actual commands/results; do not duplicate tests merely to
   increase volume.
4. After MB-688 merges: synchronize dev; author/execute web direct + group
   acceptance cases and notification navigation on the exact frontend/backend
   pair. Include two authenticated contexts, revoked access, reconnect and retry.
   Distinguish fixture browser tests from a real service walkthrough.
5. At MB-666: run the authored native journeys on the approved APK/real edge,
   capture redacted evidence and complete the web/mobile acceptance rows.

## Evidence ledger

`LOCAL_SERVICE_PASS` proves the named disposable service boundary only.
`TEST_AUTHORED_NOT_RUN`, `BLOCKED_PROVIDER`, `DEFERRED`, `STAGING_PASS` and
`STAGING_FAIL` follow the MB-666 execution vocabulary where applicable.

| Boundary | Current status | Evidence / next action |
| --- | --- | --- |
| Permission matrix / frozen scope | AUTHORED | This document; executable evidence below must be recorded after execution |
| Real Community → notification boundary | LOCAL_SERVICE_PASS | `CommunityChatNotificationCrossServiceIT`: 3 passed, no skipped; see execution below |
| Realtime / spam / report / race regression | LOCAL_SERVICE_PASS | Community gate 200 passed (109 chat tests); Realtime Community/presence integration 22 passed; see limits below |
| Web direct two-context integration | NOT_RUN | Existing fixture Playwright is not live cross-stack proof |
| Web group integration | BLOCKED_PROVIDER | MB-688 frontend PR #215 is owned on the other device; wait for merged dev |
| Native Android Community journeys | TEST_AUTHORED_NOT_RUN | MB-692 Maestro source exists; real execution belongs to MB-666 |
| Actual PUSH delivery | DEFERRED | MB-675/679; MB-691 only provides generic payload contract |
| Admin report-case administration | OUT_OF_SCOPE | Current provider is exact-message intake; do not invent admin transcript APIs |

Only synthetic accounts/messages may be used. Keep tokens, private keys, raw
content, report details and clinical/Identity data out of committed evidence.
Record SHA pair, command, result and limitations with each execution. No E2EE,
continuous monitoring or professional emergency-response claim. MB-693 stays
In Progress until its remaining acceptance evidence is actually executed.

## Reproducible service execution

Prerequisites: Java 21, Maven wrapper, Node 24 and a running Docker engine.
From `content-notification-service`, run `npm ci` then `npm run build`.
From `community-service`, with `SPRINGDOTENV_ENABLED=false`, run:

```sh
./mvnw -B -Dtest=CommunityChatNotificationCrossServiceIT test
```

Windows uses `mvnw.cmd`. If Node 24 is not the `node` on PATH, pass
`-Dmb693.node=<absolute-node-executable>`. The test fails explicitly if the
sibling Content build is absent. It is an opt-in `IT`, following the existing
cross-service recovery test convention; the normal Java CI `test` command
compiles it but does not execute it. Run the explicit command above for this
boundary. No workflow or shared deployment file is changed here.

The harness provisions **two disposable PostgreSQL 17 databases and one Kafka
3.9.1 broker**, migrates both owners through their executable migrations, starts
Community on a random real HTTP port and launches the compiled Content process
on another random port. Setup/mutations use Community MockMvc; delivery uses
the actual relay, Kafka consumer, worker, signed owner REST client and public
authenticated notification inbox. Test SQL seeds Content preferences and
observes its durable jobs; production services do not read each other's stores.
Identity eligibility is mocked, and inbox JWTs use an ephemeral synthetic key.
This proves neither production Identity login nor a browser/native journey.

| Automated case | Observable assertion |
| --- | --- |
| `quietHoursRecheckBlockBeforePublishingANewInboxNotice` | Real owner authorizes a message but quiet hours keep the inbox empty; a committed bilateral block then causes terminal cancellation before publication |
| `oldMembershipCannotDeliverAfterRejoinAndRecoveryReplaysOnlyOneNewNotice` | An old-epoch queued notice stays cancelled after rejoin; a new-epoch notice survives an Identity 503 as PENDING, then publishes exactly once after recovery; a new envelope ID for the same logical fact commits its Kafka offset without duplicating the inbox item |
| `resolvedRequestsAndInvitationsCannotBecomeStaleNoticesOnConsumerRecovery` | Facts committed while the consumer is absent cannot create a stale request/invitation notice after decline/acceptance |

The public inbox assertion checks generic Vietnamese copy and its closed action,
no sender profile/Identity routing reference or raw canary body, no self-event,
and no outsider inbox delivery. The relay facts are contract-validated and contain
no canary content. Content uses INFO logging; captured HTTP output must contain
the redaction marker and must not contain the canary, bearer, private-key
material or private routing field. This checks the exercised paths;
it is not a claim about every operational log or external provider.

Execution on 2026-10-22, Windows, Java 21 and Node 24: Content build and Java test
compilation exited 0; the explicit cross-service command passed **3 tests, zero
failures/errors/skips**. The first run failed only during Windows log-file
cleanup (3 errors); stdout capture replaced the locked redirected file, and
the rerun including cleanup passed. Mockito's agent warning and KafkaJS's
upstream timer warning remain visible; no assertions were suppressed.

| Command / working directory | Result | Scope / limits |
| --- | --- | --- |
| `npm ci --ignore-scripts --no-audit --no-fund`, `npm run build` / Content | Exit 0 | Locked dependencies and real compiled consumer for the cross-service test; no Content source change |
| `./mvnw -B -q -DskipTests test-compile` / Community | Exit 0 | New harness and existing support compile |
| `./mvnw -B -q -Dtest=CommunityChatNotificationCrossServiceIT test` / Community | 3 passed, 0 skipped | Actual owner/consumer boundary described above; final run includes INFO logging/redaction and quiet-hours-vs-retry assertions |
| `./mvnw -B -q clean verify` / Community | 200 passed, 36 suites, 0 failures/errors/skips; JAR built | One full affected-module gate; includes 109 chat tests in 15 suites covering permissions, budgets, report isolation, concurrency, contracts, relay and current projections |
| `node node_modules/vitest/vitest.mjs run --config vitest.integration.config.ts --configLoader bundle test/integration/community.integration.test.ts test/integration/community-presence.integration.test.ts` / Realtime | 22 passed, 2 files, exit 0 | Real Kafka/Redis/Mongo and two Socket.IO replicas; Community HTTP owner is a signed fixture. Includes crash/restart, lost invalidation and cross-device leases. Does not prove a real Community → Realtime pairing |
| `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-repository.ps1 -BaseSha origin/dev` / root | Repository and paired-change policy passed | Scoped QA files; no contracts, migrations, shared workflow or runtime feature files changed |
| QA Markdown local-reference validation; `git diff --check` | Exit 0 | Seven local references resolve; no whitespace errors |

The full Community run was warranted by the story's owner-permission/report/race
regression scope. Unchanged service suites remain in their CI lanes; no whole
repository rerun was used as a substitute for the actual missing boundary test.
Dependency installation used the system npm launcher (Node 22 engine warnings
for Realtime); all Realtime test runtime commands used explicit Node 24.19.
The broker restart/failure cases deliberately emit transport-unavailable logs;
the final test assertions passed. Required GitHub checks are separate from
these local results. This PR does not activate chat flags in a shared environment.

## Remaining client acceptance run

Use two synthetic USER accounts A/B and a third unrelated USER C. Record the
frontend/backend SHA pair, browser/device versions and fixture reset identifier.
Do not point a destructive fixture reset at the shared dev/staging data plane.

| Journey | Actions / proof to capture | Current execution boundary |
| --- | --- | --- |
| Direct consent and privacy | A requests B; B sees minimal request but no transcript; B accepts; exchange a synthetic message; change privacy on the second context and verify the server-owned revision/conflict behavior | Browser fixture tests exist; true two-context service walkthrough NOT_RUN |
| Cross-device revoke | Keep B's thread open, block A from another context, attempt history/send/reconnect and open an old notification; generic unavailable state, no queued new content or sensitive error payload | Owner/service tests cover decisions; browser two-context execution NOT_RUN |
| Private group consent / epoch | Invite B, prove no pre-accept history; accept, send, remove B, reinvite/rejoin; deny old cursor/history/notice and allow a new-epoch message | Backend executable; web BLOCKED_PROVIDER until MB-688 merge |
| Role/capacity race | Two contexts compete for the last seat or transfer ownership from the same group revision; one winner, refresh stale state, exactly one OWNER | Automated owner tests; web group walkthrough after MB-688 |
| Retry/reconnect/noise | Drop one send response and retry the same client ID; disconnect/reconnect with fresh ticket; verify one message and one logical notice; quiet hours/opt-out and current eligibility still win | Service tests; live web integration NOT_RUN, Android batch MB-666 |
| Report isolation | Report one readable synthetic message; retry returns the same case; outsider/removed viewer cannot report it; show no surrounding transcript or unsupported moderation UI | Owner automated tests; UI capture after integration |

Capture only generic state screenshots and redacted logs (action, safe error,
correlation, result). Browser tracing/video may retain chat bodies or auth data;
use synthetic text and review/redact artifacts before publication. Real Android
evidence must use the MB-666 approved build and public edge; the older controlled
Community edge routes all Community paths to the owner and does not demonstrate
the native realtime-ticket route or Socket.IO upgrade. Repair/verify that fixture
at the staging batch boundary before claiming native realtime success.
