--liquibase formatted sql

--changeset mentalbridge:care-024-plan-change-request runInTransaction:true
create table plan_change_request (
    id uuid primary key,
    user_id uuid not null references user_profile(account_id) on delete restrict,
    specialist_id uuid not null,
    source_proposal_id uuid not null,
    source_proposal_version bigint not null,
    source_appointment_id uuid not null,
    source_summary_id uuid not null,
    source_summary_version bigint not null,
    source_completion_fact_id uuid not null,
    proposal_reason_code varchar(48) not null,
    resource_id uuid not null,
    resource_version bigint not null,
    proposal_title varchar(160) not null,
    proposal_details varchar(500),
    current_support_plan_id uuid not null,
    current_support_plan_version bigint not null,
    replacement_support_plan_id uuid,
    replacement_support_plan_version bigint,
    target_slot_id varchar(64) not null,
    current_resource_id uuid,
    current_resource_version bigint,
    current_resource_title varchar(255),
    status varchar(32) not null,
    outcome_code varchar(64) not null,
    idempotency_key varchar(128) not null,
    request_hash varchar(64) not null,
    decision_idempotency_key varchar(128),
    decision_hash varchar(64),
    version bigint not null default 0,
    reviewed_at timestamptz not null,
    decided_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ux_plan_change_request_proposal unique (source_proposal_id),
    constraint ux_plan_change_request_command unique (user_id, idempotency_key),
    constraint ux_plan_change_request_decision unique (user_id, decision_idempotency_key),
    constraint fk_plan_change_request_current_owner
        foreign key (current_support_plan_id, user_id)
        references support_plan(id, user_id) on delete restrict,
    constraint fk_plan_change_request_replacement_owner
        foreign key (replacement_support_plan_id, user_id)
        references support_plan(id, user_id) on delete restrict,
    constraint ck_plan_change_request_versions check (
        source_proposal_version > 0 and source_summary_version > 0
        and resource_version >= 0 and current_support_plan_version >= 0
        and (replacement_support_plan_version is null or replacement_support_plan_version >= 0)
        and (current_resource_version is null or current_resource_version >= 0) and version >= 0
    ),
    constraint ck_plan_change_request_reason check (proposal_reason_code in (
        'POST_CONSULTATION_CONTINUITY','TRY_ALTERNATIVE_RESOURCE','ADDRESS_REPORTED_BARRIER'
    )),
    constraint ck_plan_change_request_status check (status in ('READY_FOR_REVIEW','ACCEPTED','REJECTED')),
    constraint ck_plan_change_request_outcome check (outcome_code in (
        'PROPOSAL_ADMISSIBLE','PROPOSAL_APPLIED','USER_REJECTED'
    )),
    constraint ck_plan_change_request_hash check (request_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_plan_change_request_decision check (
        (status='READY_FOR_REVIEW' and replacement_support_plan_id is null
            and replacement_support_plan_version is null and decision_idempotency_key is null
            and decision_hash is null and decided_at is null)
        or (status='ACCEPTED' and replacement_support_plan_id is not null
            and replacement_support_plan_version is not null and decision_idempotency_key is not null
            and decision_hash ~ '^[0-9a-f]{64}$' and decided_at is not null)
        or (status='REJECTED' and replacement_support_plan_id is null
            and replacement_support_plan_version is null and decision_idempotency_key is not null
            and decision_hash ~ '^[0-9a-f]{64}$' and decided_at is not null)
    )
);

create index ix_plan_change_request_user
    on plan_change_request (user_id, created_at desc, id desc);

create index ix_plan_change_request_specialist
    on plan_change_request (specialist_id, created_at desc, id desc);

alter table support_plan_command
    drop constraint ck_support_plan_command_replacement,
    add column plan_change_request_id uuid,
    add constraint fk_support_plan_command_plan_change_request
        foreign key (plan_change_request_id) references plan_change_request(id) on delete restrict,
    add constraint ck_support_plan_command_replacement check (
        (command_type='ACTIVATE'
            and source_support_plan_id is null
            and source_support_plan_version is null
            and reassessment_summary_id is null
            and plan_change_request_id is null
            and replacement_review_outcome is null)
        or
        (command_type='REPLACE'
            and source_support_plan_id is not null
            and source_support_plan_version >= 0
            and (
                (reassessment_summary_id is not null and plan_change_request_id is null
                    and replacement_review_outcome in (
                        'CURRENT_PLAN_VALID_ALTERNATIVES_AVAILABLE','CURRENT_PLAN_NOT_ADMISSIBLE'
                    ))
                or
                (reassessment_summary_id is null and plan_change_request_id is not null
                    and replacement_review_outcome='SPECIALIST_PROPOSAL_ACCEPTED')
            ))
    );
