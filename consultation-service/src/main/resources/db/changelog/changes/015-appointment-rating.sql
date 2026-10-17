--liquibase formatted sql

--changeset mentalbridge:consultation-015-appointment-rating runInTransaction:true
create table appointment_rating (
    appointment_id uuid primary key references appointment(id),
    user_account_id uuid not null,
    specialist_account_id uuid not null references specialist_profile(account_id),
    rating smallint not null,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ck_appointment_rating_value check (rating between 1 and 5),
    constraint ck_appointment_rating_version check (version >= 0)
);

create table specialist_rating_aggregate (
    specialist_account_id uuid primary key references specialist_profile(account_id),
    rating_count bigint not null,
    rating_sum bigint not null,
    version bigint not null default 0,
    updated_at timestamptz not null,
    constraint ck_specialist_rating_count check (rating_count > 0),
    constraint ck_specialist_rating_sum check (
        rating_sum >= rating_count and rating_sum <= rating_count * 5
    ),
    constraint ck_specialist_rating_aggregate_version check (version >= 0)
);

create index ix_appointment_rating_user_time
    on appointment_rating (user_account_id, updated_at desc);
