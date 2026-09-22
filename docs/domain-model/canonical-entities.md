# Canonical entity index

This index is the centralized entry point for MentalBridge persisted entities
and document aggregates. It was reconciled against the current owner
migrations on 2026-09-21, including the Care SupportPlan activity occurrence
migration in the same pull request. Exact columns, validators, constraints, and indexes remain
authoritative in owner migrations.

See [README](README.md) for status and relationship semantics.

## Identity — PostgreSQL

| Entity | Owner service | Storage | Status | Key relationships |
| --- | --- | --- | --- | --- |
| `role` | identity-service | PostgreSQL | ACTIVE | Physical one-to-many to `account` through `account.role_code`. |
| `account` | identity-service | PostgreSQL | ACTIVE | Physical role reference; logical/external identity for Care profiles, specialists, entitlements, content actors, Journal/AI owners, and Realtime participants. |
| `refresh_session` | identity-service | PostgreSQL | ACTIVE | Physical many-to-one to `account`; physical self-reference for token rotation. |
| `one_time_token` | identity-service | PostgreSQL | ACTIVE | Physical many-to-one to `account`. |
| `idempotency_record` | identity-service | PostgreSQL | ACTIVE | Optional physical reference to `account`; owns encrypted replay result metadata. |
| `outbox_event` | identity-service | PostgreSQL | ACTIVE | Logical aggregate ID; no cross-service foreign key. |
| `security_audit_event` | identity-service | PostgreSQL | ACTIVE | Optional physical subject/actor references to `account`. |
| `account_role` | identity-service | PostgreSQL | HISTORICAL | Removed by migration 002 after `account.role_code` became the single authoritative role. |

## Care — PostgreSQL

| Entity | Owner service | Storage | Status | Key relationships |
| --- | --- | --- | --- | --- |
| `user_profile` | care-service | PostgreSQL | ACTIVE | `account_id` is a logical/external Identity reference, not a physical cross-service FK. |
| `consent_decision` | care-service | PostgreSQL | ACTIVE | Physical many-to-one to `user_profile`; append-only versioned decisions. |
| `anonymous_assessment_session` | care-service | PostgreSQL | ACTIVE | Physical optional owner of anonymous `assessment_submission`; never links to an account. |
| `questionnaire_definition` | care-service | PostgreSQL | ACTIVE | Physical parent of questions, score bands, and assessment submissions. |
| `questionnaire_question` | care-service | PostgreSQL | ACTIVE | Physical many-to-one to exact questionnaire definition. |
| `questionnaire_score_band` | care-service | PostgreSQL | ACTIVE | Physical version-owned scoring ranges. |
| `assessment_submission` | care-service | PostgreSQL | ACTIVE | Exactly one physical owner: `user_profile` or `anonymous_assessment_session`; physical reference to questionnaire definition. |
| `assessment_answer` | care-service | PostgreSQL | ACTIVE | Composite physical references keep submission and question on one definition. |
| `assessment_result` | care-service | PostgreSQL | ACTIVE | Physical one-to-one with `assessment_submission`. |
| `outbox_event` | care-service | PostgreSQL | ACTIVE | Logical aggregate identity; minimized event payload. |
| `support_policy_definition` | care-service | PostgreSQL | ACTIVE | Physical parent of v1 eligibility, band meaning, tier guidance, and evaluation provenance. |
| `support_policy_eligible_definition` | care-service | PostgreSQL | ACTIVE | Physical bridge between v1 policy and questionnaire definition. |
| `screening_band_meaning` | care-service | PostgreSQL | ACTIVE | Physical child of v1 support policy. |
| `support_tier_guidance` | care-service | PostgreSQL | ACTIVE | Physical child of v1 support policy and referenced by v1 evaluation. |
| `support_evaluation` | care-service | PostgreSQL | ACTIVE | Physical owner/evidence references to profile and exact PHQ-9/GAD-7 submissions; immutable coarse v1 history. |
| `support_evaluation_request` | care-service | PostgreSQL | ACTIVE | Physical owner-scoped idempotency alias to v1 evaluation. |
| `support_evaluation_v2_policy_definition` | care-service | PostgreSQL | ACTIVE | Physical parent of domain-aware v2 policy provenance. |
| `support_evaluation_v2_eligible_definition` | care-service | PostgreSQL | ACTIVE | Physical bridge between v2 policy, questionnaire version, and domain. |
| `support_evaluation_v2` | care-service | PostgreSQL | ACTIVE | Physical owner/evidence references to one PHQ-9 and one GAD-7 submission. |
| `support_evaluation_v2_domain` | care-service | PostgreSQL | ACTIVE | Physical child with two independent domain contributions. |
| `support_evaluation_v2_safety` | care-service | PostgreSQL | ACTIVE | Physical one-to-one safety snapshot tied to v2 PHQ-9 evidence. |
| `support_evaluation_v2_request` | care-service | PostgreSQL | ACTIVE | Physical owner-scoped idempotency alias to v2 evaluation. |
| `support_guide` | care-service | PostgreSQL | ACTIVE | Physical owner/evaluation reference; immutable all-tier guide snapshot. |
| `support_guide_resource` | care-service | PostgreSQL | ACTIVE | Physical child of guide; resource/publication IDs are logical/external Content references with persisted display snapshots. |
| `support_guide_request` | care-service | PostgreSQL | ACTIVE | Physical owner-scoped idempotency alias to guide. |
| `support_plan` | care-service | PostgreSQL | ACTIVE | Physical owner/evaluation reference with explicit draft/current/terminal lifecycle; entitlement fields snapshot a logical/external Consultation decision. |
| `support_plan_template_family` | care-service | PostgreSQL | ACTIVE | Physical ordered child of `support_plan`. |
| `support_plan_slot` | care-service | PostgreSQL | ACTIVE | Physical child of plan; exact resource/publication IDs are logical/external Content references. |
| `support_plan_slot_alternative` | care-service | PostgreSQL | ACTIVE | Physical child of slot with admitted exact Content alternatives. |
| `support_plan_request` | care-service | PostgreSQL | ACTIVE | Physical owner-scoped idempotency alias to plan. |
| `support_plan_command` | care-service | PostgreSQL | ACTIVE | Physical owner/plan reference; idempotent activation outcome with exact revalidation evidence. |
| `support_plan_command_selection` | care-service | PostgreSQL | ACTIVE | Physical child preserving the ordered exact resource-version intent committed by activation. |
| `support_plan_activity_schedule` | care-service | PostgreSQL | ACTIVE | Physical child of a plan; snapshots recurrence, local time, IANA timezone, and exact selected-resource provenance. |
| `support_plan_activity_occurrence` | care-service | PostgreSQL | ACTIVE | Physical child of a schedule and owner-matched plan; deterministic dated state plus versioned owner-only helpfulness/barrier/reflection and visibility, with exact source provenance. |
| Specialist access grants/scopes | care-service | PostgreSQL | PROPOSED | Approved consent concept; no Care owner migration exists yet. Selected Journal IDs would be logical/external Journal/AI references. |
| Follow-up plan/check-in | care-service | PostgreSQL | PROPOSED | Broader clinical follow-up remains proposed; MB-513 SupportPlan wellbeing activity occurrences are the separate active aggregate above. |
| `support_classification` / `intervention_plan` | care-service | PostgreSQL | HISTORICAL | Superseded logical names; active persistence uses versioned SupportEvaluation, Support Guide, and SupportPlan aggregates. |

