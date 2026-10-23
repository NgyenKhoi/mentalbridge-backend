# Community PostgreSQL Field Data Dictionary

Owner: `community-service`. Runtime database: `mentalbridge_community`, default schema `public`.

The [owner migration history](../../../community-service/src/main/resources/db/changelog/) is executable runtime truth; the [service README](../../../community-service/README.md) describes its behavior. The [owner logical schema](../../domain-model/relational/community-service.sql) is read-only documentation and must never provision or migrate a database.

Original owner/conceptual section labels and section-specific status notes are retained below. Grouping a description here does not promote a conceptual or historical shape into an active migration.

[All PostgreSQL owners](../postgresql-field-data-dictionary.md)

## Database `mentalbridge_community` (schema `public`)

### `public.community_profile`

Community-owned public display identity kept separate from private Care and Identity profile data.

| Field | Purpose |
| --- | --- |
| `id` | Immutable opaque UUID exposed publicly as the Community profile identifier. |
| `account_subject` | Private unique Identity JWT subject used only for ownership, blocks, abuse controls, moderation, and deletion coordination; never returned by Community APIs. |
| `display_name` | User-chosen Community name shown on active posts; bounded to 80 characters and replaced by a neutral tombstone label when the profile is deleted. |
| `avatar_preset` | Optional closed Community visual preset (`LEAF`, `SUNRISE`, `WAVE`, `LOTUS`, `CLOUD`, or `SPROUT`); arbitrary URLs and account-profile avatars are never persisted here. |
| `avatar_media_id` | Nullable current Community-owned photo selected explicitly through a versioned owner command. Composite foreign key binds the media to this exact profile. Only an unattached verified READY IMAGE is selectable; anonymous/deleted author projections never expose it. Current selection protects the image from orphan cleanup and post attachment. Removal restores the preset/initial fallback and releases the object to the existing unattached-media retention policy; no Identity or Care image is imported. |
| `status` | Community-local display lifecycle `ACTIVE` or `DELETED`; it is not an Identity account-state copy. |
| `created_at` | Immutable UTC insertion instant for the Community display identity. |
| `updated_at` | UTC instant of the latest persisted Community display change. |
| `version` | Optimistic-lock counter used by the ETag/If-Match profile replacement contract. |

### `public.community_post`

Authoritative Community-owned personal-story publication. It is not Care, Journal, SupportPlan, or AI evidence.

| Field | Purpose |
| --- | --- |
| `id` | Immutable opaque UUID used by feed/detail links and future Community interactions. |
| `author_profile_id` | Physical reference to the chosen Community display identity; it never exposes the linked account subject. |
| `content` | User-authored peer-support text, bounded to 5,000 characters; it is excluded from cross-service events. |
| `state` | Publication lifecycle `ACTIVE`, `OWNER_DELETED`, `MODERATION_HIDDEN`, or `MODERATION_REMOVED`; only `ACTIVE` is returned to ordinary users. |
| `comment_count` | Non-negative Community-owned display count, updated transactionally by the future comment slice. |
| `reaction_count` | Non-negative Community-owned display count, updated transactionally with reaction create/remove while the post row is locked so retries and concurrent commands cannot drift the total. |
| `idempotency_key` | Owner-scoped create-command key. It is nullable only for posts that predate MB-575 and unique together with `author_profile_id`, so separate owners may reuse the same client-generated value safely. |
| `request_fingerprint` | SHA-256 digest of normalized create input used to distinguish a safe retry from conflicting reuse; it is not Community content and is present exactly when `idempotency_key` is present. |
| `author_mode` | Author-selected public identity mode for this post: `PROFILE` renders the current Community display identity, while `ANONYMOUS` returns a neutral name with no Community profile identifier or avatar that can link the post publicly; the private owner reference remains available only for authorization, moderation, and audit. |
| `resource_id` | Nullable stable UUID logically referencing a Content-owned published Resource selected by the author. Community stores no Resource body or version and has no cross-database foreign key; current availability and display metadata are resolved through the Content REST contract. |
| `sensitive_content_warning` | Nullable explicit presentation-governance marker. The only V1 value is `SENSITIVE_CONTENT`, selected by the author or an authorized moderator; it is never inferred from sentiment, distress, Care, assessment, Journal/AI, diagnosis, or severity data and does not replace the post lifecycle state. |
| `published_at` | Immutable UTC publication instant used as the primary newest-first cursor key. |
| `updated_at` | UTC instant of the latest persisted owner or moderation change. |
| `version` | Optimistic-lock counter reserved for owner edits/deletion and moderation changes. |

