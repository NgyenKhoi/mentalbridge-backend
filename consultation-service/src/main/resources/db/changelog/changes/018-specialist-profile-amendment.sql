--liquibase formatted sql

--changeset mentalbridge:consultation-018-specialist-profile-amendment
alter table specialist_profile add column published_version bigint not null default 0;
alter table specialist_profile add constraint ck_specialist_published_version check (published_version >= 0);

create table specialist_profile_approved_version (
    id uuid primary key,
    specialist_account_id uuid not null references specialist_profile(account_id),
    published_version bigint not null check (published_version > 0),
    profile_snapshot jsonb not null check (jsonb_typeof(profile_snapshot) = 'object'),
    approved_by uuid not null,
    approved_at timestamptz not null,
    source_amendment_id uuid,
    constraint uq_specialist_approved_version unique (specialist_account_id, published_version)
);

update specialist_profile set published_version = 1 where approval_status in ('APPROVED', 'SUSPENDED');
insert into specialist_profile_approved_version (
    id, specialist_account_id, published_version, profile_snapshot, approved_by, approved_at
)
select p.account_id, p.account_id, 1,
    jsonb_build_object(
        'displayName', p.display_name, 'bio', p.biography,
        'supportAreas', (select jsonb_agg(s.support_area order by s.support_area) from specialist_profile_support_area s where s.specialist_account_id=p.account_id),
        'languages', (select jsonb_agg(l.language_tag order by l.language_tag) from specialist_profile_language l where l.specialist_account_id=p.account_id),
        'yearsOfExperience', p.years_experience, 'timezone', p.timezone
    ), coalesce(h.actor_account_id, p.reviewed_by), coalesce(h.occurred_at, p.reviewed_at)
from specialist_profile p
left join lateral (
    select actor_account_id, occurred_at from specialist_profile_status_history
    where specialist_account_id=p.account_id and approval_status='APPROVED'
    order by occurred_at desc, id desc limit 1
) h on true
where p.published_version = 1;

create table specialist_profile_amendment (
    id uuid primary key,
    specialist_account_id uuid not null references specialist_profile(account_id),
    base_published_version bigint not null,
    status varchar(24) not null,
    proposed_profile jsonb not null,
    submitted_at timestamptz,
    reviewed_at timestamptz,
    reviewed_by uuid,
    reason_code varchar(64),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0 check (version >= 0),
    constraint fk_amendment_approved_base foreign key (specialist_account_id, base_published_version)
        references specialist_profile_approved_version(specialist_account_id, published_version),
    constraint ck_amendment_snapshot check (
        jsonb_typeof(proposed_profile) = 'object'
        and jsonb_exists_all(proposed_profile, array['displayName','bio','supportAreas','languages','yearsOfExperience','timezone'])
    ),
    constraint ck_amendment_state check (
        (status = 'DRAFT' and submitted_at is null and reviewed_at is null and reviewed_by is null and reason_code is null)
        or (status = 'PENDING_REVIEW' and submitted_at is not null and reviewed_at is null and reviewed_by is null and reason_code is null)
        or (status = 'APPROVED' and submitted_at is not null and reviewed_at is not null and reviewed_by is not null and reason_code is null)
        or (status = 'REJECTED' and submitted_at is not null and reviewed_at is not null and reviewed_by is not null
            and reason_code is not null and reason_code in ('PROFILE_INFORMATION_INCOMPLETE','PROFILE_CONTENT_NOT_APPROVED','OUTSIDE_SUPPORTED_SCOPE'))
    )
);
create unique index uq_specialist_open_amendment on specialist_profile_amendment(specialist_account_id)
    where status in ('DRAFT','PENDING_REVIEW','REJECTED');
create index ix_amendment_review_queue on specialist_profile_amendment(submitted_at, id) where status='PENDING_REVIEW';
create index ix_amendment_owner_latest on specialist_profile_amendment(specialist_account_id, created_at desc, id desc);
alter table specialist_profile_approved_version add constraint fk_approved_source_amendment
    foreign key (source_amendment_id) references specialist_profile_amendment(id);

create table specialist_profile_amendment_history (
    id uuid primary key,
    amendment_id uuid not null references specialist_profile_amendment(id),
    amendment_version bigint not null,
    status varchar(24) not null check (status in ('DRAFT','PENDING_REVIEW','REJECTED','APPROVED')),
    proposed_profile jsonb not null check (jsonb_typeof(proposed_profile) = 'object'),
    actor_account_id uuid not null,
    actor_role varchar(16) not null check (actor_role in ('SPECIALIST','ADMIN')),
    reason_code varchar(64),
    occurred_at timestamptz not null,
    constraint uq_amendment_history_version unique (amendment_id, amendment_version),
    constraint ck_amendment_history_reason check (
        (status <> 'REJECTED' and reason_code is null)
        or (status = 'REJECTED' and reason_code is not null and reason_code in ('PROFILE_INFORMATION_INCOMPLETE','PROFILE_CONTENT_NOT_APPROVED','OUTSIDE_SUPPORTED_SCOPE'))
    )
);
