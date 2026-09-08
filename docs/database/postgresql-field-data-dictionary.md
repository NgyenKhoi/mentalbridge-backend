# PostgreSQL Field Data Dictionary

This document explains the business purpose of conceptual PostgreSQL fields. [`001_initial_schema.sql`](../../database/postgresql/001_initial_schema.sql) is a non-executable whole-system model and must not provision an environment. Names such as `consultation.appointment` below identify a logical owner inside that model; the physical table will be `public.appointment` in the separate `mentalbridge_consultation` database. Service-owned migration histories become executable sources of truth only when modules are implemented: Liquibase for Spring services and `node-pg-migrate` for Node.js services. It is written for developers and reviewers; descriptions are intentionally kept out of executable migrations.

When service-owned migrations are introduced, update this dictionary in the same change. A field description must explain why the value is persisted, whether it is authoritative, derived, external, or sensitive, and how nullability, time, versioning, or idempotency affects behavior. Content/Notification's executable history starts at `content-notification-service/migrations/1_initial_schema.sql`; migration 2 removes the obsolete hotline table, and migration 3 conditionally inserts the controlled Review 1 resource in the shared dev/staging database. The field descriptions under its conceptual owner below describe the resulting physical `public` tables.

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

### `public.credential_request_rate_limit`

Privacy-minimized durable rate state for verification-resend and password-recovery requests. Identity records valid request attempts for eligible, ineligible, and unknown email subjects before evaluating account state so the public limit behavior does not reveal account existence.

| Field | Purpose |
| --- | --- |
| `subject_key_hash` | Keyed, one-way 64-character fingerprint of the normalized email subject and operation purpose; the supplied address is not recoverable from this value. |
| `purpose` | Independent `VERIFY_EMAIL` or `RESET_PASSWORD` request budget so one flow cannot consume the other flow's allowance. |
| `window_started_at` | UTC start of the current fixed one-hour request window used to reset the bounded count. |
| `request_count` | Authoritative count from one through three of accepted requests in the current purpose-specific window. |
| `last_requested_at` | UTC instant of the most recent accepted request, used to enforce the 60-second cooldown. |
| `created_at` | Immutable UTC instant when Identity first created rate state for this privacy-minimized subject. |
| `updated_at` | UTC instant of the latest accepted request or window reset, used for bounded cleanup. |

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
| `action` | Stable security action code such as login, password recovery, or account-state change. |
| `outcome` | Restricted result `SUCCEEDED`, `DENIED`, or `FAILED`. |
| `reason_code` | Optional stable machine-readable explanation without sensitive free text. |
| `correlation_id` | Request/workflow UUID used to join safe operational evidence. |
| `subject_reference_hash` | Optional keyed privacy-minimized 64-character hash used to correlate bounded unknown-account abuse without storing the supplied identifier. |
| `occurred_at` | UTC instant the security decision occurred. |
| `created_at` | Immutable UTC insertion instant. |

## Owner `care` (`mentalbridge_care.public`)

The MB-88 tables below are executable Liquibase-owned structures in the Care database's default `public` schema. Later support, intervention, grant, and follow-up entries in this section remain conceptual until their owner migrations are added.

### `public.user_profile`

Care-owned non-credential user profile and communication preferences.

| Field | Purpose |
| --- | --- |
| `account_id` | External Identity account UUID and immutable profile identifier; it does not prove current account access. |
| `display_name` | User-controlled name displayed in permitted product contexts. |
| `date_of_birth` | Optional birth date used only for approved age-related eligibility or personalization rules. |
| `gender` | Optional self-described gender value used only where product policy permits. |
| `locale` | BCP 47 locale used to select translated questionnaires, resources, and messages. |
| `timezone` | IANA timezone used to calculate and render reminders and follow-up schedules. |
| `reminder_enabled` | User preference controlling optional care reminders, excluding mandatory safety behavior. |
| `created_at` | Immutable UTC profile creation instant. |
| `updated_at` | UTC instant of the latest persisted profile change, maintained by Care Service. |
| `version` | Optimistic-lock counter incremented on concurrent profile mutations. |

