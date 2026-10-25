# Canonical entity index

This index is the centralized entry point for MentalBridge persisted entities
and document aggregates. It was reconciled against the current owner
migrations on 2026-09-24, including the Care Reassessment Summary snapshot
migration in the same pull request. Exact columns, validators, constraints, and indexes remain
authoritative in owner migrations.

See [README](README.md) for status and relationship semantics.

## Identity — PostgreSQL

MB-586 adds ACTIVE `platform_report_schedule` (physical ADMIN creator and
versioned configuration), and nullable occurrence provenance on ACTIVE
`platform_report_job`. Jobs link physically to the soft-deleted schedule and
one immutable `platform_report_artifact`; the unique schedule/UTC occurrence
pair prevents duplicate generation. Manual and scheduled jobs use one engine.

MB-673 adds ACTIVE `deletion_request`, `deletion_owner_fact`, and
`retention_execution_record` for fail-closed Identity coordination and truthful
owner-scoped execution evidence. These rows do not authorize cross-owner data
access or fabricate external owner completion.

| Entity | Owner service | Storage | Status | Key relationships |
| --- | --- | --- | --- | --- |
| `role` | identity-service | PostgreSQL | ACTIVE | Physical one-to-many to `account` through `account.role_code`. |
| `account` | identity-service | PostgreSQL | ACTIVE | Physical role reference; logical/external identity for Care profiles, specialists, entitlements, content actors, Journal/AI owners, and Realtime participants. |
| `refresh_session` | identity-service | PostgreSQL | ACTIVE | Physical many-to-one to `account`; physical self-reference for token rotation. |
| `one_time_token` | identity-service | PostgreSQL | ACTIVE | Physical many-to-one to `account`. |
| `idempotency_record` | identity-service | PostgreSQL | ACTIVE | Optional physical reference to `account`; owns encrypted replay result metadata. |
| `outbox_event` | identity-service | PostgreSQL | ACTIVE | Logical aggregate ID; no cross-service foreign key. |
| `security_audit_event` | identity-service | PostgreSQL | ACTIVE | Optional physical subject/actor references to `account`. |
| `deletion_request` | identity-service | PostgreSQL | ACTIVE | Identity-coordinated workflow for one account reference and privacy-minimized subject tombstone; no cascading account FK or cross-owner storage access. |
| `deletion_owner_fact` | identity-service | PostgreSQL | ACTIVE | Physical child of `deletion_request`; one minimized state per required owner, with completion accepted only from an authoritative producer. |
| `retention_execution_record` | identity-service | PostgreSQL | ACTIVE | Immutable owner-scoped retention attempt evidence, including failed, stale, and unavailable outcomes. |
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
| `support_plan` | care-service | PostgreSQL | ACTIVE | Physical owner/evaluation reference with explicit draft/current/terminal lifecycle; entitlement fields snapshot a logical/external Consultation decision. Proposal-v1 alone snapshots the cycle timezone, number, activation local date, accumulated paused local days and current pause date; explicit review-due continuation starts the next cycle. |
| `support_plan_focus` | care-service | PostgreSQL | ACTIVE | Physical ordered native-v3 child of a plan; stores bounded plain-language focus/purpose and version provenance, never a diagnosis or score. |
| `support_plan_action` | care-service | PostgreSQL | ACTIVE | Physical ordered native-v3 child of a focus; stores one canonical type, bounded presentation/provenance, capabilities, and logical source-owner/reference without a cross-service FK. |
| `support_plan_template_family` | care-service | PostgreSQL | ACTIVE | Physical ordered child of `support_plan`. |
| `support_plan_slot` | care-service | PostgreSQL | ACTIVE | Physical child of plan; exact resource/publication IDs are logical/external Content references. |
| `support_plan_slot_alternative` | care-service | PostgreSQL | ACTIVE | Physical child of slot with admitted exact Content alternatives. |
| `support_plan_request` | care-service | PostgreSQL | ACTIVE | Physical owner-scoped idempotency alias to plan. |
| `plan_change_request` | care-service | PostgreSQL | ACTIVE | MB-560 Care-owned review/decision snapshot for one exact Consultation resource proposal; links the reviewed current plan and, only after acceptance, the atomic replacement plan. |
| `support_plan_command` | care-service | PostgreSQL | ACTIVE | Physical owner/plan reference; idempotent activation outcome with exact revalidation evidence. |
| `support_plan_command_selection` | care-service | PostgreSQL | ACTIVE | Physical child preserving the ordered exact resource-version intent committed by activation. |
| `support_plan_activity_schedule` | care-service | PostgreSQL | ACTIVE | Additive shared physical child of a plan; references either legacy v2 slot/resource provenance or one native v3 action and snapshots recurrence, local time, and IANA timezone without backfilling old rows. |
| `support_plan_activity_occurrence` | care-service | PostgreSQL | ACTIVE | Physical child of a schedule and owner-matched plan; preserves either legacy v2 provenance or native action/source/evidence provenance with deterministic dated state and policy-bounded engagement. Native RESOURCE completion also accepts exact Content practice/completion references; the same owner fact revision can satisfy only one occurrence. |
| `reassessment_self_report` | care-service | PostgreSQL | ACTIVE | Versioned owner-authored current-period experience with bounded optional context; deletion clears response content and retains only a minimal tombstone/provenance. |
| `reassessment_summary` | care-service | PostgreSQL | ACTIVE | Immutable owner-scoped snapshot of separate screening, minimized journal/context, SupportPlan engagement, explicit self-reported experience, and separately labelled supporting activity reflection; never a combined score. |
| Specialist access grants/scopes | care-service | PostgreSQL | PROPOSED | Approved consent concept; no Care owner migration exists yet. Selected Journal IDs would be logical/external Journal/AI references. |
| Follow-up plan/check-in | care-service | PostgreSQL | PROPOSED | Broader clinical follow-up remains proposed; MB-513 SupportPlan wellbeing activity occurrences are the separate active aggregate above. |
| `support_classification` / `intervention_plan` | care-service | PostgreSQL | HISTORICAL | Superseded logical names; active persistence uses versioned SupportEvaluation, Support Guide, and SupportPlan aggregates. |

