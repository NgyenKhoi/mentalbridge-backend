# MB-684 Community chat contract evidence

## Scope and review status

Contract-only prerequisite for [MB-684](https://vunguyenkhoi47.atlassian.net/browse/MB-684).
Feature/contract owner: Thảo Nguyên / Device 1. Community backend, Realtime and
Content/Notification consume the same [ADR](adr/0042-community-chat-contract.md),
[policy](policies/community-chat-policy-v1.md) and
[OpenAPI candidate](../contracts/proposals/community-chat-v1.openapi.json).
Device 2 and UX polish receive no overlapping production file handoff here.

Owner confirmation is **pending PR approval**, not inferred from assignment,
tests or the instruction to implement the task. The locked MB-683 decisions are
preserved; proposed defaults requiring explicit acceptance are retention, request
expiry, numeric limits, group history epochs, shared-group block filtering and
reported-message evidence scope. Approval plus merge accepts this candidate.
No runtime implementation begins in this PR; `ui-ready` and Docker walkthrough
remain unavailable until the downstream feature exists.

## Acceptance states before runtime coding

The table is the shared input required by MB-706. Future web/mobile owners may
vary presentation but cannot widen what a state discloses. Owner and UI-fixer
confirmation must precede runtime UI coding; screenshots are not applicable here.

| State | What a future screen shows | Must NOT show | Business rule / authority |
| --- | --- | --- | --- |
| Stranger / default privacy | Community identity and request action | Message composer/history, presence, receipt, account identity | Policy: Identity; Direct requests |
| Pending incoming request | Bounded requester identity, accept/decline/block, expiry | Request text preview, ordinary messages, presence | Policy: Direct requests |
| Declined/expired request | Generic unavailable result to original sender | Repeated solicitation or reason/block disclosure | Policy: Direct requests; Abuse |
| Accepted direct peer | Authorized history, text send, private unread | Implied online/read/delivered success | Policy: Messages |
| Preference changed | Current preference; accepted conversation stays usable | Automatic pending-request acceptance or deleted history | Policy: Direct requests |
| Blocked pair | Generic unavailable direct surface; redacted group peer | Pair's messages, unread increments, presence, reason/identity leak | Policy: Blocks |
| Group invitee | Group name, inviter, accept/decline, expiry | Member list, chat history, presence, implicit membership | Policy: Groups |
| Current group member | Messages since current join epoch; leave/report | Before-join messages, group discovery, admin controls | Policy: Groups; Messages |
| Group OWNER/ADMIN | Exactly permitted member controls | OWNER removal by ADMIN, MEMBER promotion, cap bypass | Policy: Groups; matrix below |
| Removed/left/restricted | Generic unavailable, cleared subscriptions | Retained cached history as currently accessible | Policy: Identity; Groups |
| Archived group | Retained eligible read-only history for current members | New sends, invitations, former-member access | Policy: Groups |
| Hidden/invisible/offline presence | No badge for unauthorized/hidden subject; authorized snapshot only | Exact last-seen, willingness/availability inference | Policy: Presence |
| Redis/owner unavailable | UNKNOWN or explicit unavailable/retry | Cached ONLINE or stale authorization as current truth | Policy: Presence; Realtime |
| Pending send / timeout | Pending or unknown outcome, retry with original IDs | Fabricated persisted/delivered/read message | Policy: Messages |
| Reconnect / sequence gap | Refreshing/recovery through authoritative history | Duplicate message/unread or invisible missing-content claims | Policy: Realtime |
| Moderator case | Exact reported-message evidence and bounded case actions | Arbitrary transcript, member/presence browse or auto-intervention promise | Policy: Abuse |

## Permission matrix

All grants additionally require current active actor/target state, no overriding
block and owner authorization. `Conditional` never means client-selected permission.

| Actor/relation | Direct request / send | Group history / send | Invite / member management | Presence / typing / shared receipts | Report evidence |
| --- | --- | --- | --- | --- | --- |
| Stranger | One text-free request, or establish via ANYONE; no pending send | Denied | Denied | Denied | Denied |
| Accepted direct peer | Allowed despite later privacy-mode change | No unrelated group access | Denied | Conditional on subject opt-in/current eligibility | Own visible message report only |
| Blocked peer | Denied, both directions | Pairwise content filtered; other members unaffected | No pair invitation | Denied | No new inaccessible-target report |
| Invitee | No DM permission implied | Denied until explicit accepted membership | Own invite accept/decline only | Denied | Denied |
| MEMBER | No unrelated DM permission | Since current join epoch | Self-leave only | Conditional | Own visible message report only |
| ADMIN (group role) | No unrelated DM permission | Since current join epoch | Invite, remove MEMBER, self-leave; no promote/owner removal | Conditional | No moderator power implied |
| OWNER (group role) | No unrelated DM permission | Since current join epoch | Invite, remove non-owner, promote/demote, transfer to ADMIN, archive | Conditional | No moderator power implied |
| ADMIN (platform moderator) | Denied by role alone | Denied by role alone | No private-group membership control by role | Denied | Auditable exact report case only |
| Suspended/deleted actor | Denied | Denied | Denied | Denied | No new peer action |

## Contract and abuse regressions

`realtime-service/test/contract/community-chat-contract.test.ts` is automatically
included in existing Vitest CI. It parses/resolves the proposed OpenAPI and
strict-compiles the JSON schemas using the existing Ajv2020/format dependencies.
It checks valid synthetic REST/WS/Kafka examples, malformed payloads, identity
forgery, unknown enums/versions, privacy defaults, maximum lengths, numeric
sequence safety, disjoint event variants, and forbidden notification content.
It also verifies that the proposal does not relabel existing runtime APIs.

These are executable **contract tests**, not provider/infrastructure integration
tests. No invented in-memory policy engine is used as proof of authorization.
Downstream runtime acceptance must execute the following scenarios with real
owner storage/Redis/Kafka as applicable:

| Scenario | Required runtime evidence / owner |
| --- | --- |
| Both direct privacy modes; crossed/pending/declined requests | MB-685: real pair uniqueness, recipient-only decision, suppression after new keys and preference changes |
| Same send retry, changed-content retry, unknown outcome | MB-685: one persisted message/sequence/outbox side effect; 409 on conflicting body |
| Block vs send/accept/fanout; account suspension | MB-685/689: committed authorization wins; no delivered stale grant or raw content leak |
| Last group seat; owner transfer vs remove/leave | MB-687: real transactional cap and exactly-one-owner invariants |
| Invite vs role revocation/archive/expiry; rejoin history | MB-687: no implicit membership, stale accept denied, new epoch excludes old messages |
| Shared-group blocked pair | MB-687/689: filtered history, unread, member projection and socket delivery, other peers unaffected |
| Two devices, crash TTL, background, invisible and Redis outage | MB-690: lease aggregation, TTL expiry, no false online, no stranger events |
| Opt-in changes and delayed receipt/typing/presence events | MB-689/690: immediate visible-state invalidation, current recipient authorization |
| Duplicate/reordered Kafka, DB outage/restart | MB-689/691: durable replay and offset-after-commit; no phantom message/duplicate unread/inbox/provider delivery |
| Self notification, declined request, revoked invitation, quiet-hour delay | MB-691: suppress/revalidate; generic provider payload and exact target binding |
| Moderator seeks unrelated transcript; forged case/report | MB-685/687/693: fail closed; exact reported snapshot with audited purpose only |
| Retention, deletion, report hold and expired retry tombstone | Runtime owners: purge without recreation, no unbounded evidence retention |

## Verification and release notes

Local verification:

| Command/check | Result |
| --- | --- |
| Realtime `npm run format:check`, `lint`, `typecheck`, `test`, `contract:check`, `migration:check`, `build` | Passed; full unit/HTTP lane 79 tests before two final contract-only regressions |
| Final targeted Community + appointment WebSocket contract tests | Passed: 42 tests, including the two added handshake/component regressions |
| Final targeted Prettier/ESLint and Realtime typecheck | Passed |
| `scripts/verify-repository.ps1 -BaseSha origin/dev` | Passed; 24 changed files; no runtime changes |
| Relative links introduced by MB-684 | Passed: 45 links at verification checkpoint |
| `git diff --check` | Passed |

Windows checkout used CRLF because of local Git settings. For local Prettier
verification only, existing tracked Realtime files were normalized to LF and
then restored to their original checkout line endings. No runtime/lockfile/test
baseline content change is included. Windows script policy required process-only
`-ExecutionPolicy Bypass` for the checked-in repository verifier.

Exact-head CI is recorded in the PR after push.
Required local scope: new contract tests, existing appointment WebSocket contract
tests, Realtime format/lint/typecheck/contract/migration/build checks, repository
policy and relative-link audit. The full backend matrix runs in required CI.
No runtime code or infrastructure adapter changes justify new container tests
in this contract-only PR. No UI screenshot, native run, live-stack certification,
provider call or production deployment is claimed.

Release: additive design candidate only. Existing REST/WebSocket/Kafka files and
runtime data remain unchanged. No migration/rollback, new environment key, topic
provisioning, frontend contract generation or UI change is needed. Merge accepted
contract first; then provider implementations; then compatible consumers; then
explicit Docker acceptance and bounded `ui-ready` handoff. This PR does not mark
the Jira story Done or any downstream capability live.
