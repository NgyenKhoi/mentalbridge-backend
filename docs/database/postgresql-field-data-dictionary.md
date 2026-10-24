# PostgreSQL Field Data Dictionary

This document explains the business purpose of PostgreSQL fields. The [canonical PostgreSQL logical schema](../domain-model/relational/postgresql-logical-schema.sql) is a non-executable documentation model and must never provision or migrate an environment. Names such as `consultation.availability_slot` identify a visual owner namespace; the physical table is `public.availability_slot` in the separate `mentalbridge_consultation` database. Service-owned migration histories are the executable runtime sources of truth: Liquibase for Spring services and the current SQL migration mechanism for Node.js services. This dictionary is written for developers and reviewers; descriptions are intentionally kept out of executable migrations.

When service-owned migrations are introduced, update this dictionary and the canonical logical model in the same change whenever persisted domain structure materially changes. A field description must explain why the value is persisted, whether it is authoritative, derived, external, or sensitive, and how nullability, time, versioning, or idempotency affects behavior. Content/Notification's executable history starts at `content-notification-service/migrations/1_initial_schema.sql`; migration 2 removes the obsolete hotline table, the separate Review 1 migrations insert controlled demo content and its initial item-level eligibility matrix, migration 6 adds immutable Resource Eligibility v1 provenance, migration 7 adds the separately governed reviewed safety directory, migration 8 adds its deterministic reviewed area vocabulary, and migration 14 adds semantic resource metadata plus durable learning, practice, daily-assignment, and weekly-bingo state. The field descriptions under its conceptual owner below describe the resulting physical `public` tables.

In the original introduction above, "this dictionary" means the collection of owner files below. Update the matching owner file when a migration changes persisted domain data; this page remains the stable entrypoint and preserves existing section links. Find each owner's SQL and executable migration history in the [relational model index](../domain-model/relational/README.md).

| Owner | Field definitions |
| --- | --- |
| Identity | [identity-service](postgresql/identity-service.md) |
| Care | [care-service](postgresql/care-service.md) |
| Consultation | [consultation-service](postgresql/consultation-service.md) |
| Community | [community-service](postgresql/community-service.md) |
| Content/Notification | [content-notification-service](postgresql/content-notification-service.md) |
| Conceptual AI / shared platform | [Shared and planned concepts](postgresql/shared-concepts.md) |

The legacy headings below route directly to the corresponding owner sections. Repeated original headings remain distinct, and Care-owned ConsultationBrief tables route to Care. This heading list is frozen for compatibility: add or change future tables and fields only in the owner file, without expanding this shared index.

## Database `mentalbridge_community` (schema `public`)