## Consultation — PostgreSQL

MB-384 adds ACTIVE `appointment_video_room`, physically one-to-one with
`appointment`, minimized `appointment_video_event` participation metadata, and
immutable `appointment_video_confirmation` business audit. Provider-only evidence
expires after 90 days absent disputes/valid holds; financial/confirmation history
remains. Timer end leaves HELD credit. Only explicit assigned Specialist intent,
trusted paired evidence and verified closure reuse the existing completion fact,
CONSUMED ledger and MB-516 earning invariants. Unverified 24-hour review cannot
settle or release credit. Historical chat policy remains unchanged.

| Entity | Owner service | Storage | Status | Key relationships |
| --- | --- | --- | --- | --- |
| `specialist_profile` | consultation-service | PostgreSQL | ACTIVE | `account_id` and reviewer IDs are logical/external Identity references. |
| `specialist_profile_approved_version` | consultation-service | PostgreSQL | ACTIVE | MB-635 append-only approved content snapshots; unique owner/published version; optional source amendment FK. |
| `specialist_profile_amendment` | consultation-service | PostgreSQL | ACTIVE | Private post-approval draft/review state with composite approved-base FK; one open amendment per specialist; never a public discovery source. |
| `specialist_profile_amendment_history` | consultation-service | PostgreSQL | ACTIVE | Append-only exact amendment revision/review payload and bounded actor/reason/time provenance. |
| `specialist_profile_support_area` | consultation-service | PostgreSQL | ACTIVE | Physical many-to-one child of `specialist_profile`. |
| `specialist_profile_language` | consultation-service | PostgreSQL | ACTIVE | Physical many-to-one child of `specialist_profile`. |
| `specialist_credential_document` | consultation-service | PostgreSQL | ACTIVE | Private credential metadata owned by one specialist profile; ADMIN verification and owner-controlled public badge visibility, with authenticated original storage outside PostgreSQL. |
| `specialist_profile_status_history` | consultation-service | PostgreSQL | ACTIVE | Physical history child; actor ID is a logical/external Identity reference. |
| `current_service_entitlement` | consultation-service | PostgreSQL | ACTIVE | Account and establishing actor are logical/external Identity references; current narrow package decision for consumers. |
| `availability_slot` | consultation-service | PostgreSQL | ACTIVE | Physical many-to-one to `specialist_profile`; active slots cannot overlap for one specialist. |
| `appointment_video_room` | consultation-service | PostgreSQL | ACTIVE | MB-384 physical one-to-one Appointment mapping with opaque provider/participant identifiers, closure/retry state and explicit retention hold; no media or token. |
| `appointment_video_event` | consultation-service | PostgreSQL | ACTIVE | Minimized signature-verified provider event linked to the exact room/Appointment; unique provider event identity, paired session evidence, 90-day retention except valid holds/disputes. |
| `appointment_video_confirmation` | consultation-service | PostgreSQL | ACTIVE | Immutable assigned-Specialist intent, source revision, policy/time and actor-scoped idempotency key; owner-matched Appointment reference; retained independently of provider metadata cleanup. |
| `service_credit_period`, `service_credit`, `service_credit_ledger` | consultation-service | PostgreSQL | ACTIVE | Current entitlement provisions bounded credits; appointment transitions hold and later release/consume one credit with append-only evidence. |
| `appointment`, `appointment_status_history`, `appointment_chat_evidence` | consultation-service | PostgreSQL | ACTIVE | MB-378 owns the REQUESTED snapshot, exact online slot and held credit; MB-379 settles assigned specialist acceptance/rejection and deterministic expiry with idempotent history; MB-360 records suspension cancellation outcomes; MB-558 adds package reservation caps and linked atomic replacement; MB-380 records owner cancellation/reschedule audit; MB-383 stores content-free check-in/presence/accepted-message evidence and deterministically settles chat outcome and credit under `chat-session-completion-v1`; MB-643 adds an optional opaque originating Care action reference for presentation correlation only. |
| `appointment_rating`, `specialist_rating_aggregate` | consultation-service | PostgreSQL | ACTIVE | MB-364 stores one current 1-5 rating per evidence-completed owned appointment and maintains the specialist count/sum aggregate in the same transaction. |
| `session_summary`, `agreed_next_step`, `agreed_next_step_state`, `session_summary_reuse_consent` | consultation-service | PostgreSQL | ACTIVE | MB-385 appends immutable user-visible summary versions after evidence-backed completion, keeps agreed steps separate from SupportPlan occurrences, lets only the user manage checklist state, and records separate exact-snapshot reuse consent. MB-560 adds a bounded reason code to exact-version `PLATFORM_RESOURCE` steps so Care can authoritatively retrieve proposal provenance. |
| `appointment_outbox_event` | consultation-service | PostgreSQL | ACTIVE | One minimized asynchronous status fact per appointment/version, atomically committed with appointment state and containing no clinical or message content. |
| Specialty catalogue/assignment | consultation-service | PostgreSQL | PROPOSED | Discovery policy exists, but no owner migration implements specialty persistence. |
| Subscription plan/version/entitlement | consultation-service | PostgreSQL | PROPOSED | ADR 0017 approves package semantics; exact VND prices/allocations and owner migrations remain gated. |
| User subscription/payment/IPN/upgrade | consultation-service | PostgreSQL | PROPOSED | Approved billing boundary without active migration; must not be inferred from `current_service_entitlement`. |
| Appointment completion evidence | consultation-service | PostgreSQL | ACTIVE | MB-383 records content-free check-in/presence/message evidence and only an accepted completion outcome may consume a credit. |
| `specialist_earning`, payout destination/request/attempt/item/status history, provider event | consultation-service | PostgreSQL | ACTIVE | MB-516 snapshots the consumed period's per-credit allocation and its 70% specialist share for each evidence-backed completion plus consumed credit. Local/CI use deterministic fake payout; MoMo outbound remains gated by explicit production approval and complete credentials. |
| Free-text specialist review/moderation | consultation-service | PostgreSQL | PROPOSED | MB-364 implements bounded rating only; comments, anonymity, deletion, and moderation remain unavailable. |