### `public.consent_decision`

Append-only evidence of a user grant or refusal for a versioned platform consent.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID used to cite this exact consent decision in audit and REST results. |
| `user_id` | Care-owned user profile that made the consent decision. |
| `consent_type` | Stable independent platform-consent category. Sprint 2 runtime accepts only `PRIVACY_POLICY`; reserved AI/research/marketing values are not exposed, and specialist access uses a separate scoped grant. |
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

Published seed versions are `phq9-en-us-v1` and `phq9-vi-vn-capstone-v1`. The Vietnamese row is limited to controlled local/demo Capstone use and stores its archived artifact URI, retrieval timestamp, SHA-256 checksum, use statement, and scoring citation in `source_reference`. Production review creates a new immutable publication decision/version; it never rewrites this evidence row.

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
| `privacy_policy_version` | Exact backend-published disclosure acknowledged for this submission; `legacy-pre-mb178` identifies foundation rows created before the MB-178 gate and must not be presented as Capstone consent. |
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
| `safety_item_positive` | Authoritative derived fact that the versioned questionnaire safety item met its positive rule; it remains independent from the screening level. |
| `safety_status` | Nullable transition field for the independent `NEGATIVE_SAFETY_SCREEN` or `POSITIVE_SAFETY_SCREEN` policy result; every MB-89 runtime result must populate it together with `safety_policy_version`, while null is reserved only for pre-policy foundation rows. |
| `safety_policy_version` | Nullable transition field identifying the exact approved safety policy used; paired atomically with `safety_status` so historical results remain reproducible. |
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
| `occurred_at` | UTC instant the domain fact occurred. |
| `published_at` | UTC broker-acknowledged publication instant; null while pending. |
| `attempt_count` | Non-negative relay attempt count used for bounded retry observability. |
| `next_attempt_at` | Optional UTC instant before which the relay must not retry. |
| `created_at` | Immutable UTC database insertion instant. |

### `care.support_classification`

Versioned platform support-tier result derived from approved sources, distinct from diagnosis.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID identifying this reproducible support-policy execution. |
| `user_id` | Care profile for whom the platform support tier was calculated. |
| `tier` | Authoritative approved support pathway; values never claim low, medium, or high suicide risk. |
| `policy_version` | Exact deterministic policy version needed to reproduce and audit the decision. |
| `reason_codes` | Stable machine-readable reasons supporting the tier without storing free-form model reasoning. |
| `source_assessment_ids` | Identifiers of authoritative assessment submissions used by this calculation. |
| `source_analysis_ids` | Nullable identifiers reserved for a future policy that explicitly approves structured AI indicators. `mb-support-routing-capstone-v1` prohibits AI input, so this conceptual field is empty for that version. |
| `safety_flag` | Indicates immediate safety guidance was required independently of asynchronous systems. |
| `calculated_at` | UTC instant the policy executed. |
| `superseded_at` | UTC instant a newer authoritative classification replaced this result; null while current. |
| `created_at` | Immutable UTC insertion instant for provenance. |

### `care.intervention_plan`

Versioned set of platform support actions generated for one support classification.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID used by follow-up resources and REST endpoints. |
| `user_id` | Care profile that owns and may view the plan. |
| `support_classification_id` | Exact support-policy result that justified generating the plan. |
| `template_code` | Stable reviewed intervention template identifier. |
| `template_version` | Exact template revision used so displayed actions remain auditable. |
| `status` | Lifecycle state controlling whether the plan is active, complete, superseded, or cancelled. |
| `actions` | Validated ordered structured actions copied from the reviewed template for historical stability. |
| `generated_at` | UTC instant the plan was generated from the support result. |
| `completed_at` | UTC instant the plan reached completion; null until completed. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest persisted plan status/action change. |
| `version` | Optimistic-lock counter preventing lost concurrent plan updates. |

## Conceptual owner `consultation` (`mentalbridge_consultation.public`)

### `consultation.specialist_profile`

