# PostgreSQL Field Data Dictionary

This document explains the business purpose of PostgreSQL fields. The [canonical PostgreSQL logical schema](../domain-model/relational/postgresql-logical-schema.sql) is a non-executable documentation model and must never provision or migrate an environment. Names such as `consultation.availability_slot` identify a visual owner namespace; the physical table is `public.availability_slot` in the separate `mentalbridge_consultation` database. Service-owned migration histories are the executable runtime sources of truth: Liquibase for Spring services and the current SQL migration mechanism for Node.js services. This dictionary is written for developers and reviewers; descriptions are intentionally kept out of executable migrations.

When service-owned migrations are introduced, update this dictionary and the canonical logical model in the same change whenever persisted domain structure materially changes. A field description must explain why the value is persisted, whether it is authoritative, derived, external, or sensitive, and how nullability, time, versioning, or idempotency affects behavior. Content/Notification's executable history starts at `content-notification-service/migrations/1_initial_schema.sql`; migration 2 removes the obsolete hotline table, the separate Review 1 migrations insert controlled demo content and its initial item-level eligibility matrix, migration 6 adds immutable Resource Eligibility v1 provenance, migration 7 adds the separately governed reviewed safety directory, and migration 8 adds its deterministic reviewed area vocabulary. The field descriptions under its conceptual owner below describe the resulting physical `public` tables.

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
| `occurred_at` | UTC instant the domain fact occurred. |
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

### `public.support_plan`

Care-owned runtime aggregate for MB-372/MB-373/MB-513/MB-374. MB-372 creates `DRAFT`;
MB-373 permits an explicit revalidated transition to `ACTIVE`; MB-513 adds
pause/resume, completion, replacement, and discard; MB-374 exposes those
user-confirmed transitions with immutable terminal history. Separate partial unique
indexes on `DRAFT` and on `ACTIVE`/`PAUSED` are the final concurrent guards for
one draft and one official current plan per owner.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID exposed by the owner API and used by child snapshots. |
| `user_id` | Care profile owner; locked during creation and never accepted from a client payload. |
| `support_evaluation_id` | Exact owner-matched immutable SupportEvaluation v2 that seeded composition. |
| `status` | Care-owned `DRAFT`, `ACTIVE`, `PAUSED`, `COMPLETED`, `SUPERSEDED`, or `DISCARDED` lifecycle state. |
| `version` | Optimistic-lock counter returned in the ETag; incremented by each accepted choice or activation command. |
| `evaluation_policy_version` / `evaluated_at` | Exact current-compatible Care evaluation policy and original UTC evaluation instant. |
| `selection_policy_version` | Deterministic Care composition version, fixed to `mb-support-plan-selection-v1`. |
| `resource_policy_version` / `resources_resolved_at` | Exact Content eligibility policy and UTC resolution instant used before the Care transaction. |
| `entitlement_package` / `entitlement_source` | Authoritative `PLUS`/`PREMIUM` and `DEMO`/`PAID` provenance returned by Consultation; never inferred from JWT/client state. |
| `entitlement_policy_version` / `entitlement_version` / `entitlement_decided_at` | Exact Consultation read-model version and UTC decision evidence that admitted creation. |
| `rationale_code` / `rationale_text` | Stable general-wellbeing explanation snapshot; not diagnosis, treatment, or AI reasoning. |
| `safety_status` / `safety_reason_code` / `safety_policy_version` | Independent Care-owned PHQ-9 item-9 safety evidence without answers or scores. |
| `safety_guidance_code` / `safety_guidance` | Approved safety copy stored with the draft so reload never depends on AI or another service. |
| `selected_resource_count` | Number of persisted selected exact versions, constrained to 1..5. |
| `activated_at` | UTC instant of explicit activation; null while the plan is a draft. |
| `completion_reason` | Optional bounded user-selected reason code (`USER_DECISION`, `PLAN_NO_LONGER_FITS`, or `OTHER`) stored only for a completed plan; it is not a clinical interpretation. |
| `completed_at` / `superseded_at` / `discarded_at` | UTC instant for the matching terminal state; lifecycle checks require exactly the applicable timestamp and activation history. |
| `created_at` / `updated_at` | UTC creation and latest accepted business-command instants. |

