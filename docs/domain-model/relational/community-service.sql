/*
 * MENTALBRIDGE CANONICAL LOGICAL DATA MODEL
 *
 * Purpose:
 * - Canonical relational reference for architecture documentation.
 * - ERD and domain modelling.
 * - Cross-service entity and relationship understanding.
 *
 * THIS FILE IS READ-ONLY AND NON-EXECUTABLE.
 *
 * Runtime database source of truth:
 * - Each service's owner-specific migration history.
 *
 * This file must never be used to provision, initialize,
 * migrate, or modify a runtime database.
 *
 * Any persisted model change introduced by a service migration
 * must update this logical model in the same pull request.
 *
 * The identity, care, consultation, community, and content qualifiers below are visual
 * owner namespaces only. Runtime PostgreSQL uses a separate owner database and
 * its default public schema. Cross-owner identifiers are logical/external
 * references and are deliberately not physical foreign keys.
 *
 * Reconciled from owner migrations through 2026-09-29. It includes the Care
 * SupportPlan, immutable Reassessment Summary, and Community feed migrations. Technical indexes and migration bookkeeping are
 * intentionally omitted; owner migrations remain authoritative for exact DDL.
 */

/* ========================================================================== */
/* ACTIVE — community-service / mentalbridge_community                        */
/* Evidence: community-service Liquibase changes 0001-0016.                  */
/* ========================================================================== */

CREATE TABLE community.community_profile (
    id uuid PRIMARY KEY,
    account_subject uuid NOT NULL UNIQUE, -- external -> identity.account.id
    display_name varchar(80) NOT NULL,
    avatar_preset varchar(24),
    avatar_media_id uuid,
    status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL
);

CREATE TABLE community.community_post (
    id uuid PRIMARY KEY,
    author_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
    content varchar(5000) NOT NULL,
    state varchar(24) NOT NULL,
    comment_count integer NOT NULL,
    reaction_count integer NOT NULL,
    idempotency_key varchar(128),
    request_fingerprint varchar(64),
    author_mode varchar(16) NOT NULL,
    resource_id uuid,
    sensitive_content_warning varchar(32),
    published_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL
);

