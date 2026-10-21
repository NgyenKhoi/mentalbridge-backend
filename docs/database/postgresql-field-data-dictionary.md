# PostgreSQL Field Data Dictionary

This document explains the business purpose of PostgreSQL fields. The [canonical PostgreSQL logical schema](../domain-model/relational/postgresql-logical-schema.sql) is a non-executable documentation model and must never provision or migrate an environment. Names such as `consultation.availability_slot` identify a visual owner namespace; the physical table is `public.availability_slot` in the separate `mentalbridge_consultation` database. Service-owned migration histories are the executable runtime sources of truth: Liquibase for Spring services and the current SQL migration mechanism for Node.js services. This dictionary is written for developers and reviewers; descriptions are intentionally kept out of executable migrations.

When service-owned migrations are introduced, update this dictionary and the canonical logical model in the same change whenever persisted domain structure materially changes. A field description must explain why the value is persisted, whether it is authoritative, derived, external, or sensitive, and how nullability, time, versioning, or idempotency affects behavior. Content/Notification's executable history starts at `content-notification-service/migrations/1_initial_schema.sql`; migration 2 removes the obsolete hotline table, the separate Review 1 migrations insert controlled demo content and its initial item-level eligibility matrix, migration 6 adds immutable Resource Eligibility v1 provenance, migration 7 adds the separately governed reviewed safety directory, migration 8 adds its deterministic reviewed area vocabulary, and migration 14 adds semantic resource metadata plus durable learning, practice, daily-assignment, and weekly-bingo state. The field descriptions under its conceptual owner below describe the resulting physical `public` tables.

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

## Database `mentalbridge_identity` (schema `public`)

### `public.account`

Authoritative login account and lifecycle state owned by Identity Service.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID exposed as the opaque account identifier in REST and Kafka contracts. |
| `email` | Case-insensitive normalized login and recovery address; unique because one address identifies one account. |
| `password_hash` | One-way password hash used for local authentication; plaintext is never persisted. |
| `role_code` | Authoritative immutable actor category `USER`, `SPECIALIST`, or `ADMIN`. Public registration writes only `USER` or `SPECIALIST`; deployment provisioning creates the sole `ADMIN`, and a partial unique index permits at most one. |
| `status` | Authoritative lifecycle state: `PENDING_EMAIL_VERIFICATION`, `ACTIVE`, `DISABLED`, `DELETION_PENDING`, or terminal `DELETED`; temporary credential locking is deliberately separate. |
| `email_verified_at` | UTC instant at which email ownership was verified; null until verification succeeds. |
| `failed_login_count` | Consecutive failed-login counter used by the lockout policy and reset after successful authentication. |
| `locked_until` | UTC end of a temporary authentication lock; null when no timed lock applies. |
| `last_login_at` | UTC instant of the latest successful login for security review and account activity display. |
| `created_at` | Immutable UTC insertion instant for account lifecycle and audit ordering. |
| `updated_at` | UTC instant of the latest persisted account change, maintained by Identity Service. |
| `deleted_at` | UTC soft-deletion/tombstone instant retained to coordinate controlled deletion; null for a live account. |
| `version` | Optimistic-lock counter incremented on account mutations to reject lost updates. |

### `public.role`

Reference catalogue of the mutually exclusive actor roles assigned when an account is created.

| Field | Purpose |
| --- | --- |
| `code` | Stable machine-readable actor role used by `account.role_code`, tokens, and authorization policies. |
| `description` | Human-readable explanation of the permissions and actor represented by the role. |

### `public.refresh_session`

Revocable refresh-token session for one account and client device.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID identifying the refresh session and token-rotation chain. |
| `family_id` | Stable UUID grouping every credential produced by one login rotation chain so replay, logout, and compromise handling can revoke the complete family. |
| `account_id` | Account authorized to refresh access tokens through this session. |
| `token_hash` | Unique lowercase SHA-256 hash of the high-entropy refresh credential; plaintext is returned once and never persisted. |
| `device_label` | Optional user-facing label that helps identify and revoke a device session. |
| `ip_hash` | Privacy-minimized hash of the originating IP used for anomaly and audit correlation. |
| `user_agent_hash` | Privacy-minimized client fingerprint used to detect unexpected session reuse. |
| `expires_at` | UTC instant after which refresh is rejected regardless of revocation state. |
| `rotated_from_id` | Unique predecessor session in the rotation chain; uniqueness makes concurrent rotation produce exactly one successor. |
| `revoked_at` | UTC instant at which the session was invalidated; null while active. |
| `revoke_reason` | Stable reason explaining manual, automatic, rotation, or security revocation. |
| `created_at` | Immutable UTC instant at which the refresh session was issued. |

### `public.one_time_token`

Hashed, expiring token for one approved account recovery or verification purpose.

| Field | Purpose |
| --- | --- |
| `id` | Immutable internal UUID for auditing and consuming the one-time credential. |
| `account_id` | Account whose verification or recovery action the token authorizes. |
| `purpose` | Restricted action for which the token is valid, preventing cross-purpose reuse. |
| `token_hash` | One-way token hash; only the caller holds the plaintext token. |
| `expires_at` | UTC deadline after which the token must be rejected. |
| `consumed_at` | UTC instant of successful one-time use; null until consumed. |
| `invalidated_at` | UTC instant at which replacement or policy invalidated an unused challenge; null while it remains eligible. |
| `created_at` | Immutable UTC issuance instant used for audit and cleanup. |

### `public.idempotency_record`

Bounded replay record owned by Identity for registration and refresh commands. It prevents duplicate state and lets the same logical retry receive the original completed result without persisting token plaintext.

| Field | Purpose |
| --- | --- |
| `id` | Immutable internal UUID identifying the replay record. |
| `operation` | Stable Identity operation scope; the same client key may be used independently for a different operation. |
| `idempotency_key` | Caller-generated retry key unique with `operation`. |
| `request_hash` | Lowercase SHA-256 digest of the canonical request, used to reject reuse of a key with different input. |
| `account_id` | Account affected by the completed command; nullable while registration has not committed and deleted with the account's replay records. |
| `response_status` | Original HTTP status returned on completion; null while the command is in progress. |
| `response_ciphertext` | Encrypted original response needed for exact replay; never plaintext and null while incomplete. |
| `encryption_key_version` | Non-secret key identifier required to decrypt the response during its bounded retention; null while incomplete. |
| `completed_at` | UTC instant when the authoritative outcome was stored; null while the command is in progress. |
| `expires_at` | UTC retention deadline after which the record and encrypted response can be deleted. |
| `created_at` | Immutable UTC instant when Identity first accepted the idempotency key. |

### `public.outbox_event`

Identity-owned transactional outbox. An account/session mutation and its integration fact commit together; a relay publishes only after commit.

| Field | Purpose |
| --- | --- |
| `id` | Immutable message UUID used for Kafka deduplication. |
| `message_type` | Stable language-neutral event name. |
| `schema_version` | Contract version consumers use to validate compatibility. |
| `aggregate_type` | Identity aggregate category used for routing and uniqueness. |
| `aggregate_id` | UUID of the Identity aggregate whose committed change caused the event. |
| `aggregate_version` | Authoritative nonnegative aggregate version used for ordering and stale-event rejection. |
| `correlation_id` | Request/workflow UUID propagated for tracing; it is not a business key. |
| `payload` | Minimal JSON object conforming to the published event schema; it excludes tokens, passwords, and email content. |
| `occurred_at` | UTC instant the business fact occurred. For `identity.user-notification.fact`, truncate to microseconds before saving both this column and JSON `availableAt`; the reloaded relay timestamp must exactly match the payload without PostgreSQL rounding. |
| `published_at` | UTC instant Kafka acknowledged publication; null while pending. |
| `attempt_count` | Nonnegative count of bounded relay attempts. |
| `next_attempt_at` | UTC instant after which a failed relay may retry; null when no delay is scheduled. |
| `created_at` | Immutable UTC insertion instant committed with the aggregate change. |

### `public.platform_report_schedule`

Identity owns at most 50 nondeleted recurring aggregate schedules. ADMIN-only
report history is the approved recipient/delivery target for MB-586; no email,
external URL, sensitive user projection, or second report engine is introduced.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID used by schedule management and occurrence provenance. |
| `report_type` | Supported owner-backed aggregate definition; currently ACCOUNT_ACTIVITY only. |
| `cadence` | DAILY, WEEKLY (Monday), or MONTHLY (day 1), interpreted in the recorded timezone. |
| `timezone`, `local_time` | Valid IANA zone and minute-resolution local wall time; DST gaps shift forward and overlaps use the earlier offset. |
| `period_days` | 1–366 completed UTC calendar days before the scheduled occurrence's UTC date. |
| `recipient_group`, `delivery_target` | Closed approved values ADMIN / ADMIN_REPORT_HISTORY; never a caller-supplied address or URL. |
| `status` | ACTIVE, PAUSED, or soft-deleted DELETED; deletion stops future work and preserves existing jobs. |
| `created_by`, `created_at` | Immutable local ADMIN account reference and UTC creation evidence. |
| `updated_at` | UTC last configuration, pause/resume/delete, or scheduler advancement instant. |
| `next_run_at` | UTC occurrence identity/due index; creation, update, and resume select a strictly future occurrence. |
| `last_failure_code` | Nullable minimized reason; ADMIN_ACCESS_UNAVAILABLE pauses generation when the owner account loses access. |
| `version` | Optimistic revision; stale update/delete fails with HTTP 412. |

### `public.platform_report_job`

