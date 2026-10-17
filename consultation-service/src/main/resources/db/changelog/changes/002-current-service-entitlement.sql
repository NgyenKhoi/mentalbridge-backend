--liquibase formatted sql

--changeset mentalbridge:consultation-002-current-service-entitlement
create table current_service_entitlement (
    account_id uuid primary key,
    package_code varchar(16) not null,
    source varchar(16) not null,
    source_reference varchar(128) not null,
    established_by uuid,
    effective_from timestamptz not null,
    effective_until timestamptz not null,
    policy_version varchar(64) not null,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    version bigint not null default 0,
    constraint ck_current_service_entitlement_package check (package_code in ('PLUS', 'PREMIUM')),
    constraint ck_current_service_entitlement_source check (source in ('DEMO', 'PAID')),
    constraint ck_current_service_entitlement_reference check (btrim(source_reference) <> ''),
    constraint ck_current_service_entitlement_window check (effective_until > effective_from),
    constraint ck_current_service_entitlement_policy check (policy_version = 'service-entitlement-v1'),
    constraint ck_current_service_entitlement_actor check (source <> 'DEMO' or established_by is not null)
);
