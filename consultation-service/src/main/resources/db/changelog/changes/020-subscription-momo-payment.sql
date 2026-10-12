--liquibase formatted sql

--changeset mentalbridge:consultation-020-subscription-momo-payment
create table subscription_plan (
    code varchar(16) primary key,
    display_name varchar(64) not null,
    sort_order integer not null unique,
    constraint ck_subscription_plan_code check (code in ('FREE', 'PLUS', 'PREMIUM')),
    constraint ck_subscription_plan_name check (btrim(display_name) <> ''),
    constraint ck_subscription_plan_sort check (sort_order between 0 and 100)
);

create table subscription_plan_version (
    id uuid primary key,
    plan_code varchar(16) not null references subscription_plan(code),
    version varchar(64) not null,
    currency char(3) not null,
    price_minor bigint not null,
    billing_period_months integer not null,
    consultation_credits_per_period integer not null,
    max_active_reservations integer not null,
    non_consultation_value_minor bigint not null,
    credit_value_minor bigint not null,
    specialist_share_bps integer not null,
    ai_quota_code varchar(64) not null,
    support_plan_enabled boolean not null,
    effective_from timestamptz not null,
    retired_at timestamptz,
    created_at timestamptz not null,
    constraint uq_subscription_plan_version unique (plan_code, version),
    constraint ck_subscription_plan_version_currency check (currency = 'VND'),
    constraint ck_subscription_plan_version_period check (billing_period_months = 1),
    constraint ck_subscription_plan_version_window check (retired_at is null or retired_at > effective_from),
    constraint ck_subscription_plan_version_economics check (
        (plan_code = 'FREE' and price_minor = 0 and consultation_credits_per_period = 0
            and max_active_reservations = 0 and non_consultation_value_minor = 0
            and credit_value_minor = 0 and specialist_share_bps = 7000 and support_plan_enabled = false)
        or (plan_code = 'PLUS' and price_minor > 0 and consultation_credits_per_period = 4
            and max_active_reservations = 2 and credit_value_minor = 300000
            and specialist_share_bps = 7000 and support_plan_enabled = true)
        or (plan_code = 'PREMIUM' and price_minor > 0 and consultation_credits_per_period = 10
            and max_active_reservations = 4 and credit_value_minor = 300000
            and specialist_share_bps = 7000 and support_plan_enabled = true)
    ),
    constraint ck_subscription_plan_version_allocation check (
        non_consultation_value_minor >= 0
        and price_minor >= non_consultation_value_minor
    ),
    constraint ck_subscription_plan_version_ai check (btrim(ai_quota_code) <> '')
);

create table subscription_plan_entitlement (
    plan_version_id uuid not null references subscription_plan_version(id),
    entitlement_code varchar(64) not null,
    primary key (plan_version_id, entitlement_code),
    constraint ck_subscription_plan_entitlement_code check (btrim(entitlement_code) <> '')
);

create table user_subscription (
    id uuid primary key,
    user_id uuid not null,
    plan_version_id uuid not null references subscription_plan_version(id),
    status varchar(24) not null,
    current_period_start timestamptz,
    current_period_end timestamptz,
    ended_at timestamptz,
    idempotency_key varchar(128) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_user_subscription_command unique (user_id, idempotency_key),
    constraint ck_user_subscription_status check (status in ('PENDING_PAYMENT', 'ACTIVE', 'ENDED')),
    constraint ck_user_subscription_period check (
        (status = 'PENDING_PAYMENT' and current_period_start is null and current_period_end is null and ended_at is null)
        or (status = 'ACTIVE' and current_period_start is not null and current_period_end > current_period_start and ended_at is null)
        or (status = 'ENDED' and ended_at is not null)
    )
);

create unique index uq_user_subscription_current
    on user_subscription (user_id) where status in ('PENDING_PAYMENT', 'ACTIVE');
create index ix_user_subscription_history
    on user_subscription (user_id, created_at desc, id desc);

create table subscription_status_history (
    id uuid primary key,
    subscription_id uuid not null references user_subscription(id),
    from_status varchar(24),
    to_status varchar(24) not null,
    reason_code varchar(64) not null,
    changed_at timestamptz not null,
    constraint ck_subscription_status_history check (
        (from_status is null or from_status in ('PENDING_PAYMENT', 'ACTIVE', 'ENDED'))
        and to_status in ('PENDING_PAYMENT', 'ACTIVE', 'ENDED')
    )
);

