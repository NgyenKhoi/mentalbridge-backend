--liquibase formatted sql

--changeset mentalbridge:consultation-012-resource-proposal
alter table agreed_next_step
    add column resource_proposal_reason_code varchar(48);

update agreed_next_step
set resource_proposal_reason_code='POST_CONSULTATION_CONTINUITY'
where step_type='PLATFORM_RESOURCE';

alter table agreed_next_step
    add constraint ck_agreed_next_step_proposal_reason check (
        (step_type='PLATFORM_RESOURCE' and resource_proposal_reason_code in (
            'POST_CONSULTATION_CONTINUITY','TRY_ALTERNATIVE_RESOURCE','ADDRESS_REPORTED_BARRIER'
        ))
        or (step_type<>'PLATFORM_RESOURCE' and resource_proposal_reason_code is null)
    );

create index ix_agreed_next_step_resource_proposal
    on agreed_next_step (id, summary_id)
    where step_type='PLATFORM_RESOURCE';
