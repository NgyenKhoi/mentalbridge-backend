# PostgreSQL Field Data Dictionary

This document explains the business purpose of persisted PostgreSQL fields. The original logical baseline is [`001_initial_schema.sql`](../../database/postgresql/001_initial_schema.sql); service-owned Liquibase changelogs become executable sources of truth as modules are implemented. It is written for developers and reviewers; descriptions are intentionally kept out of executable migrations.

When service-owned Liquibase migrations are introduced, update this dictionary in the same change. A field description must explain why the value is persisted, whether it is authoritative, derived, external, or sensitive, and how nullability, time, versioning, or idempotency affects behavior.

## Database `mentalbridge_identity` (schema `public`)

### `public.account`

Authoritative login account and lifecycle state owned by Identity Service.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID exposed as the opaque account identifier in REST and Kafka contracts. |
| `email` | Case-insensitive normalized login and recovery address; unique because one address identifies one account. |
| `password_hash` | One-way password hash used for local authentication; plaintext is never persisted. |
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

Reference catalogue of authorization roles assignable to accounts.

| Field | Purpose |
| --- | --- |
| `code` | Stable machine-readable role identifier used in tokens and authorization policies. |
| `description` | Human-readable explanation of the permissions and actor represented by the role. |

### `public.account_role`

Auditable many-to-many assignment of roles to accounts.

| Field | Purpose |
| --- | --- |
| `account_id` | Identity-owned account receiving the role. |
| `role_code` | Stable role code granted to the account. |
| `granted_by` | Administrator account that granted the role; null only for approved automated/bootstrap assignment. |
| `granted_at` | UTC instant at which the role became effective. |

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
| `occurred_at` | UTC instant the business fact occurred. |
| `published_at` | UTC instant Kafka acknowledged publication; null while pending. |
| `attempt_count` | Nonnegative count of bounded relay attempts. |
| `next_attempt_at` | UTC instant after which a failed relay may retry; null when no delay is scheduled. |
| `created_at` | Immutable UTC insertion instant committed with the aggregate change. |

### `public.security_audit_event`

Privacy-minimized local security record for authentication, recovery, replay, and account-administration decisions. It contains stable facts, never credentials, provider payloads, or free text.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID identifying the audit fact. |
| `account_id` | Affected account when known; nullable for enumeration-safe failures and retained as null after account deletion. |
| `actor_id` | Authenticated actor responsible for an administrative action; nullable for guests/system actions and after actor deletion. |
| `action` | Stable security action code such as login or role replacement. |
| `outcome` | Restricted result `SUCCEEDED`, `DENIED`, or `FAILED`. |
| `reason_code` | Optional stable machine-readable explanation without sensitive free text. |
| `correlation_id` | Request/workflow UUID used to join safe operational evidence. |
| `subject_reference_hash` | Optional keyed privacy-minimized 64-character hash used to correlate bounded unknown-account abuse without storing the supplied identifier. |
| `occurred_at` | UTC instant the security decision occurred. |
| `created_at` | Immutable UTC insertion instant. |

## Schema `care`

### `care.user_profile`

Care-owned non-credential user profile and communication preferences.

| Field | Purpose |
| --- | --- |
| `account_id` | External Identity account UUID and immutable profile identifier; it does not prove current account access. |
| `display_name` | User-controlled name displayed in permitted product contexts. |
| `date_of_birth` | Optional birth date used only for approved age-related eligibility or personalization rules. |
| `gender` | Optional self-described gender value used only where product policy permits. |
| `locale` | BCP 47 locale used to select translated questionnaires, resources, and messages. |
| `timezone` | IANA timezone used to calculate and render reminders and follow-up schedules. |
| `avatar_object_key` | Private object-storage key for the avatar; never a public or permanent document URL. |
| `reminder_enabled` | User preference controlling optional care reminders, excluding mandatory safety behavior. |
| `created_at` | Immutable UTC profile creation instant. |
| `updated_at` | UTC instant of the latest persisted profile change, maintained by Care Service. |
| `version` | Optimistic-lock counter incremented on concurrent profile mutations. |

