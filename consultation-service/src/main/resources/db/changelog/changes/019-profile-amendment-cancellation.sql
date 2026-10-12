--liquibase formatted sql

--changeset mentalbridge:consultation-019-profile-amendment-cancellation
alter table specialist_profile_amendment drop constraint ck_amendment_state;
alter table specialist_profile_amendment add constraint ck_amendment_state check (
    (status in ('DRAFT', 'CANCELLED') and submitted_at is null and reviewed_at is null and reviewed_by is null and reason_code is null)
    or (status = 'PENDING_REVIEW' and submitted_at is not null and reviewed_at is null and reviewed_by is null and reason_code is null)
    or (status = 'APPROVED' and submitted_at is not null and reviewed_at is not null and reviewed_by is not null and reason_code is null)
    or (status = 'REJECTED' and submitted_at is not null and reviewed_at is not null and reviewed_by is not null
        and reason_code is not null and reason_code in ('PROFILE_INFORMATION_INCOMPLETE','PROFILE_CONTENT_NOT_APPROVED','OUTSIDE_SUPPORTED_SCOPE'))
);
alter table specialist_profile_amendment_history drop constraint specialist_profile_amendment_history_status_check;
alter table specialist_profile_amendment_history add constraint specialist_profile_amendment_history_status_check
    check (status in ('DRAFT','PENDING_REVIEW','REJECTED','APPROVED','CANCELLED'));