### `public.support_plan_template_family`

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable row UUID. |
| `support_plan_id` / `ordinal` | Parent draft and stable 1-based composition order, bounded to two domains. |
| `family` | Approved immutable family selected deterministically from one domain-local instrument band. |
| `template_version` | Exact immutable Care template version; MB-372 publishes version 1 only. |
| `target_domain` | `DEPRESSIVE_SYMPTOMS` or `ANXIETY_SYMPTOMS`; never a combined severity. |

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

### `public.support_plan_command`

Owner-scoped append-only audit and replay record for MB-373 activation. Choice
replacement uses PUT semantics plus optimistic concurrency and does not create
a request-deduplication record. This table stores no bearer token, assessment
answer, journal content, or client-authored display text.

| Field | Purpose |
| --- | --- |
| `user_id` / `idempotency_key` | Owner-scoped printable retry namespace and primary key. |
| `command_type` / `request_hash` | `ACTIVATE` and the SHA-256 fingerprint of plan/version for exact retry matching. |
| `support_plan_id` / `expected_version` | Owner-matched target and optimistic version explicitly acted on by the user. |
| `resulting_version` / `resulting_status` / `resulting_updated_at` | Exact replay outcome; resulting version is the expected version plus one. |
| `evaluation_policy_version` | Current compatible Care evaluation policy revalidated immediately before the local command transaction. |
| `entitlement_package` / `entitlement_source` / `entitlement_policy_version` / `entitlement_version` / `entitlement_decided_at` | Fresh authoritative paid-entitlement evidence used by the command. |
| `resource_policy_version` / `resources_resolved_at` | Exact Content eligibility policy and resolution instant used for final exact-version validation. |
| `created_at` | UTC instant the command and its outcome committed. |

### `public.support_plan_command_selection`

Ordered exact final selection set committed by activation. A missing optional
slot represents a choice removed before activation; the plan snapshot preserves
the authoritative state.

| Field | Purpose |
| --- | --- |
| `user_id` / `idempotency_key` | Physical parent key to `support_plan_command`. |
| `ordinal` | Stable 1-based intent order, bounded to the plan's 1..5 selected-resource limit. |
| `slot_key` | Exact existing Care-owned slot selected by the user; unique within the command. |
| `resource_id` / `content_version` | Exact admitted Content version selected for that slot; unique within the command. |

### `public.support_plan_activity_schedule`

Care-owned recurrence snapshot created from one selected SupportPlan resource.
It keeps time interpretation and source attribution stable even when the profile
timezone or external content later changes.

| Field | Purpose |
| --- | --- |
| `id` | Immutable schedule UUID and parent of its occurrence history. |
| `support_plan_id` / `user_id` | Owner-matched plan and account; the composite foreign key blocks cross-owner rows. |
| `ordinal` / `schedule_version` | Stable source-slot order and recurrence revision; unique together within a plan. |
| `source_plan_version` / `source_slot_key` | Exact plan version and selected slot that authorized the schedule. |
| `source_resource_id` / `source_content_version` / `source_title` | Exact Content identity/version and reviewed title snapshot. |
| `recurrence_type` / `recurrence_day_of_week` | `DAILY`, or `WEEKLY` with ISO weekday 1..7. |
| `local_time` / `timezone` | Intended wall-clock time and snapshotted IANA timezone used for deterministic DST resolution. |
| `effective_from` / `effective_until` | Inclusive local-date validity; the end remains null while active. |
| `status` | `ACTIVE`, `PAUSED`, or `ENDED`; Content/Notification never controls it. |
| `created_at` / `updated_at` | UTC creation and latest lifecycle transition instants. |

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
| `state_reason` | Null except `PLAN_PAUSED`, `PLAN_COMPLETED`, or `PLAN_REPLACED` on cancelled rows. |
| `source_plan_version` / `source_slot_key` | Exact Care plan/slot provenance. |
| `source_resource_id` / `source_content_version` / `source_title` | Exact Content identity/version and reviewed title snapshot. |
| `version` | Optimistic counter required by explicit user state updates. |
| `created_at` / `updated_at` | UTC insertion and latest accepted transition instants. |
| `completed_at` / `skipped_at` / `cancelled_at` | Exactly the timestamp matching the persisted terminal state; all null while scheduled. |
| `hidden` | Owner-only display preference; independent of completion/skip state. |
| `helpfulness` | Optional owner rating for `COMPLETED`: `NOT_HELPFUL`, `A_LITTLE_HELPFUL`, `HELPFUL`, or `VERY_HELPFUL`. |
| `barrier_code` | Optional owner-selected reason for `SKIPPED`: `LOW_ENERGY`, `NOT_ENOUGH_TIME`, `DIFFICULT_TO_START`, `NOT_A_GOOD_FIT`, or `OTHER`. |
| `reflection` | Optional private trimmed owner reflection, 1-500 characters; excluded from the integration event. |
| `summary_reuse_approved` | Explicit approval to reuse minimized coded facts in a later bounded summary; never broad checklist monitoring consent. |
| `engagement_updated_at` | Latest accepted replacement or deletion instant. Deletion clears mutable values but retains provenance. |

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