### `care.consent_decision`

Append-only evidence of a user grant or refusal for a versioned platform consent.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID used to cite this exact consent decision in audit and REST results. |
| `user_id` | Care-owned user profile that made the consent decision. |
| `consent_type` | Stable scope category such as terms, privacy, or AI processing. |
| `policy_version` | Exact policy text/version accepted or refused so the decision remains reproducible. |
| `granted` | Authoritative decision value; false records an explicit refusal or withdrawal. |
| `decided_at` | UTC instant at which the user made the decision. |
| `evidence` | Minimized structured evidence such as channel and document hash; never raw sensitive content. |
| `created_at` | Immutable UTC insertion instant for storage and audit ordering. |

### `care.questionnaire_definition`

Immutable versioned PHQ-9 or GAD-7 questionnaire definition and scoring identity.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID referenced by questions and submissions to preserve the exact instrument version. |
| `instrument` | Validated questionnaire family whose scoring rules apply. |
| `version` | Published content version distinguishing wording or item-set changes. |
| `locale` | BCP 47 locale of the question wording. |
| `title` | Reviewed user-facing questionnaire title. |
| `scoring_version` | Deterministic scoring algorithm version needed to reproduce the stored result. |
| `status` | Publication lifecycle controlling whether new submissions may use this definition. |
| `published_at` | UTC instant the immutable definition became available; null while draft. |
| `created_at` | Immutable UTC creation instant for the definition record. |

### `care.questionnaire_question`

Version-owned questionnaire item with safety-path metadata.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID referenced by assessment answers. |
| `definition_id` | Questionnaire definition that owns the wording, order, and scoring context. |
| `item_number` | Reviewed one-based display/scoring order unique inside a definition. |
| `prompt` | Reviewed localized question wording presented to the user. |
| `safety_flag` | Marks an item whose positive response requires deterministic immediate safety evaluation. |
| `created_at` | Immutable UTC insertion instant for provenance. |

### `care.assessment_submission`

Authoritative immutable scored screening submission for an account or anonymous session.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID returned by REST and referenced by risk and follow-up decisions. |
| `user_id` | Authenticated Care profile owner; null for an anonymous screening. |
| `anonymous_session_id` | Opaque short-lived screening session identifier; null for an authenticated submission. |
| `definition_id` | Exact questionnaire definition used to validate and score all answers. |
| `total_score` | Server-computed authoritative questionnaire total, never accepted from the client. |
| `screening_level` | Validated severity band derived only from instrument score and scoring version. |
| `scoring_version` | Algorithm version used to reproduce total and band independently of later code changes. |
| `safety_flag` | Deterministic indication that a safety-sensitive answer path was triggered. |
| `idempotency_key` | Caller retry key scoped by authenticated user or anonymous session to prevent duplicate submissions. |
| `submitted_at` | UTC instant the complete validated assessment was accepted. |
| `voided_at` | UTC instant an invalidated submission stopped being used; null while authoritative. |
| `void_reason` | Reviewed reason for voiding without deleting the historical screening record. |
| `created_at` | Immutable UTC database insertion instant. |

### `care.assessment_answer`

Immutable server-validated item answers belonging to one assessment submission.

| Field | Purpose |
| --- | --- |
| `submission_id` | Assessment submission that owns and contextualizes the answer. |
| `question_id` | Exact versioned questionnaire item answered. |
| `answer_value` | Validated integer response in the instrument range 0 through 3 used for deterministic scoring. |

### `care.risk_classification`