Consultation-owned professional profile and authoritative administrator approval state. The product does not collect specialist verification documents.

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
| `decision_reason_code` | Optional stable, non-sensitive reason for rejection or suspension; unrestricted document/evidence text is not stored. |
| `created_at` | Immutable UTC profile creation instant. |
| `updated_at` | UTC instant of the latest persisted profile or approval change. |
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

### `consultation.subscription_plan`

Stable plan identity used to group immutable commercial versions.

| Field | Purpose |
| --- | --- |
| `code` | Stable plan code: `FREE`, `PREMIUM_CARE`, or `PREMIUM_PLUS`. |
| `display_name` | Reviewed user-facing plan name. |
| `tier_rank` | Unique ordering used to reject same-tier changes and every downgrade; zero is Free. |
| `active` | Controls whether a plan accepts new purchases without deleting historical versions. |
| `created_at` | Immutable UTC plan creation instant. |
| `updated_at` | UTC instant of the latest catalogue-level activation/name change. |

### `consultation.subscription_plan_version`

Immutable price, allocation, credit, revenue-share, and cancellation policy purchased by a subscription period.

| Field | Purpose |
| --- | --- |
| `id` | Immutable plan-version UUID referenced by subscriptions, upgrades, payments, and credits. |
| `plan_code` | Stable parent plan identity. |
| `version` | Monotonically increasing version within the plan; published versions are never rewritten. |
| `currency` | ISO 4217 currency for every minor-unit amount in this version; current catalogue uses `USD`. |
| `price_minor` | Exact monthly price in currency minor units: 0, 999, or 1999 for current versions. |
| `billing_period_months` | Calendar-month period count; current plans use one month rather than a fixed 30-day duration. |
| `consultation_credits_per_period` | Number of indivisible credits granted after a successful payment: 0, 1, or 3. |
| `non_consultation_value_minor` | Price allocation for non-consultation premium features; current paid plans use 499. |
| `credit_value_minor` | Explicit value allocated to each credit; current paid plans use 500 so earnings are not derived from the whole subscription. |
| `specialist_share_bps` | Specialist share in basis points snapshotted into each credit; 7000 means 70%. |
| `cancellation_cutoff_hours` | Optional whole-hour cutoff separating eligible credit release from late cancellation forfeiture. Current conceptual rows leave it null because product approval is still pending; booking cannot be production-enabled without a published policy. |
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

Authoritative paid-subscription lifecycle for one user. Absence of an active paid row means Free entitlement.

| Field | Purpose |
| --- | --- |
| `id` | Immutable subscription UUID exposed in payment/status/history APIs. |
| `user_id` | External Care user UUID owning the subscription; not proof of current account authorization. |
| `plan_version_id` | Current immutable paid-plan version; an applied upgrade changes this reference while upgrade history preserves the prior version. |
| `status` | Current lifecycle state. `CANCEL_PENDING_SESSION_END` means paid features are already disabled but one consultation that had started may finish at its snapshotted end. |
| `current_period_start` | UTC start of the active provider billing period; null while initial payment is pending. |
| `current_period_end` | UTC exclusive end of the current billing period used for benefit/credit expiry and exact upgrade seconds. |
| `cancellation_requested_at` | UTC instant immediate cancellation was requested; null otherwise. It does not imply a refund. |
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
| `reason_code` | Optional stable machine-readable reason without provider payload text. |
| `changed_at` | UTC instant the transition committed. |

### `consultation.payment_transaction`

MoMo-backed payment attempt and current externally confirmed outcome. `FAKE` may exercise the same MoMo-shaped contract in local/CI; it is not a second production payment method. The MVP creates no refund transaction.

