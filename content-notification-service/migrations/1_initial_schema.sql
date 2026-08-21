-- Migration: 1_initial_schema
-- Service:   content-notification-service
-- Database:  mentalbridge_content_notification
-- Schema:    public (service-owned database, default schema)
-- Append-only — never edit after merge

CREATE TABLE IF NOT EXISTS resource (
  id             uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  category       varchar(32)   NOT NULL
                 CHECK (category IN ('BREATHING','MEDITATION','ARTICLE','VIDEO','JOURNALING','COMMUNITY')),
  locale         varchar(16)   NOT NULL DEFAULT 'vi-VN',
  title          varchar(255)  NOT NULL,
  summary        text          NOT NULL,
  content_body   text,
  external_url   varchar(2048),
  status         varchar(16)   NOT NULL DEFAULT 'DRAFT'
                 CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
  reviewed_by    uuid,
  reviewed_at    timestamptz,
  effective_at   timestamptz,
  expires_at     timestamptz,
  created_at     timestamptz   NOT NULL DEFAULT now(),
  updated_at     timestamptz   NOT NULL DEFAULT now(),
  version        bigint        NOT NULL DEFAULT 0,
  CONSTRAINT ck_resource_content_or_url
    CHECK (content_body IS NOT NULL OR external_url IS NOT NULL),
  CONSTRAINT ck_resource_lifecycle_dates
    CHECK (expires_at IS NULL OR effective_at IS NULL OR expires_at > effective_at)
);

CREATE INDEX IF NOT EXISTS ix_resource_browse
  ON resource (locale, category, created_at DESC)
  WHERE status = 'PUBLISHED';

CREATE INDEX IF NOT EXISTS ix_resource_review_window
  ON resource (reviewed_at, effective_at)
  WHERE status = 'PUBLISHED' AND effective_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS hotline (
  id                uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  country_code      char(2)       NOT NULL,
  region            varchar(120),
  name              varchar(255)  NOT NULL,
  phone_number      varchar(40),
  website_url       varchar(2048),
  availability_text varchar(255),
  guidance          text          NOT NULL,
  locale            varchar(16)   NOT NULL DEFAULT 'vi-VN',
  active            boolean       NOT NULL DEFAULT true,
  verified_at       timestamptz   NOT NULL,
  next_review_at    timestamptz   NOT NULL,
  verified_by       uuid          NOT NULL,
  created_at        timestamptz   NOT NULL DEFAULT now(),
  updated_at        timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT ck_hotline_contact
    CHECK (phone_number IS NOT NULL OR website_url IS NOT NULL),
  CONSTRAINT ck_hotline_review_after_verify
    CHECK (next_review_at > verified_at)
);

CREATE INDEX IF NOT EXISTS ix_hotline_active_region
  ON hotline (country_code, region, locale)
  WHERE active;

CREATE INDEX IF NOT EXISTS ix_hotline_review_window
  ON hotline (next_review_at)
  WHERE active;

CREATE TABLE IF NOT EXISTS notification_preference (
  user_id     uuid         NOT NULL,
  channel     varchar(16)  NOT NULL CHECK (channel IN ('PUSH','EMAIL','IN_APP')),
  category    varchar(32)  NOT NULL
              CHECK (category IN ('ASSESSMENT','APPOINTMENT','CHAT','FOLLOW_UP','SAFETY','SYSTEM')),
  enabled     boolean      NOT NULL DEFAULT true,
  quiet_hours jsonb,
  updated_at  timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, channel, category)
);

CREATE TABLE IF NOT EXISTS notification (
  id               uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  recipient_id     uuid          NOT NULL,
  category         varchar(32)   NOT NULL,
  title            varchar(255)  NOT NULL,
  body             varchar(1000) NOT NULL,
  action_type      varchar(40),
  action_target_id uuid,
  priority         varchar(16)   NOT NULL DEFAULT 'NORMAL'
                   CHECK (priority IN ('LOW','NORMAL','HIGH')),
  read_at          timestamptz,
  expires_at       timestamptz,
  created_at       timestamptz   NOT NULL DEFAULT now(),
  deleted_at       timestamptz
);

CREATE INDEX IF NOT EXISTS ix_notification_recipient_unread
  ON notification (recipient_id, created_at DESC)
  WHERE read_at IS NULL AND deleted_at IS NULL;