Versioned platform support-tier result derived from approved sources, distinct from diagnosis.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID identifying this reproducible risk-policy execution. |
| `user_id` | Care profile for whom the platform support tier was calculated. |
| `level` | Authoritative platform support tier produced by the named policy, not a clinical diagnosis. |
| `policy_version` | Exact deterministic policy version needed to reproduce and audit the decision. |
| `reason_codes` | Stable machine-readable reasons supporting the tier without storing free-form model reasoning. |
| `source_assessment_ids` | Identifiers of authoritative assessment submissions used by this calculation. |
| `source_analysis_ids` | Identifiers of approved structured AI indicators used as supporting input. |
| `safety_flag` | Indicates immediate safety guidance was required independently of asynchronous systems. |
| `calculated_at` | UTC instant the policy executed. |
| `superseded_at` | UTC instant a newer authoritative classification replaced this result; null while current. |
| `created_at` | Immutable UTC insertion instant for provenance. |

### `care.intervention_plan`

Versioned set of platform support actions generated for one risk classification.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID used by follow-up resources and REST endpoints. |
| `user_id` | Care profile that owns and may view the plan. |
| `risk_classification_id` | Exact risk result that justified generating the plan. |
| `template_code` | Stable reviewed intervention template identifier. |
| `template_version` | Exact template revision used so displayed actions remain auditable. |
| `status` | Lifecycle state controlling whether the plan is active, complete, superseded, or cancelled. |
| `actions` | Validated ordered structured actions copied from the reviewed template for historical stability. |
| `generated_at` | UTC instant the plan was generated from the risk result. |
| `completed_at` | UTC instant the plan reached completion; null until completed. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest persisted plan status/action change. |
| `version` | Optimistic-lock counter preventing lost concurrent plan updates. |

## Schema `consultation`

### `consultation.specialist_profile`

Consultation-owned professional profile and authoritative verification state.

| Field | Purpose |
| --- | --- |
| `account_id` | External Identity account UUID and immutable specialist identifier; access still depends on current account state. |
| `display_name` | Reviewed specialist name displayed to users. |
| `biography` | Optional reviewed professional biography shown in discovery and booking. |
| `years_experience` | Non-negative stated professional experience used for informational filtering/display. |
| `consultation_methods` | Validated list of supported consultation modes used to create compatible slots. |
| `timezone` | IANA timezone used to interpret and render the specialist schedule. |
| `verification_status` | Authoritative approval workflow state controlling specialist capabilities. |
| `approved_at` | UTC instant approval became effective; null until approved. |
| `approved_by` | Identity account of the administrator who approved the specialist. |
| `created_at` | Immutable UTC profile creation instant. |
| `updated_at` | UTC instant of the latest persisted profile or verification change. |
| `version` | Optimistic-lock counter preventing lost specialist-profile updates. |

### `consultation.specialty`

Reviewed reference catalogue of specialist areas presented by the product.

| Field | Purpose |
| --- | --- |
| `code` | Stable machine-readable specialty identifier used by filters and contracts. |
| `display_name` | Reviewed user-facing specialty label. |
| `active` | Controls assignment/discovery without deleting historical specialist links. |

### `consultation.specialist_specialty`

Many-to-many assignment of reviewed specialties to a specialist profile.

| Field | Purpose |
| --- | --- |
| `specialist_id` | Specialist profile receiving the specialty. |
| `specialty_code` | Reference specialty assigned to the specialist. |

### `consultation.verification_document`

Private object metadata and review result for a specialist credential document.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID for document review and audit references. |
| `specialist_id` | Specialist profile that submitted the credential. |
| `document_type` | Validated credential category used by the verification workflow. |
| `object_key` | Unique private object-storage key; it is not a public URL. |
| `content_type` | Server-validated media type used for safe retrieval and scanning. |
| `checksum_sha256` | SHA-256 integrity digest used to detect replacement or corruption. |
| `review_status` | Authoritative review result for this exact document. |
| `reviewed_by` | Administrator account that made the review decision; null while pending. |
| `reviewed_at` | UTC instant the review decision was recorded; null while pending. |
| `rejection_reason` | Reviewed explanation visible to authorized parties when a document is rejected. |
| `created_at` | Immutable UTC metadata insertion instant. |