| Field | Purpose |
| --- | --- |
| `id` | Immutable payment UUID exposed in bounded payment history. |
| `subscription_id` | Paid subscription receiving an initial period, renewal, or upgrade. |
| `payment_provider` | `MOMO` in real environments or MoMo-shaped `FAKE` in local/CI; no other production provider is supported. |
| `momo_order_id` | Merchant-generated MoMo `orderId`, unique per provider and used as the primary IPN correlation key. |
| `momo_request_id` | Merchant-generated MoMo `requestId`, unique per provider and matched exactly on IPN. |
| `momo_trans_id` | Positive MoMo transaction ID when issued. Zero/unusable values are not copied from a failed IPN; positive values are unique. |
| `amount_minor` | Exact positive amount requested/confirmed in currency minor units. |
| `currency` | ISO 4217 currency validated against the plan/upgrade quote. |
| `status` | `PENDING`, `SUCCEEDED`, `FAILED`, or externally reported `CHARGEBACK`; refund states are unsupported. |
| `purpose` | Distinguishes `INITIAL_PURCHASE`, `RENEWAL`, and `UPGRADE` reconciliation. |
| `momo_result_code` | Latest verified MoMo `resultCode`; only final code `0` can produce `SUCCEEDED`. |
| `momo_pay_type` | Verified MoMo `payType`, such as QR or app payment. |
| `idempotency_key` | Subscription-scoped payment retry key preventing duplicate attempts. |
| `momo_response_time_epoch_ms` | Exact verified MoMo millisecond timestamp retained for signature/reconciliation evidence. |
| `provider_occurred_at` | UTC instant parsed from verified MoMo `responseTime`; null while checkout is pending. |
| `paid_at` | Provider-confirmed UTC success instant; null until successful. |
| `failed_at` | UTC terminal failure instant; null unless failed. |
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

### `consultation.consultation_credit`

One indivisible consultation right and authoritative current state; available balance is counted from these rows, never stored separately.

A composite foreign key guarantees the credit owner matches its subscription owner. Booking uses another composite key so a user cannot attach another user's credit.

| Field | Purpose |
| --- | --- |
| `id` | Immutable credit UUID used by booking, upgrade, ledger, and earning records. |
| `user_id` | External Care user UUID owning the credit. |
| `subscription_id` | Paid subscription whose successful period granted the credit. |
| `source_payment_id` | Successful payment that granted this exact credit and prevents unbacked grants. |
| `plan_version_id` | Immutable plan policy whose money/share values are snapshotted below. |
| `period_start` | UTC source-period start used for history and upgrade validation. |
| `period_end` | UTC source-period exclusive end; an appointment must start before it. |
| `ordinal` | One-based position within a subscription period, unique with period start. |
| `currency` | ISO 4217 currency of all monetary snapshots on the credit. |
| `allocated_value_minor` | Exact consultation allocation in minor units; current paid versions use 500. |
| `specialist_share_bps` | Snapshotted specialist share; current value is 7000. |
| `specialist_earning_minor` | Precomputed exact earning on completion; current value is 350, avoiding later rounding drift. |
| `status` | Authoritative state: available, appointment-reserved, upgrade-held, consumed, expired, forfeited, or revoked. |
| `expires_at` | UTC expiry equal to the source period end for an unreserved credit. |
| `created_at` | Immutable UTC grant instant. |
| `updated_at` | UTC instant of the latest validated state transition. |
| `version` | Optimistic-lock counter protecting booking, expiry, cancellation, and upgrade races. |

### `consultation.subscription_upgrade`

Immutable calculation snapshot and workflow for the only supported in-period change, Premium Care to Premium Plus.