### `public.community_post_topic`

Governed non-diagnostic topic membership used only for explicit user-selected filtering.

| Field | Purpose |
| --- | --- |
| `post_id` | Physical parent post reference; deleting a never-published test/post row cascades its classifications. |
| `topic_code` | Stable v1 category referencing `community_topic`; it never represents diagnosis or severity. Deactivation leaves this historical membership intact. |

### `public.community_topic`

Community-owned governed discovery vocabulary. This catalogue is deliberately separate from Care, assessment, Journal, emotion, severity, and AI data.

| Field | Purpose |
| --- | --- |
| `code` | Stable non-diagnostic v1 category identifier exposed by Community APIs and retained by historical posts. |
| `label` | Governed user-facing Vietnamese label; it is never free-form user input or a clinical diagnosis. |
| `description` | Governed explanatory copy that states the peer-support meaning of the label. |
| `active` | Discovery/write availability controlled by Community governance. Deactivation removes the topic from the active catalogue and new selections without deleting post history. |
| `display_order` | Unique bounded ordering used to render the active catalogue consistently. |
| `created_at` | Immutable UTC instant when the governed catalogue row was seeded. |
| `updated_at` | UTC instant of the latest governance change to label, description, order, or active state. |

### `public.community_comment_reaction`

MB-691 Community-owned supportive reaction to a currently visible active comment
or reply. One composite key serializes the actor/comment outcome without changing
the comment editing version. The same transaction writes the first non-self
`COMMENT_REACTION:<comment-id>:<actor-profile-id>` outbox identity; replacement,
removal and re-add never repeat that logical notification.

| Field | Meaning and necessity |
| --- | --- |
| `comment_id` | Physical comment FK and first composite-key component; cascades on physical comment purge, not soft deletion. |
| `profile_id` | Physical Community profile FK identifying the reacting actor; one reaction per actor/comment and cascade on profile purge. |
| `reaction` | Current supportive value, closed to `SUPPORT`, `RELATE`, `THANK_YOU`; no negative/voting or clinical meaning. |
| `created_at` | UTC insertion time of the current reaction row; a remove/re-add gets a new row but retains first-action outbox dedupe. |
| `updated_at` | UTC last replacement time; duplicate PUT is a no-op and never changes the comment authoring ETag. |

### `public.community_post_reaction`

One owner-scoped supportive reaction per post. This interaction is a Community peer-support fact and is never used as clinical, screening, Journal, SupportPlan, or ranking evidence.

| Field | Purpose |
| --- | --- |
| `post_id` | Physical active Community post target; paired with `profile_id` as the natural idempotency key. |
| `profile_id` | Private Community actor reference used to enforce one reaction per profile without exposing the linked account subject. |
| `reaction` | Current bounded supportive value `SUPPORT`, `RELATE`, or `THANK_YOU`; replacement changes the value without incrementing the post count. |
| `created_at` | Immutable UTC instant when this profile first reacted to the post. |
| `updated_at` | UTC instant when the profile last replaced its reaction; unchanged retries are no-ops. |

### `public.community_post_bookmark`

Private owner-scoped saved-post membership. Only the owning viewer's bookmark state appears in their personalized Community response.

| Field | Purpose |
| --- | --- |
| `post_id` | Physical active Community post target; paired with `profile_id` so repeated PUT/DELETE commands are naturally idempotent. |
| `profile_id` | Private Community owner reference used for authorization and retrieval; bookmarks are never listed or counted for another viewer. |
| `created_at` | Immutable UTC instant when the owner first bookmarked the post; paired with `post_id` for deterministic newest-saved cursor ordering inside that owner’s private collection. |

