# Content/Notification PostgreSQL Field Data Dictionary

Owner: `content-notification-service`. Runtime database: `mentalbridge_content_notification`, default schema `public`.

The [owner migration history](../../../content-notification-service/migrations/) is executable runtime truth; the [service README](../../../content-notification-service/README.md) describes its behavior. The [owner logical schema](../../domain-model/relational/content-notification-service.sql) is read-only documentation and must never provision or migrate a database.

Original owner/conceptual section labels and section-specific status notes are retained below. Grouping a description here does not promote a conceptual or historical shape into an active migration.

[All PostgreSQL owners](../postgresql-field-data-dictionary.md)

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

Owner-scoped, non-clinical participation state for one reviewed resource on one local calendar day. It supports consistent checklist/progress presentation. An exact-version completed row can supply a bounded Content fact to Care's explicit reconcile command; the save itself does not mutate a SupportPlan occurrence, screening result, or clinical decision.

| Field                  | Purpose                                                                                                                                                                                                |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `owner_id`             | External Identity account UUID from the verified JWT; it scopes every read and write and is not copied from request content.                                                                           |
| `resource_id`          | Content-owned resource UUID whose reviewed activity the owner followed. The local foreign key prevents progress for an unknown resource.                                                               |
| `local_date`           | User-selected calendar date in `YYYY-MM-DD` form used to group the gentle daily experience; it is not an adherence deadline.                                                                           |
| `resource_version`     | Exact immutable listed publication version actually used. An optional pinned version is validated on save; supplying a different version for an already completed daily row returns `409` before changing it. Omission retains current-version input behavior and preserves the recorded completed version. Completion evidence matches the recorded version exactly. |
| `status`               | Lightweight participation state: `IN_PROGRESS` or terminal `COMPLETED`; once confirmed for the resource and local date, later checklist edits cannot downgrade it. Absence of a row means not started. |
| `completed_action_ids` | Bounded identifiers of the user's current interactive-step selections for that resource version; they remain editable after completion and contain no journal, reflection, or other free text.         |
| `completed_at`         | Server UTC instant when the item first reached `COMPLETED`; null while in progress and preserved after confirmation. This instant converted to the requested IANA zone establishes an evidence date; the row's client-selected `local_date` does not prove when completion occurred. |
| `created_at`           | Immutable UTC insertion instant for the daily progress row.                                                                                                                                            |
| `updated_at`           | UTC instant of the latest changed progress state or action set.                                                                                                                                        |
| `version`              | Monotonic mutation counter for diagnosing multi-surface synchronization; identical replacement writes are no-ops.                                                                                      |

The `(owner_id, local_date DESC, updated_at DESC)` index supports bounded weekly history and recent-completion views. Daily completion is encouragement only; missed days do not create rows, penalties, or clinical meaning.

### `content.resource_learning_completion`

Durable owner-scoped proof that a one-time learning resource was confirmed once. The `(owner_id, resource_id)` primary key prevents re-completion from inflating practice streaks; the first completion date/time and exact resource version remain authoritative.

### `content.resource_practice_session`

Repeatable practice history. `id` is a client-generated idempotency key for one deliberate completion, so retries do not duplicate a session while multiple sessions on the same day remain representable. `support_plan_id` must come from the persisted daily assignment; direct/out-of-plan progress never creates streak evidence. `started_at` and `duration_seconds` retain optional measured session metadata. Only sessions for the current plan and resources with `streak_eligible=true` count toward practice streak.

For MB-670, action-bound Bingo and Care evidence do not require a Journey
assignment. They require the same owner, exact used `resource_version`, a
server start instant derived at read time from `completed_at` minus measured
`duration_seconds` from 60 through 7200 seconds, including historical sessions.
Deprecated request `practiceStartedAt` and stored historical client `started_at`
never supply evidence; a client-supplied calendar date is not proof either.
The session UUID identifies `RESOURCE_PRACTICE_SESSION`; its exact resource
version is the fact revision. Learning-completion rows are not this evidence.

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

Persisted Monday-based board materialized only by current-week plan-sync from
Care's minimized owner projection. The unique owner/week/SupportPlan key
preserves prior plan boards as history. Journey reads the latest board and
never writes it. The board `version` and `updated_at` provide a strong ETag
boundary; identical syncs preserve both. Every retained row, including retired
rows, counts toward the nine-item limit. Only plan resources create new items.

| Field | Purpose |
| --- | --- |
| Board `id` / `owner_id` | Content board UUID and verified external Identity owner. Every lookup and advisory lock is owner-scoped. |
| Board `week_start` / `time_zone` | Local Monday and IANA zone used to interpret server fact instants; past weeks cannot be resynced. |
| Board `support_plan_id` / `support_plan_version` | External Care identity and last observed projection revision; no cross-owner foreign key. Plan version alone does not replace item identity or reset owner checks. |
| Board `plan_tags` | Legacy bounded plan metadata; the minimized MB-670 projection supplies no diagnosis, rationale, or treatment data. |
| Board `created_at` / `updated_at` / `version` | Insertion instant, effective board-change instant and ETag counter. Effective changes allocate the next unused version across the owner's boards for that week under the advisory lock, preventing stale cross-plan ETag collisions. No-op syncs preserve the existing version. |
| Board `last_synced_at` | Server UTC instant used to select the most recently synced board when an owner revisits an existing plan in the same week. Independent of effective state changes and ETag version; migration initializes legacy boards. |
| Item `board_id` / `ordinal` | Physical board child and retained position, with at most nine rows. Retirement never frees an ordinal or deletes personal history. |
| Item `resource_id` / `resource_version` | Exact immutable local publication reference. The unique resource-per-board rule collapses multiple plan actions for one resource onto a single tile. |
| Item `support_plan_action_id` / `support_plan_action_version` | Nullable paired external Care action identity and last observed revision; both null for legacy rows. Version must be non-negative when present. Identity ignores action-version bumps so v2 adapter revisions do not churn tiles. |
| Item `retired_at` | Nullable server UTC instant of retirement when an action leaves the plan or Care reports `NONE`. Retired rows retain ordinal and owner check, remain readable, count toward capacity and reject self-confirmation. Rebinding a returning resource clears retirement. |
| Item `self_confirmed_at` | Nullable reversible owner-statement instant. A resource-version rebind clears it; removal/retirement preserves it. It never supplies a completion fact. |

