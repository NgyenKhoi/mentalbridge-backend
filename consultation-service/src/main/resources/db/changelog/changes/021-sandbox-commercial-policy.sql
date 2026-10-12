--liquibase formatted sql

--changeset mentalbridge:consultation-021-sandbox-commercial-policy
alter table subscription_plan_version
    add column pricing_policy_version varchar(64) not null default 'upgrade-offset-v1',
    add constraint ck_subscription_plan_pricing_policy check (
        pricing_policy_version in ('upgrade-offset-v1', 'sandbox-full-price-v1')
    );

alter table subscription_upgrade
    add column pricing_policy_version varchar(64) not null default 'upgrade-offset-v1',
    drop constraint ck_subscription_upgrade_amount,
    add constraint ck_subscription_upgrade_amount check (
        remaining_feature_value_minor >= 0 and available_credit_value_minor >= 0
        and amount_due_minor > 0
        and (
            (pricing_policy_version = 'upgrade-offset-v1'
                and offset_minor = remaining_feature_value_minor + available_credit_value_minor)
            or (pricing_policy_version = 'sandbox-full-price-v1' and offset_minor = 0)
        )
    );

update subscription_plan_version set retired_at = current_timestamp
where id in ('51500000-0000-4000-8000-000000000002', '51500000-0000-4000-8000-000000000003')
    and retired_at is null;

insert into subscription_plan_version (
    id, plan_code, version, currency, price_minor, billing_period_months,
    consultation_credits_per_period, max_active_reservations, non_consultation_value_minor,
    credit_value_minor, specialist_share_bps, ai_quota_code, support_plan_enabled,
    effective_from, created_at, pricing_policy_version
)
select case plan_code
        when 'PLUS' then '62200000-0000-4000-8000-000000000002'::uuid
        else '62200000-0000-4000-8000-000000000003'::uuid end,
    plan_code, 'sandbox-vnd-2026-10', currency,
    case plan_code when 'PLUS' then 5000 else 10000 end,
    billing_period_months, consultation_credits_per_period, max_active_reservations, 0,
    credit_value_minor, specialist_share_bps, ai_quota_code, support_plan_enabled,
    current_timestamp, current_timestamp, 'sandbox-full-price-v1'
from subscription_plan_version
where id in ('51500000-0000-4000-8000-000000000002', '51500000-0000-4000-8000-000000000003');

insert into subscription_plan_entitlement (plan_version_id, entitlement_code)
select case plan_version_id
        when '51500000-0000-4000-8000-000000000002'::uuid
            then '62200000-0000-4000-8000-000000000002'::uuid
        else '62200000-0000-4000-8000-000000000003'::uuid end,
    entitlement_code
from subscription_plan_entitlement
where plan_version_id in ('51500000-0000-4000-8000-000000000002', '51500000-0000-4000-8000-000000000003');
