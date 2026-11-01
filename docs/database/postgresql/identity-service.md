# Identity PostgreSQL Field Data Dictionary

Owner: `identity-service`. Runtime database: `mentalbridge_identity`, default schema `public`.

The [owner migration history](../../../identity-service/src/main/resources/db/changelog/) is executable runtime truth; the [service README](../../../identity-service/README.md) describes its behavior. The [owner logical schema](../../domain-model/relational/identity-service.sql) is read-only documentation and must never provision or migrate a database.

Original owner/conceptual section labels and section-specific status notes are retained below. Grouping a description here does not promote a conceptual or historical shape into an active migration.

[All PostgreSQL owners](../postgresql-field-data-dictionary.md)

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

### `public.deletion_request`

Identity-coordinated deletion workflow state. The row coordinates owner facts
without granting Identity access to another service's storage or treating a
missing external fact as completion.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID identifying the deletion workflow in ADMIN APIs and local owner facts. |
| `account_id` | Identity account UUID being coordinated; retained as an external workflow reference without a cascading account foreign key. |
| `subject_reference_hash` | Required lowercase SHA-256 tombstone reference used after identifying account data is removed. |
| `status` | Authoritative coordinator state `PENDING`, `IN_PROGRESS`, `HELD`, `COMPLETED`, or `FAILED`; completion requires every required authoritative owner fact. |
| `previous_status` | Nullable pre-hold resumable state (`PENDING`, `IN_PROGRESS`, or `FAILED`) restored when an ADMIN releases the hold. |
| `hold_type`, `hold_reason` | Closed hold category and optional policy-approved reason code; neither field accepts sensitive free text. |
| `retry_count`, `max_retries` | Persisted bounded-attempt counters; exhaustion escalates instead of fabricating completion. |
| `escalation_state` | Closed escalation result `NONE`, `RETRY_EXHAUSTED`, or `MANUAL_ESCALATION_REQUIRED`. |
| `failure_code` | Nullable minimized machine-readable failure reason without owner payload or user content. |
| `requested_at` | Immutable UTC instant when the deletion workflow was requested. |
| `scheduled_execution_at` | UTC instant at or after which the next eligible coordinator attempt may run. |
| `completed_at` | UTC instant of verified terminal completion; null until every required owner fact is complete. |
| `last_attempt_at` | UTC instant of the latest coordinator attempt; null before the first attempt. |
| `version` | Optimistic-lock revision incremented on workflow mutation. |
| `created_at`, `updated_at` | UTC insertion and latest persisted business-change instants. |

Indexes on `(status, scheduled_execution_at)`, `subject_reference_hash`, and
`account_id` support bounded due-work, tombstone, and account lookups.

### `public.deletion_owner_fact`

One minimized authoritative completion state per required data owner in a
deletion workflow. External owners remain unavailable until an approved
producer/consumer contract supplies their fact.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID identifying the owner fact row. |
| `deletion_request_id` | Physical parent workflow; deleting the coordinator row removes its subordinate facts. |
| `owner_service` | Closed authority code `IDENTITY`, `CARE`, `CONSULTATION`, `JOURNAL_AI`, `COMMUNITY`, or `CONTENT`; unique within one request. |
| `status` | Owner-reported state `PENDING`, `COMPLETED`, `HELD`, `FAILED`, or `UNAVAILABLE`; only authoritative `COMPLETED` contributes to workflow completion. |
| `completed_at` | UTC instant asserted by an authoritative completion fact; null for every non-completed state. |
| `evidence_reference` | Nullable bounded safe reference allowed only when an approved producer contract exists; external owners currently retain null. |
| `failure_reason` | Nullable stable owner failure code without raw error, token, or user content. |
| `updated_at` | UTC instant of the latest accepted authoritative owner state. |

### `public.retention_execution_record`

Immutable execution evidence for one owner-scoped retention policy run. A
failed or stale transaction records its truthful outcome independently; it is
never rewritten into success.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID identifying one execution attempt. |
| `policy_id` | Stable approved retention policy code evaluated by the attempt. |
| `owner_service` | Closed data-owner code whose local retention capability was invoked. |
| `job_name` | Stable bounded worker name used for operational correlation. |
| `job_status` | Terminal or in-flight state `SUCCEEDED`, `FAILED`, `RUNNING`, `STALE`, or `UNAVAILABLE`. |
| `started_at`, `completed_at` | UTC attempt start and nullable terminal completion instants. |
| `items_evaluated` | Nonnegative number of eligible local records evaluated. |
| `records_purged` | Nonnegative number of owner-authorized records actually removed. |
| `tombstones_created` | Nonnegative number of privacy-minimized tombstones created. |
| `holds_honored` | Nonnegative number of deletion/retention holds that prevented removal. |
| `failure_code` | Nullable stable failure code without raw exception or sensitive payload. |
| `created_at` | Immutable UTC insertion instant for the execution evidence. |

The `(policy_id, started_at DESC)` index supports bounded policy history and
stale-attempt review.

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