### `consultation.specialist_profile_support_area`

V1 reviewed non-clinical support domains declared by a specialist. The database
accepts only `DEPRESSIVE_SYMPTOMS` and `ANXIETY_SYMPTOMS`.

| Field | Purpose |
| --- | --- |
| `specialist_account_id` | Owning specialist profile and first half of the composite primary key. |
| `support_area` | Approved support-domain enum and second half of the composite primary key. |

### `consultation.specialist_profile_language`

V1 languages declared for user-facing support. Story 6101 limits values to
lowercase `vi` and `en`.

| Field | Purpose |
| --- | --- |
| `specialist_account_id` | Owning specialist profile and first half of the composite primary key. |
| `language_tag` | Supported language tag and second half of the composite primary key. |

### `consultation.specialist_profile_status_history`

Append-only operational audit of explicit submission and administrator status
decisions. It stores no uploaded evidence or unrestricted notes.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID audit-entry identifier. |
| `specialist_account_id` | Specialist profile whose state was submitted or changed. |
| `approval_status` | State established by this explicit action. |
| `reason_code` | Stable reason for rejection/suspension when those later transitions are implemented. |
| `actor_account_id` | Identity UUID of the specialist or administrator performing the action. |
| `actor_role` | `SPECIALIST` or `ADMIN` operational actor class. |
| `occurred_at` | UTC instant when the action became effective. |

### `consultation.subscription_plan`

Stable plan identity used to group immutable commercial versions.

| Field | Purpose |
| --- | --- |
| `code` | Stable plan code. Historical v1 values `PREMIUM_CARE`/`PREMIUM_PLUS` remain readable; new v2 catalogue values are `FREE`, `PLUS`, and `PREMIUM`. |
| `display_name` | Reviewed user-facing plan name. |
| `tier_rank` | Unique ordering used to allow only `PLUS`-to-`PREMIUM` upgrade and reject every downgrade; zero is `FREE`. |
| `active` | Controls whether a plan accepts new purchases without deleting historical versions. |
| `created_at` | Immutable UTC plan creation instant. |
| `updated_at` | UTC instant of the latest catalogue-level activation/name change. |

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

Implemented MB-377 allocation boundary. One row freezes the highest package allocation seen for an exact user, entitlement policy version, and effective period. `DEMO` and `PAID` provenance is immutable within that period.

| Field | Purpose |
| --- | --- |
| `id` | Server-generated period UUID. |
| `account_id` | External Identity user UUID that owns every credit in the period. |
| `plan_version` | Exact source entitlement policy version used by the idempotency key. |
| `package_code` | Highest allocated package in the period: `PLUS` or `PREMIUM`. |
| `source` | Explicit `DEMO` or `PAID` provenance; never inferred by the client. |
| `source_reference` | Stable entitlement lifecycle or controlled-demo reference. |
| `period_start` / `period_end` | Inclusive/exclusive UTC billing or demo window. |
| `allocated_count` | Frozen allocation after allowed upgrade: 1 for Plus or 3 for Premium. |
| `created_at` / `updated_at` | UTC creation and latest in-period upgrade instants. |
| `version` | Optimistic version incremented by allocation upgrade. |

### `consultation.service_credit`

One indivisible consultation right. Current state is owner-controlled; balances are counted from these rows and never reconstructed in the browser.