CREATE TABLE community.community_topic (
    code varchar(32) PRIMARY KEY,
    label varchar(80) NOT NULL,
    description varchar(240) NOT NULL,
    active boolean NOT NULL,
    display_order smallint NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE community.community_post_topic (
    post_id uuid NOT NULL REFERENCES community.community_post(id),
    topic_code varchar(32) NOT NULL REFERENCES community.community_topic(code),
    PRIMARY KEY (post_id, topic_code)
);

CREATE TABLE community.community_post_reaction (
    post_id uuid NOT NULL REFERENCES community.community_post(id),
    profile_id uuid NOT NULL REFERENCES community.community_profile(id),
    reaction varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (post_id, profile_id)
);

CREATE TABLE community.community_post_bookmark (
    post_id uuid NOT NULL REFERENCES community.community_post(id),
    profile_id uuid NOT NULL REFERENCES community.community_profile(id),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (post_id, profile_id)
);

CREATE TABLE community.community_interaction_outbox (
    id uuid PRIMARY KEY,
    deduplication_key varchar(200) NOT NULL UNIQUE,
    target_id uuid NOT NULL,
    event_payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    attempt_count integer NOT NULL,
    next_attempt_at timestamptz,
    created_at timestamptz NOT NULL
);

CREATE TABLE community.community_media (
    id uuid PRIMARY KEY,
    owner_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
    post_id uuid REFERENCES community.community_post(id),
    media_type varchar(16) NOT NULL,
    state varchar(16) NOT NULL,
    delivery_url varchar(2048),
    storage_provider varchar(24),
    storage_key varchar(512),
    expected_mime_type varchar(120),
    expected_size_bytes bigint,
    upload_expires_at timestamptz,
    idempotency_key varchar(128),
    request_fingerprint varchar(64),
    rejection_reason varchar(64),
    width integer,
    height integer,
    duration_seconds integer,
    alt_text varchar(300),
    position smallint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (id, owner_profile_id)
);

ALTER TABLE community.community_profile
    ADD FOREIGN KEY (avatar_media_id, id)
      REFERENCES community.community_media(id, owner_profile_id);

CREATE TABLE community.community_block (
    blocker_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
    blocked_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (blocker_profile_id, blocked_profile_id)
);

CREATE TABLE community.community_comment (
    id uuid PRIMARY KEY,
    post_id uuid NOT NULL REFERENCES community.community_post(id),
    parent_comment_id uuid,
    author_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
    content varchar(2000) NOT NULL,
    state varchar(24) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (id, post_id),
    UNIQUE (author_profile_id, idempotency_key),
    FOREIGN KEY (parent_comment_id, post_id)
        REFERENCES community.community_comment(id, post_id)
);

CREATE TABLE community.community_comment_revision (
    id uuid PRIMARY KEY,
    comment_id uuid NOT NULL REFERENCES community.community_comment(id),
    changed_by_profile_id uuid REFERENCES community.community_profile(id),
    changed_by_subject uuid,
    change_type varchar(16) NOT NULL,
    content_snapshot varchar(2000) NOT NULL,
    state_snapshot varchar(24) NOT NULL,
    comment_version bigint NOT NULL,
    changed_at timestamptz NOT NULL,
    UNIQUE (comment_id, comment_version)
);

CREATE TABLE community.community_comment_reaction (
    comment_id uuid NOT NULL REFERENCES community.community_comment(id) ON DELETE CASCADE,
    profile_id uuid NOT NULL REFERENCES community.community_profile(id) ON DELETE CASCADE,
    reaction varchar(16) NOT NULL CHECK (reaction IN ('SUPPORT', 'RELATE', 'THANK_YOU')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (comment_id, profile_id)
);

CREATE TABLE community.community_report (
    id uuid PRIMARY KEY,
    reporter_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
    target_type varchar(16) NOT NULL,
    target_id uuid NOT NULL,
    reason varchar(40) NOT NULL,
    details varchar(1000),
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (reporter_profile_id, idempotency_key),
    UNIQUE (reporter_profile_id, target_type, target_id)
);

CREATE TABLE community.community_content_hide (
    hider_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
    target_type varchar(16) NOT NULL,
    target_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (hider_profile_id, target_type, target_id)
);

CREATE TABLE community.community_moderation_case (
    id uuid PRIMARY KEY,
    target_type varchar(24) NOT NULL,
    target_id uuid NOT NULL,
    target_author_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
    state varchar(16) NOT NULL,
    priority varchar(16) NOT NULL,
    evidence_content varchar(5000) NOT NULL,
    evidence_state varchar(24) NOT NULL,
    evidence_version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (target_type, target_id)
);

CREATE TABLE community.community_moderation_action (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES community.community_moderation_case(id),
    actor_subject uuid NOT NULL, -- external -> identity.account.id
    action varchar(32) NOT NULL,
    reason_code varchar(64) NOT NULL,
    prior_state varchar(32) NOT NULL,
    resulting_state varchar(32) NOT NULL,
    target_version bigint NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (actor_subject, idempotency_key)
);

CREATE TABLE community.community_access_restriction (
    profile_id uuid PRIMARY KEY REFERENCES community.community_profile(id),
    case_id uuid NOT NULL REFERENCES community.community_moderation_case(id),
    reason_code varchar(64) NOT NULL,
    restricted_by_subject uuid NOT NULL, -- external -> identity.account.id
    restricted_at timestamptz NOT NULL,
    lifted_by_subject uuid, -- external -> identity.account.id
    lifted_at timestamptz
);

CREATE TABLE community.community_chat_privacy (
  profile_id uuid PRIMARY KEY REFERENCES community.community_profile(id) ON DELETE CASCADE,
  direct_message_policy varchar(24) NOT NULL DEFAULT 'APPROVAL_REQUIRED',
  presence_visible boolean NOT NULL DEFAULT false,
  invisible boolean NOT NULL DEFAULT false,
  typing_visible boolean NOT NULL DEFAULT false,
  read_receipts_visible boolean NOT NULL DEFAULT false,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  visibility_epoch bigint NOT NULL DEFAULT 0 CHECK (visibility_epoch >= 0),
  CHECK (direct_message_policy IN ('APPROVAL_REQUIRED', 'ANYONE'))
);
CREATE TABLE community.community_chat_conversation (
  id uuid PRIMARY KEY,
  first_profile_id uuid REFERENCES community.community_profile(id),
  second_profile_id uuid REFERENCES community.community_profile(id),
  kind varchar(8) NOT NULL DEFAULT 'DIRECT',
  name_ciphertext text,
  member_limit integer,
  state varchar(16) NOT NULL,
  management_version bigint NOT NULL DEFAULT 0 CHECK (management_version >= 0),
  event_version bigint NOT NULL DEFAULT 0 CHECK (event_version >= 0),
  last_sequence bigint NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
  created_at timestamptz NOT NULL,
  activated_at timestamptz,
  UNIQUE (first_profile_id, second_profile_id),
  CHECK ((kind = 'DIRECT' AND first_profile_id IS NOT NULL AND second_profile_id IS NOT NULL
    AND first_profile_id < second_profile_id AND state IN ('DORMANT','ACTIVE')
    AND ((state = 'ACTIVE') = (activated_at IS NOT NULL)) AND name_ciphertext IS NULL AND member_limit IS NULL)
    OR (kind = 'GROUP' AND first_profile_id IS NULL AND second_profile_id IS NULL
    AND state IN ('ACTIVE','ARCHIVED') AND activated_at IS NOT NULL AND name_ciphertext IS NOT NULL AND member_limit IS NOT NULL AND member_limit BETWEEN 20 AND 30))
);
CREATE TABLE community.community_chat_request (
  id uuid PRIMARY KEY,
  conversation_id uuid NOT NULL REFERENCES community.community_chat_conversation(id) ON DELETE CASCADE,
  requester_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
  recipient_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
  state varchar(16) NOT NULL CHECK (state IN ('PENDING', 'ACCEPTED', 'DECLINED', 'BLOCKED', 'EXPIRED')),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  decided_at timestamptz,
  decided_by_profile_id uuid REFERENCES community.community_profile(id),
  CHECK (requester_profile_id <> recipient_profile_id),
  CHECK (expires_at > created_at),
  CHECK ((state = 'PENDING') = (decided_at IS NULL))
);
CREATE UNIQUE INDEX ux_chat_request_pending ON community.community_chat_request(conversation_id) WHERE state = 'PENDING';
CREATE TABLE community.community_chat_command (
  id uuid PRIMARY KEY,
  actor_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
  operation varchar(24) NOT NULL CHECK (operation IN ('START_DIRECT', 'SEND_MESSAGE', 'CREATE_GROUP', 'INVITE_GROUP', 'REPORT_MESSAGE')),
  idempotency_key uuid NOT NULL,
  request_fingerprint varchar(100) NOT NULL,
  result_kind varchar(16) NOT NULL CHECK (result_kind IN ('ACTIVE', 'PENDING', 'MESSAGE', 'GROUP', 'INVITATION', 'REPORT')),
  target_id uuid NOT NULL,
  conversation_id uuid NOT NULL REFERENCES community.community_chat_conversation(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL,
  UNIQUE (actor_profile_id, operation, idempotency_key)
);
CREATE TABLE community.community_chat_outbox (
  id uuid PRIMARY KEY,
  conversation_id uuid NOT NULL REFERENCES community.community_chat_conversation(id) ON DELETE CASCADE,
  deduplication_key varchar(255) NOT NULL UNIQUE,
  event_payload jsonb NOT NULL,
  occurred_at timestamptz NOT NULL,
  published_at timestamptz,
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  next_attempt_at timestamptz
);

CREATE TABLE community.community_chat_message (
  id uuid PRIMARY KEY,
  conversation_id uuid NOT NULL REFERENCES community.community_chat_conversation(id) ON DELETE CASCADE,
  sender_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
  sender_membership_id uuid,
  client_message_id uuid NOT NULL,
  server_sequence bigint NOT NULL CHECK (server_sequence > 0),
  content_ciphertext text,
  content_fingerprint varchar(100) NOT NULL,
  state varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'RETRACTED')),
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  retracted_at timestamptz,
  purged_at timestamptz,
  UNIQUE (conversation_id, sender_profile_id, client_message_id),
  UNIQUE (conversation_id, server_sequence),
  CHECK (expires_at > created_at),
  CHECK ((content_ciphertext IS NULL) = (purged_at IS NOT NULL)),
  CHECK ((state = 'ACTIVE' AND retracted_at IS NULL) OR
    (state = 'RETRACTED' AND retracted_at IS NOT NULL AND content_ciphertext IS NULL AND purged_at = retracted_at)),
  CHECK (retracted_at IS NULL OR retracted_at >= created_at)
);
CREATE TABLE community.community_chat_read_state (
  conversation_id uuid NOT NULL REFERENCES community.community_chat_conversation(id) ON DELETE CASCADE,
  profile_id uuid NOT NULL REFERENCES community.community_profile(id),
  last_read_sequence bigint NOT NULL DEFAULT 0 CHECK (last_read_sequence >= 0),
  cleared_through_sequence bigint NOT NULL DEFAULT 0 CHECK (cleared_through_sequence >= 0),
  cleared_at timestamptz,
  CHECK (last_read_sequence >= cleared_through_sequence),
  PRIMARY KEY (conversation_id, profile_id)
);

CREATE TABLE community.community_chat_membership (
  id uuid PRIMARY KEY,
  conversation_id uuid NOT NULL REFERENCES community.community_chat_conversation(id) ON DELETE CASCADE,
  profile_id uuid NOT NULL REFERENCES community.community_profile(id),
  role varchar(8) NOT NULL CHECK (role IN ('OWNER','ADMIN','MEMBER')),
  joined_at timestamptz NOT NULL,
  joined_after_sequence bigint NOT NULL CHECK (joined_after_sequence >= 0),
  departed_at timestamptz,
  CHECK (departed_at IS NULL OR departed_at >= joined_at)
);
CREATE UNIQUE INDEX ux_chat_member_current ON community.community_chat_membership(conversation_id, profile_id) WHERE departed_at IS NULL;
ALTER TABLE community.community_chat_message ADD FOREIGN KEY (sender_membership_id) REFERENCES community.community_chat_membership(id);
CREATE TABLE community.community_chat_invitation (
  id uuid PRIMARY KEY,
  conversation_id uuid NOT NULL REFERENCES community.community_chat_conversation(id) ON DELETE CASCADE,
  inviter_membership_id uuid NOT NULL REFERENCES community.community_chat_membership(id),
  inviter_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
  invitee_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
  state varchar(12) NOT NULL CHECK (state IN ('PENDING','ACCEPTED','DECLINED','REVOKED','EXPIRED')),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
  decided_at timestamptz,
  accepted_membership_id uuid REFERENCES community.community_chat_membership(id),
  CHECK (inviter_profile_id <> invitee_profile_id),
  CHECK ((state = 'PENDING') = (decided_at IS NULL)),
  CHECK ((state = 'ACCEPTED') = (accepted_membership_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_chat_invite_pending ON community.community_chat_invitation(conversation_id, invitee_profile_id) WHERE state = 'PENDING';

CREATE TABLE community.community_chat_report_case (
  id uuid PRIMARY KEY,
  conversation_id uuid NOT NULL,
  message_id uuid NOT NULL,
  reporter_profile_id uuid NOT NULL REFERENCES community.community_profile(id),
  reason_code varchar(32) NOT NULL CHECK (reason_code IN ('HARASSMENT','SPAM','PRIVACY_CONCERN','SELF_HARM_OR_CRISIS_CONCERN','OTHER')),
  priority varchar(8) NOT NULL CHECK (priority IN ('NORMAL','HIGH')),
  state varchar(8) NOT NULL DEFAULT 'OPEN' CHECK (state = 'OPEN'),
  evidence_ciphertext text NOT NULL,
  details_ciphertext text NOT NULL,
  created_at timestamptz NOT NULL,
  review_due_at timestamptz NOT NULL CHECK (review_due_at > created_at),
  UNIQUE (reporter_profile_id, message_id)
);
