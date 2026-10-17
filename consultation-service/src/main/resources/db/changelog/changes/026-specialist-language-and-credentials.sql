--liquibase formatted sql

--changeset mentalbridge:consultation-026-specialist-language-and-credentials
alter table specialist_profile_language drop constraint ck_specialist_language_tag;
alter table specialist_profile_language add constraint ck_specialist_language_tag
    check (language_tag in ('vi', 'en', 'zh', 'fr'));

create table specialist_credential_document (
    id uuid primary key,
    specialist_account_id uuid not null references specialist_profile(account_id) on delete cascade,
    title varchar(160) not null,
    issuer varchar(160) not null,
    issue_year smallint not null,
    credential_type varchar(24) not null,
    file_name varchar(180) not null,
    media_type varchar(24) not null,
    file_bytes bigint not null,
    storage_key varchar(180) not null unique,
    storage_format varchar(8) not null,
    verification_status varchar(16) not null default 'PENDING',
    badge_visible boolean not null default false,
    reviewed_by uuid,
    reviewed_at timestamptz,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    version bigint not null default 0,
    constraint ck_specialist_credential_title check (btrim(title) <> ''),
    constraint ck_specialist_credential_issuer check (btrim(issuer) <> ''),
    constraint ck_specialist_credential_year check (issue_year between 1900 and 2100),
    constraint ck_specialist_credential_type check (credential_type in ('DEGREE', 'LICENSE', 'CERTIFICATE')),
    constraint ck_specialist_credential_media_type check (media_type in ('application/pdf', 'image/jpeg', 'image/png')),
    constraint ck_specialist_credential_bytes check (file_bytes between 1 and 10485760),
    constraint ck_specialist_credential_status check (verification_status in ('PENDING', 'VERIFIED', 'REJECTED')),
    constraint ck_specialist_credential_review check (
        (verification_status = 'PENDING' and reviewed_by is null and reviewed_at is null)
        or (verification_status in ('VERIFIED', 'REJECTED') and reviewed_by is not null and reviewed_at is not null)
    ),
    constraint ck_specialist_credential_badge check (not badge_visible or verification_status = 'VERIFIED')
);

create index idx_specialist_credential_owner on specialist_credential_document
    (specialist_account_id, created_at desc, id desc);
create index idx_specialist_credential_pending on specialist_credential_document
    (created_at, id) where verification_status = 'PENDING';