## Schema `care`

### `care.specialist_access_grant`

Explicit, scoped, time-bounded and revocable user permission for one specialist.

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

## Schema `consultation`

### `consultation.availability_slot`

Authoritative half-open specialist availability interval that may be booked once.

| Field | Purpose |
| --- | --- |
| `id` | Immutable slot UUID used in booking REST commands. |
| `specialist_id` | Specialist profile that owns the interval. |
| `start_at` | Inclusive UTC start instant of the available interval. |
| `end_at` | Exclusive UTC end instant, required to be later than start. |
| `timezone` | IANA timezone captured for stable human schedule rendering. |
| `method` | Consultation mode available during this interval. |
| `status` | Authoritative slot state used with database constraints to prevent conflicting bookings. |
| `created_at` | Immutable UTC slot creation instant. |
| `updated_at` | UTC instant of the latest slot state or schedule change. |
| `version` | Optimistic-lock counter preventing lost concurrent slot updates. |

### `consultation.appointment`

Authoritative booking between one user and specialist for an owned availability slot.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID exposed in appointment REST resources and Kafka events. |
| `slot_id` | Owned availability slot reserved by the appointment and protected by a unique active-booking constraint. |
| `user_id` | External Care profile UUID of the person requesting consultation. |
| `specialist_id` | Consultation-owned specialist UUID denormalized for authorization and query efficiency. |
| `status` | Authoritative appointment workflow state; transitions are validated and recorded in history. |
| `consultation_method` | Method agreed at booking, copied from the slot for historical stability. |
| `user_timezone` | IANA timezone captured at booking so the schedule remains understandable after device timezone changes. |
| `idempotency_key` | Caller retry key unique per user so uncertain REST retries return the original booking outcome. |
| `cancellation_reason` | Reviewed explanation recorded when a permitted cancellation occurs. |
| `requested_at` | UTC instant the booking request was accepted. |
| `confirmed_at` | UTC instant the appointment became confirmed; null otherwise. |
| `completed_at` | UTC instant the consultation was marked complete; null otherwise. |
| `cancelled_at` | UTC instant cancellation became effective; null otherwise. |
| `created_at` | Immutable UTC database insertion instant. |
| `updated_at` | UTC instant of the latest persisted appointment transition/change. |
| `version` | Optimistic-lock counter preventing lost concurrent appointment transitions. |

### `consultation.appointment_status_history`

Append-only audit timeline of validated appointment state transitions.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID identifying one transition record. |
| `appointment_id` | Appointment whose workflow state changed. |
| `from_status` | Previous state; null only for the initial creation transition. |
| `to_status` | New authoritative state reached by the transition. |
| `changed_by` | Identity account responsible for the transition; null for approved system actions. |
| `reason` | Optional reviewed reason explaining the transition without sensitive conversation content. |
| `changed_at` | UTC instant the transition committed. |

### `consultation.specialist_review`

User rating and moderated feedback tied to one completed appointment.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID exposed for review moderation and REST access. |
| `appointment_id` | Unique appointment proving one review opportunity and consultation relationship. |
| `user_id` | External Care profile UUID of the review author. |
| `specialist_id` | Specialist being reviewed, denormalized for efficient public queries. |
| `rating` | Validated integer rating from one through five. |
| `comment` | Optional user-authored feedback subject to moderation and logging restrictions. |
| `moderation_status` | Visibility/tombstone state controlled by moderation policy. |
| `created_at` | Immutable UTC review submission instant. |
| `updated_at` | UTC instant of the latest permitted edit or moderation change. |
| `deleted_at` | UTC user/moderator deletion instant; null while not deleted. |
| `version` | Optimistic-lock counter preventing lost review/moderation updates. |

## Schema `care`

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

## Schema `content`

### `content.resource`

