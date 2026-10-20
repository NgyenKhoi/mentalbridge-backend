# MB-687 private groups

Owner: Community / Device 1. Base: merged MB-685 `ef05d0bd`; accepted ADR 0042,
policy v1 and MB-684 permission/acceptance tables remain authoritative. This is
backend work; MB-688 owns clients. No new policy or UI-ready approval is inferred.

## Accepted states and implementation slices

| State/event | Required result |
| --- | --- |
| Create | Creator alone is OWNER; private name; configurable cap 20–30, default 30. |
| Invite | OWNER/ADMIN only; text-free minimal invitation; explicit recipient acceptance; pending does not reserve seats. |
| Accept | Current inviter authority, active account/group, no bilateral block and a free seat; atomically allocate current epoch. |
| Decline/expire/revoke | No membership; decline suppresses reinvitation; seven-day expiry; role loss/archive/block/restriction invalidates pending. |
| Role/ownership | OWNER changes ADMIN/MEMBER; transfer to current active ADMIN leaves exactly one OWNER, former owner ADMIN. |
| Leave/remove | Immediate loss of history/unread; active OWNER must transfer/archive; ADMIN removes MEMBER only. |
| Rejoin | New epoch, no earlier history/read reference, no unread inherited from prior epoch. |
| Block in shared group | Group stays usable; pair's content/unread/projections filtered before pagination; no hidden counters/order changes. |
| Archive | No sends/invites/management writes; current members retain eligible history and may self-leave. |
| Report | Currently readable exact message only; encrypted isolated snapshot, no unrelated transcript or ADMIN browse authority. |

1. Group lifecycle: migration/canonical model, active accepted REST subset,
   role/cap/invitation transactions and real PostgreSQL regression tests.
2. Group message visibility: reuse encrypted saves/outbox/read state with
   membership epoch, current eligibility, bilateral filtering and concurrency tests.
3. Report intake and delivery evidence: bounded exact-message hook, minimized
   group facts through existing relay, contract/Kafka tests, full owner gate and CI.

Runtime implementation and local gate are verified below. Required exact-head CI
and human review remain separate delivery gates; no automatic merge or UI claim.

Lifecycle checkpoint: 30 focused tests passed on disposable PostgreSQL, including
ten group workflow cases, cap configuration, closed/frozen OpenAPI compatibility,
canonical provider contract and exact migration/table inventory. The original
operation inventory needed the nine newly implemented routes; it remains exact
rather than changing implemented/planned assertions.

## Runtime coverage

| Boundary | Evidence |
| --- | --- |
| Group lifecycle/roles | 12 `CommunityGroupLifecycleIntegrationTests`: explicit recipient acceptance, minimal invite projection, declined/expired/revoked outcomes, role loss/re-promotion, ownership transfer, self-leave/archive and exact management ETags. |
| Cap/consistency | Two acceptances race for one final seat; exactly one succeeds. Concurrent ownership transfer uses one management revision and leaves one owner. Real DB rejects ownerless/multi-owner groups, null/invalid caps. Deferred invariant checks serialize through the conversation row. |
| Message authority | 10 `CommunityGroupMessageIntegrationTests`: pending cannot send/read/list; current membership only; concurrent retries create one message/unread/logical fact; retention and current account gates inherited from direct storage. |
| Epoch/block privacy | Leave/rejoin cannot restore old history, read references, retries or cursors. Bilateral blocks filter before page/count and return unavailable member projections; hidden messages do not alter public management version/list order. Read max survives reordered devices. |
| Mutation race/failure | A committed removal wins over send after its Identity lookup while waiting for owner locks. Forced recipient-outbox failure rolls back message, sequence and command; retry commits one result. |
| Report intake | 5 `CommunityChatReportIntegrationTests`: exact currently readable target, isolated encrypted immutable snapshot, safe priority, duplicate/new-key retry, strict body/rate/outsider/block/expiry/epoch denial, source-deletion isolation and no raw content/details in captured diagnostics/facts. |
| Broker/contract | Real Kafka publishes GROUP_INVITED and exact recipient MESSAGE_RECEIVED on existing conversation-keyed topic; actual envelopes satisfy unchanged accepted fact schema and exclude name/text. Existing ACK/lease/crash/outage replay tests still pass. |
| Current dependency | Existing real Identity HTTP timeout/failure/identity-binding suites remain green. Reads use fresh batched eligibility outside DB transactions, then current profile/group locks; no cached positive fallback or distributed atomic Identity-revocation claim. |

The current Identity eligibility lookup protects reads/sends while an account is
inactive. Durable asynchronous account-deletion/restriction orchestration remains
its separate owner workflow; no historical Identity suspension timeline is inferred.

## Verification — 2026-10-20

Local Windows + Java 21 + Docker Desktop, `SPRINGDOTENV_ENABLED=false`; all
PostgreSQL/Kafka dependencies are disposable containers with synthetic identities,
names, text, JWTs and keys. No shared data plane, paid provider or frontend touched.

| Command | Result |
| --- | --- |
| Community `./mvnw.cmd -B verify` | **171 passed**, zero failure/error/skip; executable JAR built. Final run includes all existing direct/feed/moderation tests and new group/report/Kafka regressions. |
| Focused lifecycle/configuration/provider/migration checkpoint | 30 passed. |
| Focused group/direct message checkpoint | 47 passed, before final additional rollback/read/race checks. |
| Focused final group/report/Kafka/provider/migration checkpoint | 49 passed, before final inbox batching/removal-race check; full final gate covers those changes. |
| Root `powershell -File scripts/verify-repository.ps1 -BaseSha origin/dev` | Repository architecture and paired-change policy passed. |
| Root `docker compose --env-file .env.compose.example --profile demo --profile full-test config --quiet` | Passed; configuration validation only. |
| Root `powershell -File scripts/verify-compose-service-boundaries.ps1` | Passed; credentials/keys remain with the same owners. |
| Added Markdown relative-link audit and `git diff --check origin/dev` | Passed before publishing. |

