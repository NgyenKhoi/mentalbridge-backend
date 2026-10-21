# Community chat policy v1

- Policy ID: `community-chat-policy-v1`
- Decision: [MB-COMMUNITY-CHAT-001](../adr/0042-community-chat-contract.md)
- Status: Accepted by MB-684 PR #206 (merge `318248c7`); runtime configuration is delivered by implementation stories
- Scope: authenticated active Community `USER` accounts; all package tiers

## Identity and authority

Only the verified subject selects the actor. Community resolves its own active
profile; public DTOs contain Community profile UUID/display name, never Identity
subject, email, real name, account role, Care/Journal/assessment/SupportPlan data.
Anonymous/deleted post authors cannot be reverse-resolved into a DM target.
Requests to self, unknown/inactive profiles and bilaterally blocked profiles do
not reveal target existence. ADMIN/SPECIALIST role alone grants no peer-chat access.
Suspension/deletion/restriction overrides every privacy mode and membership.
Runtime delivery must establish a current account-state gate; existing JWT-only
authentication is not evidence of immediate suspension/revocation support.

Server authorization is checked for every REST read/write, subscribe, reconnect,
fanout, typing/presence/receipt projection and notification delivery. On owner
failure deny access with a bounded 503; a stale positive grant is never a fallback.
Internal presence/typing/receipt checks bind both conversation and exact subject
profile; subscribe checks bind the conversation without a subject selector.
Message projection binds the exact conversation/message and signed end-viewer;
no request body/header may select another actor or recipient.
Notification checks require exact recipient, kind, target ID and conversation ID.
The provider compares them to its owned records; a schema-valid mismatch still denies.
Invalid/inaccessible object identifiers share `TARGET_UNAVAILABLE`/404 without
confirming block, membership, private group or report existence.

## Direct requests and preference changes

`APPROVAL_REQUIRED` is the default. `ANYONE` permits an eligible peer to establish
a direct conversation without a request. It does not bypass blocks/restrictions.
An unordered Community-profile pair has at most one direct relationship.

In approval mode a stranger may create one bounded, **text-free** request.
Before acceptance neither participant may send ordinary messages; the recipient
sees only the bounded Community requester projection and accept/decline/block.
No message preview, presence, typing, read receipt or transcript exists here.
Crossed requests remain pending; they do not constitute implicit acceptance.

Only the request recipient decides with an exact version. Accept atomically
creates/activates the pair's one conversation after revalidation. Decline stores
a suppression tombstone: the original requester cannot resubmit, including via
another idempotency key or preference change. The declining recipient may
explicitly initiate a reverse conversation later, subject to the other user's
current privacy. Block also uses the existing Community bilateral block authority.
Unblock alone never accepts a request or recreates a conversation.

Requests expire after seven days of server time. Expiry/decline/block cannot be
overridden by a late accept or replay. Same terminal decision is a no-op; a
different terminal decision conflicts. Pending requests remain pending when a
recipient switches to ANYONE; no background conversion or new duplicate request.
Existing accepted conversations remain usable across privacy preference changes.

## Groups and membership

Groups are private, invite-only, and absent from public discovery. Creation makes
the creator the sole OWNER. The configurable active-member cap defaults to 30
and is restricted to 20–30 in v1, including the owner. Pending invitations do not
reserve a seat. Acceptance locks/revalidates the group and allocates the seat
atomically, so two acceptances for the last seat yield exactly one membership.

OWNER/ADMIN may invite. The invited person must explicitly accept. An invitation
reveals only its ID, group name, inviter Community projection and expiry, not
members, messages, unread state or presence. It expires after seven days; revoked,
declined and expired invitations cannot be accepted. No repeat invitation to the
same group/person after decline in v1. Removal of the inviter's management role,
archive, restriction or bilateral block invalidates outstanding invitations.

OWNER alone promotes/demotes ADMIN, transfers ownership to a current active ADMIN,
or archives. ADMIN may remove MEMBER only; OWNER may remove any non-owner.
MEMBER/ADMIN may leave. OWNER must transfer ownership or archive first. Transfer
atomically leaves exactly one OWNER in an active group and makes the former owner ADMIN. Client
role replacement never accepts OWNER. No concurrent command may remove the last
owner in an active group or exceed the cap. Archived groups may retain a departed
owner tombstone and allow self-leave but no new management writes. Use aggregate version conflict
for management commands.