| Field | Purpose |
| --- | --- |
| `id` | Stable credit UUID. |
| `period_id` | Owning immutable-provenance service-credit period. |
| `ordinal` | One-based position within the allocation; unique per period and capped at three. |
| `state` | `AVAILABLE`, `HELD`, `CONSUMED`, or `FORFEITED`. A release returns the state to `AVAILABLE` while the ledger preserves the fact. |
| `appointment_id` | Future local appointment UUID required for held and terminal appointment outcomes. |
| `created_at` / `updated_at` | UTC creation and latest transition instants. |
| `version` | Optimistic transition counter. |

### `consultation.service_credit_ledger`

Append-only evidence for provisioning and appointment-driven transitions.

| Field | Purpose |
| --- | --- |
| `id` | Immutable event UUID. |
| `credit_id` | Credit whose state changed. |
| `account_id` | Denormalized owner UUID for bounded history and account-scoped idempotency. |
| `event_type` | `PROVISIONED`, `HELD`, `RELEASED`, `CONSUMED`, or `FORFEITED`. |
| `appointment_id` | Required correlation for every non-provisioning transition. |
| `idempotency_key` | Owner command key unique per account; exact replay does not append another event. |
| `occurred_at` | Immutable server UTC transition instant. |

### `consultation.subscription_plan_version`

Immutable price, allocation, credit, revenue-share, and cancellation policy purchased by a subscription period.

| Field | Purpose |
| --- | --- |
| `id` | Immutable plan-version UUID referenced by subscriptions, upgrades, payments, and credits. |
| `plan_code` | Stable parent plan identity. |
| `version` | Monotonically increasing version within the plan; published versions are never rewritten. |
| `currency` | ISO 4217 currency for every amount. Historical demo versions may use `USD`; every new real-payment v2 version uses `VND`. |
| `price_minor` | Exact period price in currency minor units. Historical values remain immutable; v2 VND values require approval before publication. |
| `billing_period_months` | Calendar-month period count; current plans use one month rather than a fixed 30-day duration. |
| `consultation_credits_per_period` | Number of indivisible credits granted after a successful payment: 0, 1, or 3. |
| `non_consultation_value_minor` | Versioned price allocation for non-consultation features, used for upgrade calculation without runtime FX. |
| `credit_value_minor` | Fixed per-credit `creditAllocation`; v2 stores its approved VND amount so earnings are not derived from the whole package. |
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

Authoritative paid-subscription lifecycle for one user. Absence of an active
paid row means `FREE` entitlement. Historical plan codes remain unchanged;
new v2 plan versions use `PLUS` or `PREMIUM`.

Legacy cancellation/renewal fields below preserve the conceptual v1 history;
they do not authorize a v2 user-facing operation. Scope v2 exposes only new
purchase and `PLUS`-to-`PREMIUM` upgrade, with no downgrade/refund API.

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
| `purpose` | Distinguishes historical purposes. New v2 user-facing operations create only purchase or upgrade attempts; no downgrade/refund purpose exists. |
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
| `allocated_value_minor` | Exact fixed `creditAllocation` in plan currency minor units; historical values remain immutable and v2 uses approved VND values. |
| `specialist_share_bps` | Snapshotted specialist share; current value is 7000. |
| `specialist_earning_minor` | Precomputed 70% share of `allocated_value_minor`, payable only after evidence-backed `COMPLETED`. |
| `status` | Authoritative state: available, appointment-reserved, upgrade-held, consumed, expired, forfeited, or revoked. |
| `expires_at` | UTC expiry equal to the source period end for an unreserved credit. |
| `created_at` | Immutable UTC grant instant. |
| `updated_at` | UTC instant of the latest validated state transition. |
| `version` | Optimistic-lock counter protecting booking, expiry, cancellation, and upgrade races. |

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

### `consultation.appointment`

Authoritative scheduled consultation between one user and specialist for an
owned availability slot and credit. Historical v1 in-person appointments keep
their immutable location snapshot. New v2 appointments are chat/video only and
require channel-end, provider/server evidence, summary/next-step reuse approval,
and dispute fields before runtime is claimed.