The first expanded contract checkpoint retained an old implemented-operation
inventory; it was updated to require the delivered group operations. The first
report checkpoint/full run expected 403 for an unregistered ADMIN case route;
the exact correct 404 assertion was fixed, with no handler/permission added.
Final full verify is green after these corrections and the final source-deletion,
owner-lock, batch-eligibility and removal-race changes. No test was skipped or
contract validation relaxed. Full unrelated module checks are delegated to the
required repository CI; the run link and final SHA are recorded in the PR.

## MB-688 typed API handoff

Use [`community-chat-v1.json`](../contracts/openapi/community-chat-v1.json)
registered by canonical Community OpenAPI **v1.13**. All twenty public operations
retain the accepted MB-684 DTO components. No socket/internal/ADMIN case operation
is registered. A valid current USER and active Community identity are required.

1. POST `/api/v1/community/chat/groups` with UUID `Idempotency-Key` and
   `{"name":"Nhóm đồng hành tổng hợp"}` returns Group (201). Creator is OWNER.
2. POST `/groups/{conversationId}/invitations` below the same chat root uses
   quoted **group** `If-Match`, stable UUID retry key and
   `{"inviteeCommunityProfileId":"11111111-1111-4111-8111-111111111111"}`.
   Result is Invitation (201); ETag is group management revision, while
   Invitation.version is its independent decision revision.
3. GET `/invitations` returns only own currently valid minimal invitations, with
   opaque cursor/limit. POST `/invitations/{id}/decision` uses quoted
   **invitation** version and `{"decision":"ACCEPT"}` or `DECLINE`.
   ACCEPT returns Group + current management ETag; DECLINE is 204 without ETag.
4. Current members GET `/conversations/{id}` and `/groups/{id}/members`.
   Conversation is the typed DIRECT/GROUP union; GROUP includes groupName,
   viewerRole, peers, unreadCount and management version. Ineligible peers have
   only `state:UNAVAILABLE`; do not infer hidden identities.
5. OWNER PUT `/groups/{id}/members/{profile}/role` with `{"role":"ADMIN"}` or
   `MEMBER`, or POST `/groups/{id}/ownership` with
   `{"newOwnerCommunityProfileId":"22222222-2222-4222-8222-222222222222"}`.
   Each uses group If-Match; role success ETag is group revision (not a Member
   revision). Only a current active ADMIN can receive ownership.
6. DELETE `/groups/{id}/members/{profile}` performs authorized removal/self-leave;
   DELETE `/groups/{id}` archives. Both require group If-Match and return 204
   without ETag. Still-authorized clients must reread getConversation before
   another versioned command. Removed/self-leavers have no further read right.
7. Shared `/conversations/{id}/messages` and `/read-cursor` retain accepted
   message/save/read DTOs. SAVED + UNCONFIRMED proves durable acceptance only.
   Cursor strings are opaque and viewer/epoch-bound; restart on 404. Dedupe
   message UUIDs; never infer sequence gaps or receipt/online status.
8. POST `/reports` with UUID retry key,
   `{"messageId":"33333333-3333-4333-8333-333333333333","reasonCode":"SPAM"}`
   (optional details up to 1,000 code points) returns `{caseId,state:RECEIVED}`.
   It grants no general case/transcript access or emergency response promise.

All UUIDs above are synthetic references. Errors retain closed Problem shape;
inaccessible targets are uniform 404, stale versions/conflicts 409, throttling
429 with Retry-After, dependency uncertainty 503. Surface explicit pending,
empty, stale/removed, blocked-unavailable, archived-read-only and retry states.
Do not auto-accept, optimistically create membership, invent delivery, reveal
unavailable peers or locally increment a version after 204.

## Release, rollback and scope

Apply owner Liquibase **0019/0020** after MB-685's 0017/0018. Canonical SQL is
documentation only. The default-off chat flag, dedicated Identity credential,
versioned encryption keys and existing relay remain unchanged. The sole new
setting is `COMMUNITY_CHAT_GROUP_MEMBER_LIMIT=30`, validated 20–30, captured per
new group. Provisioned Kafka topic/schema remains the same. Disable API/relay
flags to roll back capability; retain additive rows, report evidence and keys.
Overlapping old binaries remain DIRECT-only and must not serve new group routes.

Conversation listing resolves current viewer-related metadata and batch account
eligibility before filtering/ordering/pagination. Cost is proportional to the
viewer's related conversations; there is no public search or discovery. Pending
invitation lists batch current inviter eligibility rather than making one remote
call per invitation. No HTTP/broker call occurs inside owner transactions.

MB-688 owns group clients; MB-689/690 own delegated current projections,
socket/presence; MB-691 owns notification materialization/provider gates and
deduplication; remaining epic work owns auditable ADMIN report resolution,
retention after resolution and account-deletion orchestration. Group facts are
never reusable permission grants: each downstream delivery must reauthorize its
exact current recipient/target and discard revoked queued content. No new UI,
appointment chat change, provider channel or production deployment is claimed.