Strong If-Match refers to the privacy revision, request revision, invitation
revision or moderation-case revision for those operations. All group management
commands use the public relationship/group **management** revision, including
member-role updates and invites. This is distinct from the private message
sequence and outbox aggregate revision: messages/read acknowledgements do not
advance it, so hidden traffic cannot leak through public versions or ETags.
Body-bearing management successes return the current management ETag, even when
the body is a Member or Invitation; Invitation.version remains the invitation's
own decision revision. Invitation decision If-Match never uses a group revision.
Archive and remove/leave return 204 with neither body nor ETag. A still-authorized
member must call getConversation for its management version before another
versioned command; never increment/reuse the pre-command version. Self-leavers
and removed actors cannot reread/manage the group. Invitation DECLINE 204 grants
no group access or ETag. A matching version never substitutes for permission.

New members see messages only from their current membership epoch onward.
Leave/removal immediately revokes history, socket, presence, typing, receipts and
unread access. Rejoining creates a new epoch and cannot reveal earlier messages.
Archive disables writes/invitations, retaining eligible history for current
members within retention. A former member has no archive-history entitlement.

## Blocks in direct and shared-group surfaces

Either direction of a block prevents direct sends, new requests, invites,
pairwise presence, typing, receipts and notification routing. Pending actions
cannot race past a committed block. Old direct history is unavailable while
blocked; unblocking may restore an already accepted relationship, never a pending
one. Removal of a block does not remove a decline suppression tombstone.

Shared groups remain usable for other members. The blocked pair cannot see or
receive each other's messages, unread increments, presence, typing or receipts.
Their member projections use `UNAVAILABLE` without profile/name fields. Group
role-management authorization does not grant message visibility. The provider
filters before pagination/counting. No public lastSequence, per-message sequence,
read-sequence watermark or global activity timestamp is returned. Hidden traffic
does not change visible unread, public management version, conversation ordering,
pagination availability or emit a receipt/resync solely for that traffic.
Existing retained records are not broadcast again after unblocking. A fresh
authorized REST read may recover newly eligible retained history from the current
membership epoch; old visibility cursors are invalidated, never widened in place.

| Visibility transition | History, unread and recovery contract |
| --- | --- |
| First join / rejoin | New membership epoch; no earlier messages/read references; unread starts at zero and counts only subsequent eligible non-self messages. Rejoin cannot restore a prior epoch. |
| Block | Direct unavailable; shared-group pair filtered before page/count/receipt projection. Private read watermark is retained; its public reference becomes null if no eligible read message remains. |
| Hidden group traffic | No public counter, list reorder, extra empty page, receipt or resync revealing that traffic. Other eligible members retain their own authorized stream. |
| Unblock | Fresh owner authorization; current-epoch retained history may become eligible on REST read, with unread recomputed from eligible non-self messages above the retained private read watermark. No old-message rebroadcast. |
| Leave / removal / restriction | All history, read references, unread, socket and ephemeral access revoked; uniformly TARGET_UNAVAILABLE. Queued content must be discarded. |
| Pagination / replay gap | Server resolves private sequence gaps and filters first; only missed eligible content may cause client resync. Reconnect may request a fresh authorized snapshot without disclosing a gap count. |

## Messages, retry and unread truth

Text only, 1–4,000 Unicode code points after blank-only validation; render as
plain text. No file, voice, video, rich HTML, public share or E2EE claim. No
automatic import of health records or AI processing of messages. User-authored
text is private even when the user chooses to discuss personal health.

Community commits a message, authoritative sender, monotonically increasing
conversation sequence and required outbox facts in one local transaction.
`(sender, conversationId, clientMessageId)` is unique. Same retry returns the same
message/sequence; changed content conflicts. Idempotency-Key is bound to owner,
operation and normalized body; unknown timeout outcome is reconciled with the
same keys. Deleted or expired dedupe records cannot allow a late retry to recreate
an expired message: retain content-free command identity tombstones while the
conversation exists and return conflict for a retry whose content has expired.
Deleting the conversation removes its content and identity records; future
commands against that old conversation ID return TARGET_UNAVAILABLE, never create
it again. Account deletion removes actor identifiers under its deletion policy.

