--liquibase formatted sql

--changeset mentalbridge:identity-009-report-schedules runInTransaction:true
CREATE TABLE platform_report_schedule (
    id uuid PRIMARY KEY,
    report_type varchar(64) NOT NULL CHECK (report_type = 'ACCOUNT_ACTIVITY'),
    cadence varchar(16) NOT NULL CHECK (cadence IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    timezone varchar(64) NOT NULL,
    local_time time NOT NULL CHECK (extract(second FROM local_time) = 0),
    period_days integer NOT NULL CHECK (period_days BETWEEN 1 AND 366),
    recipient_group varchar(16) NOT NULL CHECK (recipient_group = 'ADMIN'),
    delivery_target varchar(24) NOT NULL CHECK (delivery_target = 'ADMIN_REPORT_HISTORY'),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED', 'DELETED')),
    created_by uuid NOT NULL REFERENCES account(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    next_run_at timestamptz NOT NULL,
    last_failure_code varchar(64),
    version bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_platform_report_schedule_due ON platform_report_schedule (next_run_at, id) WHERE status = 'ACTIVE';
ALTER TABLE platform_report_job ADD COLUMN schedule_id uuid REFERENCES platform_report_schedule(id);
ALTER TABLE platform_report_job ADD COLUMN scheduled_for timestamptz;
ALTER TABLE platform_report_job ADD CONSTRAINT ck_platform_report_occurrence CHECK ((schedule_id IS NULL) = (scheduled_for IS NULL));
ALTER TABLE platform_report_job ADD CONSTRAINT ux_platform_report_occurrence UNIQUE (schedule_id, scheduled_for);