### `public.community_interaction_outbox`

Community-owned durable publication facts for eligible comments, replies, and first reactions. The table is committed in the same local transaction as the interaction and relayed independently, so Kafka or Notification availability cannot roll back an accepted Community command.

| Field | Purpose |
| --- | --- |
| `id` | Immutable event UUID included as `eventId` in the versioned Community interaction fact. |
| `deduplication_key` | Stable logical interaction identity, unique across the retained outbox, so command retries, reaction replacement, and remove/re-add flows cannot create another notification fact for the same interaction. |
| `target_id` | Community post or parent-comment UUID used as the Kafka partition key without copying content. |
| `event_payload` | Exact minimized `mentalbridge.community.interaction.v1` JSON object. It contains routing identifiers, interaction kind, UTC occurrence time, and safe Community-post deep-link metadata only; post/comment text, media, display identity, email, and Care/Journal/AI/SupportPlan data are prohibited. |
| `occurred_at` | Immutable UTC instant when the eligible Community interaction committed. |
| `published_at` | Nullable UTC Kafka acknowledgement time; null keeps the row eligible for independent relay retry. |
| `attempt_count` | Non-negative number of relay claims, used only for bounded retry backoff and observability. |
| `next_attempt_at` | Nullable UTC lease or retry deadline; null before first claim and after acknowledged publication. |
| `created_at` | Immutable UTC insertion instant, equal to the interaction occurrence time. |

### `public.community_media`

Safe metadata for Community-owned image/video objects. Binary content and provider payloads are not stored in PostgreSQL.

| Field | Purpose |
| --- | --- |
| `id` | Immutable opaque UUID used by the owner-scoped media contract. |
| `owner_profile_id` | Physical Community owner reference used to prevent another profile attaching media. |
| `post_id` | Nullable physical attachment to one post; null while an upload is pending/orphaned or after post detachment. |
| `media_type` | Closed display kind `IMAGE` or `VIDEO`. |
| `state` | Provider-independent lifecycle `PENDING`, `PROCESSING`, `READY`, `REJECTED`, `DELETED`, or `EXPIRED`; only `READY` delivery metadata is returned publicly. |
| `delivery_url` | Signed HTTPS transformed-delivery URL present only for `READY` media. It delivers a metadata-stripped derivative rather than exposing the authenticated original object. |
| `storage_provider` | Nullable provider discriminator. MB-576-created objects use `CLOUDINARY`; the value is cleared after confirmed provider deletion and remains nullable for legacy fixture rows. |
| `storage_key` | Unique provider-side public identifier scoped beneath the Community profile and media UUID. It is not a delivery URL or credential and is cleared only after provider deletion succeeds. |
| `expected_mime_type` | Normalized allowlisted MIME type declared from the browser file before upload and verified against the provider-detected format during finalize. |
| `expected_size_bytes` | Browser-observed byte count used with the media-type limit and exact provider byte count to reject substituted or oversized uploads. |
| `upload_expires_at` | UTC deadline for the signed upload intent; pending objects beyond it are expired by the retention job. |
| `idempotency_key` | Printable caller retry key unique per Community owner so an uncertain intent request returns the original bounded upload authorization. |
| `request_fingerprint` | SHA-256 of normalized file name, media type, MIME type, and declared byte count used to reject conflicting reuse of an upload-intent key. It cannot recover file content. |
| `rejection_reason` | Stable internal reason code present only for `REJECTED` media. Provider payloads and user file content are never stored in this field or returned publicly. |
| `width` | Optional positive pixel width used to reserve truthful layout space. |
| `height` | Optional positive pixel height used to reserve truthful layout space. |
| `duration_seconds` | Optional positive whole-second video duration. |
| `alt_text` | Optional bounded user-facing accessibility text; null when unavailable. |
| `position` | Stable zero-based order within a post, bounded to ten media positions. |
| `created_at` | Immutable UTC metadata creation instant. |
| `updated_at` | UTC instant of the latest processing, attachment, or lifecycle change. |
| `version` | Optimistic-lock counter reserved for media lifecycle commands. |

