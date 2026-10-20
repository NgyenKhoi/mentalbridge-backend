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
   idempotency, private sequence/unread, minimized outbox/relay, real concurrency,
   outage and contract tests. Final affected module checks and exact-head CI.

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

## Verification checkpoint

Checkpoint 1: Identity current-account provider and contract tests: 5 passed.
Checkpoint 2: direct-chat workflow PostgreSQL tests: 13 passed; existing
moderation audit tests: 6 passed. Active/frozen contract compatibility and
consumer deadline/fail-closed tests: 11 passed. Strict duplicate/trailing JSON
and the 32 KiB request-body limit are covered. Message/history delivery and
final exact-head CI are still in progress; no completion claim yet.

The initial workflow test run found a Mockito re-stubbing error in the timeout
test (NPE before the request); `doThrow` corrected that test setup. The first
canonical OpenAPI-reference check found unescaped route braces and unresolved
parameter-reference diagnostics; URI-encoded fragments and explicit parameter
shapes corrected the references without weakening validator checks.