## Community — PostgreSQL

| Entity | Owner service | Storage | Status | Key relationships |
| --- | --- | --- | --- | --- |
| `community_profile` | community-service | PostgreSQL | ACTIVE | `account_subject` is a private logical/external Identity reference; public APIs expose only the Community profile ID, chosen display name, and optional governed avatar preset. |
| `community_post` | community-service | PostgreSQL | ACTIVE | Physical many-to-one to `community_profile`; optional logical `resource_id` reference to a Content-owned Resource; active reads are newest-first and fail closed for hidden, removed, or blocked content. |
| `community_post_topic` | community-service | PostgreSQL | ACTIVE | Physical many-to-one classification using only governed non-diagnostic Community topic codes. |
| `community_topic` | community-service | PostgreSQL | ACTIVE | Governed non-diagnostic topic catalogue whose active state controls discovery and new classification without deleting historical post membership. |
| `community_media` | community-service | PostgreSQL/object storage | ACTIVE | Physical owner/post references hold safe metadata only; binary content remains in object storage and only `READY` delivery metadata is public. |
| `community_block` | community-service | PostgreSQL | ACTIVE | Physical profile-to-profile visibility rule applied symmetrically to feed and detail reads. |
| `community_comment` | community-service | PostgreSQL | ACTIVE | Physical post, optional same-post root parent, and author profile references implement chronological comments and one-level replies with owner-scoped command replay. |
| `community_comment_revision` | community-service | PostgreSQL | ACTIVE | Physical comment and acting-profile references retain immutable create/edit/delete snapshots for moderation-compatible audit. |
| `community_comment_reaction` | community-service | PostgreSQL | ACTIVE | One supportive reaction per profile/comment; local foreign keys and first-action outbox identity prevent concurrent duplicates and repeat notifications. |
| Reactions, bookmarks, reports, moderation cases/actions, and their command replay | community-service | PostgreSQL | ACTIVE | Community Liquibase changes `0009` and `0010` own reports/moderation, one supportive reaction per profile/post, and private bookmarks. |

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
| `resource_daily_progress` / `resource_practice_session` | content-notification-service | PostgreSQL | ACTIVE | Owner-scoped exact-version Content facts; server completion/start instants determine their local date for Care reconciliation and action-bound Bingo. Learning completion is a separate fact and is not reconciliation evidence. |
| `resource_weekly_bingo` | content-notification-service | PostgreSQL | ACTIVE | Board unique per external owner, local week and external Care SupportPlan; separate sync time selects the latest board without changing its ETag on an identical sync. |
| `resource_weekly_bingo_item` | content-notification-service | PostgreSQL | ACTIVE | Physical ordered child of a board and exact publication snapshot; nullable external Care action/version identifies legacy rows, retirement preserves history and capacity, and self-confirmation remains independent of computed Content evidence. |
| `resource_reward_preference` | content-notification-service | PostgreSQL | ACTIVE | Owner-scoped optional visibility only; authoritative XP is a deterministic read model over exact progress, practice, and publication evidence. |
| `achievement_preference` | content-notification-service | PostgreSQL | ACTIVE | Owner-scoped private-gallery visibility only; achievement status is a deterministic read model over minimized source-owner facts. |
| `notification_preference` | content-notification-service | PostgreSQL | ACTIVE | One role-scoped atomic aggregate per external Identity owner covering channels, allowed content groups, quiet hours/timezone, and email cadence/opt-ins; specialist scope cannot enable user wellbeing categories. |
| `notification` | content-notification-service | PostgreSQL | ACTIVE | Recipient ID is a logical/external Identity reference. Content owns durable delivery state and deduplicates producer facts by recipient, source, and versioned logical identity; MB-565 adds bounded assessment-result, SupportGuide, and USER account-status categories plus exact internal SupportGuide actions. |
| `community_notification_job` | content-notification-service | PostgreSQL | ACTIVE | Durable minimized Community notice work/receipts with recipient/source/action uniqueness, leased worker claims, quiet-hour/retry scheduling and an optional physical notification reference for atomic unread reaction grouping. |
| `appointment_email_reminder` | content-notification-service | PostgreSQL | ACTIVE | One delivery-state row per external recipient, Consultation appointment, and appointment version; no email address or message body is persisted. |
| `push_device_registration` | content-notification-service | PostgreSQL | ACTIVE | Encrypted Expo registration with one active owner per installation/token, natural PUT idempotence, permission, rotation, revocation, and invalidation lifecycle. |
| `push_delivery_attempt` | content-notification-service | PostgreSQL | ACTIVE | Deduplicated bounded provider-submission and receipt evidence per authoritative notification/device; neither ticket acceptance nor a successful receipt claims physical display. |
| `push_device_command_rate_limit` | content-notification-service | PostgreSQL | ACTIVE | Owner-keyed fixed-window protection for authenticated registration/revocation commands; stores no device token or notification content. |
| `appointment_reminder_checkpoint` | content-notification-service | PostgreSQL | ACTIVE | Latest consumed version/status per external Consultation appointment for stale and duplicate event rejection. |
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
| Longitudinal Analysis Job/Result | journal-ai-service | MongoDB | ACTIVE | Owner-scoped exact-source comparison with immutable result provenance. |
| AI Companion Conversation | journal-ai-service | MongoDB `ai_companion_conversations` | ACTIVE | Owner-scoped aggregate embedding bounded encrypted user/assistant messages; context kinds only. |
| AI Companion Command | journal-ai-service | MongoDB `ai_companion_commands` | ACTIVE | Owner/key-scoped immutable replay snapshot with a logical conversation reference. |
| AI Companion Daily Quota | journal-ai-service | MongoDB `ai_companion_quota_ledgers` | ACTIVE | Owner/local-day ledger derived from server timezone and current Consultation entitlement. |
| AI Companion Rate Limit | journal-ai-service | MongoDB `ai_companion_rate_ledgers` | ACTIVE | Short-lived owner/window counter with TTL; contains no content. |
| AI Companion Voice Session | journal-ai-service | MongoDB `ai_voice_sessions` | ACTIVE | MB-756 migration 013; keyed owner replay identity, frozen admission policy/entitlement provenance, fixed authorized quota day/deadline, fenced authority and content-free usage settlement. Metadata is swept 30 days from creation with held-slot reference scrubbing; media/session/worker/turn APIs remain separate scope. |
| AI Companion Voice Daily Usage | journal-ai-service | MongoDB `ai_voice_daily_usage` | ACTIVE | Unique owner/Vietnam day; independent used/reserved voice sessions and seconds plus bounded HMAC pending attempts lasting ten seconds. Unique owner/pending-key claims prevent owner-call fanout across midnight; expiry fences reject stale admission/cleanup. Failed cleanup preserves attempt counts; accepted replay is exempt. Metadata TTL never releases capacity. |
| AI Companion Voice Capacity | journal-ai-service | MongoDB `ai_voice_capacity` | ACTIVE | One bounded two-slot singleton enforces global two and owner one across processes. First teardown proof must be newer than current fenced progress. No TTL; uncertain teardown retains a slot, then creation-bound privacy cleanup scrubs owner/session references into anonymous quarantine pending provider-verified isolation. |
| AI Companion Voice Media | journal-ai-service | MongoDB `ai_voice_media` | ACTIVE | MB-757 migration 014; opaque AI room/dispatch mapping, recoverable side-effect phase and worker/control/publication fences. No content or participant-token replay; original session creation bounds retention, and deletion never frees uncertain capacity. |
| AI Companion Voice Media Command | journal-ai-service | MongoDB `ai_voice_media_commands` | ACTIVE | Keyed owner/operation/command binding to one Voice Session for connection/end. No raw key or media credential; expiry is capped by the original session metadata deadline. |

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