### `public.community_block`

Community-local bilateral visibility exclusion applied by feed and detail queries without calling Identity, Care, or Journal.

| Field | Purpose |
| --- | --- |
| `blocker_profile_id` | Physical Community profile that chose to block another profile. |
| `blocked_profile_id` | Physical Community profile hidden from the blocker; the database forbids self-blocking. |
| `created_at` | Immutable UTC instant when the block became effective. |

### `public.community_comment`

Authoritative Community-owned comment or one-level reply. Its text is peer-support content and is never Care, Journal, screening, SupportPlan, specialist, or AI evidence.

| Field | Purpose |
| --- | --- |
| `id` | Immutable opaque UUID exposed as the comment identifier for replies and owner commands. |
| `post_id` | Physical parent post reference used to enforce visibility and update the post's active comment count transactionally. |
| `parent_comment_id` | Nullable physical reference to a top-level comment on the same post; non-null identifies a V1 one-level reply and database/application constraints prevent cross-post or deeper nesting. |
| `author_profile_id` | Physical Community display-profile owner used for public identity, bilateral block checks, owner authorization, moderation, and audit without exposing the private account subject. |
| `content` | Current user-authored peer-support text bounded to 2,000 characters and retained inside Community storage after owner deletion for governed audit. |
| `state` | Lifecycle `ACTIVE`, `OWNER_DELETED`, `MODERATION_HIDDEN`, or `MODERATION_REMOVED`; ordinary reads expose active content and a neutral owner-deleted tombstone, while moderation states fail closed. |
| `idempotency_key` | Printable caller retry key unique per Community author, so a repeated create returns the original comment without incrementing counts twice. |
| `request_fingerprint` | SHA-256 digest of normalized post, parent, and content input used to reject conflicting idempotency-key reuse; it cannot recover comment text. |
| `created_at` | Immutable UTC insertion instant and primary chronological pagination key. |
| `updated_at` | UTC instant of the latest persisted owner or moderation change. |
| `version` | Optimistic-lock counter exposed in the comment and quoted ETag for exact `If-Match` edit/delete commands. |

### `public.community_comment_revision`

Immutable Community-local audit snapshots for comment creation and mutation. It is not exposed through the public comment API.

| Field | Purpose |
| --- | --- |
| `id` | Immutable opaque UUID identifying one audit snapshot. |
| `comment_id` | Physical reference to the comment whose historical state is retained. |
| `changed_by_profile_id` | Nullable physical Community profile for an owner-authored mutation; mutually exclusive with `changed_by_subject`. |
| `changed_by_subject` | Nullable private Identity subject for an authorized ADMIN moderation mutation; never exposed publicly and mutually exclusive with `changed_by_profile_id`. |
| `change_type` | Stable action `CREATED`, `EDITED`, `OWNER_DELETED`, or `MODERATED` used by audit and moderation review. |
| `content_snapshot` | Exact Community comment text at the recorded version, retained only for governed audit and moderation compatibility. |
| `state_snapshot` | Comment lifecycle state after the recorded action. |
| `comment_version` | Exact optimistic-lock version represented by the snapshot; unique per comment for deterministic history. |
| `changed_at` | Immutable UTC instant when the recorded change committed. |

### `public.community_report`

Immutable, idempotent user report of one visible post or comment. Reports route content governance only and never become clinical evidence.

| Field | Purpose |
| --- | --- |
| `id` | Immutable report UUID. |
| `reporter_profile_id` | Private Community reporter reference; never returned in an admin case response. |
| `target_type`, `target_id` | Community-owned post/comment reference captured without a polymorphic cross-service foreign key. |
| `reason` | Stable governed report reason, including the non-diagnostic crisis-concern routing reason. |
| `details` | Optional bounded untrusted reporter context, visible only to authorized moderation. |
| `idempotency_key`, `request_fingerprint` | Owner-scoped retry identity and normalized SHA-256 command fingerprint. |
| `created_at` | Immutable accepted-report instant. |

### `public.community_content_hide`

Owner-scoped feed/comment exclusion that does not alter the target or another user's view.