create table payment_transaction (
    id uuid primary key,
    subscription_id uuid not null references user_subscription(id),
    target_plan_version_id uuid not null references subscription_plan_version(id),
    payment_provider varchar(16) not null,
    momo_order_id varchar(128) not null,
    momo_request_id varchar(128) not null,
    momo_trans_id bigint,
    amount_minor bigint not null,
    currency char(3) not null,
    status varchar(16) not null,
    purpose varchar(16) not null,
    momo_result_code integer,
    momo_pay_type varchar(32),
    idempotency_key varchar(128) not null,
    momo_response_time_epoch_ms bigint,
    provider_occurred_at timestamptz,
    paid_at timestamptz,
    failed_at timestamptz,
    expires_at timestamptz not null,
    checkout_url varchar(2048) not null,
    qr_code_url varchar(2048),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_payment_order unique (payment_provider, momo_order_id),
    constraint uq_payment_request unique (payment_provider, momo_request_id),
    constraint uq_payment_trans unique (payment_provider, momo_trans_id),
    constraint uq_payment_command unique (subscription_id, idempotency_key),
    constraint ck_payment_provider check (payment_provider in ('FAKE', 'MOMO')),
    constraint ck_payment_amount check (amount_minor > 0),
    constraint ck_payment_currency check (currency = 'VND'),
    constraint ck_payment_status check (status in ('PENDING', 'SUCCEEDED', 'FAILED', 'CHARGEBACK')),
    constraint ck_payment_purpose check (purpose in ('PURCHASE', 'UPGRADE')),
    constraint ck_payment_times check (expires_at > created_at),
    constraint ck_payment_finality check (
        (status = 'PENDING' and paid_at is null and failed_at is null)
        or (status = 'SUCCEEDED' and paid_at is not null and failed_at is null)
        or (status = 'FAILED' and paid_at is null and failed_at is not null)
        or (status = 'CHARGEBACK' and paid_at is not null)
    )
);
create index ix_payment_subscription_history
    on payment_transaction (subscription_id, created_at desc, id desc);

create table momo_payment_ipn (
    id uuid primary key,
    payment_id uuid references payment_transaction(id),
    contract_version varchar(32) not null,
    deduplication_key char(64) not null unique,
    partner_code varchar(64) not null,
    order_id varchar(128) not null,
    request_id varchar(128) not null,
    amount_minor bigint not null,
    currency char(3) not null,
    order_info_sha256 char(64) not null,
    order_type varchar(64) not null,
    trans_id bigint not null,
    result_code integer not null,
    result_message varchar(256) not null,
    pay_type varchar(32) not null,
    response_time_epoch_ms bigint not null,
    extra_data_sha256 char(64) not null,
    provider_occurred_at timestamptz not null,
    safe_optional_details jsonb not null default '{}'::jsonb,
    payload_sha256 char(64) not null,
    signature_key_version varchar(32) not null,
    signature_verified_at timestamptz not null,
    processing_status varchar(32) not null,
    failure_code varchar(64),
    received_at timestamptz not null,
    processed_at timestamptz,
    acknowledged_at timestamptz,
    constraint ck_momo_payment_currency check (currency = 'VND'),
    constraint ck_momo_payment_amount check (amount_minor > 0),
    constraint ck_momo_payment_response_time check (response_time_epoch_ms > 0),
    constraint ck_momo_payment_status check (
        processing_status in ('RECEIVED', 'PROCESSED', 'VERIFIED_UNMATCHED', 'PROCESSING_FAILED')
    )
);
create index ix_momo_payment_ipn_order on momo_payment_ipn (order_id, received_at desc);

