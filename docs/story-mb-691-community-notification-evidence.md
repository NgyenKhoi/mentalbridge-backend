# MB-691 Community notification delivery evidence

Story: [MB-691](https://vunguyenkhoi47.atlassian.net/browse/MB-691).
Backend owner: Thảo Nguyên (`Thaonguyen0507`). This is the backend/contract lane;
frontend/device integration and a merged-stack acceptance walkthrough are still
required before the end-user journey is `ui-ready`.

## Accepted scope and decisions

The owner accepted the brainstorm on 2026-10-21: implement supportive comment
reactions in this story; deliver inbox plus a safe PUSH payload contract; group
reactions by one active unread notification per recipient/target; defer new
Community inbox notices during quiet hours. Existing inbox history stays readable.
Retry, replacement and remove/re-add by the same actor do not repeat a notice.
After reading, a new actor may create another notification.

Community owns posts/comments/reactions, private chat targets and current
authorization. Content owns copy, preferences, jobs, inbox grouping and receipts.
Kafka carries minimized committed facts; it never carries a permission grant.
Exact chat authorization uses a dedicated signed REST request before the local
Content transaction. No service reads another service's database.

## Review slices and contracts

1. Community exact notification authorization: the active
   [chat OpenAPI](../contracts/openapi/community-chat-v1.json) exposes the closed
   five-field notification query, including the actor identity bound to the fact.
   Separate Content RS256 credentials, audience, scope, 30-second validity and
   strict claims isolate this route from Identity and Realtime credentials.
2. Community comment reactions: [OpenAPI](../contracts/openapi/community-comment-reaction-v1.json)
   adds GET/PUT/DELETE at `/api/v1/community/comments/{commentId}/reaction`.
   One SUPPORT/RELATE/THANK_YOU reaction exists per comment/actor. First nonself
   reactions publish through the existing transactional outbox to the separate
   [comment-reaction v1 topic](../contracts/events/community/community-comment-reaction-v1.schema.json).
   Existing interaction-v1 facts remain compatible.
3. Content delivery: existing post/comment/reply facts and new comment-reaction
   and recipient-specific chat facts enter durable jobs. Inbox insert/grouping
   and job completion are one transaction. Expiring claim tokens fence workers;
   dependency failures retry without caching a positive authorization decision.
   [Notification OpenAPI](../contracts/openapi/content-notification-service.yaml)
   adds three kinds and closed actions. The
   [PUSH schema](../contracts/events/community/community-push-v1.schema.json)
   contains only an opaque notification ID and fixed generic Vietnamese body.

Community migration `0021-community-comment-reactions.yaml` and Content migration
`26_add_community_notice_delivery.sql` are the executable data truth. Their
[canonical model](domain-model/relational/postgresql-logical-schema.sql),
[entity index](domain-model/canonical-entities.md) and
[field dictionary](database/postgresql-field-data-dictionary.md) change with them.
The logical SQL is documentation and must never provision a runtime database.

## Acceptance states

Policy: [Community chat policy](policies/community-chat-policy-v1.md#notification-integration).

| State | Observable backend result / client expectation | Must not expose | Business rule / evidence |
| --- | --- | --- | --- |
| First nonself comment reaction | Store the actor's reaction and generic notice pointing to its post | Comment text, actor list, health data | Community reaction integration; Content Kafka integration |
| Replace, retry, remove/re-add | Desired reaction state changes naturally; no second logical notice | Duplicate unread items | Durable producer identity and Content receipt |
| Two actors, unread target | One active unread reaction notice | Inferred reaction count | Recipient lock plus unread target grouping; two-worker test |
| Read or deleted notice | Read ends grouping; a new actor can start another; replay preserves tombstone | Restored deleted item or duplicate old actor notice | Content PostgreSQL integration |
| Quiet hours | Keep existing inbox; defer new publication, then recheck after quiet hours | Premature new notice | Current timezone/preferences, durable deferred job |
| Opt-out at intake/delivery | Terminal cancellation; later opt-in does not revive old work | Stale replay notice | Preference lock and cancellation receipt |
| Eligible message/request/invitation | Generic notice with exact closed target action | Body, sender/group name, arbitrary URL | Current Community owner authorization before materialization |
| Denied, expired, revoked chat target | Cancel work; client open also reauthorizes | Transcript access from a pending request/invitation | Exact actor/recipient/target binding; owner provider tests |
| Owner unavailable or malformed decision | Retain job and retry; no new inbox item | Cached authorization bypass | Signed HTTP fixture and dependency recovery tests |
| Consumer/worker downtime | Catch up from Kafka or an expired claim; deliver at most once | Lost work or duplicate notice | Real Kafka/PostgreSQL, restart/replay and stale-worker fencing tests |
| Poison or changed identity reuse | Park digest-only diagnostics | Raw fact, chat content, credential | Strict envelope/key/semantic validation and bounded input |

Engagement facts retain the existing MB-617 trust boundary and generic post
link behavior: Community reauthorizes on opening. They do not use the private
chat authorization endpoint. An unavailable post produces the existing safe
unavailable state.

## Verification on the synchronized dev baseline

Base: `7c0bf5ff` (includes MB-690 and the merged MB-645 Care changes).
Windows, Java 21, Maven 3.9.16, Node 24; disposable PostgreSQL 16 and Kafka
Confluent 7.8 containers. Dotenv was disabled for Spring; tests never used the
developer `.env`, shared databases or paid providers.

| Command (owning module unless stated) | Result |
| --- | --- |
| Community `mvn -q verify` | 201 tests exercised; initial run found one stale migration/table inventory in the context test |
| Community `mvn -q -Dtest=CommunityServiceApplicationTests verify` after that test-only correction | 10 passed; all current normal-suite reports total 201 with zero failures/errors |
| Community `mvn -q -Dtest=CommunityNotificationRecoveryCrossServiceIT test` | 1 passed, zero skipped; real Spring producer, compiled Node consumer, two PostgreSQL databases and Kafka; consumer absence/catch-up/restart/replay |
| Content `node node_modules/vitest/vitest.mjs run --minWorkers 1 --maxWorkers 2` | 224 passed, 36 files |
| Content `node node_modules/vitest/vitest.mjs run --config vitest.integration.config.ts --minWorkers 1 --maxWorkers 1` | 77 passed, 5 files; includes new five-test durable delivery suite |
| Content `tsc --noEmit`, `tsc -p tsconfig.build.json`, `eslint .`, `prettier --check .` | All exit 0 |
| Content `node scripts/generate-resource-eligibility-contract.mjs --check`, `node scripts/validate-contracts.mjs`, `node scripts/validate-migrations.mjs` | All exit 0 |
| Root `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-repository.ps1 -BaseSha origin/dev` | Repository and paired-change policy passed |
| Root `docker compose --env-file .env.compose.example --profile demo --profile full-test config --quiet` and `scripts/verify-compose-service-boundaries.ps1` | Both exit 0; Compose is validated without loading the developer `.env` |
| Root `git diff --check` | Exit 0 |

Earlier focused runs exposed missing Nest explicit injection metadata, an
OpenAPI fragment URI escaping error, transactional spy setup and expected enum
inventories. Those were corrected before the recorded passing gates. The final
integration run also exercises transient intake failure without advancing the
Kafka offset. KafkaJS emits its upstream Node 24 timeout warning; assertions
passed without suppressing it. GitHub quality-gate must pass on the published
head before merge; local evidence does not substitute for it.

## Rollout and downstream handoff

Apply additive migrations, provision the new topic, deploy the Community owner
provider, then the compatible Content consumer. New notice/authorization flags
default false. Configure dedicated private/public keys and key ID; never reuse
Realtime credentials. Enable the client journey only after consumer enum/route
integration. Roll back binaries in reverse order while retaining migrations,
jobs and receipts; dropping receipts can invalidate deduplication.

Frontend `origin/dev` at `e34b2cc` has no Community conversation/request/invitation
pages. The handoff actions resolve to `/community/chat/conversations/{id}`,
`/community/chat/requests/{id}`, `/community/chat/invitations/{id}`; Device-2 must
implement/map them and reauthorize at the owner. There is no browser/mobile
acceptance or live PUSH claim in this PR.

Existing [MB-675](https://vunguyenkhoi47.atlassian.net/browse/MB-675) owns backend
device registration/native provider delivery;
[MB-679](https://vunguyenkhoi47.atlassian.net/browse/MB-679) owns device permissions,
token lifecycle and safe native links. Message retraction, per-user conversation
deletion and appointment chat remain separate use cases.