Private sequences/outbox versions and public management versions are decimal
strings, not JavaScript-unsafe numeric IDs. Private global sequences never leave
the owner/service fact boundary. Public message IDs are random UUIDs without
sequence/time encoding; no counter can be reconstructed from those IDs.
REST 201/SAVED means durable acceptance only, delivery UNCONFIRMED. Socket ACK
means command acceptance only. Neither proves delivery, reading, availability,
engagement, clinical progress or completion. Message writes never use the
appointment WebSocket `message.send` command.

History is cursor-paginated in ascending private server sequence, bounded to 50
eligible records. Opaque authenticated cursors bind actor, conversation, direction,
membership and visibility epoch; neither raw nor reversibly encoded counters
are public cursors. Filter before selecting items/continuations; hidden gaps
cannot generate an empty intermediate page or reveal remaining counts. A stale
visibility cursor is TARGET_UNAVAILABLE; restart an authorized read. Continuation
availability/meaning must not change solely because of hidden traffic (token
bytes may vary due to nonce/signing). No eligible remainder means nextCursor=null.

ReadCursor accepts lastReadMessageId, not a sequence. Community resolves the
currently visible exact-conversation/current-epoch message to its private sequence
and atomically advances max(previous, resolved). Cross-conversation, blocked or
prior-epoch IDs return TARGET_UNAVAILABLE. ReadResult contains the last currently
eligible read message ID or null; changing visibility may change this reference
without decreasing the private monotonic watermark. Unread counts exclude self
and blocked/removed/ineligible messages. Duplicate/out-of-order acknowledgements
cannot decrease private state or add unread. Shared receipts use a message ID
visible to both subject and observer; suppress an ineligible reference entirely.
Receipts are a separate explicit opt-in; disabling them never resets private unread.

## Presence, typing and receipts

Default `presenceVisible=false`, `typingVisible=false`, `readReceiptsVisible=false`.
`invisible=true` overrides presence opt-in. Only accepted direct peers or current
shared-group peers may observe an opted-in, unblocked subject. Strangers,
requesters, invitees and moderators get no presence event or exact last-seen.
No UI may infer willingness to talk from technical connectivity.

Realtime records one lease per authenticated device/socket in Redis. Heartbeat
interval 20s, foreground lease TTL 60s; server time owns expiry. ONLINE means at
least one eligible foreground unexpired lease. Background/disconnect removes only
that lease; another active device keeps ONLINE. Crash expires at TTL; reconnect
creates a new lease. No client duration or claimed online flag is authoritative.
Redis failure returns UNKNOWN for otherwise authorized peers, never cached ONLINE.
Do not publish UNKNOWN events to hidden subjects' unauthorized viewers.
Privacy/block/removal changes revoke subscriptions and clear visible badges
immediately; a generic conversation invalidation carries no blocked-person ID.
Clients clear stale badges on disconnect/reconnect pending a fresh authorization
and snapshot. A successful subscribe emits freshly authorized presence projections
for eligible subjects only; unauthorized/hidden subjects produce no event.
Presence is neither stored in Kafka nor durable message history.

Typing additionally requires the subject's explicit typing opt-in and current
send permission; expire after 5s, remove on background/disconnect/send/revocation.
Shared receipt projection requires its subject's receipt opt-in plus the viewer's
current eligibility. Never emit an event solely to disclose that opt-in is off.

## Realtime and durable event contract

MB-749/ADR 0043 adds a native-only public ticket exchange at
`POST /api/v1/community/socket-credentials`. The verified Identity USER subject
and original expiry select the session; no client actor/expiry selector or BFF
signing key is accepted. Current Community eligibility is checked at issue and
consume. It reuses the opaque one-use namespace ticket and no-store response;
native ingress does not replace or relax the trusted BFF operation below.

Socket.IO namespace `/community` is separate from `/realtime`. Realtime issues a
one-use 30s credential through authenticated BFF delegation and consumes it
atomically; it is bound to namespace, subject and JWT expiry. Never put a bearer
or ticket in a URL, event, log, persistent browser storage or notification.
Every command and fanout is owner-authorized, including all group recipients.
Delegation has audience `community-service`, current subject and exact action;
ordinary client headers cannot impersonate a delegated subject.