| Field | Purpose |
| --- | --- |
| `hider_profile_id` | Community profile that chose the personal hide. |
| `target_type`, `target_id` | Hidden post/comment identity. |
| `created_at` | Immutable instant at which the personal hide became effective. |

### `public.community_moderation_case`

One Community-local governed case per reported target, with a minimized immutable first-report evidence snapshot.

| Field | Purpose |
| --- | --- |
| `id` | Opaque case UUID exposed only to ADMIN. |
| `target_type`, `target_id` | Exact Community target under review. |
| `target_author_profile_id` | Private author reference used for bounded Community access restriction. |
| `state` | Workflow state `OPEN`, `IN_REVIEW`, or `RESOLVED`. |
| `priority` | `HIGH` only when at least one user selected `SELF_HARM_OR_CRISIS_CONCERN`; it is routing priority, not diagnosis or inferred risk. |
| `evidence_content`, `evidence_state`, `evidence_version` | Minimized target snapshot fixed when the case is first created. |
| `created_at`, `updated_at`, `version` | Creation, latest decision time, and optimistic audit sequence. |

### `public.community_moderation_action`

Append-only ADMIN decision record preserving exact actor, reason, target version, and before/after state.

| Field | Purpose |
| --- | --- |
| `id`, `case_id` | Immutable action UUID and owning moderation case. |
| `actor_subject` | Private ADMIN Identity subject used for accountability. |
| `action`, `reason_code` | Bounded governed decision and stable rationale code; post actions include explicit `APPLY_SENSITIVE_WARNING` and `REMOVE_SENSITIVE_WARNING` without automatic classification. |
| `prior_state`, `resulting_state`, `target_version` | Exact lifecycle or warning transition and resulting target version. Warning actions record `NONE`/`SENSITIVE_CONTENT` and leave the target lifecycle untouched. |
| `idempotency_key`, `request_fingerprint` | ADMIN-scoped retry identity and normalized SHA-256 command fingerprint. |
| `created_at` | Immutable committed-decision instant. |

### `public.community_access_restriction`

Community-only access restriction derived from an auditable case. It does not change Identity, Care, SupportPlan, booking, or third-party contact state.

| Field | Purpose |
| --- | --- |
| `profile_id`, `case_id` | Restricted Community profile and authorizing moderation case. |
| `reason_code` | Stable bounded governance rationale. |
| `restricted_by_subject`, `restricted_at` | ADMIN subject and UTC activation instant. |
| `lifted_by_subject`, `lifted_at` | Nullable paired ADMIN subject/time recording restoration without deleting history. |

## MB-685 direct Community chat — public tables

These tables belong only to `mentalbridge_community`. Liquibase `0017-community-direct-chat` is runtime authority. The canonical logical model is documentation only.

### `public.community_chat_privacy`

| Field | Purpose |
| --- | --- |
| `profile_id` | Community-owned profile key; one preference aggregate per viewer, removed with that profile. |
| `direct_message_policy` | Explicit ANYONE or default APPROVAL_REQUIRED; never auto-accepts a pending request or revokes an accepted conversation. |
| `presence_visible` | Explicit technical-presence opt-in; defaults false, does not assert availability or willingness. |
| `invisible` | Viewer-selected override that suppresses presence even when opted in; defaults false. |
| `typing_visible` | Explicit typing projection opt-in, false until selected. |
| `read_receipts_visible` | Explicit shared-receipt opt-in, independent of private unread state; defaults false. |
| `version` | Public preference replacement revision used by strong If-Match; messages and blocks do not advance it. |
| `visibility_epoch` | Private cursor/revocation epoch advanced on preference/block/restriction changes; never exposed as a counter. |

### `public.community_chat_conversation`