Reviewed self-help content or external resource managed by Content/Notification Service.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID used in REST resources and intervention action targets. |
| `category` | Stable content category used for browsing and recommendation rules. |
| `locale` | BCP 47 locale of the reviewed content. |
| `title` | Reviewed user-facing title. |
| `summary` | Reviewed short description used in listings. |
| `content_body` | Optional reviewed first-party content body; one body or external URL is required. |
| `external_url` | Optional reviewed external destination; one body or external URL is required. |
| `status` | Publication lifecycle controlling user visibility. |
| `reviewed_by` | Administrator account that approved the content; null before review. |
| `reviewed_at` | UTC instant of latest approval/review; null before review. |
| `created_at` | Immutable UTC content creation instant. |
| `updated_at` | UTC instant of the latest content or publication change. |
| `version` | Optimistic-lock counter preventing lost content edits. |

### `content.hotline`

Reviewed regional crisis/support contact whose freshness is actively governed.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID referenced by safety responses and audit. |
| `country_code` | ISO 3166-1 alpha-2 country used to select an applicable contact. |
| `region` | Optional sub-country applicability label for regional routing. |
| `name` | Verified organization or service name shown to users. |
| `phone_number` | Verified contact number; one phone number or website is required. |
| `website_url` | Verified support website; one website or phone number is required. |
| `availability_text` | Reviewed human-readable operating hours/timezone guidance. |
| `guidance` | Reviewed safe instructions accompanying the contact; never generated dynamically by AI. |
| `locale` | BCP 47 locale of the displayed contact guidance. |
| `active` | Controls selection without deleting historical reviewed contacts. |
| `verified_at` | UTC instant an administrator last confirmed the contact details. |
| `next_review_at` | UTC deadline for mandatory re-verification and freshness monitoring. |
| `verified_by` | Administrator account responsible for the verification. |
| `created_at` | Immutable UTC contact insertion instant. |
| `updated_at` | UTC instant of the latest verified data change. |

### `content.notification_preference`

Per-user channel and category choice controlling optional notification delivery.

| Field | Purpose |
| --- | --- |
| `user_id` | External Care profile UUID whose preference is recorded. |
| `channel` | Delivery channel governed by this preference. |
| `category` | Notification category governed independently for this channel. |
| `enabled` | Whether optional delivery is allowed; safety/legal exceptions require explicit policy. |
| `quiet_hours` | Validated local-time window and timezone settings delaying non-urgent delivery. |
| `updated_at` | UTC instant the user last changed this preference. |

### `content.notification`

Durable in-app notification and safe delivery payload owned by Content/Notification Service.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID used for REST history, Kafka delivery, and read idempotency. |
| `recipient_id` | External Identity account UUID of the intended recipient. |
| `category` | Stable category used for preference, priority, and presentation rules. |
| `title` | Reviewed/minimized user-visible title safe for the selected channel. |
| `body` | Reviewed/minimized user-visible body that excludes raw sensitive source content. |
| `action_type` | Optional stable client action the notification may open. |
| `action_target_id` | Optional opaque resource UUID resolved through an authorized REST request. |
| `priority` | Delivery/presentation priority, not a clinical severity decision. |
| `read_at` | UTC instant the recipient marked the in-app notification read; null while unread. |
| `expires_at` | Optional UTC instant after which the notification should no longer be presented. |
| `created_at` | Immutable UTC creation instant used for cursor ordering. |
| `deleted_at` | UTC user/policy tombstone instant; null while visible in history. |

## Schema `ai`

### `ai.analysis_job`

Durable Journal/AI orchestration state for one provider analysis of one journal revision.

| Field | Purpose |
| --- | --- |
| `id` | Immutable job UUID returned by REST and used as the Kafka command correlation key. |
| `user_id` | External Care profile UUID owning the journal and result; not an authorization substitute. |
| `journal_entry_id` | Journal/AI-owned logical entry UUID being analyzed. |
| `journal_revision` | Positive immutable revision number ensuring results cannot be attached to edited text. |
| `provider` | Selected execution provider or PhoBERT worker for this reproducible run. |
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

## Schema `platform`

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