Community Kafka topic candidate `mentalbridge.community.chat.v1` is keyed by
conversationId. `aggregateId == payload.conversationId`; aggregateVersion is
the private committed outbox revision, distinct from public management versions.
MESSAGE_SAVED carries only the message
pointer/sequence for Realtime. AUTHORIZATION_CHANGED invalidates recipients; it
is not a durable permission grant. Recipient-specific MESSAGE_RECEIVED,
DIRECT_REQUESTED and GROUP_INVITED carry targetId and private routing reference.
The kind must match the exact stored message/request/invitation, conversation,
actor and recipient. Routing references never appear in public DTOs or sockets.
Use UTC microsecond truncation before outbox persistence. event/message ID is
stable across relay retries. Logical recipient/kind/target identity dedupes a
new envelope ID. No text, group name, health, identity email or provider data.

MB-689 adds a private `READ_STATE_CHANGED` transport fact when the existing
read watermark advances, carrying only conversation and recipient-self routing
references. It grants no shared receipt opt-in and contains no read reference
or counter. Realtime fetches the recipient's current absolute private read state.
The owner also issues encrypted viewer/visibility/membership-bound delivery
cursors over ordered eligible message IDs. A fresh subscription starts at the
current tail and signals REST history repair; retained history is not rebroadcast.

A pending direct request may reserve a private conversation routing UUID; that
identifier and its fact do not grant either participant message/history access.
Only an accepted relationship activates the conversation's message capability.

Realtime consumes pointers and calls the read-only POST
`/internal/v1/community/chat/message-projections` separately for each exact message
and recipient. This route returns Message, not a boolean authorization grant.
`MessageReadDelegationClaims` binds a trusted Realtime signature, issuer/actor
service, audience community-service, scope community:message:project, USER subject,
conversationId/messageId and a maximum 30-second lifetime. Community checks
0 < exp-iat <= 30 and iat <= now < exp; wrong audience/signature/expiry is 401,
wrong service/scope 403. Claims/body target mismatch and inaccessible targets are
uniform TARGET_UNAVAILABLE/404. No viewer/actor/recipient selector exists in the
body. The public listMessages BearerAuth remains Identity USER-only.

The owner resolves the signed viewer and verifies current account state,
accepted relationship, bilateral blocks, membership/revocation and current-epoch
message visibility in the same consistent read that returns the content. A
schema-valid cross-conversation pointer still denies. A generic authorization
boolean/decisionVersion cannot be reused to read some other content. Realtime
must re-read under fresh delegation on replay and immediately before each fanout;
discard queued projections on revocation, never retain a grant or raw-content
cache. Any known revocation wins over a queued send; an owner outage yields 503,
never cached text. Downstream tests must exercise revocation between consumption
and delivery, including delayed/lost invalidation.

Realtime never looks up Community tables or caches raw content in Redis. Private
sequence orders owner results; Kafka arrival across partitions and socket arrival
are not transcript order. Server-side gaps are reconciled against current eligible
history before emitting resync.required; a hidden-only gap emits nothing. No
global sequence/version/gap count is sent to the viewer. Clients dedupe by message
ID, use socket delivery as a refresh signal and reconcile order from ascending
REST history (including after reconnect/resync), rather than append in arrival
order or invent a missing-message counter. Permission changes must be rechecked
at delivery even if invalidation is delayed or lost.

Consumers commit offsets only after durable side effects. Transient storage or
authorization-service failures retain a replayable offset/retry with bounded
1s–30s exponential backoff and no busy loop. Poison schema/identity mismatch
parks digest-only diagnostics; it never logs the payload. A replay cannot repeat
an external provider delivery. Redis/Kafka outage cannot roll back a committed
Community message; live delivery may degrade, history remains authoritative.

## Notification integration

Reuse MB-580/617/563 notification ownership and current preference/quiet-hour
policies. Do not republish existing post/comment/reaction facts. MB-691 separately
audits comment-reaction gaps and reaction aggregation; this contract adds only
chat/request/invitation facts. Self-events are suppressed. A group message emits
one logical recipient fact per currently eligible member excluding sender and
blocked pairs. No broadcast recipient list is placed in a fact.

V1 chat/request/invitation notices use the existing
`contentGroups.communityInteraction` preference together with the master/channel
switches. They are not mandatory service/security alerts that bypass opt-out.
Quiet-hour policy remains Content-owned and is rechecked at deferred delivery.