| Field | Purpose |
| --- | --- |
| `id` | Random opaque conversation UUID; a dormant routing UUID grants no public transcript capability. |
| `first_profile_id` | First member of the lexicographically ordered pair; physical Community profile reference. |
| `second_profile_id` | Second distinct member of the ordered pair; the pair is unique even across crossed/concurrent starts. |
| `state` | DORMANT until explicit acceptance or eligible ANYONE initiation; ACTIVE relationships survive later privacy changes while current blocks/restrictions override access. |
| `management_version` | Public relationship-management revision, separate from message activity and private event sequence. |
| `event_version` | Private monotonically increasing outbox aggregate revision, shared by facts of one committed owner mutation; never a public ETag. |
| `last_sequence` | Private server message ordering/watermark allocator; never serialized in public conversations or message DTOs. |
| `created_at` | Immutable UTC microsecond insertion instant for the pair identity. |
| `activated_at` | Nullable UTC instant of the first accepted relationship; present exactly when ACTIVE and used as initial list ordering before any messages. |

### `public.community_chat_request`

| Field | Purpose |
| --- | --- |
| `id` | Stable opaque request UUID; separately retained so terminal decisions can be safely reconciled. |
| `conversation_id` | Physical owner-local pair/routing reference; request creation alone grants neither participant history. |
| `requester_profile_id` | Exact original Community requester, used by recipient projections, per-actor rate limits and permanent decline/block suppression. |
| `recipient_profile_id` | Only this profile may accept, decline or block; no requester-selected actor. |
| `state` | PENDING, ACCEPTED, DECLINED, BLOCKED or EXPIRED; same terminal decision is a no-op, conflicting decisions fail. |
| `version` | Request-specific exact decision revision, independent of conversation or privacy revisions. |
| `created_at` | Immutable UTC request creation; counts genuine new requests against the rolling 24-hour actor limit. |
| `expires_at` | UTC server expiry seven days after creation; elapsed requests cannot be accepted, regardless of stored state. |
| `decided_at` | Nullable terminal transition instant; populated exactly when the request leaves PENDING. Content-free terminal rows preserve anti-spam and retry truth. |
| `decided_by_profile_id` | Nullable exact recipient who explicitly decided; absent for expiry or block/restriction invalidation, so an invalidated request cannot masquerade as a replay of that recipient's command. |

### `public.community_chat_command`

| Field | Purpose |
| --- | --- |
| `id` | Internal random command record UUID, never a user-visible engagement metric. |
| `actor_profile_id` | Verified Community actor; command identity is isolated across owners. |
| `operation` | Closed owner command namespace START_DIRECT or SEND_MESSAGE; prevents cross-operation key reuse. |
| `idempotency_key` | Client UUID unique with actor and operation; uncertain outcomes reconcile without duplicate effects. |
| `request_fingerprint` | Versioned keyed HMAC of normalized command input, never raw message text; old key versions remain available for safe replay. |
| `result_kind` | Bounded ACTIVE/PENDING/MESSAGE pointer discriminator; replay reauthorizes rather than returning a cached transcript. |
| `target_id` | Opaque owner result reference; content-free tombstone prevents an expired/deleted message being recreated by a late retry. |
| `created_at` | Immutable UTC command acceptance; retained while its conversation exists, with deletion handled by owner cleanup. |

### `public.community_chat_outbox`

| Field | Purpose |
| --- | --- |
| `id` | Stable envelope message UUID reused by every relay attempt. |
| `conversation_id` | Owner-local aggregate pointer and Kafka partition key; cascading aggregate erasure removes queued content-free facts. |
| `deduplication_key` | Unique aggregate revision/kind/target/recipient identity, preventing repeated logical facts on retry. |
| `event_payload` | Closed minimized Community chat fact only; excludes text, group name, email, clinical data and credentials. MB-689 adds recipient-self `READ_STATE_CHANGED` with only conversation and routing references when the existing private watermark advances. It carries neither unread count nor read-message ID; Realtime obtains fresh owner state. No new domain column or migration is introduced. |
| `occurred_at` | Owner occurrence instant truncated to UTC microseconds before persistence/serialization. |
| `published_at` | Nullable Kafka acknowledgement instant; only an acknowledged publish marks completion. |
| `attempt_count` | Persisted bounded-backoff attempt number; no volatile offset or process-local retry authority. |
| `next_attempt_at` | Nullable lease/backoff instant for safe concurrent SKIP LOCKED claims and replay after a crashed publisher. |

### `public.community_chat_message`