## Consultation — PostgreSQL

| Entity | Owner service | Storage | Status | Key relationships |
| --- | --- | --- | --- | --- |
| `specialist_profile` | consultation-service | PostgreSQL | ACTIVE | `account_id` and reviewer IDs are logical/external Identity references. |
| `specialist_profile_support_area` | consultation-service | PostgreSQL | ACTIVE | Physical many-to-one child of `specialist_profile`. |
| `specialist_profile_language` | consultation-service | PostgreSQL | ACTIVE | Physical many-to-one child of `specialist_profile`. |
| `specialist_profile_status_history` | consultation-service | PostgreSQL | ACTIVE | Physical history child; actor ID is a logical/external Identity reference. |
| `current_service_entitlement` | consultation-service | PostgreSQL | ACTIVE | Account and establishing actor are logical/external Identity references; current narrow package decision for consumers. |
| `availability_slot` | consultation-service | PostgreSQL | ACTIVE | Physical many-to-one to `specialist_profile`; active slots cannot overlap for one specialist. |
| Specialty catalogue/assignment | consultation-service | PostgreSQL | PROPOSED | Discovery policy exists, but no owner migration implements specialty persistence. |
| Subscription plan/version/entitlement | consultation-service | PostgreSQL | PROPOSED | ADR 0017 approves package semantics; exact VND prices/allocations and owner migrations remain gated. |
| User subscription/payment/IPN/upgrade/credit ledger | consultation-service | PostgreSQL | PROPOSED | Approved billing boundary without active migration; must not be inferred from `current_service_entitlement`. |
| Appointment/status history/completion evidence | consultation-service | PostgreSQL | PROPOSED | Flow is approved, but no appointment owner migration exists. `availability_slot` alone is not a booking. |
| Specialist earning/payout/reconciliation | consultation-service | PostgreSQL | PROPOSED | Approved financial boundary remains gated by pricing, credentials, and owner migrations. |
| Specialist review | consultation-service | PostgreSQL | PROPOSED | Product scope exists, but no persistence migration is active. |