MB-691 owner clarification, accepted on 2026-10-21, applies prospectively to new
Community notices: defer new inbox publication while the recipient is in quiet
hours; existing inbox history remains readable. Recheck current preference and
exact target eligibility at deferred delivery. Group supportive post/comment
reactions by recipient and exact target into one active unread notification;
after reading, a new actor may start another group. Retry, replacement and
remove/re-add by an already recorded actor do not repeat the logical notice.
Tombstones win over replay. MB-691 includes backend comment reactions and the
generic PUSH payload contract. Device registration and actual PUSH delivery are
tracked by MB-675 (backend) and MB-679 (device permissions, token lifecycle and
safe links), explicitly outside this slice.

Content checks current exact chat target/recipient authorization through the
MB-691 Community internal API before inbox materialization. A 503 retries;
a definitive denied/expired/revoked result cancels. Preferences/quiet hours are
Content-owned; deferred delivery revalidates before sending. Opening a deep link
always reauthorizes in Community. Links are closed route descriptors built by
the app (`conversationId`, `requestId`, `invitationId`), never arbitrary URLs.
These are contract descriptors, not claims that frontend routes already exist.

In-app copy is generic Vietnamese: “Bạn có tin nhắn mới.”, “Bạn có lời nhắn chờ
chấp nhận.”, “Bạn có lời mời vào nhóm riêng.” Push uses only “Bạn có thông báo
mới trên MentalBridge.” with an opaque notification ID. No body, sender name,
group name, profile ID, exact presence, message count or health data on a lock
screen. No new push provider/channel is activated by MB-684.

## Abuse, moderation and retention candidate

Only a participant currently entitled to read a message can report it. Capture
one immutable reported-message snapshot, not an entire transcript or surrounding
history. ADMIN may read that evidence only via an authenticated auditable case
operation and take a bounded action on the reported message/account. ADMIN
cannot list arbitrary conversations, join groups, see presence or bypass blocks.
Crisis-concern reason raises existing queue priority and may offer immediate
help; it does not promise monitoring, diagnosis or third-party intervention.

Proposed retention for explicit review: chat content 90 days from creation;
resolved report evidence 180 days after resolution. Unresolved cases require a
bounded review date; legal holds must have authorized purpose, audit and expiry.
Account deletion removes membership/visibility immediately and purges private
content under the approved deletion workflow; retained evidence stays isolated
under its case authority. These candidate numbers cannot ship without owner
acceptance. Encrypt private content/evidence at rest with externally managed
keys; TLS in transit. No end-to-end encryption claim. No migration is created here.

Logs/metrics/audit contain action, safe code, correlation ID, version and latency
only. No body, report details, credentials, private routing reference, health
attributes, group name or presence timeline. Safe operational audit does not
grant general private-history access.

## Numeric limits candidate

These are v1 review defaults, not environment variables implemented by MB-684.
Enforce server-side across devices; report Retry-After without exposing other users.

| Boundary | Default |
| --- | --- |
| New direct requests | 5 per actor per rolling 24h; one pending per pair; permanent decline suppression for original requester |
| Group invitations | 20 per inviter per rolling 24h; one pending per group/invitee; no reinvite after decline |
| Message sends | 60 per actor per minute and burst 10 per 5s; duplicates do not create durable effects |
| Group creation | 5 per actor per rolling 24h |
| Reports | 10 per actor per rolling 24h; duplicate exact actor/message report is idempotent |
| Socket connections | 5 per account; reject excess without evicting unrelated devices |
| REST body / WebSocket frame | 32 KiB UTF-8; reject oversize before parsing, without logging the body |
| Subscribe | 30 per socket per minute; at most 30 active conversations |
| Typing | 1 update per second per actor/conversation; 5s expiry |
| Heartbeat | no faster than 1 per 10s per socket; expected interval 20s, TTL 60s |
| Owner query deadline | 500ms connect, 2s total; fail closed; safe reads may retry once within deadline |

## Compatibility and approval boundary

All new operations/schemas remain proposals and planned until implemented by
their delivery stories. Existing Community REST, interaction schema and all
appointment schemas remain byte-for-byte unchanged. No frontend/generated
client, runtime configuration or executable data model changes in MB-684.
Owner approval covers the acceptance/permission tables, retention/default limits,
shared-group blocking/history and least-privilege review before runtime coding.
There is no `ui-ready` claim; Docker walkthrough and handoff belong to runtime
stories under MB-706/707 after implementation and merge.
