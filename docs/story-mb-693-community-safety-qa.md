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
| Suspended / ineligible account | Uniform unavailable result | Reading, sending, socket projection or notice delivery via cached grant | Current Identity eligibility; owner tests plus MB-693 outage/recovery test |
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
| Real Community → notification boundary | NOT_RUN | Implement and run the focused cross-service IT |
| Realtime / spam / report / race regression | NOT_RUN | Run the mapped owner/service tests on this branch |
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
