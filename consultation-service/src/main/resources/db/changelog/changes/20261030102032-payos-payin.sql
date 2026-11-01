--liquibase formatted sql

--changeset mentalbridge:consultation-20261030102032-payos-payin
create sequence payment_order_code_seq as bigint
    start with 1 minvalue 1 maxvalue 9007199254740991 no cycle;

alter table payment_transaction
    alter column momo_order_id drop not null,
    alter column momo_request_id drop not null,
    drop constraint ck_payment_provider,
    add column order_code bigint,
    add column payos_payment_link_id varchar(128),
    add column payos_reference varchar(128),
    add column qr_code text,
    add column bank_bin varchar(16),
    add column bank_account_number varchar(64),
    add column bank_account_name varchar(256),
    add column payment_description varchar(9),
    add column terminal_reason varchar(24),
    add column reconciliation_next_at timestamptz,
    add column reconciliation_failures integer not null default 0,
    add column reconciliation_failure_code varchar(64),
    add constraint ck_payment_provider check (payment_provider in ('FAKE', 'MOMO', 'PAYOS')),
    add constraint ck_payment_provider_identity check (
        (payment_provider in ('FAKE', 'MOMO') and momo_order_id is not null and momo_request_id is not null)
        or (payment_provider = 'PAYOS' and order_code is not null
            and momo_order_id is null and momo_request_id is null and momo_trans_id is null)
    ),
    add constraint ck_payment_order_code check (order_code between 1 and 9007199254740991),
    add constraint ck_payment_payos_metadata check (
        (payos_payment_link_id is null or btrim(payos_payment_link_id) <> '')
        and (payos_reference is null or btrim(payos_reference) <> '')
        and (payment_provider = 'PAYOS' or (payos_payment_link_id is null and payos_reference is null))
    ),
    add constraint ck_payment_qr_details check (
        qr_code is null or (
            btrim(qr_code) <> '' and bank_bin is not null and btrim(bank_bin) <> ''
            and bank_account_number is not null and btrim(bank_account_number) <> ''
            and bank_account_name is not null and btrim(bank_account_name) <> ''
            and payment_description is not null and btrim(payment_description) <> ''
        )
    ),
    add constraint ck_payment_terminal_reason check (
        terminal_reason is null or (status = 'FAILED' and terminal_reason in ('EXPIRED', 'CANCELLED', 'PROVIDER_FAILED'))
    ),
    add constraint ck_payment_reconciliation_failures check (reconciliation_failures >= 0),
    add constraint uq_payment_order_code unique (payment_provider, order_code),
    add constraint uq_payment_payos_link unique (payos_payment_link_id),
    add constraint uq_payment_payos_reference unique (payos_reference);

create table payos_payment_webhook (
    id uuid primary key,
    payment_id uuid references payment_transaction(id),
    contract_version varchar(32) not null,
    source varchar(24) not null,
    deduplication_key char(64) not null unique,
    order_code bigint not null,
    amount_minor bigint not null,
    payment_link_id varchar(128) not null,
    reference varchar(128) not null,
    code varchar(32),
    success boolean,
    payload_sha256 char(64) not null,
    signature_key_version varchar(32) not null,
    signature_verified_at timestamptz not null,
    processing_status varchar(32) not null,
    failure_code varchar(64),
    received_at timestamptz not null,
    processed_at timestamptz,
    acknowledged_at timestamptz,
    constraint ck_payos_receipt_source check (source in ('WEBHOOK', 'RECONCILIATION')),
    constraint ck_payos_receipt_amount check (amount_minor >= 0),
    constraint ck_payos_receipt_status check (
        processing_status in ('RECEIVED', 'PROCESSED', 'VERIFIED_UNMATCHED', 'PROCESSING_FAILED')
    )
);
create index ix_payos_payment_webhook_order on payos_payment_webhook (order_code, received_at desc);
create index ix_payos_payment_webhook_reference on payos_payment_webhook (reference);