Immutable-scope asynchronous aggregate report request coordinated by Identity. Only an ADMIN may create or browse these jobs; each job is tied to one versioned owner projection and never reads another service database.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID for report history, retry provenance, and artifact lookup. |
| `report_type`, `scope_version` | Supported aggregate definition and deterministic output schema version. |
| `period_start`, `period_end` | Inclusive UTC calendar-day scope, bounded to at most 366 days. |
| `requested_by`, `requested_at` | ADMIN subject and immutable request instant. |
| `status` | Durable asynchronous state `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, or `STALE`. |
| `source_versions` | JSON object of exact authoritative source/projection versions captured at request time. |
| `idempotency_key`, `request_hash` | ADMIN-scoped retry key and normalized SHA-256 request identity. |
| `schedule_id`, `scheduled_for` | Nullable together for manual jobs; schedule FK and exact UTC occurrence instant form a unique atomic deduplication key. Original scheduled jobs remain linked after soft deletion; retry_of preserves retry lineage. |
| `retry_of` | Nullable prior failed/stale job; retries create a new job and preserve the original outcome. |
| `started_at`, `completed_at`, `failed_at`, `failure_code` | Explicit processing and terminal provenance without sensitive failure payloads. |

### `public.platform_report_artifact`

Immutable, bounded-retention aggregate artifact for one completed platform report. Content is limited to one MiB and contains no raw Journal, assessment, chat, private-note, or provider payload fields.

| Field | Purpose |
| --- | --- |
| `report_job_id` | Completed job identity and one-to-one artifact key. |
| `media_type`, `file_name` | Download representation and safe server-defined filename. |
| `content` | Generated aggregate JSON bytes; deleted after the retention deadline. |
| `content_sha256`, `content_length` | Integrity digest and bounded byte size. |
| `generated_at`, `retained_until` | Immutable generation provenance and exclusive download deadline. |

### `public.security_audit_event`

Privacy-minimized local security record for authentication, recovery, replay, and account-administration decisions. It contains stable facts, never credentials, provider payloads, or free text.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID identifying the audit fact. |
| `account_id` | Affected account when known; nullable for enumeration-safe failures and retained as null after account deletion. |
| `actor_id` | Authenticated actor responsible for an administrative action; nullable for guests/system actions and after actor deletion. |
| `actor_type` | Restricted actor category (`ADMIN`, `SYSTEM`). |
| `actor_reference_hash` | Privacy-minimized 64-character SHA-256 reference for an administrative actor account UUID, retained after account deletion so reads return a safe tombstone identifier rather than leaking deleted account details or misattributing to SYSTEM. |
| `action` | Stable security action code such as login, password recovery, or account-state change. |
| `outcome` | Restricted result `SUCCEEDED`, `DENIED`, or `FAILED`. |
| `reason_code` | Optional stable machine-readable explanation without sensitive free text. |
| `correlation_id` | Request/workflow UUID used to join safe operational evidence. |
| `subject_reference_hash` | Privacy-minimized 64-character SHA-256 reference for a known target UUID, or a bounded hash supplied by an enumeration-safe flow. It remains after account deletion so administration audit reads return a tombstone instead of historical profile data. |
| `source_service` | Platform service that produced the audit fact (`IDENTITY`, `CONSULTATION`, `CONTENT`, `COMMUNITY`). |
| `domain` | Administrative bounded domain (`ACCOUNT_ADMINISTRATION`, `SPECIALIST_REVIEW`, `RESOURCE_MANAGEMENT`, `COMMUNITY_MODERATION`). |
| `occurred_at` | UTC instant the security decision occurred. |
| `created_at` | Immutable UTC insertion instant. |

Indexes `ix_security_audit_filter`, `ix_security_audit_subject_reference`, `ix_security_audit_service_domain`, `ix_security_audit_actor_reference`, and `ix_security_audit_actor_type` support bounded action/result/time, tombstone-target, multi-service/domain, and actor queries. The administration API applies a 365-day read-retention boundary and a maximum 90-day query window; physical archival or deletion remains an operational retention job outside request processing.

## Owner `care` (`mentalbridge_care.public`)

The MB-88 assessment/profile tables and MB-271 support-routing tables below are executable Liquibase-owned structures in the Care database's default `public` schema. Later intervention, grant, and follow-up entries in this section remain conceptual until their owner migrations are added.

### `public.user_profile`

Care-owned non-credential user profile. Locale, timezone, and reminders are fixed release defaults rather than editable preferences.

| Field | Purpose |
| --- | --- |
| `account_id` | External Identity account UUID and immutable profile identifier; it does not prove current account access. |
| `display_name` | User-controlled name displayed in permitted product contexts. |
| `date_of_birth` | Optional birth date used only for approved age-related eligibility or personalization rules. |
| `gender` | Optional self-described gender value used only where product policy permits. |
| `locale` | Fixed to `vi-VN` in the current release for translated questionnaires, resources, and messages. |
| `timezone` | Fixed to `Asia/Ho_Chi_Minh` in the current release; no reminder scheduling is implied. |
| `reminder_enabled` | Fixed to `false`; reminder delivery is not implemented in the current release. |
| `created_at` | Immutable UTC profile creation instant. |
| `updated_at` | UTC instant of the latest persisted profile change, maintained by Care Service. |
| `version` | Optimistic-lock counter incremented on concurrent profile mutations. |

### `public.consent_decision`

Append-only evidence of a user grant or refusal for a versioned platform consent.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID used to cite this exact consent decision in audit and REST results. |
| `user_id` | Care-owned user profile that made the consent decision. |
| `consent_type` | Stable independent platform-consent category. Runtime accepts `PRIVACY_POLICY` and `AI_PROCESSING` as separate decision streams; research/marketing remain unavailable, and specialist access uses a separate scoped grant. |
| `policy_version` | Exact approved policy text/version accepted or refused so the decision remains reproducible. |
| `granted` | Authoritative decision value; false records an explicit refusal or withdrawal. |
| `evidence` | Minimized JSON object such as approved channel or document hash; never raw health content. |
| `idempotency_key` | Required retry key unique for one user and consent type so an uncertain retry returns the original decision. |
| `request_hash` | Lowercase SHA-256 digest used to reject reuse of the key with different consent content; request plaintext is not recoverable from it. |
| `decided_at` | UTC instant at which the user made the decision. |
| `created_at` | Immutable UTC insertion instant for storage and audit ordering. |

### `public.anonymous_assessment_session`

Short-lived isolated authorization context for a guest assessment. The table intentionally has no user/account/claim field, so registration cannot silently attach its result.

| Field | Purpose |
| --- | --- |
| `id` | Immutable opaque session UUID used only together with the bearer token. |
| `token_hash` | Unique lowercase SHA-256 hash of the high-entropy session token; the plaintext token is returned once and never persisted. |
| `expires_at` | Effective UTC access deadline: 30 minutes after the latest valid activity, capped at two hours after `created_at` for controlled Capstone use. |
| `closed_at` | Optional UTC instant the session was invalidated before expiry. |
| `created_at` | Immutable UTC creation instant used to validate the expiry interval. |

### `public.questionnaire_definition`

Immutable versioned PHQ-9 or GAD-7 questionnaire definition and scoring identity.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID referenced by questions and submissions to preserve the exact instrument version. |
| `instrument` | Validated questionnaire family whose scoring rules apply. |
| `version` | Published content version distinguishing wording or item-set changes. |
| `locale` | BCP 47 locale of the question wording. |
| `title` | Reviewed user-facing questionnaire title. |
| `reference_period_days` | Number of preceding days the questionnaire asks the respondent to consider. |
| `expected_question_count` | Exact item count required for completion; constrained to nine for PHQ-9 and seven for GAD-7. |
| `scoring_version` | Deterministic scoring algorithm version needed to reproduce the stored result. |
| `response_options` | Reviewed four-element JSON array mapping values 0 through 3 to localized labels. |
| `source_reference` | Bibliographic or controlled-content provenance needed to audit wording and scoring. |
| `status` | Publication lifecycle controlling whether new submissions may use this definition. |
| `published_at` | UTC instant the immutable definition became available; null while draft. |
| `created_at` | Immutable UTC creation instant for the definition record. |

Published seed versions are `phq9-en-us-v1`, `phq9-vi-vn-capstone-v2`, and `gad7-vi-vn-adult-v1`. `phq9-vi-vn-capstone-v1` is retained as an immutable retired definition for historical submissions. The GAD-7 row records the UNC Vietnam 2024 artifact URI, retrieval date, SHA-256 checksum, self-administered `0..3` mapping, excluded interviewer-only codes, and scoring citation. The PHQ-9 v2 row records the Product Owner-approved Q2 correction without claiming that wording is verbatim from the archived SBIRT artifact. Production review creates a new immutable publication decision/version; it never rewrites this evidence.

### `public.questionnaire_question`

Version-owned questionnaire item with safety-path metadata.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID referenced by assessment answers. |
| `definition_id` | Questionnaire definition that owns the wording, order, and scoring context. |
| `item_number` | Reviewed one-based display/scoring order unique inside a definition. |
| `prompt` | Reviewed localized question wording presented to the user. |
| `safety_item` | Marks an item whose positive response is preserved independently of the total score; exact user guidance remains policy-owned. |
| `created_at` | Immutable UTC insertion instant for provenance. |

### `public.questionnaire_score_band`

Version-owned score range used by server scoring to derive a non-diagnostic screening level.

| Field | Purpose |
| --- | --- |
| `definition_id` | Exact questionnaire definition whose scoring ranges own this row. |
| `code` | Stable non-diagnostic screening-level code returned by the Care contract. |
| `minimum_score` | Inclusive lower integer bound for the band. |
| `maximum_score` | Inclusive upper integer bound for the band. |
| `ordinal` | Positive display/severity order unique within one definition. |

### `public.assessment_submission`

Immutable accepted screening envelope for exactly one authenticated profile or anonymous session. Server-computed fields are stored in `assessment_result`.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID returned by REST and referenced by later support and follow-up decisions. |
| `user_id` | Authenticated Care profile owner; null for an anonymous screening. |
| `anonymous_session_id` | Care-owned short-lived session identifier; null for an authenticated submission and never accompanied by a user ID. |
| `definition_id` | Exact questionnaire definition used to validate and score all answers. |
| `privacy_policy_version` | Exact backend-published disclosure/consent version used for this immutable submission. New PHQ-9/GAD-7 rows use `privacy-capstone-v3`; historical v1/v2 values are retained without backfill, and `legacy-pre-mb178` identifies foundation rows created before the MB-178 gate and must not be presented as Capstone consent. |
| `idempotency_key` | Required retry key unique per authenticated user or anonymous session so the logical submission is persisted once. |
| `request_hash` | Lowercase SHA-256 digest of the canonical definition-and-answer request; it detects conflicting retries without logging answers. |
| `submitted_at` | UTC instant the complete validated assessment was accepted. |
| `retention_expires_at` | Required UTC deletion/access deadline for anonymous data and null for authenticated history; its duration remains policy-owned. |
| `voided_at` | UTC instant an invalidated submission stopped being used; null while authoritative. |
| `void_reason_code` | Stable machine-readable reason required when voided; unrestricted sensitive explanation is not stored. |
| `created_at` | Immutable UTC database insertion instant. |

### `public.assessment_answer`

Immutable server-validated item answers belonging to one assessment submission.

| Field | Purpose |
| --- | --- |
| `submission_id` | Assessment submission that owns and contextualizes the answer. |
| `definition_id` | Redundant exact definition key used in composite foreign keys so a question from another version cannot be attached. |
| `question_id` | Exact versioned questionnaire item answered. |
| `answer_value` | Validated integer response in the instrument range 0 through 3 used for deterministic scoring. |

### `public.assessment_result`

One authoritative server-owned scoring result for an accepted submission. Clients never supply these fields.

| Field | Purpose |
| --- | --- |
| `submission_id` | One-to-one assessment submission whose complete validated answers produced the result. |
| `total_score` | Server-computed integer sum constrained to the supported range 0 through 27. |
| `screening_level` | Non-diagnostic score band selected from the definition's versioned ranges. |
| `scoring_version` | Exact deterministic algorithm version needed to reproduce the score and band. |
| `safety_item_positive` | Nullable questionnaire-specific derived fact. PHQ-9 stores whether item 9 met its positive rule; GAD-7 stores null because it has no equivalent safety item. |
| `safety_status` | Questionnaire-specific safety result. New PHQ-9 rows store `NEGATIVE_SAFETY_SCREEN` or `POSITIVE_SAFETY_SCREEN`; GAD-7 stores `NOT_APPLICABLE`. Null is reserved only for pre-policy foundation rows. |
| `safety_policy_version` | Exact approved safety policy for PHQ-9. It is null for GAD-7 because no item-9-equivalent policy is evaluated, and null with `safety_status` is reserved for pre-policy foundation rows. |
| `disclaimer_code` | Stable `SCREENING_NOT_DIAGNOSIS` presentation key required for every result. |
| `calculated_at` | UTC instant Care completed deterministic scoring. |
| `created_at` | Immutable UTC insertion instant for persistence provenance. |

### `public.outbox_event`

Care-owned transactional outbox row inserted in the same local transaction as an aggregate change. Payloads are minimal JSON objects and never include raw assessment answers.

| Field | Purpose |
| --- | --- |
| `id` | Immutable message UUID used for at-least-once publication and consumer deduplication. |
| `message_type` | Stable language-neutral integration fact name. |
| `schema_version` | Version of the root JSON Schema contract that validates the published message. |
| `aggregate_type` | Stable Care aggregate category that caused the event. |
| `aggregate_id` | Immutable Care aggregate UUID used as the Kafka key and event subject. |
| `aggregate_version` | Non-negative owner version used to distinguish aggregate changes and reject duplicate event creation. |
| `correlation_id` | Required workflow trace UUID propagated without sensitive payload data. |
| `payload` | Minimized JSON object conforming to the future event contract; assessment answer content is prohibited. |
| `occurred_at` | UTC instant the domain fact occurred. For `care.user-notification.fact`, truncate to microseconds before saving both this column and JSON `availableAt`; the reloaded relay timestamp must exactly match the payload without PostgreSQL rounding. |
| `published_at` | UTC broker-acknowledged publication instant; null while pending. |
| `attempt_count` | Non-negative relay attempt count used for bounded retry observability. |
| `next_attempt_at` | Optional UTC instant before which the relay must not retry. |
| `created_at` | Immutable UTC database insertion instant. |

### `public.support_policy_definition`

Immutable publication record for a locale-specific deterministic routing policy.

| Field | Purpose |
| --- | --- |
| `version` | Stable policy identifier retained by every evaluation. |
| `locale` | Reviewed BCP 47 content locale. |
| `status` | `DRAFT`, `PUBLISHED`, or `RETIRED`; at most one published version per locale. |
| `reviewed_by` / `approved_at` | Accountable review provenance and UTC decision instant. |
| `source_reference` | Traceable Story/policy/ADR sources. |
| `created_at` | Immutable database creation instant. |

### `public.support_policy_eligible_definition`

Exact allow-list linking a support policy to compatible immutable questionnaire and scoring versions. This prevents an implicit “latest” lookup or silent cross-version interpretation.

### `public.screening_band_meaning`

Policy-, instrument-, and band-specific Vietnamese meaning. The rows are authoritative Care reference data used to explain a completed screening without changing its score, band, safety result, or support pathway.

- `meaning_code`: Stable machine-readable key for the instrument and band so historical results resolve the intended explanation independently of display wording.
- `content_version`: Immutable copy version used to reproduce the explanation shown for a result. Migration `017-screening-meaning-copy.sql` moves the current controlled-demo rows to `mb-screening-meaning-vi-vn-v2` without changing table structure or prior assessment facts.
- `reference_period_days`: Instrument reference window in days; currently `14` for PHQ-9 and GAD-7 meanings.
- `meaning_text`: Reviewed Vietnamese explanation of what the instrument-specific band reflects over its reference period; it is descriptive and not a diagnosis.
- `limitation_text`: Reviewed Vietnamese boundary explaining that the screening is limited, non-diagnostic, and does not replace professional evaluation.

### `public.support_tier_guidance`

One bounded, versioned next step per support tier. `safety_guidance_text` is required only for `SAFETY_FOLLOW_UP_RECOMMENDED`; no row authorizes automatic contact, booking, sharing, or intervention.

### `public.support_evaluation`

Immutable versioned platform support-tier result derived from one explicit compatible PHQ-9/GAD-7 pair, distinct from diagnosis.

This section describes the executable `mb-support-routing-capstone-v1` history. Its separate assessment references preserve instrument-specific evidence; it has no global severity field. The coarse tier and existing reason columns are not sufficient to select a resource or SupportPlan. Issue #48 therefore adds separate v2 tables below without backfilling or reinterpreting these rows.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID identifying this reproducible support-policy execution. |
| `user_id` | Care profile for whom the platform support tier was calculated. |
| `phq9_assessment_id` / `gad7_assessment_id` | Exact owned evidence pair; database constraints require two distinct IDs. |
| `support_tier` | Authoritative approved support pathway; values never claim low, medium, or high suicide risk. |
| `policy_version` | Exact deterministic policy version needed to reproduce and audit the decision. |
| `primary_reason_code` / `secondary_reason_code` | Stable ordered explanation. The secondary reason is allowed only for the PHQ-then-GAD moderate-or-higher pair. |
| `evaluated_at` | UTC instant the deterministic policy executed. |
| `created_at` | Immutable UTC insertion instant for provenance. |

### `public.support_evaluation_request`

Per-user idempotency aliases for combined-support commands. Multiple keys may safely resolve to the same immutable evidence-pair evaluation, while reuse of any key with different evidence is rejected.

| Field | Purpose |
| --- | --- |
| `user_id` | Care profile that owns both the command key and referenced evaluation. |
| `idempotency_key` | Caller-generated retry key unique for one user. |
| `request_hash` | Lowercase SHA-256 digest of the canonical PHQ-9/GAD-7 evidence pair; request plaintext is not recoverable from it. |
| `support_evaluation_id` | Immutable evaluation returned for this key; the composite foreign key prevents cross-owner aliases. |
| `created_at` | Immutable UTC instant when Care accepted the command key. |

### `public.support_evaluation_v2_policy_definition`

Immutable Care-owned publication record for the additive domain-aware policy.
The partial unique index permits only one `PUBLISHED` v2 policy per locale and
does not retire or modify the separate v1 policy table.

| Field | Purpose |
| --- | --- |
| `version` | Immutable machine-readable policy identity; the controlled publication is `mb-support-routing-capstone-v2`. |
| `locale` | BCP 47 locale whose questionnaire/domain mapping was reviewed. |
| `status` | Publication state `DRAFT`, `PUBLISHED`, or `RETIRED`; only the published row accepts new evaluations. |
| `reviewed_by` | Accountable review description retained for policy provenance, not an authorization decision at request time. |
| `approved_at` | UTC decision instant for this immutable policy publication. |
| `source_reference` | Traceable issue and accepted ADR identifiers used to reproduce the decision. |
| `created_at` | Immutable UTC database insertion instant. |

### `public.support_evaluation_v2_eligible_definition`

Exact questionnaire/scoring/domain allow-list for v2. The instrument/domain
constraint permits only PHQ-9/`DEPRESSIVE_SYMPTOMS` and
GAD-7/`ANXIETY_SYMPTOMS`; no implicit latest definition or enum-only domain
extension is allowed.

| Field | Purpose |
| --- | --- |
| `policy_version` | Owning immutable v2 policy version. |
| `definition_id` | Exact Care-owned questionnaire definition accepted as evidence. |
| `instrument` | `PHQ9` or `GAD7`, constrained together with its approved domain. |
| `questionnaire_version` | Exact external questionnaire version copied for compatibility validation. |
| `scoring_version` | Exact deterministic score-band policy required from the result. |
| `domain` | Approved screening domain contributed by this instrument; never a global mental-health classification. |

### `public.support_evaluation_v2`

Immutable aggregate root for one explicit compatible PHQ-9/GAD-7 pair. This
table is additive and has no foreign key, update path, or backfill involving
`public.support_evaluation`. Owner/evidence composite foreign keys prevent an
evaluation from referencing another user's assessment.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID exposed by the v2 REST resource and integration event. |
| `user_id` | Care profile that owns the command, both evidence rows, and every read. |
| `phq9_assessment_id` | Exact owned PHQ-9 result supplying depression-domain and item-9 safety evidence. |
| `gad7_assessment_id` | Exact owned GAD-7 result supplying anxiety-domain evidence; must differ from the PHQ-9 ID. |
| `policy_version` | Exact domain-aware policy version used; included in evidence-pair uniqueness for reproducible re-evaluation under a future version. |
| `evaluated_at` | UTC instant at which Care executed the local deterministic policy. |
| `created_at` | Immutable UTC insertion instant; not a substitute for `evaluated_at`. |

The owner/evidence/policy unique constraint makes different idempotency keys for
the same decision resolve to one aggregate. The owner-history index supports
deterministic descending history by evaluation instant and UUID tie-breaker.

### `public.support_evaluation_v2_domain`

Exactly two immutable contribution snapshots are inserted with the aggregate.
Unique ordinal, instrument, and domain constraints prevent duplicate or
collapsed contributions. Database checks bind PHQ-9 to ordinal 1 and
`DEPRESSIVE_SYMPTOMS`, GAD-7 to ordinal 2 and `ANXIETY_SYMPTOMS`, every level to
its stable reason, and each minimal/mild versus moderate-or-higher level to its
domain-local pathway.

| Field | Purpose |
| --- | --- |
| `id` | Immutable internal UUID for one domain snapshot. |
| `support_evaluation_id` | Owning v2 aggregate; deletion is restricted to preserve history. |
| `ordinal` | Stable presentation/event order: PHQ-9 first and GAD-7 second. |
| `assessment_id` | Exact assessment evidence captured by this contribution. |
| `definition_id` | Exact questionnaire definition paired with the assessment by a composite foreign key. |
| `instrument` | Instrument whose band has meaning; constrained to the matching domain and ordinal. |
| `domain` | Independent approved screening domain used by downstream family composition. |
| `questionnaire_version` | Immutable questionnaire-version snapshot so later reference-data changes cannot alter history. |
| `scoring_version` | Immutable score-band policy snapshot from the assessment result. |
| `screening_level` | Instrument-specific band; never a global severity. GAD-7 cannot contain `MODERATELY_SEVERE`. |
| `support_pathway` | Domain-local `SELF_GUIDED_SUPPORT` or `PROFESSIONAL_SUPPORT_RECOMMENDED`; safety never overwrites it. |
| `reason_code` | Stable instrument-and-level explanation such as `PHQ9_LEVEL_MILD`; unrestricted sensitive text is not stored. |

### `public.support_evaluation_v2_safety`

One independent PHQ-9 item-9 safety snapshot per v2 aggregate. The composite
foreign key requires the source to equal the aggregate's PHQ-9 assessment.
No raw answer value, total score, intent, plan, imminence, or urgency label is
stored.

| Field | Purpose |
| --- | --- |
| `support_evaluation_id` | Aggregate identity and one-to-one primary key. |
| `source_assessment_id` | Exact PHQ-9 evidence reference; constrained to the aggregate's PHQ-9 ID. |
| `instrument` | Constant `PHQ9`, making the safety source explicit. |
| `trigger_code` | Constant `PHQ9_ITEM_9`; not a separate screening domain. |
| `safety_status` | Exact `NEGATIVE_SAFETY_SCREEN` or `POSITIVE_SAFETY_SCREEN` result retained independently of both bands. |
| `safety_policy_version` | Exact deterministic item-9 policy version used by the source result. |
| `reason_code` | Stable `PHQ9_ITEM9_NEGATIVE` or `PHQ9_ITEM9_POSITIVE`, constrained to match the status. |

### `public.support_evaluation_v2_request`

Per-user v2 idempotency namespace. It is intentionally separate from the v1
request table so the same caller key cannot alias resources with different
contract semantics.

| Field | Purpose |
| --- | --- |
| `user_id` | Owner scope for the retry key and returned aggregate. |
| `idempotency_key` | Caller-generated printable key, 16-128 characters, unique per v2 owner. |
| `request_hash` | Lowercase SHA-256 of the canonical v2 version and ordered PHQ-9/GAD-7 identifiers; original request data is not recoverable. |
| `support_evaluation_id` | Immutable v2 outcome; composite foreign key prevents a cross-owner alias. |
| `created_at` | Immutable UTC instant when Care accepted this v2 key. |

### `public.screening_episode`

Care-owned grouping and resume context for one guided `INITIAL_CHECK` or
`REASSESSMENT`. It prevents session cookies or a changing “latest assessment”
query from choosing SupportPlan evidence. Standalone assessment history is not
attached implicitly.

| Field | Purpose |
| --- | --- |
| `id` / `user_id` | Opaque episode identifier and authenticated Care owner. |
| `purpose` | Exact guided journey: `INITIAL_CHECK` or `REASSESSMENT`. |
| `status` | `IN_PROGRESS`, `READY` after both instruments, or `COMPLETED` after exact evaluation. |
| `phq9_assessment_id` / `gad7_assessment_id` | Owner-matched assessment pair submitted inside this episode. |
| `support_evaluation_id` | Exact immutable v2 SupportEvaluation consumed by Support Guide/SupportPlan policy. |
| `presentation_evaluation_id` | Optional v1 presentation-compatible evaluation used by the current result UI; it has no SupportPlan authority. |
| `created_at` / `updated_at` / `completed_at` | Resume, ordering, and completion instants in UTC. |
| `version` | Optimistic aggregate version. |

A partial unique index permits at most one `IN_PROGRESS` or `READY` episode per
owner and purpose. Completed episodes remain immutable history, and a new
guided run creates a new episode.

### `public.support_guide`

Immutable one-time MB-511 guidance owned by Care. It is deliberately not a
SupportPlan aggregate and has no draft/active lifecycle, activities, adherence,
reminders, or treatment fields.

| Field | Purpose |
| --- | --- |
| `id` | Immutable guide UUID exposed by owner-only APIs. |
| `user_id` | Care profile owner; participates in composite owner foreign keys. |
| `support_evaluation_id` | Exact owner-matched v2 SupportEvaluation provenance. |
| `guide_policy_version` | Immutable generation policy, fixed to `mb-support-guide-capstone-v1`. |
| `generated_at` | UTC generation instant used for stable history ordering. |
| `explanation_code` / `explanation_text` | Approved non-diagnostic standard explanation snapshot. |
| `safety_status` / `safety_reason_code` / `safety_policy_version` | Synchronous Care-authoritative PHQ-9 item-9 safety provenance. |
| `safety_guidance_code` / `safety_guidance` | Locally available approved safety copy; never dependent on Content or AI. |
| `resource_status` | Stable `AVAILABLE`, `PARTIAL`, `EMPTY`, `STALE`, or `UNAVAILABLE` generation outcome. |
| `resource_policy_version` / `resources_resolved_at` | Exact Content eligibility decision provenance. |
| `phrasing_status` | `STANDARD` or `AI_UNAVAILABLE_FALLBACK`; neither has decision authority. |
| `created_at` | Database insertion instant. |

### `public.support_guide_resource`

Ordered immutable snapshot of at most four exact reviewed resources selected at
guide generation. History reads this snapshot and does not silently re-resolve
current Content state.

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable row UUID. |
| `support_guide_id` / `ordinal` | Parent guide and unique 1-based display order, constrained to 1..4. |
| `resource_id` / `content_version` / `publication_id` | Exact Content resource and eligibility-publication provenance. |
| `domain` / `eligibility_role` | Exact approved domain and `PRIMARY`/`ADJUNCT` role used at generation. |
| `category` / `title` / `summary` / `external_url` | Reviewed display snapshot returned by Content; URL remains optional. |

### `public.support_guide_request`

Owner-scoped idempotency record for guide generation. It stores only a
SHA-256 request fingerprint and the resulting guide reference, never raw
assessment answers or scores.

| Field | Purpose |
| --- | --- |
| `user_id` / `idempotency_key` | Owner and printable 16-128 character retry namespace. |
| `request_hash` | SHA-256 of the ordered exact PHQ-9/GAD-7 assessment references. |
| `support_guide_id` | Owner-matched immutable replay result. |
| `created_at` | UTC instant the key was accepted. |

### `public.support_plan_personalization`

Immutable Care-owned revisions of optional, non-diagnostic user preferences
used only to shape policy-admitted SupportPlan choices and defaults. Screening
evidence remains in its own aggregates. A revision with no selected field is an
explicit `SKIPPED` fact rather than inferred preference data.

| Field | Purpose |
| --- | --- |
| `id` | Immutable revision UUID exposed only as bounded provenance. |
| `user_id` / `revision` | Owner and strictly increasing owner-local revision; profile locking serializes creation. |
| `schema_version` | Exact input vocabulary, fixed initially to `support-plan-personalization-v1`. |
| `status` | `PROVIDED` when any bounded field is present or `SKIPPED` when the user submits none. |
| `preferred_focus` | Optional non-diagnostic `ANXIETY`, `MOOD`, `SLEEP`, `MOTIVATION`, or `UNDERSTANDING_EMOTIONS` intent. |
| `desired_pace` | Optional practical `GENTLE`, `BALANCED`, or `ACTIVE` workload preference. |
| `daily_time_budget_minutes` | Optional approximate bounded budget of 5, 10, 15, 20, or 30 minutes. |
| `preferred_daypart` | Optional `MORNING`, `AFTERNOON`, `EVENING`, or `FLEXIBLE` defaulting preference. |
| `submitted_at` | UTC instant the user submitted this exact immutable revision. |

### `public.support_plan_personalization_activity_mode`

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable child UUID. |
| `personalization_id` / `ordinal` | Parent revision and stable user-selected order. |
| `activity_mode` | Unique bounded mode: `READING`, `GUIDED_PRACTICE`, `JOURNALING`, `EMOTION_CHECK_IN`, or `ROUTINE`. |

### `public.support_plan_personalization_barrier`

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable child UUID. |
| `personalization_id` / `ordinal` | Parent revision and stable user-selected order. |
| `barrier` | Unique bounded practical barrier: `LOW_ENERGY`, `LIMITED_TIME`, or `DIFFICULT_TO_START`; never a diagnosis. |

### `public.support_plan`

Care-owned runtime aggregate for the supported v2 Resource-slot model and the
native v3 cross-domain action model. Existing v2 rows and history remain in
place; v3 is an explicit model branch rather than a backfill or silent rewrite.
User-confirmed lifecycle transitions retain immutable terminal history. Separate
partial unique indexes on `DRAFT` and on `ACTIVE`/`PAUSED` are the final
concurrent guards for one draft and one official current plan per owner.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID exposed by the owner API and used by child snapshots. |
| `user_id` | Care profile owner; locked during creation and never accepted from a client payload. |
| `support_evaluation_id` | Exact owner-matched immutable SupportEvaluation v2 that seeded composition. |
| `status` | Care-owned `DRAFT`, `ACTIVE`, `PAUSED`, `COMPLETED`, `SUPERSEDED`, or `DISCARDED` lifecycle state. |
| `version` | Optimistic-lock counter returned in the ETag; incremented by each accepted choice or activation command. |
| `model_version` | Explicit persisted model discriminator: `2` for Resource slots or `3` for native focuses/actions. It participates in the v3 child foreign key so a focus cannot attach to a v2 plan. |
| `plan_policy_version` | Native v3 composition policy reference, required and bounded for model 3; null for v2. |
| `cycle_timezone` | Nullable IANA timezone snapshotted from proposal-v1 draft schedules. It stays fixed through activation, pauses and continuation; all older-policy/v2 plans keep null. |
| `cycle_number` | Nullable positive proposal cycle number, initially 1. Only explicit review-due `CYCLE_CONTINUE` increments it atomically with the plan version and new reassessment schedule. |
| `cycle_started_on` | Nullable activation local date for the current proposal cycle. Null in a draft; explicit continuation replaces it with today's local date without changing plan activation history. |
| `cycle_paused_days` | Nullable nonnegative accumulated whole local-date pause intervals for the current proposal cycle. Resume or terminal closure accumulates the current interval; continuation resets it to zero. |
| `cycle_paused_on` | Nullable local start date of an ongoing pause. Read-time cycle math adds elapsed local days without writing; resume/terminal closure clears it. No health text is stored. |
| `action_count` | Native v3 action count, constrained to 1..20; fixed to zero for v2. |
| `evaluation_policy_version` / `evaluated_at` | Exact current-compatible Care evaluation policy and original UTC evaluation instant. |
| `selection_policy_version` | Deterministic v2 Care composition version, fixed to `mb-support-plan-selection-v1`; null for v3. |
| `resource_policy_version` / `resources_resolved_at` | Exact v2 Content eligibility policy and UTC resolution instant used before the Care transaction; both null for v3. |
| `personalization_id` / `personalization_revision` / `personalization_schema_version` | Optional exact immutable preference revision snapshotted when the plan draft was composed. Later preference changes never rewrite this reference or the active plan. |
| `entitlement_package` / `entitlement_source` | Authoritative `PLUS`/`PREMIUM` and `DEMO`/`PAID` provenance returned by Consultation; never inferred from JWT/client state. |
| `entitlement_policy_version` / `entitlement_version` / `entitlement_decided_at` | Exact Consultation read-model version and UTC decision evidence that admitted creation. |
| `rationale_code` / `rationale_text` | Stable general-wellbeing explanation snapshot; not diagnosis, treatment, or AI reasoning. |
| `safety_status` / `safety_reason_code` / `safety_policy_version` | Independent Care-owned PHQ-9 item-9 safety evidence without answers or scores. |
| `safety_guidance_code` / `safety_guidance` | Approved safety copy stored with the draft so reload never depends on AI or another service. |
| `selected_resource_count` | Number of persisted selected exact versions for v2, constrained to 1..5; null for v3. |
| `activated_at` | UTC instant of explicit activation; null while the plan is a draft. |
| `completion_reason` | Optional bounded user-selected reason code (`USER_DECISION`, `PLAN_NO_LONGER_FITS`, or `OTHER`) stored only for a completed plan; it is not a clinical interpretation. |
| `completed_at` / `superseded_at` / `discarded_at` | UTC instant for the matching terminal state; lifecycle checks require exactly the applicable timestamp and activation history. |
| `created_at` / `updated_at` | UTC creation and latest accepted business-command instants. |

### `public.support_plan_focus`

Ordered, versioned, non-diagnostic intent within one native v3 plan. The table
stores bounded Care orchestration copy only; it never stores assessment,
journal, emotion, or consultation payloads.

| Field | Purpose |
| --- | --- |
| `id` | Immutable focus UUID. |
| `support_plan_id` / `support_plan_model_version` / `user_id` | Owner-matched parent reference fixed to model 3; composite keys block v2 attachment and cross-owner injection. |
| `ordinal` | Unique 1-based order within the plan, constrained to 1..10. |
| `title` / `rationale` | Bounded plain-language focus copy; title is required and rationale is optional, non-blank when present. |
| `version` | Non-negative optimistic revision. |
| `created_at` / `updated_at` | UTC creation and latest accepted change instants. |

### `public.support_plan_action`

Versioned typed intention within one focus. Care stores the orchestration
envelope and bounded source references, not source-domain content. Source
authority and reference shape are constrained by action type.

| Field | Purpose |
| --- | --- |
| `id` | Immutable native action UUID. |
| `support_plan_id` / `plan_focus_id` / `user_id` | Owner-matched plan and focus references; composite foreign keys prevent cross-plan or cross-owner attachment. |
| `ordinal` | Unique 1-based order within the focus, constrained to 1..20. |
| `action_type` | One of `RESOURCE`, `JOURNAL`, `EMOTION_CHECK_IN`, `ROUTINE_SELF_MANAGEMENT`, `PROFESSIONAL_SUPPORT`, `REASSESSMENT_SCREENING`, or `SELF_REPORT_REVIEW`; unknown types fail closed. |
| `purpose_code` / `rationale` | Required bounded purpose and optional bounded non-diagnostic rationale. |
| `source_owner` / `source_kind` / `source_id` / `source_revision` / `source_policy_version` | Minimal type-specific source reference: `CONTENT`/`RESOURCE_VERSION`; optional `JOURNAL_AI`/`JOURNAL_TEMPLATE`; optional `JOURNAL_AI`/`EMOTION_CHECK_IN_CAPABILITY`; `CONSULTATION`/`PROFESSIONAL_SUPPORT_PATHWAY` or `APPOINTMENT`; or `CARE`/`QUESTIONNAIRE_DEFINITION`. Routine/review actions carry no source. |
| `source_publication_id` / `source_eligibility_policy_version` | Exact publication and eligibility evidence required for `RESOURCE`; absent for every other action type. |
| `source_verified_at` | UTC source-verification instant, required for `RESOURCE`, optional for other referenced sources, and absent when the action carries no source. |
| `provenance_type` | Required admission origin: `USER_SELECTED`, `SUPPORT_GUIDE`, `SPECIALIST`, or `SYSTEM_POLICY`. |
| `provenance_reference_id` / `provenance_reference_version` / `provenance_accepted_at` | Bounded optional exact origin pair and required UTC acceptance instant. Reference ID and version are both null or both present. |
| `presentation_title` / `presentation_summary` / `presentation_instructions` / `presentation_external_url` | Bounded user-facing snapshot sufficient to understand the action without copying raw source-domain content. Title is required; the other fields are optional and non-blank when present. |
| `status` | `DRAFT`, `ACTIVE`, or `ENDED`, with timestamps constrained to the matching lifecycle shape. |
| `version` | Non-negative action revision retained by schedule and occurrence source snapshots. |
| `activated_at` / `ended_at` | UTC lifecycle instants; ending requires prior activation and cannot precede it. |
| `created_at` / `updated_at` | UTC creation and latest accepted change instants. |

### `public.support_plan_template_family`

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable row UUID. |
| `support_plan_id` / `ordinal` | Parent draft and stable 1-based composition order, bounded to two domains. |
| `family` | Approved immutable family selected deterministically from one domain-local instrument band. |
| `template_version` | Exact immutable Care template version; MB-372 publishes version 1 only. |
| `target_domain` | `DEPRESSIVE_SYMPTOMS` or `ANXIETY_SYMPTOMS`; never a combined severity. |
| `focus_title` / `focus_rationale` | Optional bounded human-readable focus copy persisted when a preference revision shapes this policy-admitted family; both null for deterministic fallback. |
| `focus_reason_code` | `USER_PREFERENCE_AND_CARE_POLICY` when the family copy was personalized; makes the reason visible without copying screening evidence. |

### `public.support_plan_slot`

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable slot UUID referenced by alternatives. |
| `support_plan_id` / `ordinal` | Parent draft and deterministic bounded 1-based display order. |
| `slot_key` / `slot_kind` / `target_domain` / `purpose_code` | Care policy identity, `CORE`/`OPTIONAL` rule, domain, and non-clinical purpose. |
| `selected_resource_id` / `selected_content_version` / `selected_publication_id` | Exact Content resource, immutable version, and review publication selected by Care; all are null together only when the user removes an `OPTIONAL` slot. |
| `selected_role` / `selected_category` | Exact eligibility role and reviewed display category; only `PRIMARY` can satisfy a core query. |
| `selected_title` / `selected_summary` / `selected_external_url` | Reviewed display snapshot; no raw assessment or journal content is stored. |

The complete-tuple constraint prevents partial snapshots. Core slots remain
selected by application policy. The unique selected-resource key prevents the
same exact version from filling multiple composed slots. The 1..5 ordinal check
matches the aggregate bound.

### `public.support_plan_slot_alternative`

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable alternative UUID. |
| `support_plan_slot_id` / `ordinal` | Parent slot and deterministic server-admitted order, bounded to eleven so the previous selected candidate can remain admitted after a swap. |
| `resource_id` / `content_version` / `publication_id` | Exact eligible Content version and review publication retained for later user choice. |
| `eligibility_role` / `category` | Exact allowed role and reviewed display category. |
| `title` / `summary` / `external_url` | Reviewed display snapshot returned on reload; not AI-generated content. |

### `public.support_plan_request`

| Field | Purpose |
| --- | --- |
| `user_id` / `idempotency_key` | Owner-scoped printable retry namespace. |
| `request_hash` | One-way SHA-256 of the exact SupportEvaluation reference; no answers, score, or bearer token is recoverable. |
| `support_plan_id` | Owner-matched stored draft returned for identical retries and concurrent aliases. |
| `created_at` | UTC instant at which Care accepted the alias. |

### `public.plan_change_request`

Care-owned, user-reviewed request derived from one authoritative exact-version
resource proposal in a completed Consultation appointment. Creating the request
only records a review snapshot; it never creates a draft or changes the current
plan. Acceptance repeats every authoritative check and links the replacement
created in the same transaction.

| Field | Purpose |
| --- | --- |
| `id` / `version` | Stable request identity and optimistic decision version. |
| `user_id` / `specialist_id` | Owning user and advisory specialist; only the user can decide. |
| `source_proposal_id` / `source_proposal_version` | Exact Consultation proposal identity and version; unique so retries cannot create competing requests. |
| `source_appointment_id` / `source_summary_id` / `source_summary_version` / `source_completion_fact_id` | Immutable evidence that the proposal came from the latest visible summary of the completed appointment. |
| `proposal_reason_code` / `proposal_title` / `proposal_details` | Bounded user-visible rationale and snapshot; no raw chat, journal, or assessment answers. |
| `resource_id` / `resource_version` | Exact Content version revalidated at review and again on acceptance. |
| `current_support_plan_id` / `current_support_plan_version` | Exact official plan reviewed when the request was created; any later change makes acceptance stale. |
| `target_slot_id` / `current_resource_id` / `current_resource_version` / `current_resource_title` | Compatible Care-owned slot and its optional current exact resource for the comparison UI. |
| `replacement_support_plan_id` / `replacement_support_plan_version` | Null while pending or rejected; populated only by the atomic accepted replacement. |
| `status` / `outcome_code` | `READY_FOR_REVIEW`, `ACCEPTED`, or `REJECTED` with stable admissible/applied/rejected outcome. |
| `idempotency_key` / `request_hash` | Owner-scoped exact retry identity for review creation. |
| `decision_idempotency_key` / `decision_hash` | Owner-scoped exact retry identity for the final accept/reject command. |
| `reviewed_at` / `decided_at` / `created_at` / `updated_at` | Server-owned review, decision, and persistence instants. |

### `public.support_plan_command`

Owner-scoped append-only audit and replay record for activation and MB-375
replacement confirmation. Choice
replacement uses PUT semantics plus optimistic concurrency and does not create
a request-deduplication record. This table stores no bearer token, assessment
answer, journal content, or client-authored display text.

| Field | Purpose |
| --- | --- |
| `user_id` / `idempotency_key` | Owner-scoped printable retry namespace and primary key. |
| `command_type` / `request_hash` | `ACTIVATE` or `REPLACE` and the SHA-256 fingerprint of every referenced plan/version and reassessment summary for exact retry matching. |
| `support_plan_id` / `expected_version` | Owner-matched target and optimistic version explicitly acted on by the user. |
| `resulting_version` / `resulting_status` / `resulting_updated_at` | Exact replay outcome; resulting version is the expected version plus one. |
| `evaluation_policy_version` | Current compatible Care evaluation policy revalidated immediately before the local command transaction. |
| `entitlement_package` / `entitlement_source` / `entitlement_policy_version` / `entitlement_version` / `entitlement_decided_at` | Fresh authoritative paid-entitlement evidence used by the command. |
| `resource_policy_version` / `resources_resolved_at` | Exact Content eligibility policy and resolution instant used for final exact-version validation. |
| `source_support_plan_id` / `source_support_plan_version` | For `REPLACE`, the exact former current plan and pre-supersede optimistic version; null for initial activation. This is the immutable replacement relation. |
| `reassessment_summary_id` | For `REPLACE`, the owner-matched canonical v2 summary presented during review; null for initial activation. The JSON snapshot remains in `reassessment_summary` rather than being duplicated here. |
| `plan_change_request_id` | For an MB-560 specialist-proposal replacement, the exact Care-owned accepted request; mutually exclusive with `reassessment_summary_id`. |
| `replacement_review_outcome` | Governed review outcome that admitted confirmation. Unchanged reviews are not persisted because they cannot mutate a plan. |
| `created_at` | UTC instant the command and its outcome committed. |

### `public.support_plan_command_selection`

Ordered exact final selection set committed by activation or replacement. A missing optional
slot represents a choice removed before activation; the plan snapshot preserves
the authoritative state.

| Field | Purpose |
| --- | --- |
| `user_id` / `idempotency_key` | Physical parent key to `support_plan_command`. |
| `ordinal` | Stable 1-based intent order, bounded to the plan's 1..5 selected-resource limit. |
| `slot_key` | Exact existing Care-owned slot selected by the user; unique within the command. |
| `resource_id` / `content_version` | Exact admitted Content version selected for that slot; unique within the command. |

### `public.support_plan_activity_schedule`

Care-owned schedule snapshot for either one selected v2 Resource slot or one
native v3 PlanAction. The two source shapes are mutually exclusive. It keeps
time interpretation and source attribution stable when profile timezone or an
external source later changes. Partial unique indexes admit at most one current
(`DRAFT`, `ACTIVE`, or `PAUSED`) schedule for each source slot or native action
within a plan while retaining ended revisions as history.

| Field | Purpose |
| --- | --- |
| `id` | Immutable schedule UUID and parent of its occurrence history. |
| `support_plan_id` / `user_id` | Owner-matched plan and account; the composite foreign key blocks cross-owner rows. |
| `ordinal` / `schedule_version` | Stable source order and recurrence revision; unique together within a plan. Ordinal is bounded to 1..5 for v2 or 1..20 for v3. |
| `source_plan_version` | Exact plan revision that authorized the schedule. |
| `source_slot_key` / `source_resource_id` / `source_content_version` | Required exact v2 slot and Content-version source; all are null for a native v3 schedule. |
| `source_action_id` / `source_action_version` / `source_action_type` | Required exact native v3 action snapshot; all are null for a v2 schedule. The immutable ID/plan/owner/type relation is foreign-key enforced, while `source_action_version` deliberately remains the historical revision snapshot so a later optimistic lifecycle update cannot invalidate existing schedules. |
| `source_title` | Reviewed user-facing title snapshot for either source branch. |
| `schedule_policy_version` | Required bounded generation policy for v3; null for v2. |
| `reminder_enabled` | Care-owned reminder preference snapshot, default false; it does not mutate a source-domain fact. |
| `recurrence_type` / `recurrence_day_of_week` / `recurrence_days_mask` | v3 supports `ONCE`, `DAILY`, or `WEEKLY`; v2 remains `DAILY`/`WEEKLY`. A weekly schedule carries either the legacy single ISO weekday 1..7 or the v2 schedule-policy bit mask 1..127, never both. |
| `preferred_daypart` / `estimated_duration_minutes` | Schedule-policy v2 snapshots an optional `MORNING`, `AFTERNOON`, `EVENING`, or `FLEXIBLE` preference and a required 5..180 minute estimate in five-minute increments. Earlier policy rows keep both null. |
| `local_time` / `timezone` | Intended wall-clock time and snapshotted IANA timezone used for deterministic DST resolution. |
| `effective_from` / `effective_until` | Inclusive local-date validity; the end remains null while active. |
| `occurrence_generation_until` | Internal inclusive cutoff for an ended revision. It may precede `effective_from` when a future schedule is removed before its first valid day and prevents new occurrences after removal without exposing an invalid effective-date range. |
| `status` | `DRAFT`, `ACTIVE`, `PAUSED`, or `ENDED`; Content/Notification never controls it. A draft schedule becomes effective only with the plan activation command. |
| `activated_at` | UTC instant when this schedule revision first became active. It remains null for a draft that was never activated and is retained after end, allowing occurrence generation to distinguish a valid predecessor cadence from discarded draft history. |
| `created_at` / `updated_at` | UTC creation and latest lifecycle transition instants. |

### `public.support_plan_schedule_command`

Owner-scoped idempotency record for creating, replacing, or removing an action
schedule. It stores the request fingerprint and exact plan/schedule replay
outcome so an accepted retry cannot repeat lifecycle effects.

| Field | Purpose |
| --- | --- |
| `user_id` / `idempotency_key` | Owner-scoped printable retry namespace and primary key. |
| `request_hash` | Lowercase SHA-256 fingerprint of the full schedule command, including target action and optimistic plan version. |
| `support_plan_id` / `action_id` | Owner-matched plan and native action explicitly addressed by the command. |
| `expected_plan_version` / `resulting_plan_version` | Optimistic input revision and exact replayed plan revision; the result cannot precede the expected version. |
| `resulting_schedule_id` / `resulting_schedule_version` | Exact resulting schedule pair for create/replace, or both null for removal. |
| `created_at` | UTC instant the command and its outcome committed. |

### `public.support_plan_activity_occurrence`

Persisted logical instance for one schedule/local date. The database unique key
on schedule, schedule version, and local date makes retries and concurrent
generation idempotent.

| Field | Purpose |
| --- | --- |
| `id` | Deterministic UUID derived from the logical intent key. |
| `activity_schedule_id` / `support_plan_id` / `user_id` | Owning schedule, plan, and account for owner-scoped history. |
| `schedule_version` / `local_date` | Logical uniqueness key with the parent schedule. |
| `local_time` / `timezone` / `scheduled_at` | Wall-clock intent, IANA zone snapshot, and resolved UTC instant. |
| `state` | Persisted `SCHEDULED`, `COMPLETED`, `SKIPPED`, or `CANCELLED`; `MISSED` is computed only in reads. |
| `state_reason` | Null except `PLAN_PAUSED`, `PLAN_COMPLETED`, `PLAN_REPLACED`, `ACTION_ENDED`, or `SCHEDULE_CHANGED` on cancelled rows. |
| `source_plan_version` | Exact Care plan revision provenance. |
| `source_slot_key` / `source_resource_id` / `source_content_version` | Required exact v2 slot and Content-version snapshot; all are null for a native v3 occurrence. |
| `source_action_id` / `source_action_version` / `source_action_type` | Required exact native v3 action snapshot; all are null for v2. One foreign key blocks cross-plan, cross-owner, or type substitution, and a second binds the entire plan/action/version snapshot to the originating schedule; the snapshotted revision remains stable if the current action lifecycle version advances. |
| `source_title` | Reviewed user-facing title snapshot for either source branch. |
| `evidence_owner` / `evidence_kind` / `evidence_id` / `evidence_revision` | Minimal native v3 completion reference: Care acknowledgement for Resource/routine, Care review submission, Journal entry/check-in fact, Consultation appointment fulfilment, or Care assessment history. Authority/kind must match the action type; no raw source content is copied. Journal `JOURNAL_ENTRY` and emotion facts use immutable creation revision `1`, not a mutable private-content revision. A partial unique index prevents the same authoritative fact revision from satisfying more than one occurrence. |
| `evidence_occurred_at` / `evidence_verified_at` | UTC source-fact occurrence and Care verification instants, with verification no earlier than occurrence. Journal writing facts use server-generated entry `createdAt`, not the private entry's user-selected `occurredAt` or revision timestamp; the instant must fall on the occurrence day in its schedule timezone and after activation. Accepted historical evidence survives source revision/deletion. Evidence is absent unless a native v3 occurrence is `COMPLETED`. |
| `version` | Optimistic counter required by explicit user state updates. |
| `created_at` / `updated_at` | UTC insertion and latest accepted transition instants. |
| `completed_at` / `skipped_at` / `cancelled_at` | Exactly the timestamp matching the persisted terminal state; all null while scheduled. |
| `hidden` | Owner-only display preference; independent of completion/skip state. |
| `helpfulness` | Optional owner rating for `COMPLETED`: `NOT_HELPFUL`, `A_LITTLE_HELPFUL`, `HELPFUL`, or `VERY_HELPFUL`. |
| `barrier_code` | Optional owner-selected reason for `SKIPPED`: `LOW_ENERGY`, `NOT_ENOUGH_TIME`, `DIFFICULT_TO_START`, `NOT_A_GOOD_FIT`, or `OTHER`. |
| `reflection` | Optional private trimmed owner reflection, 1-500 characters; excluded from the integration event. |
| `summary_reuse_approved` | Explicit approval to reuse minimized coded facts in a later bounded summary; never broad checklist monitoring consent. |
| `engagement_updated_at` | Latest accepted replacement or deletion instant. Deletion clears mutable values but retains provenance. |

### `public.reassessment_self_report`

Versioned Care-owned source for the user's explicit, non-diagnostic account of
the current comparison period. Only the authenticated owner may create, read,
replace, or delete it. Deletion clears the categorical response and both
optional contexts; the remaining tombstone prevents accidental resurrection
and preserves period/version provenance. Earlier immutable summary snapshots
remain unchanged.

| Field | Purpose |
| --- | --- |
| `id` | Stable UUID referenced by canonical reassessment composition. |
| `user_id` | Owning Care profile; every command and query is owner-scoped. |
| `idempotency_key` / `request_hash` | Owner-scoped create replay identity and SHA-256 request fingerprint. |
| `source_version` | Exact input contract version, currently `reassessment-self-report-v1`. |
| `current_period_start` / `current_period_end` | Half-open period the response describes. The canonical MB-559 flow uses the exact Care-issued 14-day policy window. |
| `current_experience` | Required active categorical response: `BETTER`, `ABOUT_THE_SAME`, `MORE_DIFFICULT`, or `UNSURE`; cleared on deletion. |
| `helpful_context` / `difficult_context` | Optional trimmed owner-authored context, each at most 500 characters; cleared on deletion. |
| `version` | Optimistic concurrency revision included in ETags and summary provenance. |
| `authored_at` / `updated_at` | Original creation and latest accepted replacement/deletion instants. |
| `deleted_at` | Null while active; deletion instant on a content-cleared tombstone. |

### `public.reassessment_summary`

Immutable Care-owned snapshot composed for an authenticated owner. The JSON
snapshot preserves the exact response after source assessments, Journal/AI
evidence, or mutable engagement later changes or is deleted. It contains no raw
journal text, provider response, assessment answers, combined score, or clinical
improvement verdict.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID exposed as the reassessment-summary identifier. |
| `user_id` | Care profile that owns the snapshot; the physical foreign key and every query enforce owner isolation. |
| `idempotency_key` | Caller retry key unique per owner, 16-128 visible ASCII characters at the API boundary. |
| `request_hash` | SHA-256 of the exact PHQ-9/GAD-7 assessment IDs, Journal job/legacy analysis reference, comparison-period bounds, and optional self-report ID; conflicting key reuse fails. |
| `summary_version` | Composition/schema policy version. Historical compatibility rows remain `reassessment-summary-v1`; canonical explicit-self-report rows use `reassessment-summary-v2`. |
| `journal_job_id` | Required external Journal longitudinal job UUID for canonical v2 snapshots, including failed-job snapshots. Care resolves its authoritative status. Null only on historical v1 rows. |
| `journal_analysis_id` | Resolved analysis UUID when a v2 job succeeded, or the requested legacy v1 analysis UUID. It is nullable for failed/unavailable v2 jobs. |
| `previous_period_start` / `previous_period_end` | Half-open UTC bounds for the prior evidence period. Database checks require 7-31 days. |
| `current_period_start` / `current_period_end` | Half-open UTC bounds for the current evidence period. It must have the same duration and cannot overlap the prior period. |
| `snapshot` | Authoritative derived JSON object returned by current/detail/history reads. V2 keeps explicit self-reported experience separate from supporting activity reflection, plus the other three dimensions, exact source/version/coverage data, explicit `UNAVAILABLE` or `INSUFFICIENT_DATA` states, and the no-combined-score disclaimer. Reassessment composition is the only writer. |
| `composed_at` | Immutable UTC instant when Care completed local calculation, safe Journal/AI fallback, and snapshot persistence. |

### `care.intervention_plan`

Versioned set of platform support actions generated for one support classification.

This is a non-executable legacy conceptual baseline, not an approved
SupportPlan schema. ADR 0013 freezes immutable templates, composition,
eligibility, and lifecycle; ADR 0017 limits the durable plan to
`PLUS`/`PREMIUM`, confirms one official Care-owned current plan, and routes
specialist proposals through `PlanChangeRequest`. The legacy shape cannot
represent those requirements and was not promoted into a migration. MB-372
instead adds the normalized `public.support_plan*` schema above for initial
draft creation and reload under [SupportPlan policy v2](../policies/support-plan-policy-v2.md).

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID used by follow-up resources and REST endpoints. |
| `user_id` | Care profile that owns and may view the plan. |
| `support_classification_id` | Legacy reference to the support-policy result; a future schema requires the exact compatible SupportEvaluation reference. |
| `template_code` | Legacy single-template identifier; insufficient for the approved composition of independent domain template families. |
| `template_version` | Legacy single-template revision; a future schema must preserve every composed immutable family/version reference. |
| `status` | Lifecycle state controlling whether the plan is active, complete, superseded, or cancelled. |
| `actions` | Legacy structured action snapshot; insufficient to represent approved slots, selected exact resource versions, allowed alternatives, and eligibility evidence. |
| `generated_at` | UTC instant the plan was generated from the support result. |
| `completed_at` | UTC instant the plan reached completion; null until completed. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest persisted plan status/action change. |
| `version` | Optimistic-lock counter preventing lost concurrent plan updates. |

## Conceptual owner `consultation` (`mentalbridge_consultation.public`)

### `consultation.specialist_profile`

Consultation-owned professional profile and authoritative administrator approval state. Private credential documents are stored separately and reviewed independently.

Database checks require a submitted timestamp once review starts, reviewer identity/time for terminal approval decisions, and a stable reason for rejection or suspension.

| Field | Purpose |
| --- | --- |
| `account_id` | External Identity account UUID and immutable specialist identifier; access still depends on current account state. |
| `display_name` | Reviewed specialist name displayed to users. |
| `biography` | Optional reviewed professional biography shown in discovery and booking. |
| `years_experience` | Non-negative stated professional experience used for informational filtering/display. |
| `timezone` | IANA timezone used to interpret and render the specialist schedule. |
| `approval_status` | Authoritative profile-review state controlling discovery, slots, appointments, and specialist access. |
| `submitted_at` | UTC instant the specialist submitted a complete profile for review; null before submission. |
| `reviewed_at` | UTC instant the latest administrator decision became effective; null until reviewed. |
| `reviewed_by` | External Identity administrator UUID responsible for the latest decision; null until reviewed. |
| `decision_reason_code` | Optional stable, non-sensitive rejection or suspension enum. MB-360 allows only the reviewed incomplete/content/scope rejection reasons and policy/quality/account-review suspension reasons; unrestricted evidence text is not stored. |
| `created_at` | Immutable UTC profile creation instant. |
| `updated_at` | UTC instant of the latest persisted profile or approval change. |
| `version` | Optimistic-lock counter preventing lost specialist-profile updates. |
| `published_version` | Monotonic approved-content version: initial approval/promotion increments it, draft edits and suspension/restoration do not. Zero means no approved snapshot yet. |

### `consultation.specialist_profile_support_area`

V1 reviewed non-clinical support domains declared by a specialist. The database
accepts only `DEPRESSIVE_SYMPTOMS` and `ANXIETY_SYMPTOMS`.

| Field | Purpose |
| --- | --- |
| `specialist_account_id` | Owning specialist profile and first half of the composite primary key. |
| `support_area` | Approved support-domain enum and second half of the composite primary key. |

### `consultation.specialist_profile_language`

Reviewed languages declared for user-facing support. Values are lowercase
`vi`, `en`, `zh`, and `fr`.

| Field | Purpose |
| --- | --- |
| `specialist_account_id` | Owning specialist profile and first half of the composite primary key. |
| `language_tag` | Supported language tag and second half of the composite primary key. |

### `consultation.specialist_credential_document`

Consultation-owned private credential metadata. Originals live in authenticated Cloudinary storage. Only a specialist owner or ADMIN can request a short-lived original-file link; only ADMIN can verify. Badge visibility never grants access to the original.

| Field | Purpose |
| --- | --- |
| `id` | Stable credential identifier for owner/admin review and optimistic commands. |
| `specialist_account_id` | Owning specialist profile; cascading deletion removes the metadata with the profile. |
| `title` / `issuer` / `issue_year` / `credential_type` | Specialist-supplied professional claim and ADMIN review context; public badge text is eligible only after verification. |
| `file_name` / `media_type` / `file_bytes` | Bounded original-file display and safety metadata, without storing the binary. |
| `storage_key` / `storage_format` | Private Cloudinary locator and format used only inside Consultation to issue an expiring URL. The key is unique and never public. |
| `verification_status` | `PENDING`, `VERIFIED`, or `REJECTED`; upload always begins pending. |
| `badge_visible` | Specialist choice; database permits true only for verified documents. |
| `reviewed_by` / `reviewed_at` | External ADMIN identity and UTC time of a terminal review decision; both null while pending. |
| `created_at` / `updated_at` | UTC creation and last review/visibility change. |
| `version` | Optimistic counter for review and badge commands. |

### `consultation.specialist_profile_status_history`

Append-only operational audit of explicit submission and administrator status
decisions. It stores no uploaded evidence or unrestricted notes.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID audit-entry identifier. |
| `specialist_account_id` | Specialist profile whose state was submitted or changed. |
| `approval_status` | State established by this explicit action. |
| `reason_code` | Stable reviewed reason for an applied rejection or suspension; null for submit, resubmit, approval, and restoration. |
| `actor_account_id` | Identity UUID of the specialist or administrator performing the action. |
| `actor_role` | `SPECIALIST` or `ADMIN` operational actor class. |
| `occurred_at` | UTC instant when the action became effective. |

### `consultation.specialist_profile_approved_version`

MB-635 append-only approved six-field snapshots. Consultation is authoritative;
one baseline is captured for existing approved/suspended profiles, without
reconstructing unavailable old content. Profile snapshots contain professional
display data, never account credentials, verification files or health data.

| Field | Purpose |
| --- | --- |
| `id` | Immutable opaque snapshot identifier; migration baseline reuses the owner UUID. |
| `specialist_account_id` | Local specialist profile FK; paired with published_version for immutable content identity. |
| `published_version` | Positive per-specialist approved-content sequence, unique with specialist_account_id. |
| `profile_snapshot` | Exact approved JSON object with displayName, bio, supportAreas, languages, yearsOfExperience and timezone. Append-only; no amendment draft content leaks into discovery. |
| `approved_by` | External Identity UUID of the approving ADMIN; baseline uses available approval audit metadata. |
| `approved_at` | UTC effective approval time; baseline uses available approval metadata, not a fabricated historic content version. |
| `source_amendment_id` | Optional local amendment FK identifying promotion; null for initial/baseline approval. |

### `consultation.specialist_profile_amendment`

Separate private amendment to the approved public snapshot. At most one
DRAFT/PENDING_REVIEW/REJECTED row per specialist; approved and cancelled rows remain historical.
Only owner SPECIALIST and ADMIN can read it. Every command locks the owner profile
first and checks optimistic version; promotion additionally checks published base.

| Field | Purpose |
| --- | --- |
| `id` | Immutable opaque UUID used by owner/admin amendment APIs. |
| `specialist_account_id` | Local owning specialist profile FK; JWT subject determines owner access. |
| `base_published_version` | Exact approved content sequence used to derive the draft; composite FK to approved history prevents nonexistent bases. |
| `status` | DRAFT, PENDING_REVIEW, REJECTED, APPROVED or terminal CANCELLED amendment state; never replaces profile approval_status. Owner cancellation preserves the public profile and prior audit revisions, clears current submission/review metadata and frees the one-open-amendment constraint. |
| `proposed_profile` | Authoritative six-field proposed JSON payload, validated like the initial profile. It is private until reviewed promotion. |
| `submitted_at` | UTC latest explicit submission; null for DRAFT/CANCELLED, cleared on pending-review edits or cancellation. |
| `reviewed_at` | UTC latest ADMIN amendment decision; null before review, after resubmission or cancellation. Prior decisions remain in history. |
| `reviewed_by` | External Identity ADMIN UUID for latest decision, nullable before review, after resubmission or cancellation. |
| `reason_code` | Closed rejection reason only; retained while correcting REJECTED content, cleared on explicit resubmission or cancellation. No unrestricted notes. |
| `created_at` | Immutable UTC draft creation time, used with id to select latest owner amendment. |
| `updated_at` | UTC last persisted amendment change, distinct from public profile updated_at. |
| `version` | Optimistic-lock counter in amendment ETag; stale edits/submissions/decisions return 412. |

### `consultation.specialist_profile_amendment_history`

Append-only exact revision/provenance, including the payload ADMIN reviewed.
Later correction/resubmission never overwrites a rejected payload or actor/time.

| Field | Purpose |
| --- | --- |
| `id` | Immutable opaque history UUID. |
| `amendment_id` | Local amendment FK retaining the full audit chain. |
| `amendment_version` | Persisted revision corresponding to this history fact; unique with amendment_id. |
| `status` | Resulting amendment state for this revision. |
| `proposed_profile` | Exact six-field JSON payload of that revision, retained for approved/rejected review audit. |
| `actor_account_id` | External Identity UUID of the specialist editor/submitter or ADMIN reviewer. |
| `actor_role` | Bounded SPECIALIST/ADMIN actor class; no user identity content. |
| `reason_code` | Closed rejection reason for REJECTED facts, null otherwise. |
| `occurred_at` | UTC effective revision instant; ordering uses amendment_version, not wall-clock alone. |

### `consultation.subscription_plan`

Stable plan identity used to group immutable commercial versions.

| Field | Purpose |
| --- | --- |
| `code` | Stable authoritative catalogue code: `FREE`, `PLUS`, or `PREMIUM`. |
| `display_name` | Reviewed user-facing plan name. |
| `sort_order` | Unique presentation and upgrade ordering; zero is `FREE`, followed by `PLUS` and `PREMIUM`. |

### `consultation.current_service_entitlement`

Authoritative current-package read model used by cross-service capability and
model-routing decisions. It is deliberately not a payment, subscription,
billing-period, consultation-credit, or ledger aggregate. Absence of an
effective row means `FREE` with synthetic `DEFAULT_FREE` provenance. Stored
rows are only explicit bounded `DEMO` or future billing-produced `PAID`
projections; an expired row is ignored rather than silently extending access.

| Field | Purpose |
| --- | --- |
| `account_id` | External Identity user UUID and primary key for the single current projection; it is never accepted from an AI client as a routing claim. |
| `package_code` | Current paid/demo package, restricted to `PLUS` or `PREMIUM`; `FREE` is represented by no effective row. |
| `source` | Provenance class `DEMO` or future `PAID`; it prevents controlled-demo access from masquerading as payment. |
| `source_reference` | Stable non-blank demo identifier or future billing lifecycle reference supporting operational traceability. |
| `established_by` | External Identity administrator UUID required for `DEMO`; future automated `PAID` projections may leave it null while retaining their billing reference. |
| `effective_from` | Inclusive UTC instant from which the projection may authorize package-specific behavior. |
| `effective_until` | Exclusive UTC instant after which lookup returns `FREE` unless a new effective projection exists. |
| `policy_version` | Exact entitlement-read policy; MB-369 fixes `service-entitlement-v1`. |
| `created_at` | Immutable UTC insertion instant for this current projection row. |
| `updated_at` | UTC instant of the latest authoritative projection replacement. |
| `version` | Optimistic-lock counter reserved for safe future demo/billing projection updates. |

### `consultation.service_credit_period`

Implemented MB-377/MB-558 allocation boundary. One row freezes the highest package allocation seen for an exact user, entitlement policy version, credit policy version, and effective period. `DEMO` and `PAID` provenance is immutable within that period; an existing v1 period is never expanded to v2 quantities.

| Field | Purpose |
| --- | --- |
| `id` | Server-generated period UUID. |
| `account_id` | External Identity user UUID that owns every credit in the period. |
| `plan_version` | Exact purchased plan-version UUID text for new billing periods, resolved from the matching successful payment and period start. Legacy/demo periods retain their original entitlement-policy label; reload never re-provisions them under a new identifier. |
| `credit_policy_version` | Exact allocation policy frozen at period creation: historical `consultation-credit-v1` or current `consultation-credit-v2`. |
| `package_code` | Highest allocated package in the period: `PLUS` or `PREMIUM`. |
| `source` | Explicit `DEMO` or `PAID` provenance; never inferred by the client. |
| `source_reference` | Stable entitlement lifecycle or controlled-demo reference. |
| `period_start` / `period_end` | Inclusive/exclusive UTC billing or demo window. |
| `allocated_count` | Frozen allocation after an allowed in-period upgrade: v1 stores Plus 1 or Premium 3; v2 stores Plus 4 or Premium 10. A renewal creates a separate period and never adds unused prior credits. |
| `created_at` / `updated_at` | UTC creation and latest in-period upgrade instants. |
| `version` | Optimistic version incremented by allocation upgrade. |

### `consultation.service_credit`

One indivisible consultation right. Current state is owner-controlled; balances are counted from these rows and never reconstructed in the browser.

| Field | Purpose |
| --- | --- |
| `id` | Stable credit UUID. |
| `period_id` | Owning immutable-provenance service-credit period. |
| `ordinal` | One-based position within the policy-versioned allocation; unique per period and capped at ten. |
| `state` | `AVAILABLE`, appointment `HELD`, upgrade `UPGRADE_HELD`, `CONSUMED`, `FORFEITED`, period `EXPIRED`, or upgrade-applied `REVOKED`. A release returns the state to `AVAILABLE` while the ledger preserves the fact. |
| `appointment_id` | Local appointment UUID required only for appointment-held and appointment-terminal outcomes; upgrade-held/expired/revoked credits keep it null. |
| `created_at` / `updated_at` | UTC creation and latest transition instants. |
| `version` | Optimistic transition counter. |

### `consultation.service_credit_ledger`

Append-only evidence for provisioning and appointment-driven transitions.

| Field | Purpose |
| --- | --- |
| `id` | Immutable event UUID. |
| `credit_id` | Credit whose state changed. |
| `account_id` | Denormalized owner UUID for bounded history and account-scoped idempotency. |
| `event_type` | Stable provisioning, booking, upgrade hold/release, consumption, expiry, forfeiture, revocation, or explicit post-settlement adjustment fact. The ledger never represents a refund. |
| `appointment_id` | Appointment correlation for booking/session transitions; null for provisioning and subscription-upgrade transitions. |
| `idempotency_key` | Owner command key unique per account; exact replay does not append another event. |
| `occurred_at` | Immutable server UTC transition instant. |

### `consultation.appointment`

Implemented MB-378/MB-558 request aggregate with MB-379 decision settlement, MB-380 owner cancellation/reschedule audit, and MB-383 evidence-backed chat settlement. One row is the immutable scheduling snapshot created while the same local transaction locks the exact availability slot, holds one eligible credit, and enforces the package reservation cap. New requests support only in-app chat or gated in-app video.

| Field | Purpose |
| --- | --- |
| `id` | Server-generated appointment UUID and local credit-ledger correlation. |
| `user_account_id` | External Identity USER UUID that owns and may reload the request. |
| `specialist_account_id` | Approved specialist snapshotted from the selected slot. |
| `availability_slot_id` | Exact published 60-minute slot; a partial unique index permits at most one active request/confirmation. |
| `service_credit_id` | Earliest-expiring available credit that covers the appointment start; unique while the appointment is active. |
| `status` | Initial `REQUESTED`; active reservations are `REQUESTED`, `CONFIRMED`, or `IN_PROGRESS`; channel end records `SESSION_ENDED`; only evidence-backed completion advances to `COMPLETED`; decision/cancellation outcomes may be `REJECTED`, `EXPIRED`, or `CANCELLED`. |
| `modality` | `IN_APP_CHAT` or `IN_APP_VIDEO`; physical, phone, and external-link modes are not accepted. |
| `scheduled_start_at` / `scheduled_end_at` | Immutable exact UTC interval copied from availability and constrained to 60 minutes. |
| `display_timezone` | IANA timezone copied from the slot for stable user display. |
| `requested_at` | Server UTC command instant used for unexpired-window validation and deadline calculation. |
| `decision_deadline_at` | Earlier of 24 hours after request or two hours before start when that cutoff is still future; otherwise the unexpired slot end. Always after the request and no later than scheduled end. The decision/expiry owner consumes this handoff. |
| `idempotency_key` | Printable user-scoped request key; exact retry returns this row and conflicting reuse fails. |
| `replaces_appointment_id` | Optional self-reference to the eligible future appointment replaced by this request. The old immutable schedule is retained as `CANCELLED`; its reservation capacity and, when policy permits, exact held credit move to the replacement atomically. One old appointment may be replaced only once. |
| `originating_plan_action_id` | Optional opaque Care `PROFESSIONAL_SUPPORT` action reference supplied only by an explicit owner booking flow. A replacement inherits the owned prior appointment's value when the request omits it and rejects a conflicting explicit value. It has no cross-service foreign key, grants no data access, never authorizes Consultation to mutate SupportPlan lifecycle, and supports the owner-and-action appointment-history query. |
| `decided_at` | Server UTC instant of specialist acceptance/rejection or deterministic deadline expiry; null until a request decision occurs. |
| `decision_reason` | Stable `SPECIALIST_ACCEPTED`, `SPECIALIST_REJECTED`, or `DECISION_DEADLINE_EXPIRED` outcome paired with `decided_at`. |
| `cancellation_reason` | Stable reason required only for `CANCELLED`: `USER_CANCELLED`, `USER_RESCHEDULED`, or `SPECIALIST_SUSPENDED`. |
| `cancelled_at` | Server UTC cancellation instant required only for `CANCELLED`. |
| `cancelled_by` | Identity actor UUID required only for `CANCELLED`; owner commands record the user and suspension records the administrator. |
| `cancellation_credit_outcome` | Immutable `RELEASED`, `FORFEITED`, or `TRANSFERRED_TO_REPLACEMENT` result required only for `CANCELLED`. It describes this appointment's settlement even when the shared credit is currently held by its replacement. |
| `session_outcome` / `session_outcome_reason` | Separate final chat result and stable reason: completion, either/both no-show, insufficient evidence, or timed-out evidence review. Video records only verified COMPLETED; unverified video retains null outcome/reason with HELD credit. |
| `session_policy_version` | `chat-session-completion-v1` for chat; `livekit-video-completion-v1` for video. Video end alone leaves outcome/fact/settlement null and credit HELD. |
| `session_ended_at` / `session_settled_at` | Server instants for history-only channel end and later outcome/credit settlement. End alone never consumes a credit. |
| `evidence_review_started_at` / `evidence_failure_reason` | Minimized technical-reconciliation state. The end-plus-35-minute fallback is historical chat policy only. Video flags unresolved manual review after 24 hours and remains HELD; no automatic release/settlement is approved. |
| `completion_fact_id` | Opaque unique fact present only for evidence-backed `COMPLETED`; MB-516 uses it as a unique earning input. Timer-only session end still creates no earning. |
| `created_at` / `updated_at` | UTC insertion and latest authoritative state-change instants. |
| `version` | Optimistic state-transition counter for later decision commands. |

### `consultation.appointment_status_history`

Append-only transition evidence introduced by MB-360, extended by MB-379 for idempotent decisions/expiry, and completed by MB-380 for initial request plus cancellation/reschedule settlement history.

| Field | Purpose |
| --- | --- |
| `id` | Immutable transition UUID. |
| `appointment_id` | Appointment whose authoritative status changed. |
| `from_status` / `to_status` | Valid current appointment states before and after the transition, including the shared `IN_PROGRESS` state used by the credit/reschedule lifecycle. |
| `changed_by` | Identity actor UUID responsible for the transition. |
| `reason` | Stable reviewed outcome reason without private consultation content. |
| `idempotency_key` | Optional printable command key. MB-379 specialist decisions and deterministic expiry use one key per appointment command; a partial unique index prevents duplicate evidence. |
| `credit_outcome` | Null for non-cancellation transitions; immutable `RELEASED`, `FORFEITED`, or `TRANSFERRED_TO_REPLACEMENT` for a transition into `CANCELLED`. |
| `changed_at` | Server UTC instant at which the transition committed. |

### `consultation.appointment_video_room`

MB-384 owner mapping: one opaque provider room per appointment. Never contains
secrets, media, private consultation content or client-selected authority.

| Field | Purpose |
| --- | --- |
| `appointment_id` | Local one-to-one owner reference and primary key; serializes access/closure with the appointment lock. |
| `room_name` | Unique opaque UUID-based Cloud room, stable across reconnects; no business/person identifiers. |
| `user_identity` / `specialist_identity` | Distinct random provider identities, mapped server-side to assigned roles. |
| `state` | PREPARING, OPEN, CLOSING, CLOSED or privacy PURGED tombstone; cannot authorize joining an ended appointment. |
| `failure_reason` | Bounded technical uncertainty, not clinical content or provider error payload. |
| `next_close_at` | Durable bounded retry/claim time for revocation and room deletion; never extends the appointment. |
| `next_verification_at` | Fair bounded reconciliation retry cursor; updates provider-room version, not the Appointment ETag on every unsuccessful poll. |
| `closed_at` | Server acknowledgement of successful revocation/deletion, not appointment completion. |
| `created_at` | Server mapping instant. |
| `retention_hold_reason` | Optional bounded operator legal/dispute/valid retention hold code; no free-text health information. |
| `version` | Optimistic concurrency counter, in addition to owner appointment locking. |

### `consultation.appointment_video_confirmation`

Immutable business confirmation audit, distinct from expiring participation
metadata. Never removed by the LiveKit retention job; no video/content is stored.

| Field | Purpose |
| --- | --- |
| `appointment_id` / `id` | One intent per owner appointment; confirmation UUID has a composite owner FK from Appointment.video_confirmation_id, preventing cross-appointment provenance. |
| `confirmed_by` | Assigned currently approved Specialist's authenticated Identity subject, not a request field. |
| `source_version` | Strong Appointment version observed before the accepted command. |
| `idempotency_key` | Printable 16–128 character command key, unique per Specialist; replays recover the same intent even with an old If-Match. |
| `accepted_at` | Server UTC assertion time; may precede scheduled end. |
| `policy_version` | Immutable livekit-video-completion-v1 provenance, distinct from historical chat. |

`consultation.appointment.video_confirmation_id` is nullable for historical
chat and non-confirmed video. A video COMPLETED row requires an owner-linked
confirmation, immutable completion fact, verified outcome, actual end within the
scheduled interval and settlement not earlier than actual end. Review flags
remain non-terminal SESSION_ENDED with null settledAt/outcome and credit HELD.

### `consultation.appointment_video_event`

MB-384 minimized signature-verified LiveKit participation metadata, never raw
webhook bodies, JWTs, recording, audio/video or consultation content. A source
event ID is globally unique. The owner appointment lock serializes insertion,
duplicate/conflict handling, verification and retention.

| Field | Purpose |
| --- | --- |
| `event_id` | Bounded provider idempotency identity; identical replay does not create new evidence. Conflicting replay flags uncertainty rather than overwriting. |
| `appointment_id` | Owner-local room/appointment reference; no client-authored assignment. |
| `event_type` | Only participant_joined, participant_left and room_finished. |
| `room_sid` | Provider room incarnation; pairing/overlap cannot mix unrelated room incarnations. |
| `participant_identity` / `participant_sid` | Opaque assigned identity and connection SID; nullable only for room_finished. Reconnect uses separate pairs. |
| `participant_role` | USER/SPECIALIST derived from the private room mapping, not provider metadata claims; null for room_finished. |
| `occurred_at` | Signature-verified source UTC occurrence; validity/overlap is bounded by the authoritative appointment window. |
| `received_at` | Server intake UTC instant; retention never removes a recent delayed delivery. |

Cleanup deletes evidence and replaces room/participant identifiers with privacy
tombstones after both closure and latest intake are older than 90 days. Any
appointment dispute or bounded operator retention hold prevents cleanup. Purged
room callbacks cannot recreate evidence. Appointment, status history, Brief,
Summary, completion facts and financial rows are not deleted by this job.

### `consultation.appointment_chat_evidence`

Content-free, append-only evidence received from Realtime for an assigned
participant. Subscribe/join is deliberately absent and raw chat content is
never stored.

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable evidence-row UUID. |
| `appointment_id` / `evidence_id` | Owner-local appointment and source idempotency identity; the pair is unique. |
| `participant_account_id` / `participant_role` | Server-authenticated assigned `USER` or `SPECIALIST`; never accepted from a client claim. |
| `evidence_type` | `CHECK_IN`, `PRESENCE_INTERVAL`, or `ACCEPTED_MESSAGE`. |
| `interval_started_at` | Required only for a server-observed presence interval, capped at 60 seconds per fact. |
| `message_id` | Realtime-owned accepted-message UUID required only for message evidence; it is not message content. |
| `occurred_at` | Server-observed occurrence used for the half-open appointment-window calculation. |
| `received_at` | Consultation receipt time used to enforce grace and reconciliation deadlines. |

### `consultation.appointment_dispute`

MB-619's one-per-appointment dispute aggregate. It stores only operational reason/provenance and bounded evidence metadata; raw chat, ConsultationBrief, Journal, assessment, recording, private notes, and clinical conclusions are prohibited.

| Field | Purpose |
| --- | --- |
| `id` / `appointment_id` | Stable dispute identity and unique contested appointment. |
| `appointment_version` | Exact appointment version observed when the dispute opened. |
| `opened_by_account_id` / `opened_by_role` | Server-authenticated participant provenance; role is `USER` or assigned `SPECIALIST`. |
| `reason_code` | Stable bounded operational reason; never free-text session content. |
| `evidence_type` / `evidence_occurred_at` | Optional minimized operational metadata identifying an access, connection, or provider fact and its occurrence time. |
| `opened_at` / `eligible_until` | Server timestamps proving the command was accepted inside the 24-hour post-settlement window. |
| `status` | `OPEN` gates future earning eligibility; `RESOLVED` is immutable. |
| `resolution_outcome` / `resolution_reason` | Bounded admin decision and stable non-clinical rationale. |
| `resolved_by` / `resolved_at` | Resolving administrator and server audit time. |
| `prior_appointment_status` / `prior_session_outcome` | Exact immutable source facts observed during resolution. |
| `resulting_appointment_status` / `resulting_session_outcome` | Explicit resulting facts; MB-619 does not silently rewrite them. |
| `credit_action` | Exact `NONE`, `ALREADY_AVAILABLE`, or `ADJUSTED_RELEASED` result. |
| `open_idempotency_key` / `resolution_idempotency_key` | Stable command identities for replay-safe open and resolution. |
| `created_at` / `updated_at` / `version` | UTC persistence timestamps and optimistic resolution version. |

### `consultation.specialist_client_continuity_audit`

Content-free MB-592 evidence that an authenticated specialist attempted to load
the bounded current/recent client relationship projection. It records neither
client identifiers nor health, assessment, journal, chat, or summary content.

| Field | Purpose |
| --- | --- |
| `id` | Immutable audit-event UUID. |
| `specialist_account_id` | Identity specialist UUID whose current approval and appointment relationships were evaluated. |
| `action` | Bounded operation name; MB-592 permits only `LIST`. |
| `outcome` | `ALLOWED` when the bounded projection was returned or `DENIED` when current specialist eligibility failed closed. |
| `reason_code` | Stable content-free authorization result used for privacy review and operational investigation. |
| `relationship_count` | Number of relationship rows returned; always zero for a denied attempt and never identifies a client. |
| `occurred_at` | Immutable server UTC instant at which Consultation evaluated the request. |

### `consultation.session_summary`

Append-only MB-385 snapshot published by the assigned specialist only after the appointment has evidence-backed `COMPLETED` authority. Amendments create a new row and never rewrite the prior user-visible version.

| Field | Purpose |
| --- | --- |
| `id` | Immutable summary-snapshot UUID. |
| `appointment_id` | Completed owner appointment; version numbering is scoped to this appointment. |
| `user_account_id` / `specialist_account_id` | Denormalized user owner and publishing specialist provenance. |
| `summary_version` / `schema_version` | Monotonic appointment version and bounded `session-summary-v1` payload contract. |
| `topics_discussed` | One to eight short, user-visible, non-diagnostic topics. |
| `progress_summary` / `specialist_note_for_user` | Optional bounded user-visible text; private notes, raw journals, assessment answers, and chat transcript are excluded. |
| `follow_up_suggested` | Non-binding indication that another appointment may be useful. |
| `amends_summary_id` | Prior immutable snapshot corrected by this version; null only for version one. |
| `idempotency_key` / `request_hash` | Specialist-scoped command replay and conflicting-reuse evidence. |
| `published_at` | Immutable server publication instant. |

### `consultation.agreed_next_step`

Immutable steps agreed in one published summary. They are user-visible suggestions and never become Care SupportPlan occurrences automatically.

| Field | Purpose |
| --- | --- |
| `id` / `summary_id` / `ordinal` | Stable step identity and display order within an immutable summary version. |
| `step_type` | Bounded checklist, journal, emotion check-in, reassessment, follow-up appointment, or platform resource type. |
| `title` / `details` | Bounded user-visible action wording. |
| `resource_id` / `resource_version` | Exact Content resource proposal required only for `PLATFORM_RESOURCE`; Care revalidates any later plan change under MB-560. |
| `resource_proposal_reason_code` | Required bounded rationale for `PLATFORM_RESOURCE`: post-consultation continuity, an alternative resource, or an addressed reported barrier; null for every other step type. |
| `created_at` | Server creation instant inherited from summary publication. |

### `consultation.agreed_next_step_state`

Mutable checklist projection controlled only by the user. It is intentionally excluded from specialist reads and is not clinical monitoring.

| Field | Purpose |
| --- | --- |
| `next_step_id` / `user_account_id` | One state row for the owning user's immutable agreed step. |
| `state` | User-selected `PENDING`, `COMPLETED`, or `SKIPPED`. |
| `hidden` | User-controlled visibility in their own checklist. |
| `version` / `updated_at` | Optimistic concurrency and latest user action instant. |

### `consultation.session_summary_reuse_consent`

Separate user consent for future reuse of one exact summary snapshot. Appointment-preparation sharing does not imply this consent, and revocation blocks later handoff reads.

| Field | Purpose |
| --- | --- |
| `summary_id` / `user_account_id` | Exact immutable snapshot and owning user. |
| `approved` | Current user decision for future ConsultationBrief reuse. |
| `version` / `updated_at` | Optimistic concurrency and latest decision instant. |
| `approved_at` / `revoked_at` | Auditable approval or latest revocation instant without copying the summary. |

### `consultation.appointment_outbox_event`

Transactional handoff of one minimized appointment lifecycle version to the
Content/Notification reminder consumer. The row commits with the appointment
transition and excludes email addresses and consultation content.

| Field | Purpose |
| --- | --- |
| `id` | Stable Kafka `messageId` reused across relay attempts. |
| `appointment_id`, `appointment_version` | Unique aggregate/version identity preserving per-appointment order and dedupe. |
| `correlation_id` | Safe request/job correlation UUID. |
| `payload` | Minimized user/specialist owner IDs, status, UTC start, modality, and replacement link only. It excludes Journal, assessment, consultation brief, session summary, and chat bodies. |
| `occurred_at` | Authoritative transition instant. |
| `published_at` | Kafka acknowledgement instant; null while pending. |
| `attempt_count`, `next_attempt_at` | Bounded observable relay retry/lease state. |
| `created_at` | Immutable insertion instant. |

### `consultation.consultation_admin_audit_outbox`

Consultation-owned durable transactional outbox for administrator actions (specialist profile approve, reject, suspend, restore). The row commits in the same local transaction as the profile and status history update, and is relayed to Kafka topic `mentalbridge.admin.audit-event.v1`.

| Field | Purpose |
| --- | --- |
| `id` | Immutable event UUID matching the `eventId` in the audit event fact. |
| `deduplication_key` | Stable unique command idempotency key preventing duplicate outbox entries on retried actions. |
| `event_type` | Stable language-neutral event type (e.g. `consultation.specialist.approved`). |
| `correlation_id` | End-to-end trace correlation UUID passed from caller ingress. |
| `target_account_id` | Nullable specialist account UUID subject of the administrative action. |
| `payload` | Strict minimized `administration-audit-event-v1` JSON object; excludes clinical notes and sensitive credentials. |
| `occurred_at` | Authoritative UTC timestamp of the administrative decision. |
| `published_at` | Nullable UTC instant when published and acknowledged by Kafka; null while pending. |
| `attempt_count` | Non-negative relay retry counter for observable bounded backoff. |
| `next_attempt_at` | Scheduled UTC instant for the next retry attempt when relay fails. |
| `created_at` | Immutable row creation timestamp. |

### `consultation.subscription_plan_version`

Immutable price, allocation, credit, revenue-share, and cancellation policy purchased by a subscription period.

| Field | Purpose |
| --- | --- |
| `id` | Immutable plan-version UUID referenced by subscriptions, upgrades, payments, and credits. |
| `plan_code` | Stable parent plan identity. |
| `version` | Immutable version within the plan; published versions are never rewritten. |
| `currency` | Contract-fixed `VND` currency for every amount. |
| `price_minor` | Immutable monthly VND price. New MB-622 sandbox versions use PLUS 5,000/PREMIUM 10,000; retired v2 versions retain 1,390,000/3,490,000 without historical rewriting. |
| `pricing_policy_version` | Immutable rule: ordinary/historical `upgrade-offset-v1` or synthetic `sandbox-full-price-v1`; sandbox upgrade applies zero offset and cannot enable real payout. |
| `billing_period_months` | Calendar-month period count; MB-515 supports exactly one month. |
| `consultation_credits_per_period` | Credits granted for the active period: FREE 0, PLUS 4, or PREMIUM 10; unused credits never roll over. |
| `max_active_reservations` | Frozen active appointment cap: FREE 0, PLUS 2, or PREMIUM 4. |
| `non_consultation_value_minor` | Versioned price allocation for non-consultation features, used for upgrade calculation without runtime FX. |
| `credit_value_minor` | Fixed per-credit allocation; paid v2 versions store 300,000 VND so earnings are not derived from the whole package. |
| `specialist_share_bps` | Specialist share in basis points snapshotted into each credit; 7000 means 70%. |
| `ai_quota_code` | Stable quota/configuration code frozen with the purchased plan version. |
| `support_plan_enabled` | Frozen plan decision controlling SupportPlan entitlement for the period. |
| `effective_from` | UTC instant at which the version may be offered to new purchases. |
| `retired_at` | Optional UTC instant after which new purchases cannot select the version; historical records remain valid. |
| `created_at` | Immutable UTC insertion instant. |

### `consultation.subscription_plan_entitlement`

Allow-list of feature decisions attached to one immutable plan version.

| Field | Purpose |
| --- | --- |
| `plan_version_id` | Plan version that authoritatively includes the capability. |
| `entitlement_code` | Stable capability code returned through narrow current-entitlement decisions; safety guidance is never gated here. |

### `consultation.user_subscription`

Authoritative paid-subscription lifecycle for one user. Absence of an active
paid row means `FREE` entitlement. MB-515 exposes new purchase and
`PLUS`-to-`PREMIUM` upgrade only, with no downgrade/refund API.

| Field | Purpose |
| --- | --- |
| `id` | Immutable subscription UUID used by payment, upgrade, status, and audit records. |
| `user_id` | External Identity user UUID owning the subscription; not proof of current request authorization. |
| `plan_version_id` | Current immutable paid-plan version; an applied upgrade changes this reference while upgrade history preserves the prior version. |
| `status` | Current lifecycle state: `PENDING_PAYMENT`, `ACTIVE`, or terminal `ENDED`. |
| `current_period_start` | UTC start of the active provider billing period; null while initial payment is pending. |
| `current_period_end` | UTC exclusive end of the current billing period used for benefit/credit expiry and exact upgrade seconds. |
| `ended_at` | UTC instant the paid subscription ceased being current; null while active/pending. |
| `idempotency_key` | User-scoped creation retry key returning the original subscription outcome. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest lifecycle, period, or plan change. |
| `version` | Optimistic-lock counter protecting renewal, cancellation, and upgrade races. |

### `consultation.subscription_status_history`

Append-only timeline of subscription status transitions.

| Field | Purpose |
| --- | --- |
| `id` | Immutable transition UUID. |
| `subscription_id` | Subscription whose authoritative status changed. |
| `from_status` | Previous status; null only for initial creation. |
| `to_status` | New validated status. |
| `reason_code` | Required stable machine-readable reason without provider payload text. |
| `changed_at` | UTC instant the transition committed. |

### `consultation.payment_transaction`

MoMo-backed payment attempt and current externally confirmed outcome. `FAKE` may exercise the same MoMo-shaped contract in local/CI; it is not a second production payment method. The MVP creates no refund transaction.

| Field | Purpose |
| --- | --- |
| `id` | Immutable payment UUID exposed in bounded payment history. |
| `subscription_id` | Paid subscription receiving an initial purchase or upgrade. |
| `target_plan_version_id` | Exact immutable plan version whose price and rights were used to create this attempt. |
| `payment_provider` | `MOMO` in real environments or MoMo-shaped `FAKE` in local/CI; no other production provider is supported. |
| `momo_order_id` | Merchant-generated MoMo `orderId`, unique per provider and used as the primary IPN correlation key. |
| `momo_request_id` | Merchant-generated MoMo `requestId`, unique per provider and matched exactly on IPN. |
| `momo_trans_id` | Positive MoMo transaction ID when issued. Zero/unusable values are not copied from a failed IPN; positive values are unique. |
| `amount_minor` | Exact positive amount requested/confirmed in currency minor units. |
| `currency` | ISO 4217 currency validated against the plan/upgrade quote. |
| `status` | `PENDING`, `SUCCEEDED`, `FAILED`, or externally reported `CHARGEBACK`; refund states are unsupported. |
| `purpose` | Distinguishes historical purposes. New v2 user-facing operations create only purchase or upgrade attempts; no downgrade/refund purpose exists. |
| `momo_result_code` | Latest verified MoMo `resultCode`; only final code `0` can produce `SUCCEEDED`. |
| `momo_pay_type` | Verified MoMo `payType`, such as QR or app payment. |
| `idempotency_key` | Subscription-scoped payment retry key preventing duplicate attempts. |
| `momo_response_time_epoch_ms` | Exact verified MoMo millisecond timestamp retained for signature/reconciliation evidence. |
| `provider_occurred_at` | UTC instant parsed from verified MoMo `responseTime`; null while checkout is pending. |
| `paid_at` | Provider-confirmed UTC success instant; null until successful. |
| `failed_at` | UTC terminal failure instant; null unless failed. |
| `expires_at` | UTC deadline after which the pending attempt can no longer activate rights. |
| `checkout_url` | Nullable safe provider redirect. Null while durable checkout preparation is awaiting an unconfirmed provider response (including timeout); no redirect or automatic charge resend. Verified callback may finalize the payment even when null. Historical URLs are unchanged. Never authorization evidence. |
| `qr_code_url` | Optional safe MoMo QR URL returned with checkout. |
| `created_at` | Immutable UTC attempt creation instant. |
| `updated_at` | UTC instant of the latest verified payment-state change. |
| `version` | Optimistic-lock counter preventing conflicting webhook/status application. |

### `consultation.momo_payment_ipn`

Verified, deduplicated receipt of the selected MoMo One-Time Payment v2 IPN contract. Invalid signatures/malformed payloads produce bounded metrics and safe diagnostics but do not persist attacker-controlled fields. Raw payload, signature, `orderInfo`, and `extraData` are discarded after hashing.

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable receipt UUID. |
| `payment_id` | Matched local payment after signature and partner/order/request/amount validation; null for a verified but unknown order. |
| `contract_version` | MoMo DTO/signature-policy version defining required/optional fields and result handling. |
| `deduplication_key` | SHA-256 key derived from contract version plus verified `partnerCode|orderId|requestId|transId|resultCode|responseTime`. |
| `partner_code` | Verified merchant `partnerCode`, matched to environment configuration. |
| `order_id` | Verified MoMo `orderId`, matched to local `momo_order_id`. |
| `request_id` | Verified MoMo `requestId`, matched to local `momo_request_id`. |
| `amount_minor` | Verified VND amount; MoMo VND has no fractional minor digits. |
| `currency` | Contract-fixed `VND`; a compatible VND plan version is required before real payment. |
| `order_info_sha256` | Hash of required signed `orderInfo`; raw provider text is not persisted. |
| `order_type` | Verified MoMo order type included in the canonical signature. |
| `trans_id` | Exact verified MoMo `transId`, including provider sentinel values needed for receipt evidence. |
| `result_code` | Verified MoMo result code. Only final `0` may activate subscription. |
| `result_message` | Bounded provider-owned description associated with the result code; never used to decide status. |
| `pay_type` | Verified MoMo payment channel included in the signature. |
| `response_time_epoch_ms` | Exact positive provider timestamp included in the signature and deduplication key. |
| `extra_data_sha256` | Hash of required signed `extraData`; arbitrary decoded content is never stored or trusted. |
| `provider_occurred_at` | UTC instant parsed from `responseTime`. |
| `safe_optional_details` | Versioned allow-list for non-sensitive optional MoMo fields such as `paymentOption`/`userFee`; unknown fields are discarded, not copied wholesale. |
| `payload_sha256` | Integrity hash for reconciliation; it cannot reconstruct the prohibited raw payload. |
| `signature_key_version` | Identifier of the checksum/access-key configuration used for validation, never the secret itself. |
| `signature_verified_at` | UTC instant complete MoMo HMAC-SHA256 validation succeeded; required because only verified IPNs are persisted. |
| `processing_status` | Receipt outcome: received, processed, verified-but-unmatched, or internal processing failed. |
| `failure_code` | Stable safe diagnostic; null when processing succeeds. |
| `received_at` | UTC instant MentalBridge received the event. |
| `processed_at` | UTC instant business effects committed; null while unprocessed. |
| `acknowledged_at` | Best-effort UTC evidence that HTTP 204 was emitted; latency metrics remain authoritative for the 15-second SLA. |

### `consultation.subscription_upgrade`

Immutable calculation snapshot and workflow for the only supported v2
in-period change, `PLUS` to `PREMIUM`. Historical Premium Care/Premium Plus
rows retain their original plan-version provenance.

| Field | Purpose |
| --- | --- |
| `id` | Immutable upgrade UUID exposed in quote/status operations. |
| `subscription_id` | Current paid subscription being upgraded. |
| `from_plan_version_id` | `PLUS` version used for remaining-value calculation; historical v1 upgrade references remain valid. |
| `to_plan_version_id` | Higher `PREMIUM` version that starts a full new period after verified payment. |
| `payment_id` | Required unique upgrade payment attempt created with the quote in one transaction. |
| `old_period_start` | Snapshotted UTC start of the period being ended. |
| `old_period_end` | Snapshotted UTC end used to calculate actual total/remaining seconds. |
| `total_period_seconds` | Exact positive number of seconds in the old provider period; never assumed to be 30 days. |
| `remaining_period_seconds` | Exact positive seconds remaining at quote time, not greater than total seconds. |
| `remaining_feature_value_minor` | `floor(old non-consultation value × remaining / total)` in minor units. |
| `available_credit_value_minor` | Sum of allocations for old credits atomically moved from available to upgrade-held. |
| `offset_minor` | Applied non-withdrawable offset: sum of remaining feature and held-credit value for upgrade-offset-v1; exactly zero for sandbox-full-price-v1. Nominal credit value stays recorded separately. |
| `pricing_policy_version` | Snapshotted pricing rule selected from both immutable plan versions; historical rows default to upgrade-offset-v1. Mixed policy upgrades are rejected. |
| `amount_due_minor` | Exact target full-period price minus offset; positive and verified against payment. |
| `currency` | ISO 4217 currency shared by both plan versions, held credits, and payment. |
| `status` | Pending payment, applied, failed, or expired; only one pending upgrade per subscription. |
| `idempotency_key` | Subscription-scoped retry key returning the original quote/checkout. |
| `quoted_at` | UTC calculation instant used to determine remaining seconds. |
| `quote_expires_at` | UTC instant after which held credits must be released or expired. |
| `applied_at` | UTC instant verified payment atomically activated `PREMIUM`; null until applied. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest workflow-state change. |
| `version` | Optimistic-lock counter protecting webhook/expiry races. |

### `consultation.subscription_upgrade_credit`

Exact old credits held and valued by one upgrade quote.

| Field | Purpose |
| --- | --- |
| `upgrade_id` | Upgrade whose offset includes the credit. |
| `credit_id` | Unique credit held by at most one upgrade; reserved/consumed credits cannot appear. |
| `allocated_value_minor` | Allocation snapshot included in the quote, retained for deterministic reconciliation. |

## Conceptual owner `care` (`mentalbridge_care.public`)

### `care.specialist_access_grant`

Explicit, scoped, time-bounded and revocable user permission for one specialist.
ADR 0014 requires each runtime grant to identify the appointment and immutable
user-approved `ConsultationBrief`; the current logical baseline predates those
fields and is not implementation-ready for specialist sharing.

| Field | Purpose |
| --- | --- |
| `id` | Immutable grant UUID cited in authorization decisions and audit events. |
| `user_id` | Care profile granting access to owned sensitive resources. |
| `specialist_id` | External Consultation specialist UUID receiving access; current approval must still be checked. |
| `purpose` | User-visible approved purpose that constrains use and supports audit. |
| `starts_at` | UTC instant at which the grant starts authorizing reads. |
| `expires_at` | Optional UTC end of authorization; null only for a policy-permitted open-ended grant. |
| `granted_at` | UTC instant the user explicitly created the grant. |
| `revoked_at` | UTC instant revocation became effective; null while not revoked. |
| `revoke_reason` | Optional reviewed reason retained for the user and audit history. |
| `created_at` | Immutable UTC database insertion instant. |

### `care.specialist_access_scope`

Allowed resource categories belonging to one specialist access grant.

| Field | Purpose |
| --- | --- |
| `grant_id` | Grant whose authorization is narrowed by this scope. |
| `scope` | Stable resource category the specialist may read while the grant is active. |

### `care.specialist_journal_access`

Explicit journal-entry allow-list for grants containing journal access.

| Field | Purpose |
| --- | --- |
| `grant_id` | Grant providing the parent purpose, specialist, time window, and revocation state. |
| `journal_entry_id` | External Journal/AI entry UUID explicitly selected by the user; it is not proof the entry still exists. |

## Conceptual owner `consultation` (`mentalbridge_consultation.public`)

### `consultation.availability_slot`

Authoritative half-open 60-minute online slot published by an approved
specialist. MB-362 implements the v2 shape without PracticeLocation, phone, or
external meeting-link fields. Historical in-person appointment snapshots, if
introduced by a historical migration, remain separate and readable.

| Field | Purpose |
| --- | --- |
| `id` | Immutable slot UUID used in booking REST commands. |
| `specialist_account_id` | Consultation-owned specialist profile that owns the interval and serializes publication. |
| `start_at` | Inclusive UTC start instant of the available interval. |
| `end_at` | Exclusive UTC end instant, required to be later than start. |
| `timezone` | IANA timezone captured for stable human schedule rendering. |
| `modality` | Online mode; only `IN_APP_CHAT` or capability-gated `IN_APP_VIDEO` is accepted. |
| `status` | Authoritative `ACTIVE` or `WITHDRAWN` state used by the overlap exclusion constraint. |
| `idempotency_key` | Printable caller retry key unique per specialist; exact replay returns the same slot identity and current state, while conflicting reuse is rejected. |
| `withdrawn_at` | UTC instant at which the owner withdrew the future slot; null while active and retained for tombstone audit. |
| `created_at` | Immutable UTC slot creation instant. |
| `updated_at` | UTC instant of the latest slot state or schedule change. |
| `version` | Optimistic-lock counter preventing lost concurrent slot updates. |

### `consultation.specialist_earning`

Implemented by MB-516. One immutable VND allocation is created in the same
transaction as an evidence-backed `COMPLETED` appointment and exact credit
consumption. Elapsed time, `SESSION_ENDED`, no-show, cancellation, and released
or forfeited credits do not create an earning.

| Field | Purpose |
| --- | --- |
| `id` | Immutable earning UUID shown in specialist/admin history. |
| `appointment_id` | Unique completed appointment proving that one earning may exist. |
| `completion_fact_id` | Unique opaque completion evidence fact, preventing any second earning for the same completion. |
| `consumed_credit_id` | Unique consumed credit whose allocation funds the earning. |
| `specialist_account_id` | Specialist who completed the appointment and owns the payable amount. |
| `plan_version` | Entitlement/plan version snapshotted from the consumed credit period. |
| `currency` | ISO 4217 currency of all amounts in the earning. |
| `credit_allocation_minor` | Exact fixed allocation snapshot, currently 300,000 VND per consumed credit. |
| `specialist_share_bps` | Revenue-share snapshot, currently 7000 (70%). |
| `specialist_amount_minor` | Exact specialist amount, currently 210,000 VND. It is never derived from subscription price. |
| `platform_allocation_minor` | Remaining 90,000 VND allocation. This is not represented or described as platform profit. |
| `idempotency_source` | Stable completion-fact source preventing duplicate creation on settlement replay. |
| `status` | Settlement/payout state. `PAID` requires a linked payout with verified `SUCCEEDED` provider outcome. |
| `earned_at` | UTC appointment-completion instant. |
| `settlement_available_at` | UTC instant seven days after earning at which it becomes payout eligible. |
| `paid_at` | UTC verified payout-success instant; null until `PAID`. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest settlement/payout/reversal state change. |
| `version` | Optimistic-lock counter protecting settlement and payout races. |

### `consultation.specialist_payout_destination`

Encrypted specialist-owned destination used by a provider payout adapter. Raw wallet/account data never enters logs, events, or broad read models.

| Field | Purpose |
| --- | --- |
| `id` | Immutable destination UUID referenced by payout requests. |
| `specialist_account_id` | Specialist who owns and may manage this destination. |
| `payout_provider` | `MOMO` in real environments or MoMo-shaped `FAKE` locally; only MoMo may be used in production after credential approval. |
| `destination_type` | Allow-listed provider route: MoMo wallet or domestic bank account. |
| `destination_ciphertext` | Encrypted provider-required wallet/account details; never returned as stored ciphertext to clients. |
| `encryption_key_version` | Key identifier needed for controlled rotation and decryption. |
| `destination_fingerprint` | One-way normalized fingerprint used for duplicate detection without revealing destination data. |
| `display_hint` | Safe masked label, such as a last-four hint, for specialist confirmation. |
| `status` | Verification/availability state; only `VERIFIED` destinations accept new payouts. |
| `verified_at` | UTC provider/application verification instant; required for `VERIFIED`. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest verification or disablement change. |
| `version` | Optimistic-lock counter protecting concurrent destination changes. |

### `consultation.specialist_payout`

Idempotent provider payout request and its reconciled current outcome. Local/CI
uses a fake adapter; real domestic payout stays disabled until exact VND plan
prices, fixed `creditAllocation`, and MoMo credentials exist. Runtime FX is not
allowed.

| Field | Purpose |
| --- | --- |
| `id` | Immutable payout request UUID. |
| `specialist_account_id` | Specialist receiving the attached available earnings. |
| `destination_id` | Verified encrypted payout destination selected for this request. |
| `currency` | ISO 4217 currency shared by every attached earning. |
| `amount_minor` | Exact positive requested amount in minor units, equal to attached payout items. |
| `payout_provider` | `MOMO`/`FAKE` namespace used for request, status, and IPN reconciliation. |
| `status` | `PENDING`, `PROCESSING`, `SUCCEEDED`, `FAILED`, or uncertainty-preserving `UNKNOWN`. |
| `idempotency_key` | Specialist-scoped command key preventing duplicate logical payout creation. Provider attempts derive separate keys. |
| `requested_on` | Server-local request date enforcing the planned maximum of one payout request per specialist per day. |
| `requested_at` | UTC instant MentalBridge created the payout request. |
| `completed_at` | UTC instant a verified provider attempt proved the logical payout succeeded; required for `SUCCEEDED`. |
| `last_failure_code` | Safe diagnostic from the latest definite failure; never contains destination/raw payload data. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest verified reconciliation change. |
| `version` | Optimistic-lock counter protecting duplicate callbacks/status updates. |

### `consultation.specialist_payout_attempt`

Immutable provider-call lineage for one logical payout. A definite failed attempt may be followed by a new numbered attempt; an `UNKNOWN` attempt must be queried/reconciled and blocks a new transfer attempt.

| Field | Purpose |
| --- | --- |
| `id` | Immutable provider-attempt UUID. |
| `payout_id` | Logical payout whose fixed earnings/destination/amount are being sent. |
| `payout_provider` | `MOMO`/`FAKE` namespace, constrained to match the parent payout. |
| `attempt_number` | Monotonic sequence within the logical payout. |
| `provider_idempotency_key` | Unique provider-scoped transfer key; retries of the same network call reuse it. |
| `provider_payout_reference` | Provider transfer identity when known, unique within provider. |
| `status` | Provider-call outcome including `UNKNOWN` for timeout/ambiguous delivery. |
| `requested_at` | UTC instant the attempt was sent or queued for sending. |
| `provider_confirmed_at` | UTC instant verified provider evidence proved success. |
| `failed_at` | UTC instant a definite terminal failure was verified. |
| `failure_code` | Safe stable failure code without sensitive payload data. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest provider reconciliation update. |
| `version` | Optimistic-lock counter protecting callback/status-query races. |

### `consultation.specialist_payout_item`

Immutable allocation of available earnings to one payout request.

| Field | Purpose |
| --- | --- |
| `payout_id` | Provider payout request containing the earning. |
| `earning_id` | Unique earning attached to at most one payout. |
| `amount_minor` | Exact portion requested from this earning; it becomes paid only when the parent payout succeeds. Current flow uses the full available specialist amount. |

### `consultation.specialist_payout_status_history`

Append-only lifecycle evidence for a payout request, including uncertainty and reconciliation.

| Field | Purpose |
| --- | --- |
| `id` | Immutable transition UUID. |
| `payout_id` | Payout whose current status changed. |
| `from_status` | Prior status; null for initial creation. |
| `to_status` | New validated status based on local command or verified provider evidence. |
| `reason_code` | Safe machine-readable reason without raw provider/destination data. |
| `changed_at` | UTC instant the transition committed. |

### `consultation.payout_provider_event`

Deduplicated receipt of payout IPN/callback events. Only the integrity hash and safe processing metadata are retained.

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable event-receipt UUID. |
| `payout_attempt_id` | Optional resolved local attempt; null until the provider identity can be correlated safely. |
| `payout_provider` | `MOMO`/`FAKE` namespace for event identity and verification policy. |
| `provider_event_id` | Provider event identity, unique within the provider for replay protection. |
| `event_type` | Allow-listed event category used for reconciliation routing. |
| `payload_sha256` | Integrity hash; raw callback payload and destination data are not stored here. |
| `processing_status` | Receipt state: received, processed, safely rejected, or failed. |
| `failure_code` | Stable safe processing diagnostic; null on success. |
| `received_at` | UTC instant MentalBridge received the callback. |
| `processed_at` | UTC instant its verified effect committed; null while unresolved. |

### `consultation.appointment_rating`

One current bounded rating tied to an evidence-backed completed appointment.
MB-364 does not collect a comment, anonymous flag, diagnosis, or moderation
payload.

| Field | Purpose |
| --- | --- |
| `appointment_id` | Primary key and local foreign key proving exactly one current rating opportunity for the completed appointment. |
| `user_account_id` | External Identity owner UUID copied from the locked appointment; callers cannot supply it. |
| `specialist_account_id` | Local specialist foreign key copied from the locked appointment for aggregate maintenance. |
| `rating` | Validated integer rating from one through five. |
| `created_at` | Immutable UTC review submission instant. |
| `updated_at` | UTC instant of the latest owner edit. |
| `version` | Optimistic-lock counter preventing lost rating edits. |

### `consultation.specialist_rating_aggregate`

Transactionally maintained display/ranking projection. It contains no review
text and is never accepted from a client.

| Field | Purpose |
| --- | --- |
| `specialist_account_id` | Primary/local foreign key for the rated specialist. |
| `rating_count` | Number of distinct current appointment ratings; always positive while the row exists. |
| `rating_sum` | Exact sum used with count to derive a two-decimal average without accumulating rounding error. |
| `version` | Monotonic aggregate update counter for operational verification. |
| `updated_at` | UTC instant of the latest create/edit contribution. |

## Conceptual owner `care` (`mentalbridge_care.public`)

### `care.follow_up_plan`

Scheduled follow-up workflow linked to an intervention or appointment.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID identifying the follow-up workflow. |
| `user_id` | Care profile that owns and receives the follow-up. |
| `intervention_plan_id` | Optional originating Care intervention; one origin link is required. |
| `appointment_id` | Optional external Consultation appointment origin; one origin link is required. |
| `status` | Authoritative workflow state controlling future scheduling. |
| `next_due_at` | UTC next eligible check-in/reminder instant; null when no next occurrence is scheduled. |
| `cadence_days` | Positive interval in calendar days used to calculate recurring follow-up. |
| `created_by` | Identity account or approved actor that initiated the plan. |
| `created_at` | Immutable UTC plan creation instant. |
| `updated_at` | UTC instant of the latest schedule or status change. |
| `version` | Optimistic-lock counter preventing duplicate/concurrent scheduling changes. |

### `care.follow_up_check_in`

User response captured for one occurrence of a follow-up plan.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID identifying the check-in response. |
| `plan_id` | Follow-up plan that requested the check-in. |
| `mood_score` | Optional user-reported integer mood value from one through ten. |
| `note_ciphertext` | Optional encrypted private note; plaintext is prohibited in this relational field and logs. |
| `assessment_submission_id` | Optional assessment completed as part of this check-in. |
| `submitted_at` | UTC instant the check-in was accepted. |

## Conceptual owner `content` (`mentalbridge_content_notification.public`)

### `content.resource`

Reviewed self-help content or external resource managed by Content/Notification Service.

The fixed UUID `00000000-0000-4000-8000-000000000101` identifies a visibly labeled synthetic Review 1 resource. Migration 3 inserts it idempotently only when the pre-production migration session explicitly enables the Review 1 seed setting. It is not created in production by default and carries no production clinical approval.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID used in REST resources and intervention action targets. |
| `category` | Stable content category used for browsing and recommendation rules. |
| `locale` | BCP 47 locale of the reviewed content. |
| `title` | Reviewed user-facing title. |
| `summary` | Reviewed short description used in listings. |
| `content_body` | Optional reviewed first-party content body; one body or external URL is required. |
| `external_url` | Optional reviewed external destination; required for `VIDEO` and restricted to verified YouTube URLs. A resource may also carry a reviewed body. |
| `source_organization` | Organization responsible for the referenced source. Required for new published catalogue resources. |
| `source_title` | Human-readable title of the referenced source. Required for new published catalogue resources. |
| `source_url` | HTTP(S) provenance URL, distinct from the user action in `external_url`. Required for new published catalogue resources. |
| `source_review_note` | Review/licensing note documenting how MentalBridge adapted and checked the source. Required for new published catalogue resources. |
| `resource_kind` | Product meaning independent of media format: `LEARNING`, `PRACTICE`, `HABIT`, `ACTION`, or `REFLECTION`. |
| `interaction_type` | Renderer key for the reviewed experience. Unknown values must fall back to a reader/checklist and must never imply breathing instructions. |
| `repeatability` | `ONE_TIME` records durable learning completion; `REPEATABLE` records each deliberately confirmed practice session with a client-generated idempotency ID. |
| `completion_mode` | Reviewed completion interaction: explicit confirmation, video confirmation, timed practice, or discrete steps. |
| `streak_eligible` | Whether completed repeatable sessions may contribute to the separate practice streak. Learning and daily checklist state never contribute. |
| `expected_duration_minutes` | Reviewed duration estimate, constrained to 1..120 minutes. |
| `cooldown_days` / `recommended_frequency_per_week` | Gentle scheduling metadata used to avoid mechanical repetition; neither is a clinical adherence target. |
| `plan_tags` | Bounded SupportPlan domain/template-family tags used only after the BFF supplies the authoritative active plan snapshot. |
| `structured_content` | Reviewed section/callout/checklist JSON rendered by the generic reader. It contains no user-authored data. |
| `interaction_config` | Reviewed, interaction-specific configuration such as timed phases or action labels. |
| `safety_notes` | Reviewed user-facing cautions and stop conditions appropriate to the resource. |
| `source_retrieved_at` / `source_content_hash` | Offline ingestion provenance. The optional lowercase SHA-256 hash detects source drift without scraping at runtime. |
| `content_version_label` | Human-readable curated content release label, separate from the optimistic-lock `version`. |
| `source_review_status` | Explicit editorial gate: `REVIEWED`, `REVIEW_REQUIRED`, or `NEEDS_SOURCE_REVIEW`. Only `REVIEWED` rows are eligible for public listing, detail, progress, or daily scheduling. |
| `catalogue_visibility` | `LISTED` for catalogue browsing or `DIRECT_ONLY` for exact-version compatibility links that must not appear in the public list. |
| `status` | Publication lifecycle controlling user visibility: `DRAFT`, `PUBLISHED`, or `ARCHIVED`. |
| `reviewed_by` | Administrator account UUID that approved the content for publication; null before review. |
| `reviewed_at` | UTC instant of the latest administrator approval; null before review. Only rows with both fields set are served to users. |
| `effective_at` | Optional UTC instant before which the resource is not yet active; null means immediately available after review. Used for scheduled content releases. |
| `expires_at` | Optional UTC instant after which the resource is no longer served; null means no expiry. Must be later than `effective_at` when both are set. |
| `created_at` | Immutable UTC content creation instant. |
| `updated_at` | UTC instant of the latest content or publication change. |
| `version` | Optimistic-lock counter preventing lost concurrent content edits. |

### `content.resource_daily_progress`

Owner-scoped, non-clinical participation state for one reviewed resource on one local calendar day. It supports consistent checklist/progress presentation and never changes SupportPlan occurrence history, screening results, or clinical decisions.

| Field                  | Purpose                                                                                                                                                                                                |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `owner_id`             | External Identity account UUID from the verified JWT; it scopes every read and write and is not copied from request content.                                                                           |
| `resource_id`          | Content-owned resource UUID whose reviewed activity the owner followed. The local foreign key prevents progress for an unknown resource.                                                               |
| `local_date`           | User-selected calendar date in `YYYY-MM-DD` form used to group the gentle daily experience; it is not an adherence deadline.                                                                           |
| `resource_version`     | Exact resource version visible when the progress was last saved, retained so later catalogue edits cannot be presented as the content originally followed.                                             |
| `status`               | Lightweight participation state: `IN_PROGRESS` or terminal `COMPLETED`; once confirmed for the resource and local date, later checklist edits cannot downgrade it. Absence of a row means not started. |
| `completed_action_ids` | Bounded identifiers of the user's current interactive-step selections for that resource version; they remain editable after completion and contain no journal, reflection, or other free text.         |
| `completed_at`         | UTC instant when the item first reached `COMPLETED`; null while in progress and preserved permanently after confirmation, including across later checklist edits.                                      |
| `created_at`           | Immutable UTC insertion instant for the daily progress row.                                                                                                                                            |
| `updated_at`           | UTC instant of the latest changed progress state or action set.                                                                                                                                        |
| `version`              | Monotonic mutation counter for diagnosing multi-surface synchronization; identical replacement writes are no-ops.                                                                                      |

The `(owner_id, local_date DESC, updated_at DESC)` index supports bounded weekly history and recent-completion views. Daily completion is encouragement only; missed days do not create rows, penalties, or clinical meaning.

### `content.resource_learning_completion`

Durable owner-scoped proof that a one-time learning resource was confirmed once. The `(owner_id, resource_id)` primary key prevents re-completion from inflating practice streaks; the first completion date/time and exact resource version remain authoritative.

### `content.resource_practice_session`

Repeatable practice history. `id` is a client-generated idempotency key for one deliberate completion, so retries do not duplicate a session while multiple sessions on the same day remain representable. `support_plan_id` must come from the persisted daily assignment; direct/out-of-plan progress never creates streak evidence. `started_at` and `duration_seconds` retain optional measured session metadata. Only sessions for the current plan and resources with `streak_eligible=true` count toward practice streak.

### `content.resource_reward_preference`

Stores only whether the authenticated owner wants the optional reward presentation visible. XP and grants are a deterministic read model over reviewed Content facts and are not persisted in this table.

| Field | Purpose |
| --- | --- |
| `owner_id` | External Identity account UUID from the verified JWT and table primary key. |
| `visibility_state` | `VISIBLE` or `HIDDEN`; hiding has no effect on resource, safety, Support Guide, SupportPlan, or professional access. |
| `version` | Monotonic counter incremented only when the preference changes. |
| `updated_at` | UTC instant of the latest preference change. |

### `content.achievement_preference`

Stores only the authenticated owner's reversible presentation choice for the
private achievement collection. Achievement items are deterministic read-model
records over authoritative owner facts and are not duplicated in this table.

| Field | Purpose |
| --- | --- |
| `owner_id` | External Identity account UUID from the verified JWT and table primary key. |
| `visibility_state` | `VISIBLE` or `HIDDEN`; it has no effect on source records or product access. |
| `version` | Monotonic optimistic-concurrency version incremented on an effective preference change. |
| `updated_at` | UTC instant of the latest effective preference change. |

### `content.resource_bookmark`

Private persistent saved-resource state. The owner is always taken from the
verified JWT and the saved target is an immutable reviewed publication snapshot.

| Field | Purpose |
| --- | --- |
| `owner_id` | External Identity account UUID and first half of the primary key. |
| `resource_id` / `resource_version` | Exact published, reviewed snapshot saved by the owner. |
| `version` | Monotonic counter advanced only when a later current publication version replaces the saved version. |
| `created_at` | Original save instant, retained across exact-version retries and later version refreshes. |
| `updated_at` | Latest effective bookmark change instant. |

### `content.resource_daily_assignment` and `content.resource_daily_assignment_item`

Persisted, owner/date-scoped daily challenge derived from an authoritative active SupportPlan snapshot supplied by the trusted BFF. Materialization is allowed only for local Plan Days 1–14. The header stores timezone, plan identity/version, and plan tags; ordered items store exact resource IDs and a non-clinical selection reason. The unique owner/date key keeps reloads stable even if catalogue ordering changes.

### `content.resource_weekly_bingo` and `content.resource_weekly_bingo_item`

Persisted Monday-based board derived from the same plan snapshot. The unique
owner/week key prevents reshuffling during the week. The board `version` and
`updated_at` provide a strong ETag concurrency boundary. Each item retains the
exact `resource_version` and may store a reversible `self_confirmed_at` owner
statement. The read model computes verified evidence from exact-version,
same-plan daily completion or eligible measured practice; it never copies that
evidence onto the board. Self-confirmation and Content verification remain
separate, and neither a skipped nor a fully checked board has clinical meaning.
For boards predating exact-version persistence, migration binds an item only to
the latest publication snapshot available at the board creation instant. An
item without recoverable publication provenance is removed rather than guessed;
the board header remains authoritative and can be returned with `items: []`.

### `content.notification_preference`

Owner-scoped aggregate containing the shared notification preference contract. A
single row is updated atomically so channel, content-group, quiet-hour, timezone,
and email choices cannot drift across web, email, inbox, or future mobile consumers.

| Field | Purpose |
| --- | --- |
| `user_id` | External Identity account UUID and aggregate owner; it comes only from the verified JWT. |
| `owner_type` | Authoritative `USER` or `SPECIALIST` preference scope. SPECIALIST rows are database-constrained to keep Journal, emotion, screening, streak, Community, wellbeing-digest, and resource-reminder controls disabled. |
| `notifications_enabled` | Top-level user switch for optional notification delivery. |
| `channel_in_app_enabled` | Whether optional in-app delivery is enabled. |
| `channel_email_enabled` | Whether email delivery is enabled; email delivery still requires an applicable behavior and opt-in. |
| `channel_push_enabled` | Persisted future-mobile preference only; it does not imply a registered device or active provider. |
| `group_journal_reminder_enabled` | Independent journal/reminder content-group choice. |
| `group_emotion_check_in_enabled` | Independent emotion check-in content-group choice. |
| `group_streak_milestone_enabled` | Independent streak/milestone content-group choice. |
| `group_screening_reassessment_enabled` | Independent screening/reassessment content-group choice. |
| `group_appointment_message_enabled` | Independent appointment/message content-group choice. |
| `group_resource_system_enabled` | Independent resource/system content-group choice. |
| `group_community_interaction_enabled` | Independent in-app choice for eligible Community comment, reply, and first-reaction facts. Disabled facts are durably recorded as cancelled so replay cannot create a later surprise notification. |
| `quiet_hours_enabled` | Whether non-bypass delivery observes the local quiet window. |
| `quiet_hours_start` | Inclusive local wall-clock start in `time_zone`; later-than-end windows cross midnight. |
| `quiet_hours_end` | Exclusive local wall-clock end in `time_zone`; it must differ from start when enabled. |
| `time_zone` | Validated IANA timezone used for deterministic local-day and daylight-saving interpretation. |
| `email_cadence` | Explicit email cadence: `IMMEDIATE`, `DAILY_DIGEST`, or `WEEKLY_DIGEST`. |
| `email_wellbeing_digest_enabled` | Explicit opt-in for wellbeing digest emails. |
| `email_resource_reminders_enabled` | Explicit opt-in for resource reminder emails. |
| `email_daily_digest_time` | Owner-selected local wall-clock time for the daily digest, interpreted in `time_zone`. |
| `email_resource_reminder_time` | Owner-selected local wall-clock time for the capped resource reminder, interpreted in `time_zone`. |
| `email_appointment_reminders_enabled` | Dedicated default-off opt-in for the single appointment email; digest cadence does not alter it. |
| `version` | Optimistic-lock counter returned in a strong ETag and required by updates. |
| `created_at` | UTC instant when stable defaults were first persisted. |
| `updated_at` | UTC instant the owner last changed this aggregate. |

### `content.wellbeing_email_delivery`

MB-514's auditable email-delivery ledger. The unique owner/local-date/kind key guarantees at-most-once successful delivery for each daily digest or capped resource reminder. `content_counts` stores aggregate non-sensitive counts only; recipient email and Journal, assessment, chat, or provider payload content are never stored. State, bounded attempt count, safe failure code, provider message identifier, and timestamps support retry/audit without duplicating the authoritative preference aggregate.

### `content.notification`

MB-691 extends Community notice delivery with three generic chat/request/invite
categories and closed `OPEN_COMMUNITY_CONVERSATION`, `OPEN_COMMUNITY_REQUEST`,
`OPEN_COMMUNITY_INVITATION` actions. Their opaque target belongs to Community and
never grants access; opening it requires current owner authorization.
`grouping_key` is nullable Content-owned `POST:<id>` or `COMMENT:<id>` metadata
used only to join new reaction notices to an existing active unread item. A read,
tombstone or expiry ends that group; it is not a reaction count or permission.
Chat source identity is `<kind>:<target-id>` per recipient, independent of the
transport message ID. Comment-reaction source identity is `<comment-id>:<actor-profile-id>`.
Existing interaction-v1 event identities remain unchanged for replay compatibility.

Durable in-app notification and safe delivery payload owned by Content/Notification Service.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID used for REST history, Kafka delivery, and read idempotency. |
| `recipient_id` | External Identity account UUID of the intended recipient. |
| `category` | Stable kind used for preference, priority, and presentation rules. MB-564 adds separate Journal/emotion kinds; MB-617 adds Community kinds; MB-565 adds `ASSESSMENT_RESULT_AVAILABLE`, `SUPPORT_GUIDE_AVAILABLE`, and `ACCOUNT_STATUS` while retaining historical generic kinds. |
| `title` | Reviewed/minimized user-visible title safe for the selected channel. |
| `body` | Reviewed/minimized user-visible body that excludes raw sensitive source content. |
| `action_type` | Optional approved internal action enum used to derive a relative client route; MB-565 adds `OPEN_SUPPORT_GUIDE` bound to its exact UUID target, while account status has no action. `OPEN_SPECIALIST_APPOINTMENTS` resolves only to the specialist appointment workspace and arbitrary producer URLs are never stored. |
| `action_target_id` | Optional opaque target UUID for the closed action enum, including exact resource, SupportGuide, Community post, conversation, request or invitation. This pointer never grants access; the owner read handles unavailable targets safely. |
| `grouping_key` | Nullable `POST:<id>` or `COMMENT:<id>` reaction target key; new jobs may join one active unread unexpired item under the recipient preference lock. Reading/deleting ends the group. |
| `priority` | Delivery/presentation priority, not a clinical severity decision. |
| `read_at` | UTC instant the recipient marked the in-app notification read; null while unread. |
| `expires_at` | Required UTC inbox-retention deadline, no later than 90 days after creation; expired rows are tombstoned and no longer returned. |
| `occurred_at` | Authoritative UTC instant of the source event, kept separate from inbox insertion time for delayed delivery. |
| `created_at` | Immutable UTC creation instant used for cursor ordering. |
| `deleted_at` | UTC user/policy tombstone instant; null while visible in history. |
| `source` | Stable bounded producer namespace such as `CONTENT`, `REALTIME`, or `COMMUNITY_INTERACTION_V1`; it carries no provider payload or user-authored text. |
| `source_identity` | Stable identity used with recipient/source to collapse replay. Journal/emotion uses `<kind>:<local-date>`; legacy Community interaction v1 retains `eventId`; chat uses `<kind>:<target-id>`; comment reaction uses `<comment-id>:<actor-profile-id>`. Appointment uses `<appointment-id>:<aggregate-version>`; Care/Identity uses `<fact-type>:<aggregate-id>:<aggregate-version>`. |
| `request_fingerprint` | SHA-256 of the validated minimized create command or Community v1 fact; changed reuse of a dedupe identity is rejected instead of overwriting the original notification. |
| `delivery_state` | Durable delivery lifecycle (`PENDING`, `DELIVERED`, `FAILED`, or `CANCELLED`); only `DELIVERED` records appear in the inbox. |
| `version` | Monotonic mutation counter incremented for the first read or tombstone transition. |

### `content.community_notification_job`

MB-691 Content-owned durable work and receipt ledger. Validated generic copy and
minimal routing/target pointers survive consumer/worker restarts and quiet-hour
deferral. No message body, group/sender name, credential, health field or cached
authorization grant is stored. Inbox insertion/grouping and terminal job outcome
commit together. Exact chat authorization is read remotely before that transaction.

| Field | Meaning and necessity |
| --- | --- |
| `id` | Immutable internal UUID for claiming one delivery job; not exposed as a notification or target ID. |
| `recipient_id` | External Identity recipient reference used for owner-scoped dedupe; never proof of eligibility. |
| `source` | Closed namespace for interaction v1, comment reaction v1 or chat v1, separating their logical identities. |
| `source_identity` | Stable logical action identity, unique with source/recipient, so a new transport envelope cannot repeat the action. |
| `request_fingerprint` | SHA-256 of validated minimized semantic input; changed identity reuse is parked instead of overwriting the original work. |
| `projection` | Generic reviewed copy and closed target/authorization descriptors only; these are pointers, not owner permission decisions. |
| `state` | `PENDING` remains recoverable; `DELIVERED` links to an inbox item, including a grouped item; `CANCELLED` prevents opt-out/revocation replay. |
| `available_at` | UTC earliest next processing time for quiet-hour deferral or bounded dependency retry. |
| `claim_token` | Nullable random worker fencing token; a stale worker cannot complete a job claimed by another instance. |
| `claim_until` | UTC expiry of the 30-second claim; paired with the token and permits crash recovery without a remote call inside a DB transaction. |
| `attempts` | Persisted claim count for capped 1–30-second retry backoff; not a user-visible delivery count. |
| `notification_id` | Nullable physical reference to the one resulting inbox item; multiple reaction jobs may reference one unread grouped notification. |
| `expires_at` | Source occurrence plus 90 days; expired work is not delivered and bounded cleanup removes receipts. Old replay remains expired. |
| `created_at` | Immutable UTC intake instant, separate from authoritative source occurrence and inbox insertion time. |

### `content.appointment_email_reminder`

Content/Notification-owned intent and privacy-safe delivery evidence for one
exact confirmed appointment version. It never stores the email address or
rendered body.

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable reminder UUID. |
| `recipient_id` | External Identity USER owner; part of the dedupe identity. |
| `appointment_id` | External opaque Consultation appointment UUID. |
| `appointment_version` | Exact confirmed aggregate version; stale versions are invalidated. |
| `appointment_status` | Minimized status snapshot, initially `CONFIRMED`. |
| `modality` | Safe `IN_APP_CHAT` or `IN_APP_VIDEO` template fact. |
| `scheduled_start_at` | Authoritative UTC start used as the absolute send cutoff. |
| `target_at` | Exact start-minus-60-minutes policy instant. |
| `due_at` | Current deterministic candidate after late confirmation or quiet-hour deferral. |
| `delivery_state` | Pending/processing or terminal delivered, invalidated, suppressed, failed, expired, or unknown outcome. |
| `attempt_count` | Bounded provider submission count, never greater than three. |
| `first_attempt_at`, `last_attempt_at`, `next_attempt_at` | Safe retry timing and provider-idempotency TTL evidence. |
| `provider_idempotency_key` | Deterministic UUID reused for all attempts of the same reminder identity. |
| `provider_message_id` | Provider acknowledgement identifier; it contains no recipient or message body. |
| `failure_code` | Coarse stable diagnostic category without downstream response text. |
| `delivered_at`, `invalidated_at` | Terminal outcome instants. |
| `created_at`, `updated_at` | Owner-local audit timestamps. |

### `content.appointment_reminder_checkpoint`

Latest consumed Consultation version per appointment. It prevents delayed or
replayed lifecycle events from recreating stale reminder intent.

| Field | Purpose |
| --- | --- |
| `appointment_id` | External Consultation aggregate identity. |
| `latest_version` | Highest applied aggregate version. |
| `latest_status` | Safe latest lifecycle status used for diagnostics. |
| `last_message_id` | Last applied integration-event UUID. |
| `updated_at` | UTC application instant. |

## Conceptual owner `ai` (owner-local `public` tables)

### `ai.analysis_job`

Durable Journal/AI orchestration state for one provider analysis of one journal revision.

| Field | Purpose |
| --- | --- |
| `id` | Immutable job UUID returned by REST and used as the Kafka command correlation key. |
| `user_id` | External Care profile UUID owning the journal and result; not an authorization substitute. |
| `journal_entry_id` | Journal/AI-owned logical entry UUID being analyzed. |
| `journal_revision` | Positive immutable revision number ensuring results cannot be attached to edited text. |
| `provider` | Selected versioned execution provider for this reproducible run. Initial provider scope is OpenAI/Gemini; an optional PhoBERT value is valid only after ADR 0011's activation gate passes. |
| `prompt_version` | Exact prompt/input contract version used to interpret and validate the result. |
| `status` | Authoritative asynchronous job lifecycle used by REST polling and workers. |
| `attempt_count` | Number of claimed execution attempts used to enforce bounded retry. |
| `next_attempt_at` | UTC earliest retry instant; null when no retry is scheduled. |
| `error_code` | Stable safe failure classification; raw provider payloads and journal content are prohibited. |
| `result_document_id` | MongoDB result identifier for a validated completed analysis; null until available. |
| `requested_at` | UTC instant the user/system requested analysis. |
| `started_at` | UTC instant the current/first execution began; null before processing. |
| `completed_at` | UTC terminal completion instant; null while unfinished. |
| `created_at` | Immutable UTC database insertion instant. |
| `updated_at` | UTC instant of the latest job state, retry, or result-link change. |

### `ai.evaluation_dataset`

Governed metadata for a licensed, de-identified benchmark dataset stored privately.

| Field | Purpose |
| --- | --- |
| `id` | Immutable dataset UUID referenced by benchmark runs and sample documents. |
| `name` | Reviewed human-readable dataset identity unique with its version. |
| `version` | Source/content version distinguishing immutable dataset releases. |
| `source_url` | Optional provenance location for source and usage review, not a runtime download instruction. |
| `license_name` | Reviewed license/basis authorizing benchmark use. |
| `object_key` | Unique private object-storage key for the imported dataset artifact. |
| `checksum_sha256` | SHA-256 integrity digest proving the benchmark artifact has not changed. |
| `sample_count` | Validated non-negative number of imported samples for completeness checks. |
| `label_schema` | Versioned structured definition of expected labels and allowed values. |
| `preprocessing_version` | Exact deterministic preprocessing pipeline version required for reproducible evaluation. |
| `status` | Validation/publication lifecycle controlling whether benchmark runs may use the dataset. |
| `imported_by` | Authorized administrator account that initiated and attested the import. |
| `created_at` | Immutable UTC metadata insertion instant. |

### `ai.benchmark_run`

Reproducible execution and metrics for comparing configured models against one dataset.

| Field | Purpose |
| --- | --- |
| `id` | Immutable run UUID used by workers, predictions, and REST status polling. |
| `dataset_id` | Exact governed dataset version evaluated by this run. |
| `configuration` | Validated immutable model, prompt, thresholds, seed, and execution settings. |
| `status` | Authoritative asynchronous benchmark lifecycle. |
| `metrics` | Validated aggregate evaluation metrics; null until sufficient results exist. |
| `started_by` | Authorized research/admin account that requested the run. |
| `started_at` | UTC instant worker execution began; null while pending. |
| `completed_at` | UTC terminal completion instant; null while unfinished. |
| `created_at` | Immutable UTC request/insertion instant. |

## Conceptual owner `platform` (owner-local `public` tables)

### `platform.outbox_event`

Durable local transaction record awaiting publication to a Kafka topic.

| Field | Purpose |
| --- | --- |
| `id` | Immutable message UUID used for Kafka message identity and consumer deduplication. |
| `aggregate_type` | Stable owner aggregate category used for routing and diagnostics. |
| `aggregate_id` | Aggregate UUID used as the Kafka record key to preserve per-aggregate partition ordering. |
| `event_type` | Stable past-tense integration event or explicit asynchronous command name. |
| `schema_version` | Positive JSON contract version used by producers and consumers. |
| `correlation_id` | Workflow/trace grouping UUID propagated from the initiating request; not a business key. |
| `payload` | Validated minimized JSON payload; secrets and raw sensitive content are prohibited. |
| `occurred_at` | UTC instant the business fact committed, distinct from Kafka publication time. |
| `published_at` | UTC instant Kafka acknowledged publication; null while awaiting relay. |
| `attempt_count` | Number of relay publication attempts used for bounded retry and alerting. |
| `next_attempt_at` | UTC earliest next relay attempt after backoff; null for immediate eligibility. |
| `last_error_code` | Latest stable safe relay error category without broker payload or secret details. |

### `platform.audit_event`

Append-only minimized security/business audit fact for sensitive access and administrative changes.

| Field | Purpose |
| --- | --- |
| `id` | Immutable audit UUID used for export and integrity review. |
| `occurred_at` | UTC instant the audited action or decision occurred. |
| `actor_id` | Identity account UUID when a known user acted; null for anonymous/service actors. |
| `actor_type` | Actor category distinguishing account, service, and anonymous actions. |
| `action` | Stable machine-readable action that was attempted. |
| `resource_type` | Stable category of the resource affected or read. |
| `resource_id` | Opaque resource identifier retained for traceability across different identifier types. |
| `subject_user_id` | User whose sensitive data was affected, when different from the actor. |
| `consent_grant_id` | Exact consent grant used to authorize a sensitive action; null when not applicable. |
| `purpose` | Approved purpose for the sensitive access, minimized to avoid content disclosure. |
| `outcome` | Whether the attempt succeeded, was denied, or failed. |
| `correlation_id` | Trace/workflow UUID linking the audit fact to safe distributed diagnostics. |
| `ip_hash` | Privacy-minimized source IP hash used for anomaly investigation. |
| `metadata` | Allow-listed safe structured context; raw journals, chats, answers, tokens, and documents are prohibited. |

### `platform.moderation_case`

Governed workflow for reviewing a reported account, review, or chat message reference.

| Field | Purpose |
| --- | --- |
| `id` | Immutable case UUID exposed to authorized moderation APIs. |
| `reporter_id` | Identity account that submitted the report. |
| `target_type` | Category of referenced object determining the owning service and review policy. |
| `target_id` | Opaque target identifier; content is retrieved only through an authorized owner API. |
| `reason_code` | Stable report reason used for routing and aggregate reporting. |
| `description` | Optional reporter context, treated as untrusted sensitive user input. |
| `status` | Authoritative moderation workflow state. |
| `assigned_admin_id` | Administrator currently responsible for the case; null while unassigned. |
| `resolution_code` | Stable terminal action/reason code; null before resolution. |
| `resolution_note` | Authorized internal rationale minimized to required governance detail. |
| `created_at` | Immutable UTC report/case creation instant. |
| `resolved_at` | UTC instant a terminal resolution was recorded; null while open. |
| `updated_at` | UTC instant of the latest assignment, status, or resolution change. |

### `platform.retention_policy`

Versioned approved rule defining how long one data category may be retained.

| Field | Purpose |
| --- | --- |
| `id` | Immutable policy-row UUID used by deletion/audit workflows. |
| `data_category` | Stable owned data category to which the retention duration applies. |
| `policy_version` | Immutable reviewed policy revision unique within the data category. |
| `retention_days` | Positive maximum retention duration in calendar days. |
| `legal_basis` | Reviewed legal/policy justification for retaining this data category. |
| `active_from` | UTC instant this version begins governing new retention decisions. |
| `active_until` | Optional UTC instant this version stops applying; null while current. |
| `approved_by` | Authorized administrator account that approved the policy version. |
| `created_at` | Immutable UTC database insertion instant. |

### `platform.deletion_request`

User/account deletion workflow coordinating independent data owners without a distributed transaction.

| Field | Purpose |
| --- | --- |
| `id` | Immutable workflow UUID referenced by all deletion tasks. |
| `user_id` | Identity account whose owned data must be deleted or anonymized. |
| `status` | Aggregate deletion workflow state derived from owner task outcomes. |
| `requested_at` | UTC instant the valid deletion request was accepted. |
| `grace_expires_at` | Optional UTC instant after a documented cancellation grace period ends. |
| `started_at` | UTC instant fan-out processing began; null while pending. |
| `completed_at` | UTC instant all required owner tasks reached terminal success; null until complete. |
| `requested_by` | Identity account or authorized administrator that initiated the request. |
| `correlation_id` | Unique workflow correlation UUID used across Kafka commands, tasks, and audit. |
| `last_error_code` | Latest stable safe aggregate failure category; null when no failure is active. |

### `platform.deletion_task`

Idempotent per-data-owner work item belonging to one deletion request.

| Field | Purpose |
| --- | --- |
| `request_id` | Parent deletion workflow UUID. |
| `data_owner` | Stable service/owner name responsible for deleting its own stores and projections. |
| `status` | Authoritative owner-task state used to calculate aggregate request progress. |
| `attempt_count` | Number of processing attempts used to bound retries and alert operators. |
| `completed_at` | UTC instant this owner confirmed terminal completion; null while unfinished. |
| `last_error_code` | Latest stable safe owner failure category without deleted content. |
| `updated_at` | UTC instant of the latest task claim, retry, or status change. |


## Owner `content-notification` (`mentalbridge_content_notification.public`)

### `public.resource`

Reviewed self-help content published through admin workflow, never user-contributed. Each resource requires explicit review approval before publication.

These fields establish reviewed public visibility only. SupportPlan eligibility is a separate immutable exact-version publication; no caller may infer it from this row's review, status, category, or type.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID exposed in REST contracts and used as the primary resource identifier. |
| `category` | Stable resource category for filtering and display: `BREATHING`, `MEDITATION`, `ARTICLE`, `VIDEO`, `JOURNALING`, or `COMMUNITY`. |
| `locale` | BCP 47 locale tag identifying the language/region of the content; defaults to `vi-VN`. |
| `title` | Reviewed user-facing resource title displayed in lists and detail views; maximum 255 characters. |
| `summary` | Reviewed brief description shown in previews and search results. |
| `content_body` | Optional full reviewed content body; nullable when using external URL instead. |
| `external_url` | Optional validated user action URL; required for `VIDEO` and restricted to verified YouTube URLs. It is distinct from provenance and may coexist with `content_body`. |
| `source_organization` | Organization responsible for the source used to review or adapt this resource. |
| `source_title` | Title of the source material shown on Resource Detail. |
| `source_url` | Validated HTTP(S) provenance link shown separately from the resource action. |
| `source_review_note` | Internal/public review note preserving clinical, legal, and licensing caveats for the adapted content. |
| `catalogue_visibility` | Browsing scope: `LISTED` appears in the public catalogue; `DIRECT_ONLY` remains retrievable by exact ID for existing SupportPlans. |
| `status` | Authoritative workflow state controlling visibility: `DRAFT` (editable), `PUBLISHED` (immutable, public), or `ARCHIVED` (immutable, hidden). |
| `reviewed_by` | Identity UUID carried by a separately approved review decision; required while `PUBLISHED`. ADMIN authentication alone is not review approval. |
| `reviewed_at` | UTC timestamp copied from the immutable exact-version review decision; required while `PUBLISHED`. |
| `effective_at` | Optional UTC timestamp controlling delayed publication; resource not visible until this instant passes. |
| `expires_at` | Optional UTC timestamp after which published resource becomes hidden automatically. |
| `created_at` | Immutable UTC creation instant for audit and chronological ordering. |
| `updated_at` | UTC timestamp of latest persisted change; updated automatically on any modification. |
| `version` | Non-negative optimistic lock counter incremented on each update; prevents lost concurrent modifications. |
| `idempotency_key` | Legacy branch-local create retry field from migration 4. New writes use the actor/operation-scoped `resource_idempotency_record`; retained only for rolling compatibility. |

**State Transitions:**
- `DRAFT` → `PUBLISHED`: Requires an exact-version `APPROVED` decision from a configured review authority and complete structured source provenance.
- `PUBLISHED` → `ARCHIVED`: Preserves review metadata, resource becomes hidden but retrievable
- `ARCHIVED` → `DRAFT`: Creates the next content version and clears prior review provenance; the immutable prior publication snapshot remains historical evidence.
- Updates and deletions allowed only in `DRAFT` status
- The database check requires non-null review provenance while `PUBLISHED`; application transitions preserve provenance after publication. The check does not claim column immutability.

**Public API Safety:**
- Only `PUBLISHED` resources returned where `effective_at <= NOW()` and (`expires_at` IS NULL OR `expires_at > NOW()`)
- All published resources guaranteed to have review provenance
- `DRAFT` and `ARCHIVED` resources never exposed to public endpoints

### `public.resource_review_decision`

Append-only editorial decision for one exact draft version. ADMIN authorization is
necessary but not sufficient: `reviewed_by` must also belong to the configured
resource-review authority set.

| Field | Purpose |
| --- | --- |
| `id` | Immutable decision UUID. |
| `resource_id` / `content_version` | Exact draft identity; unique so a changed decision requires a new edited version. |
| `decision` | `APPROVED` or `REJECTED`; publication accepts only `APPROVED`. |
| `reason_code` | Bounded machine-readable rationale without resource body or sensitive text. |
| `authority_version` | Versioned configuration policy used to authorize the reviewer. |
| `reviewed_by` / `reviewed_at` | External Identity ADMIN UUID and server UTC decision instant. |

### `public.resource_publication_snapshot`

Immutable JSON snapshot of every exact published resource version. It preserves the
reviewed fields and source provenance used by historical SupportGuide/SupportPlan
references without making the version universally eligible.

| Field | Purpose |
| --- | --- |
| `resource_id` / `content_version` | Stable exact-version composite key. |
| `review_decision_id` | Exact approval that opened the publication gate; nullable only for migrated legacy publications. |
| `snapshot` | Complete immutable reviewed resource row captured atomically at publication. |
| `published_by` / `published_at` | ADMIN actor and UTC publication instant. |

### `public.resource_publication_withdrawal`

Append-only removal of an exact publication from normal catalogue use; it does not
delete the snapshot or rewrite downstream historical references.

| Field | Purpose |
| --- | --- |
| `resource_id` / `content_version` | Exact publication being withdrawn. |
| `reason_code` | Bounded `ARCHIVED`, `SUPERSEDED`, or `SOURCE_WITHDRAWN` reason. |
| `withdrawn_by` / `withdrawn_at` | ADMIN actor and UTC withdrawal instant. |

### `public.safety_directory_entry`

Versioned, administrator-reviewed facility or hotline contact owned by Content/Notification. It is eligible for area lookup only while active, reviewed, verified, source-backed, and strictly inside the 90-day verification window.

| Field | Purpose |
| --- | --- |
| `id` | Stable immutable UUID exposed as `directoryEntryId`; it is never reused for a different organization or service. |
| `name` | Reviewed user-visible organization or service name. |
| `entry_type` | Restricted `FACILITY` or `HOTLINE` presentation category; it does not assert a clinical capability. |
| `phone` | Reviewed contact string displayed unchanged; it is never synthesized into another number. |
| `address` | Reviewed user-visible address, required for facilities and nullable for non-location hotlines. |
| `active` | Authoritative publication switch; review and freshness checks are still required for public lookup. |
| `source_name` | Accountable issuing organization or evidence source for the current record version. |
| `source_reference` | Stable URI or controlled evidence reference used to reproduce the review decision. |
| `source_retrieved_at` | UTC instant when source evidence was obtained; future evidence cannot be reviewed. |
| `source_checksum` | Optional lowercase SHA-256 of lawfully retained evidence; null when no artifact is retained. |
| `reviewed_by` | External Identity administrator UUID approving the exact version; paired with `reviewed_at` and not itself proof of current authorization. |
| `reviewed_at` | UTC server instant the exact version was reviewed; null after creation or any reviewed-field update. |
| `verified_by` | External Identity administrator UUID that verified contact and coverage; paired with `verified_at`. |
| `verified_at` | UTC server instant beginning the half-open 90-day freshness interval; null after creation or update. |
| `seed_key` | Optional unique controlled-release identifier used only by owner migrations; never accepted from an admin request. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest persisted content, review, activation, or deactivation change. |
| `record_version` | Non-negative optimistic-lock counter incremented by updates and lifecycle decisions to prevent lost review changes. |

The partial lookup index begins with `active` and recent `verified_at`; coverage is joined through the area index. Ordering is by name and stable UUID, never distance.

### `public.safety_directory_coverage`

Reviewed coarse Vietnam coverage attached to the exact directory record version. No coordinates, precise user location, or inferred geocoding are stored.

| Field | Purpose |
| --- | --- |
| `entry_id` | Content-owned directory entry whose reviewed service area this row describes. |
| `ordinal` | Stable non-negative display/provenance order inside one entry and part of its primary key. |
| `coverage_level` | Restricted `NATIONWIDE`, `PROVINCE`, or `DISTRICT` scope controlling which canonical fields must be present. |
| `province_code` | Canonical coarse province code; null only for nationwide coverage. |
| `province_name` | Reviewed province display label paired with `province_code`; it may be matched by deliberate manual input. |
| `district_code` | Canonical district code required only for district coverage. |
| `district_name` | Reviewed district display label paired with `district_code`; no free-text address is treated as coverage. |

### `public.safety_directory_review_history`

Append-only accountability evidence for review/verification activation and deactivation. It excludes user location input and health data.

| Field | Purpose |
| --- | --- |
| `id` | Immutable audit UUID. |
| `entry_id` | Directory entry whose lifecycle decision was recorded. |
| `record_version` | Exact optimistic-lock version produced by the decision so later edits cannot inherit its evidence. |
| `action` | Restricted `REVIEWED` or `DEACTIVATED` outcome. A review action includes verification and activation in policy v1. |
| `actor_id` | External Identity administrator UUID accountable for the decision. |
| `source_reference` | Source evidence reference captured with the decision for reproducibility. |
| `occurred_at` | Immutable database UTC commit instant of the decision. |

### `public.safety_directory_command_record`

Durable administrator-scoped replay evidence for create commands. It prevents duplicate directory records without storing request plaintext.

| Field | Purpose |
| --- | --- |
| `actor_id` | External Identity administrator UUID owning the retry key. |
| `operation` | Stable command scope, currently only `CREATE_DIRECTORY_ENTRY`. |
| `idempotency_key` | Opaque caller key unique with actor and operation. |
| `request_fingerprint` | Lowercase SHA-256 of the canonical non-sensitive create payload, used to reject conflicting reuse. |
| `entry_id` | Directory entry created by the original command and returned on an identical replay. |
| `created_at` | Immutable database UTC instant when the command outcome was recorded. |

### `public.safety_directory_area_alias`

Reviewed, deterministic vocabulary for resolving deliberately entered manual area text. It is independent of directory-entry coverage and stores no user query, coordinates, or inferred location.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID for the vocabulary row. |
| `alias_text` | Reviewed province or district spelling; a normalized case-insensitive uniqueness rule ensures one area result per alias. |
| `province_code` | Canonical province code returned by manual-area resolution. |
| `district_code` | Canonical district code when the alias names a district; null for province-only aliases. |
| `canonical` | Marks the preferred reviewed label for the area pair without changing lookup eligibility. |
| `seed_key` | Optional unique controlled-release identifier used only by owner migrations. |
| `created_at` | Immutable database UTC insertion instant. |

### `public.content_admin_audit_outbox`

Content-owned durable transactional outbox for administrator actions (resource archive, safety directory review, deactivate). The row commits in the same local database transaction as the aggregate mutation and is relayed to Kafka topic `mentalbridge.admin.audit-event.v1`.

| Field | Purpose |
| --- | --- |
| `id` | Immutable event UUID matching the `eventId` in the audit event fact. |
| `deduplication_key` | Stable unique deduplication key preventing duplicate outbox entries on retried actions. |
| `event_type` | Stable language-neutral event type (e.g. `content.resource.archived`). |
| `correlation_id` | End-to-end trace correlation UUID established at controller ingress. |
| `payload` | Strict minimized `administration-audit-event-v1` JSON object; excludes sensitive directory secrets or raw content. |
| `occurred_at` | Authoritative UTC timestamp of the administrative decision. |
| `published_at` | Nullable UTC instant when published and acknowledged by Kafka; null while pending. |
| `attempt_count` | Non-negative relay retry counter for observable bounded backoff. |
| `next_attempt_at` | Scheduled UTC instant for next retry attempt when relay fails. |
| `created_at` | Immutable row creation timestamp. |

### `public.consultation_brief`

Care-owned editable aggregate for one Consultation-owned appointment. Content is cleared on deletion; approved snapshots remain immutable audit/provenance records.

| Field | Purpose |
| --- | --- |
| `appointment_id` | Exact external Consultation appointment UUID; one brief per appointment. |
| `user_id` / `specialist_id` | Owner and assigned specialist copied from current appointment authority, never accepted from the client. |
| `appointment_start_at` / `appointment_end_at` / `appointment_version` | Appointment timing/version last verified through the owner API. |
| `status` | `DRAFT`, `APPROVED`, or content-clearing `DELETED`. |
| `current_situation` | Owner-authored bounded non-diagnostic current context. |
| `support_evaluation_id` | Exact owned SupportEvaluation v2 provenance; raw answers and scores are excluded. |
| `user_goals` | JSON array of one to five bounded owner-authored goals. |
| `version` | Optimistic concurrency version required for updates, approval, revoke, and deletion. |

### `public.consultation_brief_snapshot`

Immutable snapshot created by one explicit approval. `screening_context` contains only the two domain/instrument levels and their questionnaire, scoring, policy, and evaluation provenance. It contains no journal, answer, chat, diagnosis, or private-note content. Owner deletion is the sole lifecycle exception: it atomically revokes access, clears all snapshot content/reference fields, and records `deleted_at`, leaving only a content-free tombstone and audit facts.

### `public.consultation_brief_grant`

Appointment-purpose read authority for exactly one snapshot and assigned specialist. `ACTIVE` grants use the bounded appointment window; `REVOKED` records retain `revoked_at` and cannot authorize future reads. Read and revoke lock the same row to provide one database ordering for races.

### `public.consultation_brief_audit`

Append-only content-free access facts. It stores actor, action, allowed/denied outcome, stable reason, correlation identifier, and time; it deliberately omits all brief fields and screening content.

### `public.consultation_brief_ai_draft_job`

Owner-private asynchronous AI suggestion state. `brief_version`, `support_evaluation_id`, `source_set_version`, and the request fingerprint bind the result to one exact saved draft. The nullable consent, entitlement, routing, provider, model, prompt, and schema fields record the successful provider decision. Suggested situation/goals exist only for `SUCCEEDED`; terminal failures retain a reason without generated content. Brief deletion marks jobs `SOURCE_DELETED` and clears both suggestion fields. The table contains no raw Journal content, screening answers, chat, diagnosis, or private specialist notes.

### `public.resource_idempotency_record`

Durable retry ownership for resource creation. The transaction serializes the same actor, operation, and key; identical requests replay the original resource and a changed payload returns `IDEMPOTENCY_CONFLICT`.

| Field | Purpose |
| --- | --- |
| `actor_id` | Identity administrator UUID that owns the retry key; prevents cross-actor key collisions. |
| `operation` | Stable command scope; currently `CREATE_RESOURCE`. |
| `idempotency_key` | Opaque caller key reused for one unchanged logical request. |
| `request_fingerprint` | SHA-256 digest of the canonical create payload used only to distinguish replay from conflicting reuse. |
| `resource_id` | Created resource UUID returned for deterministic replay; set to null after deliberate draft deletion so the key remains consumed and replays return a stable conflict. |
| `created_at` | UTC instant the command key was first accepted. |

### `public.resource_audit_event`

Append-only minimized facts written in the same database transaction as resource mutations. Content bodies, titles, URLs, and summaries are deliberately excluded.

| Field | Purpose |
| --- | --- |
| `id` | Immutable audit fact UUID. |
| `occurred_at` | Database UTC instant of the committed action. |
| `actor_id` | Identity administrator UUID responsible for the command. |
| `action` | Stable minimized outcome for resource commands plus `ELIGIBILITY_PUBLISHED` and `ELIGIBILITY_WITHDRAWN`; it never stores content or eligibility request bodies. |
| `resource_id` | Resource aggregate UUID, retained even when a draft is deleted. |
| `resource_version` | Version produced by the action, or the deleted draft version. |
| `correlation_id` | Request UUID linking the audit fact to sanitized diagnostics. |

### `public.resource_eligibility_publication`

Immutable Content-owned proof that one exact reviewed content version was explicitly admitted to Resource Eligibility v1. The unique `(resource_id, content_version, policy_version)` key prevents silent replacement. Publication is separate from general resource review and public visibility.

| Field | Purpose |
| --- | --- |
| `id` | Immutable publication UUID returned as eligibility provenance to Care. |
| `resource_id` | Content-owned resource UUID; the foreign key proves the aggregate existed locally but is not by itself eligibility. |
| `content_version` | Exact non-negative resource version reviewed for this publication; retained as a 64-bit value and serialized as a JSON string. |
| `policy_version` | Immutable decision contract identifier, fixed to `content-eligibility-v1` for reproducibility and enum evolution. |
| `locale` | BCP 47 locale of the exact content version; a Care query must match it exactly. |
| `effective_at` | UTC instant when this eligibility may begin; it cannot precede the resource's own publication window. |
| `expires_at` | Optional UTC end of eligibility; null means no eligibility-specific end and never extends the resource's own expiry. |
| `published_by` | Identity administrator UUID that executed the governed eligibility publication. |
| `published_at` | Immutable database UTC commit instant for audit and replay. |

The resolution access path starts with the unique B-tree created by `uq_resource_eligibility_exact_version` for exact `(resource_id, content_version, policy_version)` lookup, then joins bounded declarations. No duplicate exact-version index is maintained; existing public resource-list queries and indexes are unchanged.

### `public.resource_eligibility_declaration`

Immutable explicit domain-role applicability owned by Content. One row defines allowed bands and support tiers for one exact publication, domain and instrument, with exactly one role. Empty arrays, pseudo-domains, unsupported roles and cross-domain instrument pairs are rejected by database constraints.

| Field | Purpose |
| --- | --- |
| `publication_id` | Exact immutable eligibility publication to which the declaration belongs. |
| `target_domain` | Approved screening domain: `DEPRESSIVE_SYMPTOMS` or `ANXIETY_SYMPTOMS`; general-wellbeing and combined-domain aliases are prohibited. |
| `eligibility_role` | Explicit `PRIMARY` or `ADJUNCT` role. Only Care may decide slot admission, and `ADJUNCT` never satisfies a core slot. |
| `instrument` | Exact compatible instrument (`PHQ_9` or `GAD_7`) constrained to its approved target domain. |
| `screening_levels` | Non-empty set of instrument-specific bands to which this exact content version applies; GAD-7 cannot use `MODERATELY_SEVERE`. |
| `support_tiers` | Non-empty set of approved Care pathway tiers for which the declaration may be considered. |

### `public.resource_eligibility_withdrawal`

Append-only terminal withdrawal of one eligibility publication. It does not edit or erase the original provenance and makes the exact version resolve as `WITHDRAWN` for new plan decisions.

| Field | Purpose |
| --- | --- |
| `publication_id` | One-to-one immutable publication being withdrawn. |
| `reason_code` | Stable governed reason: content withdrawal, policy withdrawal, or supersession; no free-text moderation note is stored. |
| `withdrawn_by` | Identity administrator UUID responsible for the withdrawal command. |
| `withdrawn_at` | Immutable database UTC instant when new eligibility use stopped. |

### `public.resource_eligibility_command_record`

Durable actor-and-operation-scoped idempotency evidence for publish and withdraw commands. Exact retries return the original serialized response even after later withdrawal; changed request reuse conflicts.

| Field | Purpose |
| --- | --- |
| `actor_id` | Identity administrator UUID owning the retry key. |
| `operation` | Stable scope: `PUBLISH_ELIGIBILITY` or `WITHDRAW_ELIGIBILITY`. |
| `idempotency_key` | Opaque caller key unique with actor and operation. |
| `request_fingerprint` | SHA-256 digest of canonical non-sensitive command fields used only to detect conflicting reuse. |
| `publication_id` | Immutable publication that received the original command outcome. |
| `response_snapshot` | Original non-sensitive contract response retained for exact replay; excludes content, review notes and provider/storage details. |
| `created_at` | Immutable database UTC instant when the command result was recorded. |

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

Community's encrypted text-message authority under Liquibase 0018. No raw message is placed in Kafka, Redis, a notification or another owner's database.

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
| `created_at` | Immutable UTC microsecond save instant; orders eligible activity and enforces cross-device rolling send limits. |
| `expires_at` | Immutable save-time retention cutoff (90 days under accepted policy v1). Content becomes unreadable at this instant, independently of sweep timing. |
| `purged_at` | Nullable UTC physical ciphertext-erasure checkpoint; present exactly when ciphertext is null. The content-free identity tombstone prevents expired-content recreation. |

### `public.community_chat_read_state`

| Field | Purpose |
| --- | --- |
| `conversation_id` | Physical owner-local conversation key, removed when the conversation is erased. |
| `profile_id` | Exact Community reader; composite primary key isolates devices' shared private unread truth per owner/conversation. |
| `last_read_sequence` | Private monotonic max of authorized exact-message acknowledgements. Not a delivery proof, shared-receipt preference or public counter; retention/visibility changes never decrease it. |

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