Community's encrypted text-message authority under Liquibase 0018, extended by
Liquibase 0022 with author retraction. No raw message is placed in Kafka, Redis,
a notification or another owner's database.

| Field | Purpose |
| --- | --- |
| `id` | Random immutable message UUID; no global sequence/time encoding is exposed to clients. |
| `conversation_id` | Physical owner-local conversation reference; aggregate erasure cascades message records. |
| `sender_profile_id` | Server-derived Community sender, never selected by the browser or a socket command. |
| `sender_membership_id` | Immutable Community membership epoch at send time for GROUP messages; null for DIRECT. Owner migration 0019 validates conversation/profile/current membership and exclusive sequence floor when inserting. Retained after departure so other current viewers retain eligible history; not a present-day access grant to the author. |
| `client_message_id` | Caller UUID unique with sender/conversation; duplicate content reconciles one message even across transport retries. |
| `server_sequence` | Private positive per-conversation order allocated in the same transaction as content/facts; never returned in REST/socket DTOs or public versions. |
| `content_ciphertext` | Nullable versioned AES-256-GCM ciphertext with random nonce and exact conversation/message/sender associated data. Null only after retention purge; never plaintext. |
| `content_fingerprint` | Versioned keyed HMAC of normalized target/client/content used only for retry consistency; retains no recoverable message text. |
| `state` | `ACTIVE` or `RETRACTED`. Retraction is an idempotent sender-only transition and cannot return to active. |
| `created_at` | Immutable UTC microsecond save instant; orders eligible activity and enforces cross-device rolling send limits. |
| `expires_at` | Immutable save-time retention cutoff (90 days under accepted policy v1). Content becomes unreadable at this instant, independently of sweep timing. |
| `retracted_at` | Nullable UTC microsecond instant of the first successful sender retraction. A retracted row remains at its original private sequence and never creates unread. |
| `purged_at` | Nullable UTC physical ciphertext-erasure checkpoint; present exactly when ciphertext is null. For retraction it equals `retracted_at`; the content-free identity tombstone prevents retry resurrection. |

### `public.community_chat_read_state`

| Field | Purpose |
| --- | --- |
| `conversation_id` | Physical owner-local conversation key, removed when the conversation is erased. |
| `profile_id` | Exact Community reader; composite primary key isolates devices' shared private unread truth per owner/conversation. |
| `last_read_sequence` | Private monotonic max of authorized exact-message acknowledgements. Not a delivery proof, shared-receipt preference or public counter; retention/visibility changes never decrease it. |
| `cleared_through_sequence` | Private monotonic server-ordered history floor. Messages at or below it are unavailable to this profile across list, history, projection, replay, unread, report and notification authorization. |
| `cleared_at` | UTC microsecond instant of the latest deliberate clear command. It distinguishes a cleared empty view and invalidates cursors when a later clear commits at the same sequence. |

Migration 0023 adds the two personal-clear fields and constrains the private read
watermark to remain at or above the cutoff. The shared conversation and message
rows are unchanged, so another participant's history is never erased.

Migration 0018 also adds `community_chat_command.conversation_id`, a required physical aggregate reference. Existing ACTIVE/PENDING pointers are backfilled inside the owner database; erasing a conversation cascades its retry identities, preventing dangling owner identifiers. It never references another service's table.

## MB-687 private groups (Community, migration 0019)

Conversation now distinguishes DIRECT/GROUP. Nullable pair columns exist only on DIRECT; GROUP carries encrypted private name and a frozen cap. Deferred owner/cap constraints plus owner row locks ensure exactly one active-group OWNER and no over-cap membership.

### `public.community_chat_conversation` — group extension

| Field | Purpose |
| --- | --- |
| `kind` | DIRECT retains ordered pair uniqueness; GROUP is private and membership-authorized. |
| `name_ciphertext` | GROUP-only private name sealed with versioned AES-GCM and exact conversation AAD; never in facts/logs. |
| `member_limit` | GROUP-only 20–30 cap, captured at creation from validated setting; pending invitations reserve no seat. |
| `first_profile_id`, `second_profile_id` | Null only for GROUP; direct profile pair remains required/ordered. |
| `state` | GROUP ACTIVE/ARCHIVED; archived history remains membership/visibility restricted. DIRECT DORMANT/ACTIVE unchanged. |