create table subscription_upgrade (
    id uuid primary key,
    subscription_id uuid not null references user_subscription(id),
    from_plan_version_id uuid not null references subscription_plan_version(id),
    to_plan_version_id uuid not null references subscription_plan_version(id),
    payment_id uuid not null unique references payment_transaction(id),
    old_period_start timestamptz not null,
    old_period_end timestamptz not null,
    total_period_seconds bigint not null,
    remaining_period_seconds bigint not null,
    remaining_feature_value_minor bigint not null,
    available_credit_value_minor bigint not null,
    offset_minor bigint not null,
    amount_due_minor bigint not null,
    currency char(3) not null,
    status varchar(24) not null,
    idempotency_key varchar(128) not null,
    quoted_at timestamptz not null,
    quote_expires_at timestamptz not null,
    applied_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_subscription_upgrade_command unique (subscription_id, idempotency_key),
    constraint ck_subscription_upgrade_currency check (currency = 'VND'),
    constraint ck_subscription_upgrade_status check (status in ('PENDING_PAYMENT', 'APPLIED', 'FAILED', 'EXPIRED')),
    constraint ck_subscription_upgrade_period check (
        total_period_seconds > 0 and remaining_period_seconds > 0
        and remaining_period_seconds <= total_period_seconds
    ),
    constraint ck_subscription_upgrade_amount check (
        remaining_feature_value_minor >= 0 and available_credit_value_minor >= 0
        and offset_minor = remaining_feature_value_minor + available_credit_value_minor
        and amount_due_minor > 0
    )
);
create unique index uq_subscription_upgrade_pending
    on subscription_upgrade (subscription_id) where status = 'PENDING_PAYMENT';

create table subscription_upgrade_credit (
    upgrade_id uuid not null references subscription_upgrade(id),
    credit_id uuid not null unique references service_credit(id),
    allocated_value_minor bigint not null,
    primary key (upgrade_id, credit_id),
    constraint ck_subscription_upgrade_credit_value check (allocated_value_minor > 0)
);

alter table service_credit
    drop constraint ck_service_credit_state,
    drop constraint ck_service_credit_appointment,
    add constraint ck_service_credit_state check (
        state in ('AVAILABLE', 'HELD', 'UPGRADE_HELD', 'CONSUMED', 'FORFEITED', 'EXPIRED', 'REVOKED')
    ),
    add constraint ck_service_credit_appointment check (
        (state in ('AVAILABLE', 'UPGRADE_HELD', 'EXPIRED', 'REVOKED') and appointment_id is null)
        or (state in ('HELD', 'CONSUMED', 'FORFEITED') and appointment_id is not null)
    );

insert into subscription_plan (code, display_name, sort_order) values
    ('FREE', 'Miễn phí', 0),
    ('PLUS', 'Plus', 10),
    ('PREMIUM', 'Premium', 20);

insert into subscription_plan_version (
    id, plan_code, version, currency, price_minor, billing_period_months,
    consultation_credits_per_period, max_active_reservations,
    non_consultation_value_minor, credit_value_minor, specialist_share_bps,
    ai_quota_code, support_plan_enabled, effective_from, created_at
) values
    ('51500000-0000-4000-8000-000000000001', 'FREE', 'v2-vnd-2026-01', 'VND', 0, 1,
        0, 0, 0, 0, 7000, 'STANDARD_5_DAILY', false, '2026-01-01T00:00:00Z', current_timestamp),
    ('51500000-0000-4000-8000-000000000002', 'PLUS', 'v2-vnd-2026-01', 'VND', 1390000, 1,
        4, 2, 190000, 300000, 7000, 'PLUS_DAILY', true, '2026-01-01T00:00:00Z', current_timestamp),
    ('51500000-0000-4000-8000-000000000003', 'PREMIUM', 'v2-vnd-2026-01', 'VND', 3490000, 1,
        10, 4, 490000, 300000, 7000, 'PREMIUM_FAIR_USE', true, '2026-01-01T00:00:00Z', current_timestamp);

insert into subscription_plan_entitlement (plan_version_id, entitlement_code) values
    ('51500000-0000-4000-8000-000000000001', 'SUPPORT_GUIDE'),
    ('51500000-0000-4000-8000-000000000001', 'JOURNAL'),
    ('51500000-0000-4000-8000-000000000001', 'EMOTION_CHECK_IN'),
    ('51500000-0000-4000-8000-000000000002', 'SUPPORT_GUIDE'),
    ('51500000-0000-4000-8000-000000000002', 'JOURNAL'),
    ('51500000-0000-4000-8000-000000000002', 'EMOTION_CHECK_IN'),
    ('51500000-0000-4000-8000-000000000002', 'SUPPORT_PLAN'),
    ('51500000-0000-4000-8000-000000000003', 'SUPPORT_GUIDE'),
    ('51500000-0000-4000-8000-000000000003', 'JOURNAL'),
    ('51500000-0000-4000-8000-000000000003', 'EMOTION_CHECK_IN'),
    ('51500000-0000-4000-8000-000000000003', 'SUPPORT_PLAN'),
    ('51500000-0000-4000-8000-000000000003', 'ADVANCED_RECOMMENDATIONS');
