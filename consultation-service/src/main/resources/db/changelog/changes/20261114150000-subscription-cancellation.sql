--liquibase formatted sql

--changeset mentalbridge:consultation-20261114150000-subscription-cancellation
alter table user_subscription
    add column cancellation_requested_at timestamptz,
    add column cancellation_effective_at timestamptz,
    add column cancellation_live_appointment_id uuid references appointment(id),
    alter column status type varchar(32),
    drop constraint ck_user_subscription_status,
    drop constraint ck_user_subscription_period,
    add constraint ck_user_subscription_status check (
        status in ('PENDING_PAYMENT', 'ACTIVE', 'CANCEL_PENDING_SESSION_END', 'CANCELLED', 'ENDED')
    ),
    add constraint ck_user_subscription_period check (
        (status = 'PENDING_PAYMENT' and current_period_start is null and current_period_end is null
            and ended_at is null and cancellation_requested_at is null and cancellation_effective_at is null)
        or (status = 'ACTIVE' and current_period_start is not null and current_period_end > current_period_start
            and ended_at is null and cancellation_requested_at is null and cancellation_effective_at is null)
        or (status = 'CANCEL_PENDING_SESSION_END' and current_period_start is not null
            and current_period_end > current_period_start and ended_at is not null
            and cancellation_requested_at is not null and cancellation_effective_at is not null
            and cancellation_live_appointment_id is not null)
        or (status = 'CANCELLED' and ended_at is not null and cancellation_requested_at is not null
            and cancellation_effective_at is not null and cancellation_live_appointment_id is null)
        or (status = 'ENDED' and ended_at is not null and cancellation_requested_at is null
            and cancellation_effective_at is null and cancellation_live_appointment_id is null)
    );

alter table subscription_status_history
    add column changed_by uuid,
    add column outcome_code varchar(64),
    alter column from_status type varchar(32),
    alter column to_status type varchar(32),
    drop constraint ck_subscription_status_history,
    add constraint ck_subscription_status_history check (
        (from_status is null or from_status in (
            'PENDING_PAYMENT', 'ACTIVE', 'CANCEL_PENDING_SESSION_END', 'CANCELLED', 'ENDED'
        ))
        and to_status in ('PENDING_PAYMENT', 'ACTIVE', 'CANCEL_PENDING_SESSION_END', 'CANCELLED', 'ENDED')
    );

create table subscription_cancellation_command (
    id uuid primary key,
    actor_account_id uuid not null,
    subscription_id uuid not null references user_subscription(id),
    idempotency_key varchar(128) not null,
    request_fingerprint char(64) not null,
    from_status varchar(24) not null,
    accepted_status varchar(32) not null,
    requested_at timestamptz not null,
    effective_at timestamptz not null,
    live_appointment_id uuid references appointment(id),
    cancelled_appointment_count integer not null,
    revoked_credit_count integer not null,
    outcome_code varchar(64) not null,
    constraint uq_subscription_cancellation_actor_key unique (actor_account_id, idempotency_key),
    constraint ck_subscription_cancellation_key check (char_length(idempotency_key) between 16 and 128),
    constraint ck_subscription_cancellation_fingerprint check (request_fingerprint ~ '^[0-9a-f]{64}$'),
    constraint ck_subscription_cancellation_status check (
        from_status = 'ACTIVE' and accepted_status in ('CANCEL_PENDING_SESSION_END', 'CANCELLED')
    ),
    constraint ck_subscription_cancellation_counts check (
        cancelled_appointment_count >= 0 and revoked_credit_count >= 0
    )
);
create index ix_subscription_cancellation_subscription
    on subscription_cancellation_command(subscription_id, requested_at desc, id desc);

alter table service_credit_ledger
    drop constraint ck_service_credit_ledger_event,
    drop constraint ck_service_credit_ledger_appointment,
    add constraint ck_service_credit_ledger_event check (
        event_type in ('PROVISIONED', 'HELD', 'CONSUMED', 'RELEASED', 'FORFEITED', 'ADJUSTED_RELEASED', 'REVOKED')
    ),
    add constraint ck_service_credit_ledger_appointment check (
        (event_type in ('PROVISIONED', 'REVOKED') and appointment_id is null)
        or (event_type not in ('PROVISIONED', 'REVOKED') and appointment_id is not null)
    );

alter table appointment
    drop constraint ck_appointment_cancellation,
    add constraint ck_appointment_cancellation check (
        (status <> 'CANCELLED' and cancelled_at is null and cancelled_by is null
            and cancellation_reason is null and cancellation_credit_outcome is null)
        or (status = 'CANCELLED' and cancelled_at is not null and cancelled_by is not null
            and cancellation_reason is not null
            and cancellation_credit_outcome in ('RELEASED', 'FORFEITED', 'TRANSFERRED_TO_REPLACEMENT', 'REVOKED'))
    );

alter table appointment_status_history
    drop constraint ck_appointment_history_credit_outcome,
    add constraint ck_appointment_history_credit_outcome check (
        (to_status = 'CANCELLED' and credit_outcome is not null
            and credit_outcome in ('RELEASED', 'FORFEITED', 'TRANSFERRED_TO_REPLACEMENT', 'REVOKED'))
        or (to_status <> 'CANCELLED' and credit_outcome is null)
    );