Composite foreign keys require the appointment specialist to own the slot and the appointment user to own the credit; the application cannot create a locally inconsistent pairing.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID exposed in appointment REST resources and Kafka events. |
| `slot_id` | Owned availability slot reserved by the appointment and protected by a unique active-booking constraint. |
| `credit_id` | Consultation credit reserved by this appointment; a partial unique index prevents concurrent active use while allowing reuse after eligible cancellation. |
| `user_id` | External Care profile UUID of the person requesting consultation. |
| `specialist_id` | Consultation-owned specialist UUID denormalized for authorization and query efficiency. |
| `status` | Authoritative appointment workflow state; transitions are validated and recorded in history. |
| `scheduled_start_at` | Inclusive UTC start copied from the selected specialist slot at booking; chat waiting may begin ten minutes before it, but sending cannot. |
| `scheduled_end_at` | Exclusive UTC end copied from the selected specialist slot; join/send ends here even if the source availability later changes. |
| `scheduled_timezone` | Specialist slot's IANA timezone snapshot used to reproduce the originally booked schedule. |
| `channel` | Booked mode snapshot. Historical v1 includes `IN_PERSON`; new v2 records allow `IN_APP_CHAT` or contract-enabled `IN_APP_VIDEO` only. |
| `user_timezone` | IANA timezone captured at booking so the schedule remains understandable after device timezone changes. |
| `idempotency_key` | Caller retry key unique per user so uncertain REST retries return the original booking outcome. |
| `cancellation_reason` | Reviewed explanation recorded when a permitted cancellation occurs. |
| `requested_at` | UTC instant the booking request was accepted. |
| `confirmed_at` | UTC instant the appointment became confirmed; null otherwise. |
| `completed_at` | UTC instant the consultation reached evidence-based or user-confirmed completion; null otherwise. A specialist action alone is insufficient. |
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

Idempotent provider payout request and its reconciled current outcome. Local/CI
uses a fake adapter; real domestic payout stays disabled until exact VND plan
prices, fixed `creditAllocation`, and MoMo credentials exist. Runtime FX is not
allowed.

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
| `external_url` | Optional reviewed external destination; required for `VIDEO` and restricted to verified YouTube URLs. A resource may also carry a reviewed body. |
| `source_organization` | Organization responsible for the referenced source. Required for new published catalogue resources. |
| `source_title` | Human-readable title of the referenced source. Required for new published catalogue resources. |
| `source_url` | HTTP(S) provenance URL, distinct from the user action in `external_url`. Required for new published catalogue resources. |
| `source_review_note` | Review/licensing note documenting how MentalBridge adapted and checked the source. Required for new published catalogue resources. |
| `catalogue_visibility` | `LISTED` for catalogue browsing or `DIRECT_ONLY` for exact-version compatibility links that must not appear in the public list. |
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
| `reviewed_at` | UTC timestamp carried by a separately approved review decision; required while `PUBLISHED`. New publication remains blocked until MB-251 approves that decision model and authority. |
| `effective_at` | Optional UTC timestamp controlling delayed publication; resource not visible until this instant passes. |
| `expires_at` | Optional UTC timestamp after which published resource becomes hidden automatically. |
| `created_at` | Immutable UTC creation instant for audit and chronological ordering. |
| `updated_at` | UTC timestamp of latest persisted change; updated automatically on any modification. |
| `version` | Non-negative optimistic lock counter incremented on each update; prevents lost concurrent modifications. |
| `idempotency_key` | Legacy branch-local create retry field from migration 4. New writes use the actor/operation-scoped `resource_idempotency_record`; retained only for rolling compatibility. |

**State Transitions:**
- `DRAFT` → `PUBLISHED`: Blocked until MB-251 approves review authority, decision provenance, and review-version invalidation semantics
- `PUBLISHED` → `ARCHIVED`: Preserves review metadata, resource becomes hidden but retrievable
- Updates and deletions allowed only in `DRAFT` status
- The database check requires non-null review provenance while `PUBLISHED`; application transitions preserve provenance after publication. The check does not claim column immutability.

**Public API Safety:**
- Only `PUBLISHED` resources returned where `effective_at <= NOW()` and (`expires_at` IS NULL OR `expires_at > NOW()`)
- All published resources guaranteed to have review provenance
- `DRAFT` and `ARCHIVED` resources never exposed to public endpoints

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