The read model computes exact-version Content evidence rather than copying it
onto a board. Action-bound items use completed progress or eligible measured
practice whose server instant falls inside the board week in its zone; legacy
items additionally retain same-plan assignment binding. Self-confirmation and
Content verification remain separate, and neither skipped nor fully checked
boards have clinical meaning.
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
| `channel_push_enabled` | Owner opt-in for native push; delivery additionally requires an active permitted device and enabled provider worker. |
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

### `content.push_device_registration`

Content-owned native installation lifecycle. Provider tokens are sensitive
routing credentials: REST never returns them, logs never contain them, and only
the delivery adapter decrypts them immediately before a provider request.

| Field | Purpose |
| --- | --- |
| `id` | Immutable internal registration UUID used by delivery attempts. |
| `owner_id` | External Identity account UUID taken from the verified JWT; it is not client input. |
| `installation_id` | App-generated installation UUID; at most one active/disabled owner assignment exists across accounts, and it is not a hardware identifier. |
| `platform` / `provider` | Closed routing facts (`ANDROID`/`IOS`, initial `EXPO`) selecting the approved adapter. |
| `provider_token_ciphertext` / `provider_token_iv` / `provider_token_auth_tag` | AES-256-GCM encrypted provider credential and authentication material; plaintext is never persisted. |
| `provider_token_hash` | One-way SHA-256 lookup used to atomically rotate/reassign one active provider token without decrypting rows. |
| `permission_status` | Last explicit native permission result (`GRANTED` or `DENIED`). |
| `status` | Content-owned lifecycle: `ACTIVE`, `DISABLED`, `REVOKED`, or provider-invalidated `INVALID`. |
| `app_version` | Optional bounded diagnostic application version; no device model or advertising identity is stored. |
| `last_registered_at` | UTC instant of the latest material registration change. An identical PUT leaves it and `version` unchanged so enqueue eligibility cannot move past an unmaterialized notification. |
| `delivery_eligible_from_at` | UTC start of the current permitted registration interval. Token/app-version rotation keeps it stable; reactivation after revocation, invalidation, or denied permission resets it to prevent stale backfill. |
| `revoked_at` / `invalidated_at` | Mutually lifecycle-bound UTC evidence of owner revocation or provider invalidation. |
| `created_at` / `updated_at` | UTC insertion and latest persisted business-change instants. |
| `version` | Monotonic mutation counter for token rotation, permission, reassignment, and lifecycle changes. |

### `content.push_delivery_attempt`

Auditable and deduplicated provider-submission work. It references authoritative
inbox truth and stores no rendered body, token, health content, or raw provider
response.

| Field | Purpose |
| --- | --- |
| `id` | Immutable internal work UUID used to fence one claimed attempt. |
| `notification_id` | Physical reference to the authoritative minimized Content notification. |
| `device_registration_id` | Physical reference to the owner installation selected for this delivery. |
| `state` | `PENDING`/`PROCESSING`/`FAILED` work states, terminal `SUPPRESSED`/`UNKNOWN`, provider-ticket `ACCEPTED`, or receipt `CONFIRMED`; neither provider state claims physical device display. |
| `attempt_count` | Provider submissions, database constrained to at most three. |
| `available_at` | Earliest UTC retry/claim instant; quiet hours remain an additional live eligibility check. |
| `claimed_at` / `last_attempt_at` | UTC worker-claim and most recent provider-submission evidence. |
| `provider_ticket_id` | Bounded Expo acknowledgement identifier; it is not delivery proof. |
| `failure_code` | Coarse safe classification such as preference disabled, invalid device, transient outage, or unknown outcome. |
| `accepted_at` | UTC provider-ticket acceptance instant retained through later `CONFIRMED` or terminal receipt outcomes. |
| `receipt_attempt_count` | Bounded Expo receipt lookups, database constrained to at most three. |
| `receipt_checked_at` | UTC instant of the latest receipt lookup; null until the first lookup. |
| `created_at` / `updated_at` | UTC insertion and latest state-transition instants. |

### `content.push_device_command_rate_limit`

Bounded fixed-window command protection for the authenticated registration and
revocation API. It stores no provider token, installation identifier, or client
content and is not notification delivery truth.

| Field | Purpose |
| --- | --- |
| `owner_id` | External Identity account UUID used as the per-owner rate-limit key. |
| `window_started_at` | UTC start of the current fixed command window. |
| `request_count` | Atomic number of accepted commands in that window. |
| `updated_at` | UTC instant of the latest accepted command or window reset. |

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