| Field | Purpose |
| --- | --- |
| `id` | Immutable upgrade UUID exposed in quote/status operations. |
| `subscription_id` | Current paid subscription being upgraded. |
| `from_plan_version_id` | Care version used for remaining-value calculation. |
| `to_plan_version_id` | Higher Plus version that will start a full new period after payment. |
| `payment_id` | Unique upgrade payment attempt; null only before checkout creation completes. |
| `old_period_start` | Snapshotted UTC start of the period being ended. |
| `old_period_end` | Snapshotted UTC end used to calculate actual total/remaining seconds. |
| `total_period_seconds` | Exact positive number of seconds in the old provider period; never assumed to be 30 days. |
| `remaining_period_seconds` | Exact positive seconds remaining at quote time, not greater than total seconds. |
| `remaining_feature_value_minor` | `floor(old non-consultation value × remaining / total)` in minor units. |
| `available_credit_value_minor` | Sum of allocations for old credits atomically moved from available to upgrade-held. |
| `offset_minor` | Non-withdrawable sum of remaining feature and held-credit value, usable only by this upgrade. |
| `amount_due_minor` | Exact target full-period price minus offset; positive and verified against payment. |
| `currency` | ISO 4217 currency shared by both plan versions, held credits, and payment. |
| `status` | Pending payment, applied, failed, or expired; only one pending upgrade per subscription. |
| `idempotency_key` | Subscription-scoped retry key returning the original quote/checkout. |
| `quoted_at` | UTC calculation instant used to determine remaining seconds. |
| `quote_expires_at` | UTC instant after which held credits must be released or expired. |
| `applied_at` | UTC instant verified payment atomically activated Plus; null until applied. |
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

Authoritative half-open discrete slot published from a specialist's working schedule and bookable once. Start/end capture the offered interval; application validation against the standard-duration policy is pending product approval.

| Field | Purpose |
| --- | --- |
| `id` | Immutable slot UUID used in booking REST commands. |
| `specialist_id` | Specialist profile that owns the interval. |
| `start_at` | Inclusive UTC start instant of the available interval. |
| `end_at` | Exclusive UTC end instant, required to be later than start. |
| `timezone` | IANA timezone captured for stable human schedule rendering. |
| `channel` | Consultation channel offered for this interval. `IN_APP_CHAT` is initially enabled; `IN_APP_VIDEO` is reserved but cannot be enabled before its later contract. |
| `status` | Authoritative slot state used with database constraints to prevent conflicting bookings. |
| `created_at` | Immutable UTC slot creation instant. |
| `updated_at` | UTC instant of the latest slot state or schedule change. |
| `version` | Optimistic-lock counter preventing lost concurrent slot updates. |

### `consultation.appointment`

Authoritative scheduled consultation between one user and specialist for an owned availability slot and credit. It intentionally contains no physical location, phone number, or external meeting link.

Composite foreign keys require the appointment specialist to own the slot and the appointment user to own the credit; the application cannot create a locally inconsistent pairing.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID exposed in appointment REST resources and Kafka events. |
| `slot_id` | Owned availability slot reserved by the appointment and protected by a unique active-booking constraint. |
| `credit_id` | Consultation credit reserved by this appointment; a partial unique index prevents concurrent active use while allowing reuse after eligible cancellation. |
| `user_id` | External Care profile UUID of the person requesting consultation. |
| `specialist_id` | Consultation-owned specialist UUID denormalized for authorization and query efficiency. |
| `status` | Authoritative appointment workflow state; transitions are validated and recorded in history. |
| `scheduled_start_at` | Inclusive UTC start copied from the selected specialist slot at booking; join/send is not authorized before it. |
| `scheduled_end_at` | Exclusive UTC end copied from the selected specialist slot; join/send ends here even if the source availability later changes. |
| `scheduled_timezone` | Specialist slot's IANA timezone snapshot used to reproduce the originally booked schedule. |
| `channel` | Booked channel snapshot. Initially only `IN_APP_CHAT` is operational; `IN_APP_VIDEO` is future intent. |
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

### `consultation.consultation_credit_ledger_entry`

Append-only evidence for every credit grant, booking reservation/release, upgrade hold/release, consumption, expiry, forfeiture, and revocation.

| Field | Purpose |
| --- | --- |
| `id` | Immutable ledger-entry UUID. |
| `credit_id` | Credit whose authoritative state changed. |
| `appointment_id` | Optional appointment that caused reservation, release, consumption, or forfeiture. |
| `upgrade_id` | Optional upgrade that caused a hold, hold release, or revocation; mutually exclusive with appointment cause. |
| `entry_type` | Stable transition fact, including upgrade hold/release; it never represents a payment refund. |
| `from_status` | Previous credit state; null only for the grant entry. |
| `to_status` | New authoritative credit state after the transaction. |
| `reason_code` | Optional safe machine-readable policy/provider reason. |
| `idempotency_key` | Credit-scoped transition identity preventing replay from appending or applying the effect twice. |
| `occurred_at` | UTC instant the credit transition committed. |