Command operations also support CREATE_GROUP/INVITE_GROUP; result kinds GROUP/INVITATION. Actor/operation/key fingerprints bind normalized name or exact group/invitee. Group creation command tombstones retain original creator rate/retry identity even after ownership transfer.

### `public.community_chat_membership`

| Field | Purpose |
| --- | --- |
| `id` | Random immutable membership-epoch UUID; a rejoin always creates a new row. |
| `conversation_id` | Community-owned GROUP foreign key, deleted with the conversation. |
| `profile_id` | Owner-local Community identity; one current epoch per group/profile. |
| `role` | OWNER/ADMIN/MEMBER; exactly one OWNER while group is ACTIVE, enforced at commit. |
| `joined_at` | UTC start of explicit accepted membership; used for deterministic member pagination. |
| `joined_after_sequence` | Private exclusive message floor captured on join; prior epochs/history/read references cannot be recovered. |
| `departed_at` | Nullable UTC leave/removal instant; nonnull immediately revokes current rights. |

### `public.community_chat_invitation`

| Field | Purpose |
| --- | --- |
| `id` | Random invitation UUID, public only to invitee/current authorized manager. |
| `conversation_id` | Exact private group foreign key; invitation alone is not membership. |
| `inviter_membership_id` | Exact inviting management epoch; loss of that role/epoch invalidates outstanding invitations. |
| `inviter_profile_id` | Owner-local inviter identity for current role/block checks and per-actor rate limit. |
| `invitee_profile_id` | Exact recipient identity; never selected through a browser actor field. |
| `state` | PENDING/ACCEPTED/DECLINED/REVOKED/EXPIRED; decline permanently suppresses same group/person reinvitation. |
| `version` | Private invitation decision revision; separate from group management If-Match/ETag. |
| `created_at` | Immutable UTC issue instant, owner-clock rate/pagination authority. |
| `expires_at` | Seven-day UTC deadline; late accept/replay cannot create membership. |
| `decided_at` | UTC terminal transition instant, null exactly while pending. |
| `accepted_membership_id` | Exact created epoch for ACCEPTED only; replay cannot recreate an epoch after departure. |

### `public.community_chat_report_case` (MB-687, migration 0020)

Exact readable-message intake is isolated from feed reports and general chat reads. No report provider notification or ADMIN browse endpoint is enabled.

| Field | Purpose |
| --- | --- |
| `id` | Opaque case reference returned only to the reporter; no transcript browse right. |
| `conversation_id` | Logical source UUID, deliberately not a foreign key: deleting source history cannot block or erase isolated purpose-bound report evidence. Not a read grant. |
| `message_id` | Logical exact source UUID without a source-content foreign key; one immutable evidence snapshot survives source purge/deletion. |
| `reporter_profile_id` | JWT-resolved Community reporter; exact actor/message report is naturally unique. |
| `reason_code` | Closed author-selected reason, never inferred diagnosis or distress. |
| `priority` | HIGH only for explicit SELF_HARM_OR_CRISIS_CONCERN; otherwise NORMAL; no monitoring/intervention promise. |
| `state` | OPEN intake only in MB-687. Resolution, auditable ADMIN evidence access and legal-hold handling remain separately delivered. |
| `evidence_ciphertext` | Versioned AES-GCM snapshot of only exact message, sender Community ID and creation time, bound to case UUID; no surrounding transcript. |
| `details_ciphertext` | Optional reporter-authored details encrypted with distinct case purpose; never events/logs. |
| `created_at` | Immutable UTC intake time, rate-limit authority. |
| `review_due_at` | UTC bounded unresolved-review deadline, 30 days after intake; not a deletion/clinical-response SLA. |

Commands additionally support REPORT_MESSAGE/REPORT result, actor-scoped normalized-payload fingerprint and exact case pointer. New-key duplicates return the original actor/message case without copying or replacing evidence.