[Open field definitions](postgresql/community-service.md#database-mentalbridge_community-schema-public).

### `public.community_profile`

[Open field definitions](postgresql/community-service.md#publiccommunity_profile).

### `public.community_post`

[Open field definitions](postgresql/community-service.md#publiccommunity_post).

### `public.community_post_topic`

[Open field definitions](postgresql/community-service.md#publiccommunity_post_topic).

### `public.community_topic`

[Open field definitions](postgresql/community-service.md#publiccommunity_topic).

### `public.community_comment_reaction`

[Open field definitions](postgresql/community-service.md#publiccommunity_comment_reaction).

### `public.community_post_reaction`

[Open field definitions](postgresql/community-service.md#publiccommunity_post_reaction).

### `public.community_post_bookmark`

[Open field definitions](postgresql/community-service.md#publiccommunity_post_bookmark).

### `public.community_interaction_outbox`

[Open field definitions](postgresql/community-service.md#publiccommunity_interaction_outbox).

### `public.community_media`

[Open field definitions](postgresql/community-service.md#publiccommunity_media).

### `public.community_block`

[Open field definitions](postgresql/community-service.md#publiccommunity_block).

### `public.community_comment`

[Open field definitions](postgresql/community-service.md#publiccommunity_comment).

### `public.community_comment_revision`

[Open field definitions](postgresql/community-service.md#publiccommunity_comment_revision).

### `public.community_report`

[Open field definitions](postgresql/community-service.md#publiccommunity_report).

### `public.community_content_hide`

[Open field definitions](postgresql/community-service.md#publiccommunity_content_hide).

### `public.community_moderation_case`

[Open field definitions](postgresql/community-service.md#publiccommunity_moderation_case).

### `public.community_moderation_action`

[Open field definitions](postgresql/community-service.md#publiccommunity_moderation_action).

### `public.community_access_restriction`

[Open field definitions](postgresql/community-service.md#publiccommunity_access_restriction).

## Database `mentalbridge_identity` (schema `public`)

[Open field definitions](postgresql/identity-service.md#database-mentalbridge_identity-schema-public).

### `public.account`

[Open field definitions](postgresql/identity-service.md#publicaccount).

### `public.role`

[Open field definitions](postgresql/identity-service.md#publicrole).

### `public.refresh_session`

[Open field definitions](postgresql/identity-service.md#publicrefresh_session).

### `public.one_time_token`

[Open field definitions](postgresql/identity-service.md#publicone_time_token).

### `public.idempotency_record`

[Open field definitions](postgresql/identity-service.md#publicidempotency_record).

### `public.outbox_event`

[Open field definitions](postgresql/identity-service.md#publicoutbox_event).

### `public.platform_report_schedule`

[Open field definitions](postgresql/identity-service.md#publicplatform_report_schedule).

### `public.platform_report_job`

[Open field definitions](postgresql/identity-service.md#publicplatform_report_job).

### `public.platform_report_artifact`

[Open field definitions](postgresql/identity-service.md#publicplatform_report_artifact).

### `public.security_audit_event`

[Open field definitions](postgresql/identity-service.md#publicsecurity_audit_event).

## Owner `care` (`mentalbridge_care.public`)

[Open field definitions](postgresql/care-service.md#owner-care-mentalbridge_carepublic).

### `public.user_profile`

[Open field definitions](postgresql/care-service.md#publicuser_profile).

### `public.consent_decision`

[Open field definitions](postgresql/care-service.md#publicconsent_decision).

### `public.anonymous_assessment_session`

[Open field definitions](postgresql/care-service.md#publicanonymous_assessment_session).

### `public.questionnaire_definition`

[Open field definitions](postgresql/care-service.md#publicquestionnaire_definition).

### `public.questionnaire_question`

[Open field definitions](postgresql/care-service.md#publicquestionnaire_question).

### `public.questionnaire_score_band`

[Open field definitions](postgresql/care-service.md#publicquestionnaire_score_band).

### `public.assessment_submission`

[Open field definitions](postgresql/care-service.md#publicassessment_submission).

### `public.assessment_answer`

[Open field definitions](postgresql/care-service.md#publicassessment_answer).

### `public.assessment_result`

[Open field definitions](postgresql/care-service.md#publicassessment_result).

### `public.outbox_event`

[Open field definitions](postgresql/care-service.md#publicoutbox_event).

### `public.support_policy_definition`

[Open field definitions](postgresql/care-service.md#publicsupport_policy_definition).

### `public.support_policy_eligible_definition`

[Open field definitions](postgresql/care-service.md#publicsupport_policy_eligible_definition).

### `public.screening_band_meaning`

[Open field definitions](postgresql/care-service.md#publicscreening_band_meaning).

### `public.support_tier_guidance`

[Open field definitions](postgresql/care-service.md#publicsupport_tier_guidance).

### `public.support_evaluation`

[Open field definitions](postgresql/care-service.md#publicsupport_evaluation).

### `public.support_evaluation_request`

[Open field definitions](postgresql/care-service.md#publicsupport_evaluation_request).

### `public.support_evaluation_v2_policy_definition`

[Open field definitions](postgresql/care-service.md#publicsupport_evaluation_v2_policy_definition).

### `public.support_evaluation_v2_eligible_definition`

[Open field definitions](postgresql/care-service.md#publicsupport_evaluation_v2_eligible_definition).

### `public.support_evaluation_v2`

[Open field definitions](postgresql/care-service.md#publicsupport_evaluation_v2).

### `public.support_evaluation_v2_domain`

[Open field definitions](postgresql/care-service.md#publicsupport_evaluation_v2_domain).

### `public.support_evaluation_v2_safety`

[Open field definitions](postgresql/care-service.md#publicsupport_evaluation_v2_safety).

### `public.support_evaluation_v2_request`

[Open field definitions](postgresql/care-service.md#publicsupport_evaluation_v2_request).

### `public.screening_episode`

[Open field definitions](postgresql/care-service.md#publicscreening_episode).

### `public.support_guide`

[Open field definitions](postgresql/care-service.md#publicsupport_guide).

### `public.support_guide_resource`

[Open field definitions](postgresql/care-service.md#publicsupport_guide_resource).

### `public.support_guide_request`

[Open field definitions](postgresql/care-service.md#publicsupport_guide_request).

### `public.support_plan_personalization`

[Open field definitions](postgresql/care-service.md#publicsupport_plan_personalization).

### `public.support_plan_personalization_activity_mode`

[Open field definitions](postgresql/care-service.md#publicsupport_plan_personalization_activity_mode).

### `public.support_plan_personalization_barrier`

[Open field definitions](postgresql/care-service.md#publicsupport_plan_personalization_barrier).

### `public.support_plan`

[Open field definitions](postgresql/care-service.md#publicsupport_plan).

### `public.support_plan_focus`

[Open field definitions](postgresql/care-service.md#publicsupport_plan_focus).

### `public.support_plan_action`

[Open field definitions](postgresql/care-service.md#publicsupport_plan_action).

### `public.support_plan_template_family`

[Open field definitions](postgresql/care-service.md#publicsupport_plan_template_family).

### `public.support_plan_slot`

[Open field definitions](postgresql/care-service.md#publicsupport_plan_slot).

### `public.support_plan_slot_alternative`

[Open field definitions](postgresql/care-service.md#publicsupport_plan_slot_alternative).

### `public.support_plan_request`

[Open field definitions](postgresql/care-service.md#publicsupport_plan_request).

### `public.plan_change_request`

[Open field definitions](postgresql/care-service.md#publicplan_change_request).

### `public.support_plan_command`

[Open field definitions](postgresql/care-service.md#publicsupport_plan_command).

### `public.support_plan_command_selection`

[Open field definitions](postgresql/care-service.md#publicsupport_plan_command_selection).

### `public.support_plan_activity_schedule`

[Open field definitions](postgresql/care-service.md#publicsupport_plan_activity_schedule).

### `public.support_plan_schedule_command`

[Open field definitions](postgresql/care-service.md#publicsupport_plan_schedule_command).

### `public.support_plan_activity_occurrence`

[Open field definitions](postgresql/care-service.md#publicsupport_plan_activity_occurrence).

### `public.reassessment_self_report`

[Open field definitions](postgresql/care-service.md#publicreassessment_self_report).

### `public.reassessment_summary`

[Open field definitions](postgresql/care-service.md#publicreassessment_summary).

### `care.intervention_plan`

[Open field definitions](postgresql/care-service.md#careintervention_plan).

## Conceptual owner `consultation` (`mentalbridge_consultation.public`)

[Open field definitions](postgresql/consultation-service.md#conceptual-owner-consultation-mentalbridge_consultationpublic).

### `consultation.specialist_profile`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_profile).

### `consultation.specialist_profile_support_area`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_profile_support_area).

### `consultation.specialist_profile_language`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_profile_language).

### `consultation.specialist_credential_document`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_credential_document).

### `consultation.specialist_profile_status_history`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_profile_status_history).

### `consultation.specialist_profile_approved_version`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_profile_approved_version).

### `consultation.specialist_profile_amendment`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_profile_amendment).

### `consultation.specialist_profile_amendment_history`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_profile_amendment_history).

### `consultation.subscription_plan`

[Open field definitions](postgresql/consultation-service.md#consultationsubscription_plan).

### `consultation.current_service_entitlement`

[Open field definitions](postgresql/consultation-service.md#consultationcurrent_service_entitlement).

### `consultation.service_credit_period`

[Open field definitions](postgresql/consultation-service.md#consultationservice_credit_period).

### `consultation.service_credit`

[Open field definitions](postgresql/consultation-service.md#consultationservice_credit).

### `consultation.service_credit_ledger`

[Open field definitions](postgresql/consultation-service.md#consultationservice_credit_ledger).

### `consultation.appointment`

[Open field definitions](postgresql/consultation-service.md#consultationappointment).

### `consultation.appointment_status_history`

[Open field definitions](postgresql/consultation-service.md#consultationappointment_status_history).

### `consultation.appointment_video_room`

[Open field definitions](postgresql/consultation-service.md#consultationappointment_video_room).

### `consultation.appointment_video_confirmation`

[Open field definitions](postgresql/consultation-service.md#consultationappointment_video_confirmation).

### `consultation.appointment_video_event`

[Open field definitions](postgresql/consultation-service.md#consultationappointment_video_event).

### `consultation.appointment_chat_evidence`

[Open field definitions](postgresql/consultation-service.md#consultationappointment_chat_evidence).

### `consultation.appointment_dispute`

[Open field definitions](postgresql/consultation-service.md#consultationappointment_dispute).

### `consultation.specialist_client_continuity_audit`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_client_continuity_audit).

### `consultation.session_summary`

[Open field definitions](postgresql/consultation-service.md#consultationsession_summary).

### `consultation.agreed_next_step`

[Open field definitions](postgresql/consultation-service.md#consultationagreed_next_step).

### `consultation.agreed_next_step_state`

[Open field definitions](postgresql/consultation-service.md#consultationagreed_next_step_state).

### `consultation.session_summary_reuse_consent`

[Open field definitions](postgresql/consultation-service.md#consultationsession_summary_reuse_consent).

### `consultation.appointment_outbox_event`

[Open field definitions](postgresql/consultation-service.md#consultationappointment_outbox_event).

### `consultation.consultation_admin_audit_outbox`

[Open field definitions](postgresql/consultation-service.md#consultationconsultation_admin_audit_outbox).

### `consultation.subscription_plan_version`

[Open field definitions](postgresql/consultation-service.md#consultationsubscription_plan_version).

### `consultation.subscription_plan_entitlement`

[Open field definitions](postgresql/consultation-service.md#consultationsubscription_plan_entitlement).

### `consultation.user_subscription`

[Open field definitions](postgresql/consultation-service.md#consultationuser_subscription).

### `consultation.subscription_status_history`

[Open field definitions](postgresql/consultation-service.md#consultationsubscription_status_history).

### `consultation.payment_transaction`

[Open field definitions](postgresql/consultation-service.md#consultationpayment_transaction).

### `consultation.momo_payment_ipn`

[Open field definitions](postgresql/consultation-service.md#consultationmomo_payment_ipn).

### `consultation.subscription_upgrade`

[Open field definitions](postgresql/consultation-service.md#consultationsubscription_upgrade).

### `consultation.subscription_upgrade_credit`

[Open field definitions](postgresql/consultation-service.md#consultationsubscription_upgrade_credit).

## Conceptual owner `care` (`mentalbridge_care.public`)

[Open field definitions](postgresql/care-service.md#conceptual-owner-care-mentalbridge_carepublic).

### `care.specialist_access_grant`

[Open field definitions](postgresql/care-service.md#carespecialist_access_grant).

### `care.specialist_access_scope`

[Open field definitions](postgresql/care-service.md#carespecialist_access_scope).

### `care.specialist_journal_access`

[Open field definitions](postgresql/care-service.md#carespecialist_journal_access).

## Conceptual owner `consultation` (`mentalbridge_consultation.public`)

[Open field definitions](postgresql/consultation-service.md#conceptual-owner-consultation-mentalbridge_consultationpublic-1).

### `consultation.availability_slot`

[Open field definitions](postgresql/consultation-service.md#consultationavailability_slot).

### `consultation.specialist_earning`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_earning).

### `consultation.specialist_payout_destination`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_payout_destination).

### `consultation.specialist_payout`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_payout).

### `consultation.specialist_payout_attempt`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_payout_attempt).

### `consultation.specialist_payout_item`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_payout_item).

### `consultation.specialist_payout_status_history`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_payout_status_history).

### `consultation.payout_provider_event`

[Open field definitions](postgresql/consultation-service.md#consultationpayout_provider_event).

### `consultation.appointment_rating`

[Open field definitions](postgresql/consultation-service.md#consultationappointment_rating).

### `consultation.specialist_rating_aggregate`

[Open field definitions](postgresql/consultation-service.md#consultationspecialist_rating_aggregate).

## Conceptual owner `care` (`mentalbridge_care.public`)

[Open field definitions](postgresql/care-service.md#conceptual-owner-care-mentalbridge_carepublic-1).

### `care.follow_up_plan`

[Open field definitions](postgresql/care-service.md#carefollow_up_plan).

### `care.follow_up_check_in`

[Open field definitions](postgresql/care-service.md#carefollow_up_check_in).

## Conceptual owner `content` (`mentalbridge_content_notification.public`)

[Open field definitions](postgresql/content-notification-service.md#conceptual-owner-content-mentalbridge_content_notificationpublic).

### `content.resource`

[Open field definitions](postgresql/content-notification-service.md#contentresource).

### `content.resource_daily_progress`

[Open field definitions](postgresql/content-notification-service.md#contentresource_daily_progress).

### `content.resource_learning_completion`

[Open field definitions](postgresql/content-notification-service.md#contentresource_learning_completion).

### `content.resource_practice_session`

[Open field definitions](postgresql/content-notification-service.md#contentresource_practice_session).

### `content.resource_reward_preference`

[Open field definitions](postgresql/content-notification-service.md#contentresource_reward_preference).

### `content.achievement_preference`

[Open field definitions](postgresql/content-notification-service.md#contentachievement_preference).

### `content.resource_bookmark`

[Open field definitions](postgresql/content-notification-service.md#contentresource_bookmark).

### `content.resource_daily_assignment` and `content.resource_daily_assignment_item`

[Open field definitions](postgresql/content-notification-service.md#contentresource_daily_assignment-and-contentresource_daily_assignment_item).

### `content.resource_weekly_bingo` and `content.resource_weekly_bingo_item`

[Open field definitions](postgresql/content-notification-service.md#contentresource_weekly_bingo-and-contentresource_weekly_bingo_item).

### `content.notification_preference`

[Open field definitions](postgresql/content-notification-service.md#contentnotification_preference).

### `content.wellbeing_email_delivery`

[Open field definitions](postgresql/content-notification-service.md#contentwellbeing_email_delivery).

### `content.notification`

[Open field definitions](postgresql/content-notification-service.md#contentnotification).

### `content.community_notification_job`

[Open field definitions](postgresql/content-notification-service.md#contentcommunity_notification_job).

### `content.appointment_email_reminder`

[Open field definitions](postgresql/content-notification-service.md#contentappointment_email_reminder).

### `content.appointment_reminder_checkpoint`

[Open field definitions](postgresql/content-notification-service.md#contentappointment_reminder_checkpoint).

## Conceptual owner `ai` (owner-local `public` tables)

[Open field definitions](postgresql/shared-concepts.md#conceptual-owner-ai-owner-local-public-tables).

### `ai.analysis_job`

[Open field definitions](postgresql/shared-concepts.md#aianalysis_job).

### `ai.evaluation_dataset`

[Open field definitions](postgresql/shared-concepts.md#aievaluation_dataset).

### `ai.benchmark_run`

[Open field definitions](postgresql/shared-concepts.md#aibenchmark_run).

## Conceptual owner `platform` (owner-local `public` tables)

[Open field definitions](postgresql/shared-concepts.md#conceptual-owner-platform-owner-local-public-tables).

### `platform.outbox_event`

[Open field definitions](postgresql/shared-concepts.md#platformoutbox_event).

### `platform.audit_event`

[Open field definitions](postgresql/shared-concepts.md#platformaudit_event).

### `platform.moderation_case`

[Open field definitions](postgresql/shared-concepts.md#platformmoderation_case).

### `platform.retention_policy`

[Open field definitions](postgresql/shared-concepts.md#platformretention_policy).

### `platform.deletion_request`

[Open field definitions](postgresql/shared-concepts.md#platformdeletion_request).

### `platform.deletion_task`

[Open field definitions](postgresql/shared-concepts.md#platformdeletion_task).

## Owner `content-notification` (`mentalbridge_content_notification.public`)

[Open field definitions](postgresql/content-notification-service.md#owner-content-notification-mentalbridge_content_notificationpublic).

### `public.resource`

[Open field definitions](postgresql/content-notification-service.md#publicresource).

### `public.resource_review_decision`

[Open field definitions](postgresql/content-notification-service.md#publicresource_review_decision).

### `public.resource_publication_snapshot`

[Open field definitions](postgresql/content-notification-service.md#publicresource_publication_snapshot).

### `public.resource_publication_withdrawal`

[Open field definitions](postgresql/content-notification-service.md#publicresource_publication_withdrawal).

### `public.safety_directory_entry`

[Open field definitions](postgresql/content-notification-service.md#publicsafety_directory_entry).

### `public.safety_directory_coverage`

[Open field definitions](postgresql/content-notification-service.md#publicsafety_directory_coverage).

### `public.safety_directory_review_history`

[Open field definitions](postgresql/content-notification-service.md#publicsafety_directory_review_history).

### `public.safety_directory_command_record`

[Open field definitions](postgresql/content-notification-service.md#publicsafety_directory_command_record).

### `public.safety_directory_area_alias`

[Open field definitions](postgresql/content-notification-service.md#publicsafety_directory_area_alias).

### `public.content_admin_audit_outbox`

[Open field definitions](postgresql/content-notification-service.md#publiccontent_admin_audit_outbox).

### `public.consultation_brief`

[Open field definitions](postgresql/care-service.md#publicconsultation_brief).

### `public.consultation_brief_snapshot`

[Open field definitions](postgresql/care-service.md#publicconsultation_brief_snapshot).

### `public.consultation_brief_grant`

[Open field definitions](postgresql/care-service.md#publicconsultation_brief_grant).

### `public.consultation_brief_audit`

[Open field definitions](postgresql/care-service.md#publicconsultation_brief_audit).

### `public.consultation_brief_ai_draft_job`

[Open field definitions](postgresql/care-service.md#publicconsultation_brief_ai_draft_job).

### `public.resource_idempotency_record`

[Open field definitions](postgresql/content-notification-service.md#publicresource_idempotency_record).

### `public.resource_audit_event`

[Open field definitions](postgresql/content-notification-service.md#publicresource_audit_event).

### `public.resource_eligibility_publication`

[Open field definitions](postgresql/content-notification-service.md#publicresource_eligibility_publication).

### `public.resource_eligibility_declaration`

[Open field definitions](postgresql/content-notification-service.md#publicresource_eligibility_declaration).

### `public.resource_eligibility_withdrawal`

[Open field definitions](postgresql/content-notification-service.md#publicresource_eligibility_withdrawal).

### `public.resource_eligibility_command_record`

[Open field definitions](postgresql/content-notification-service.md#publicresource_eligibility_command_record).

## MB-685 direct Community chat — public tables

[Open field definitions](postgresql/community-service.md#mb-685-direct-community-chat--public-tables).

### `public.community_chat_privacy`

[Open field definitions](postgresql/community-service.md#publiccommunity_chat_privacy).

### `public.community_chat_conversation`

[Open field definitions](postgresql/community-service.md#publiccommunity_chat_conversation).

### `public.community_chat_request`

[Open field definitions](postgresql/community-service.md#publiccommunity_chat_request).

### `public.community_chat_command`

[Open field definitions](postgresql/community-service.md#publiccommunity_chat_command).

### `public.community_chat_outbox`

[Open field definitions](postgresql/community-service.md#publiccommunity_chat_outbox).

### `public.community_chat_message`

[Open field definitions](postgresql/community-service.md#publiccommunity_chat_message).

### `public.community_chat_read_state`

[Open field definitions](postgresql/community-service.md#publiccommunity_chat_read_state).

## MB-687 private groups (Community, migration 0019)

[Open field definitions](postgresql/community-service.md#mb-687-private-groups-community-migration-0019).

### `public.community_chat_conversation` — group extension

[Open field definitions](postgresql/community-service.md#publiccommunity_chat_conversation--group-extension).

### `public.community_chat_membership`

[Open field definitions](postgresql/community-service.md#publiccommunity_chat_membership).

### `public.community_chat_invitation`

[Open field definitions](postgresql/community-service.md#publiccommunity_chat_invitation).

### `public.community_chat_report_case` (MB-687, migration 0020)

[Open field definitions](postgresql/community-service.md#publiccommunity_chat_report_case-mb-687-migration-0020).