## MB-685 active Community chat entities

| Entity | Owner | Storage | Status | Invariant |
| --- | --- | --- | --- | --- |
| `community_chat_privacy` | community-service | PostgreSQL | ACTIVE | Owner migration 0017; exact fields in the Community data dictionary; no cross-owner foreign keys. |
| `community_chat_conversation` | community-service | PostgreSQL | ACTIVE | Owner migration 0017; exact fields in the Community data dictionary; no cross-owner foreign keys. |
| `community_chat_request` | community-service | PostgreSQL | ACTIVE | Owner migration 0017; exact fields in the Community data dictionary; no cross-owner foreign keys. |
| `community_chat_command` | community-service | PostgreSQL | ACTIVE | Owner migration 0017; exact fields in the Community data dictionary; no cross-owner foreign keys. |
| `community_chat_outbox` | community-service | PostgreSQL | ACTIVE | Owner migration 0017; exact fields in the Community data dictionary; no cross-owner foreign keys. |

## MB-685 message and read authorities

| Entity | Owner | Storage | Status | Invariant |
| --- | --- | --- | --- | --- |
| `community_chat_message` | community-service | PostgreSQL | ACTIVE | Migrations 0018/0019/0022; encrypted active text or sender-retracted content-free tombstone, unique sender/conversation/client identity, immutable GROUP author membership epoch and private sequence; 90-day content expiry and no retry resurrection. |
| `community_chat_read_state` | community-service | PostgreSQL | ACTIVE | Migration 0018; one private monotonic read cursor per current reader/conversation; no shared-receipt opt-in implied. |

### MB-687 private group extension — ACTIVE

- `community_chat_conversation`: DIRECT ordered pair or GROUP private encrypted name/cap; GROUP ACTIVE/ARCHIVED.
- `community_chat_membership`: current role and immutable join message floor; leave/rejoin uses a new epoch. Deferred commit constraints protect one OWNER for active groups and configured cap.
- `community_chat_invitation`: exact inviter epoch/invitee, seven-day decision lifecycle, accepted epoch link and permanent decline suppression.
- `community_chat_command`: additive group-create/invite retry identities; no raw name or invitation text.

All physical relationships remain inside Community PostgreSQL. Migration 0019 is runtime truth; logical SQL is never executed.

- MB-687 `community_chat_report_case` — ACTIVE: exact actor/message OPEN intake, encrypted isolated message/details snapshot, safe reason/priority and bounded unresolved-review deadline. No ADMIN transcript access or resolution workflow is activated. Owner migration 0020; REPORT_MESSAGE command identities reuse the Community command table.
