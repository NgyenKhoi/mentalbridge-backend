# Care PostgreSQL Field Data Dictionary

Owner: `care-service`. Runtime database: `mentalbridge_care`, default schema `public`.

The [owner migration history](../../../care-service/src/main/resources/db/changelog/) is executable runtime truth; the [service README](../../../care-service/README.md) describes its behavior. The [owner logical schema](../../domain-model/relational/care-service.sql) is read-only documentation and must never provision or migrate a database.

Original owner/conceptual section labels and section-specific status notes are retained below. Grouping a description here does not promote a conceptual or historical shape into an active migration.

[All PostgreSQL owners](../postgresql-field-data-dictionary.md)

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
| `consent_type` | Stable independent platform-consent category. Runtime accepts `PRIVACY_POLICY` and `AI_PROCESSING` through the unchanged generic API; `AI_VOICE_PROCESSING` has a separate voice API and immutable disclosure version. Voice authorization requires both current AI streams. Research/marketing remain unavailable, and specialist access uses a separate scoped grant. |
| `policy_version` | Exact approved policy text/version accepted or refused so the decision remains reproducible. |
| `granted` | Authoritative decision value; false records an explicit refusal or withdrawal. |
| `evidence` | Minimized JSON object such as approved channel or document hash; never raw health content. |
| `idempotency_key` | Required retry fingerprint unique for one user and consent type so an uncertain retry returns the original decision. Voice stores a purpose- and owner-bound HMAC-SHA256 hex digest of the caller UUID using `CARE_IDEMPOTENCY_HMAC_KEY`; existing generic consent rows retain their original key semantics without backfill. |
| `request_hash` | Lowercase SHA-256 digest used to reject reuse of the key with different consent content; request plaintext is not recoverable from it. |
| `decided_at` | Server-issued UTC decision instant. Voice writes hold the profile row lock and advance at least one PostgreSQL microsecond beyond the previous voice decision when the clock ties or moves backward, so the latest withdrawal cannot lose to a random UUID tie-break. |
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
| `evidence_owner` / `evidence_kind` / `evidence_id` / `evidence_revision` | Minimal native v3 completion reference: Care acknowledgement for Resource/routine, Content `RESOURCE_PRACTICE_SESSION` or `RESOURCE_COMPLETION` for Resource, Care review submission, Journal entry/check-in fact, Consultation appointment fulfilment, or Care assessment history. Authority/kind must match the action type; no raw source content is copied. Content revision is the exact resource version; practice ID is the session UUID, completion ID is `<resourceId>:<localDate>` of the owner's completed daily row. Journal `JOURNAL_ENTRY` and emotion facts use immutable creation revision `1`. The partial unique index includes `user_id`, preventing the same owner fact revision from satisfying multiple occurrences without colliding across owners' resource/date completion IDs. |
| `evidence_occurred_at` / `evidence_verified_at` | UTC source-fact occurrence and verification instants, with verification no earlier than occurrence. Journal writing facts use server-generated entry `createdAt`; the instant must fall on the occurrence day in its schedule timezone and after activation. Content uses progress `completed_at` or practice server `completed_at - duration_seconds`, including historical sessions with client-supplied `started_at`; its fact instant must fall on the occurrence day in the occurrence's pinned timezone, never merely match a client-selected local date. Accepted historical evidence survives source revision/deletion. Evidence is absent unless a native v3 occurrence is `COMPLETED`. |
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
draft creation and reload under [SupportPlan policy v2](../../policies/support-plan-policy-v2.md).

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

## Care-owned ConsultationBrief tables

These descriptions were previously placed under the Content/Notification heading in the combined dictionary. They are grouped here because Care owns the tables, as stated in the existing descriptions and [Care persistence inventory](../../../care-service/README.md#postgresql-ownership). The field definitions and lifecycle statements are preserved.

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