## Content/Notification — PostgreSQL

| Entity | Owner service | Storage | Status | Key relationships |
| --- | --- | --- | --- | --- |
| `resource` | content-notification-service | PostgreSQL | ACTIVE | Root reviewed-content aggregate; actor IDs are logical/external Identity references. |
| `resource_idempotency_record` | content-notification-service | PostgreSQL | ACTIVE | Physical optional reference to replayed `resource`; actor ID is external. |
| `resource_audit_event` | content-notification-service | PostgreSQL | ACTIVE | Logical local resource reference retained independently for audit history. |
| `resource_eligibility_publication` | content-notification-service | PostgreSQL | ACTIVE | Physical exact-version child of `resource`. |
| `resource_eligibility_declaration` | content-notification-service | PostgreSQL | ACTIVE | Physical child of publication with domain/role/instrument eligibility. |
| `resource_eligibility_withdrawal` | content-notification-service | PostgreSQL | ACTIVE | Physical one-to-one append-only withdrawal of a publication. |
| `resource_eligibility_command_record` | content-notification-service | PostgreSQL | ACTIVE | Physical replay reference to publication; actor ID is external. |
| `notification_preference` | content-notification-service | PostgreSQL | ACTIVE | User ID is a logical/external Identity reference. |
| `notification` | content-notification-service | PostgreSQL | ACTIVE | Recipient ID is a logical/external Identity reference. |
| Area directory | content-notification-service | PostgreSQL | PROPOSED | ADR 0017 requires provenance-bearing area entries, but no current migration defines the entity. |
| `hotline` | content-notification-service | PostgreSQL | HISTORICAL | Created by migration 1 and explicitly dropped by migration 2; it must not appear as active safety data. |

## Journal/AI — MongoDB

| Entity | Owner service | Storage | Status | Key relationships |
| --- | --- | --- | --- | --- |
| Journal Entry | journal-ai-service | MongoDB `journal_entries` | ACTIVE | Owner ID is a logical/external Identity reference; embeds bounded Journal Revisions and command replay records. |
| Journal Revision | journal-ai-service | Embedded document | ACTIVE | Physical embedding inside one Journal Entry; exact revision is referenced logically by jobs/results. |
| Analysis Job | journal-ai-service | MongoDB `analysis_jobs` | ACTIVE | Logical same-owner reference to exact Journal Entry revision; owner ID is external Identity. |
| Journal Analysis Result | journal-ai-service | MongoDB `journal_analysis_results` | ACTIVE | Logical one-to-one result for job and exact Journal revision; MongoDB has no FK. |
| Emotion Check-In | journal-ai-service | MongoDB `emotion_check_ins` | ACTIVE | Owner ID is external Identity; embeds bounded encrypted revisions and replay commands. |
| Benchmark Dataset | journal-ai-service | MongoDB `benchmark_datasets` | ACTIVE | Metadata root for synthetic, versioned benchmark input. |
| Benchmark Run | journal-ai-service | MongoDB `benchmark_runs` | ACTIVE | Logical reference to exact benchmark dataset version/digest. |
| Benchmark Case Result | journal-ai-service | MongoDB `benchmark_case_results` | ACTIVE | Logical child of run, unique per case/provider/model. |
| Longitudinal Analysis Job/Result | journal-ai-service | MongoDB | PROPOSED | ADR 0015 defines the bounded model, but no migration creates these collections. |

## Realtime — MongoDB

| Entity | Owner service | Storage | Status | Key relationships |
| --- | --- | --- | --- | --- |
| Conversation | realtime-service | MongoDB `conversations` | ACTIVE | Appointment ID is a logical/external Consultation reference; participant account IDs are external Identity references. |
| Message | realtime-service | MongoDB `messages` | ACTIVE | Logical same-owner reference to Conversation; sender ID is external Identity. |
| Message Receipt | realtime-service | MongoDB | PROPOSED | Approved/deferred high-water delivery/read model; no migration or runtime persistence exists. |
| Message Attachment | realtime-service | MongoDB/object metadata | PROPOSED | Deferred until file/provider and authorization contracts are accepted. |
| Moderation evidence | realtime-service | MongoDB | PROPOSED | Deferred owner model; current message field only carries `moderationHold`. |

## Cross-cutting proposed models

The former whole-system SQL listed centralized `platform` tables for audit,
moderation, retention, deletion, and generic outbox data. No deployable owns a
shared platform database. These capabilities remain owner-local projections or
future owner-specific persistence and are `PROPOSED`, not active centralized
tables. A future migration must identify the actual owner before adding them to
the active model.
