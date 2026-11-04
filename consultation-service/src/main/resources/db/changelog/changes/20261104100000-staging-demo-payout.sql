--liquibase formatted sql

--changeset mentalbridge:consultation-20261104100000-staging-demo-payout
alter table specialist_earning
    add column earning_policy_version varchar(64) not null default 'specialist-earning-v1',
    add column funding_payment_id uuid references payment_transaction(id),
    add constraint ck_specialist_earning_funding_policy check (
        (earning_policy_version = 'specialist-earning-v1' and funding_payment_id is null)
        or (earning_policy_version = 'staging-demo-paid-v1' and funding_payment_id is not null)
    );

alter table specialist_payout
    drop constraint ck_specialist_payout_amount,
    add constraint ck_specialist_payout_amount check (amount_minor > 0);