### `consultation.specialist_earning`

One immutable monetary allocation created only by a completed appointment; current status supports settlement and provider payout reconciliation.

| Field | Purpose |
| --- | --- |
| `id` | Immutable earning UUID shown in specialist/admin history. |
| `appointment_id` | Unique completed appointment proving that one earning may exist. |
| `credit_id` | Unique consumed credit whose snapshotted allocation funds the earning. |
| `specialist_id` | Specialist who completed the appointment and owns the payable amount. |
| `currency` | ISO 4217 currency of all amounts in the earning. |
| `allocated_value_minor` | Credit allocation snapshot; current plan versions use 500. |
| `specialist_share_bps` | Revenue-share snapshot; current plan versions use 7000. |
| `specialist_amount_minor` | Exact specialist amount; current versions use 350 per completed credit. |
| `platform_amount_minor` | Exact remainder of the credit allocation; current versions use 150. |
| `status` | Settlement/payout state. `PAID` requires a linked payout with verified `SUCCEEDED` provider outcome. |
| `earned_at` | UTC appointment-completion instant. |
| `settlement_available_at` | UTC instant the earning becomes eligible for a provider payout request. |
| `reversed_at` | UTC instant an approved chargeback/reconciliation reversal was recorded; null otherwise. |
| `reversal_reason_code` | Stable non-sensitive reversal reason; null unless reversed. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest settlement/payout/reversal state change. |
| `version` | Optimistic-lock counter protecting settlement and payout races. |

### `consultation.specialist_payout_destination`

Encrypted specialist-owned destination used by a provider payout adapter. Raw wallet/account data never enters logs, events, or broad read models.

| Field | Purpose |
| --- | --- |
| `id` | Immutable destination UUID referenced by payout requests. |
| `specialist_id` | Specialist who owns and may manage this destination. |
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

Idempotent provider payout request and its reconciled current outcome. Local/CI uses a fake adapter; real domestic payout stays disabled until compatible VND pricing or an approved versioned FX policy exists.

| Field | Purpose |
| --- | --- |
| `id` | Immutable payout request UUID. |
| `specialist_id` | Specialist receiving the attached available earnings. |
| `destination_id` | Verified encrypted payout destination selected for this request. |
| `currency` | ISO 4217 currency shared by every attached earning. |
| `amount_minor` | Exact positive requested amount in minor units, equal to attached payout items. |
| `payout_provider` | `MOMO`/`FAKE` namespace used for request, status, and IPN reconciliation. |
| `status` | `PENDING`, `PROCESSING`, `SUCCEEDED`, `FAILED`, or uncertainty-preserving `UNKNOWN`. |
| `idempotency_key` | Specialist-scoped command key preventing duplicate logical payout creation. Provider attempts derive separate keys. |
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
| `external_url` | Optional reviewed external destination; one body or external URL is required. |
| `status` | Publication lifecycle controlling user visibility: `DRAFT`, `PUBLISHED`, or `ARCHIVED`. |
| `reviewed_by` | Administrator account UUID that approved the content for publication; null before review. |
| `reviewed_at` | UTC instant of the latest administrator approval; null before review. Only rows with both fields set are served to users. |
| `effective_at` | Optional UTC instant before which the resource is not yet active; null means immediately available after review. Used for scheduled content releases. |
| `expires_at` | Optional UTC instant after which the resource is no longer served; null means no expiry. Must be later than `effective_at` when both are set. |
| `created_at` | Immutable UTC content creation instant. |
| `updated_at` | UTC instant of the latest content or publication change. |
| `version` | Optimistic-lock counter preventing lost concurrent content edits. |

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

## Conceptual owner `ai` (owner-local `public` tables)

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
